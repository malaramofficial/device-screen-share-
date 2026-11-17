package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class NativeHostState(
  val isHosting: Boolean = false,
  val port: Int = 8080,
  val screenWidth: Int = 1080,
  val screenHeight: Int = 2400,
  val fps: Float = 0f,
  val activeViewers: Int = 0,
  val lastInputEvent: String? = null,
  val errorMessage: String? = null,
  val internetRelay: InternetRelayState = InternetRelayState(),
)

class ScreenCastService : Service() {

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null
  private var httpServer: NativeScreenHttpServer? = null
  private var relayClient: InternetRelayClient? = null
  private var audioStreamer: AudioPlaybackStreamer? = null

  private var physicalWidth: Int = 1080
  private var physicalHeight: Int = 2400
  private var streamWidth: Int = 480
  private var streamHeight: Int = 1066
  private var lastEncodedMs: Long = 0L
  @Volatile private var lastJpegBytes: ByteArray? = null
  @Volatile private var lastRelaySentMs: Long = 0L

  private var packedPixelBuffer: ByteBuffer? = null
  private var rowScratch: ByteArray? = null

  private val keepaliveRunnable =
    object : Runnable {
      override fun run() {
        val bytes = lastJpegBytes
        val relay = relayClient
        val now = System.currentTimeMillis()
        if (
          bytes != null &&
            relay != null &&
            _hostState.value.internetRelay.viewerCount > 0 &&
            now - lastRelaySentMs >= 1500L
        ) {
          relay.sendFrame(bytes, physicalWidth, physicalHeight)
          lastRelaySentMs = now
        }
        captureHandler?.postDelayed(this, 1500L)
      }
    }

  private val projectionCallback =
    object : MediaProjection.Callback() {
      override fun onStop() {
        stopHosting()
      }
    }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopHosting()
        return START_NOT_STICKY
      }
      ACTION_CONFIGURE_RELAY -> {
        val enabled = intent.getBooleanExtra(EXTRA_RELAY_ENABLED, false)
        val url = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val roomId = intent.getStringExtra(EXTRA_RELAY_ROOM_ID).orEmpty()
        relayClient?.configure(enabled, url, roomId, physicalWidth, physicalHeight)
        return START_STICKY
      }
      ACTION_START -> {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        @Suppress("DEPRECATION")
        val resultData: Intent? =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
          } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
          }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
          _hostState.update {
            it.copy(isHosting = false, errorMessage = "Screen capture permission denied.")
          }
          stopSelf()
          return START_NOT_STICKY
        }

        val relayEnabled = intent.getBooleanExtra(EXTRA_RELAY_ENABLED, false)
        val relayUrl = intent.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val relayRoomId = intent.getStringExtra(EXTRA_RELAY_ROOM_ID).orEmpty()

        startForegroundNotification()
        startCaptureAndServer(resultCode, resultData, relayEnabled, relayUrl, relayRoomId)
      }
    }
    return START_NOT_STICKY
  }

  private fun startForegroundNotification() {
    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          getString(R.string.cast_notification_channel),
          NotificationManager.IMPORTANCE_LOW,
        )
      nm.createNotificationChannel(channel)
    }

    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      PendingIntent.getService(
        this,
        1,
        Intent(this, ScreenCastService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val notification: Notification =
      NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.cast_notification_title))
        .setContentText("Streaming live screen over Wi-Fi, Hotspot & Internet Relay")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(openIntent)
        .addAction(0, "Stop Host", stopIntent)
        .setOngoing(true)
        .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun startCaptureAndServer(
    resultCode: Int,
    resultData: Intent,
    relayEnabled: Boolean,
    relayUrl: String,
    relayRoomId: String,
  ) {
    stopResourcesOnly()

    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val (w, h, densityDpi) = readDisplayMetrics(wm)
    physicalWidth = w
    physicalHeight = h

    // Keep stream width <= 540px (multiple of 2) so frames stay ~20-45 KB for fast 4G/5G streaming
    val scale = (540f / physicalWidth.coerceAtLeast(1)).coerceAtMost(0.55f)
    streamWidth = ((physicalWidth * scale).toInt().coerceAtLeast(240) / 2) * 2
    streamHeight = ((physicalHeight * scale).toInt().coerceAtLeast(320) / 2) * 2

    packedPixelBuffer = ByteBuffer.allocateDirect(streamWidth * streamHeight * 4)
    rowScratch = ByteArray(streamWidth * 4)

    val server =
      NativeScreenHttpServer(
        onRemoteInputLog = { msg ->
          _hostState.update { it.copy(lastInputEvent = msg) }
        },
        onStatsChanged = { clients, fps ->
          _hostState.update { it.copy(activeViewers = clients, fps = fps) }
        },
      )
    val port = server.start(8080)
    httpServer = server

    val relay =
      InternetRelayClient(
        onStateChanged = { relayState ->
          _hostState.update { it.copy(internetRelay = relayState) }
        },
        onRemoteInputLog = { msg ->
          _hostState.update { it.copy(lastInputEvent = msg) }
        },
        onFrameRequested = {
          val bytes = lastJpegBytes
          if (bytes != null) {
            relayClient?.sendFrame(bytes, physicalWidth, physicalHeight)
            lastRelaySentMs = System.currentTimeMillis()
          }
        },
      )
    relayClient = relay
    relay.configure(relayEnabled, relayUrl, relayRoomId, physicalWidth, physicalHeight)

    val thread = HandlerThread("TermuxCastCapture").apply { start() }
    captureThread = thread
    val handler = Handler(thread.looper)
    captureHandler = handler
    handler.postDelayed(keepaliveRunnable, 1500L)

    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projection = mgr.getMediaProjection(resultCode, resultData)
    if (projection == null) {
      _hostState.update {
        it.copy(isHosting = false, errorMessage = "Unable to obtain MediaProjection.")
      }
      stopHosting()
      return
    }
    mediaProjection = projection
    projection.registerCallback(projectionCallback, handler)

    // Capture media/game playback audio separately from the screen frames.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
      checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
      audioStreamer = AudioPlaybackStreamer(
        projection = projection,
        onPacket = { packet -> relay.sendAudioPacket(packet) },
        onError = { message ->
          _hostState.update { it.copy(errorMessage = "Audio capture: $message") }
        },
      ).also { it.start() }
    } else {
      _hostState.update {
        it.copy(errorMessage = "Audio sharing is off. Allow the Microphone/Audio permission and restart screen sharing.")
      }
    }

    val reader = ImageReader.newInstance(streamWidth, streamHeight, PixelFormat.RGBA_8888, 2)
    imageReader = reader
    reader.setOnImageAvailableListener(
      { ir ->
        val now = System.currentTimeMillis()
        val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
        try {
          if (now - lastEncodedMs < 75L && lastJpegBytes != null) {
            return@setOnImageAvailableListener
          }
          lastEncodedMs = now

          val jpegBytes = encodeImageToJpeg(image) ?: return@setOnImageAvailableListener
          lastJpegBytes = jpegBytes
          server.updateFrame(jpegBytes, physicalWidth, physicalHeight)
          relay.sendFrame(jpegBytes, physicalWidth, physicalHeight)
          lastRelaySentMs = now
        } catch (e: Exception) {
          _hostState.update {
            it.copy(errorMessage = "Frame capture error: ${e.localizedMessage ?: e.javaClass.simpleName}")
          }
        } finally {
          image.close()
        }
      },
      handler,
    )

    virtualDisplay =
      projection.createVirtualDisplay(
        "TermuxCastDisplay",
        streamWidth,
        streamHeight,
        densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface,
        null,
        handler,
      )

    _hostState.update {
      it.copy(
        isHosting = true,
        port = port,
        screenWidth = physicalWidth,
        screenHeight = physicalHeight,
        fps = 0f,
        activeViewers = 0,
        errorMessage = null,
      )
    }
  }

  private fun encodeImageToJpeg(image: Image): ByteArray? {
    val plane = image.planes.firstOrNull() ?: return null
    val srcBuffer = plane.buffer
    val pixelStride = plane.pixelStride
    val rowStride = plane.rowStride
    val rowByteCount = streamWidth * pixelStride

    val packed =
      packedPixelBuffer?.takeIf { it.capacity() >= streamWidth * streamHeight * 4 }
        ?: ByteBuffer.allocateDirect(streamWidth * streamHeight * 4).also {
          packedPixelBuffer = it
        }
    packed.clear()

    if (rowStride == rowByteCount && srcBuffer.remaining() >= streamWidth * streamHeight * 4) {
      srcBuffer.rewind()
      packed.put(srcBuffer)
    } else {
      val scratch =
        rowScratch?.takeIf { it.size >= rowByteCount }
          ?: ByteArray(rowByteCount).also { rowScratch = it }
      for (row in 0 until streamHeight) {
        val rowOffset = row * rowStride
        if (rowOffset >= srcBuffer.limit()) break
        srcBuffer.position(rowOffset)
        val toRead = minOf(rowByteCount, srcBuffer.remaining())
        srcBuffer.get(scratch, 0, toRead)
        packed.put(scratch, 0, toRead)
      }
    }

    packed.rewind()
    val bitmap = Bitmap.createBitmap(streamWidth, streamHeight, Bitmap.Config.ARGB_8888)
    return try {
      bitmap.copyPixelsFromBuffer(packed)
      val out = ByteArrayOutputStream()
      bitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
      out.toByteArray()
    } finally {
      bitmap.recycle()
    }
  }

  private fun readDisplayMetrics(wm: WindowManager): Triple<Int, Int, Int> {
    val dpi = resources.displayMetrics.densityDpi
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val bounds = wm.currentWindowMetrics.bounds
      Triple(bounds.width(), bounds.height(), dpi)
    } else {
      @Suppress("DEPRECATION")
      val dm = DisplayMetrics()
      @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
      Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }
  }

  private fun stopResourcesOnly() {
    captureHandler?.removeCallbacks(keepaliveRunnable)
    runCatching { audioStreamer?.stop() }
    audioStreamer = null
    runCatching { virtualDisplay?.release() }
    virtualDisplay = null
    runCatching { imageReader?.close() }
    imageReader = null
    runCatching { mediaProjection?.unregisterCallback(projectionCallback) }
    runCatching { mediaProjection?.stop() }
    mediaProjection = null
    runCatching { captureThread?.quitSafely() }
    captureThread = null
    captureHandler = null
    runCatching { httpServer?.stop() }
    httpServer = null
    runCatching { relayClient?.disconnect() }
    relayClient = null
    lastJpegBytes = null
    packedPixelBuffer = null
    rowScratch = null
  }

  private fun stopHosting() {
    stopResourcesOnly()
    _hostState.update {
      it.copy(
        isHosting = false,
        fps = 0f,
        activeViewers = 0,
        internetRelay = InternetRelayState(),
      )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION") stopForeground(true)
    }
    stopSelf()
  }

  override fun onDestroy() {
    stopResourcesOnly()
    _hostState.update {
      it.copy(isHosting = false, fps = 0f, activeViewers = 0, internetRelay = InternetRelayState())
    }
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.example.service.ACTION_START_CAST"
    const val ACTION_STOP = "com.example.service.ACTION_STOP_CAST"
    const val ACTION_CONFIGURE_RELAY = "com.example.service.ACTION_CONFIGURE_RELAY"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"
    const val EXTRA_RELAY_ENABLED = "relay_enabled"
    const val EXTRA_RELAY_URL = "relay_url"
    const val EXTRA_RELAY_ROOM_ID = "relay_room_id"
    private const val CHANNEL_ID = "termuxcast_host_channel"
    private const val NOTIFICATION_ID = 4201

    private val _hostState = MutableStateFlow(NativeHostState())
    val hostState: StateFlow<NativeHostState> = _hostState.asStateFlow()
  }
}
