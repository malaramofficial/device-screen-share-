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
)

class ScreenCastService : Service() {

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null
  private var httpServer: NativeScreenHttpServer? = null

  private var physicalWidth: Int = 1080
  private var physicalHeight: Int = 2400
  private var streamWidth: Int = 540
  private var streamHeight: Int = 1200
  private var lastEncodedMs: Long = 0L

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
          _hostState.update { it.copy(isHosting = false, errorMessage = "Screen capture permission denied.") }
          stopSelf()
          return START_NOT_STICKY
        }

        startForegroundNotification()
        startCaptureAndServer(resultCode, resultData)
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
        .setContentText("Streaming live screen over local Wi-Fi / Hotspot")
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

  private fun startCaptureAndServer(resultCode: Int, resultData: Intent) {
    stopResourcesOnly()

    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val (w, h, densityDpi) = readDisplayMetrics(wm)
    physicalWidth = w
    physicalHeight = h

    val scale = (640f / physicalWidth.coerceAtLeast(1)).coerceAtMost(0.65f)
    streamWidth = (physicalWidth * scale).toInt().coerceAtLeast(240)
    streamHeight = (physicalHeight * scale).toInt().coerceAtLeast(320)

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

    val thread = HandlerThread("TermuxCastCapture").apply { start() }
    captureThread = thread
    val handler = Handler(thread.looper)
    captureHandler = handler

    val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projection = mgr.getMediaProjection(resultCode, resultData)
    if (projection == null) {
      _hostState.update { it.copy(isHosting = false, errorMessage = "Unable to obtain MediaProjection.") }
      stopHosting()
      return
    }
    mediaProjection = projection
    projection.registerCallback(projectionCallback, handler)

    val reader = ImageReader.newInstance(streamWidth, streamHeight, PixelFormat.RGBA_8888, 2)
    imageReader = reader
    reader.setOnImageAvailableListener(
      { ir ->
        val now = System.currentTimeMillis()
        val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
        try {
          if (now - lastEncodedMs < 65L) {
            return@setOnImageAvailableListener
          }
          lastEncodedMs = now

          val plane = image.planes[0]
          val buffer = plane.buffer
          val pixelStride = plane.pixelStride
          val rowStride = plane.rowStride
          val rowPadding = rowStride - pixelStride * streamWidth

          val rawBitmap =
            Bitmap.createBitmap(
              streamWidth + rowPadding / pixelStride,
              streamHeight,
              Bitmap.Config.ARGB_8888,
            )
          rawBitmap.copyPixelsFromBuffer(buffer)
          val croppedBitmap =
            if (rowPadding == 0) {
              rawBitmap
            } else {
              Bitmap.createBitmap(rawBitmap, 0, 0, streamWidth, streamHeight).also {
                rawBitmap.recycle()
              }
            }

          val out = ByteArrayOutputStream()
          croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 68, out)
          croppedBitmap.recycle()

          server.updateFrame(out.toByteArray(), physicalWidth, physicalHeight)
        } catch (_: Exception) {
          // Ignore transient frame errors
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

    _hostState.value =
      NativeHostState(
        isHosting = true,
        port = port,
        screenWidth = physicalWidth,
        screenHeight = physicalHeight,
        fps = 0f,
        activeViewers = 0,
        errorMessage = null,
      )
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
  }

  private fun stopHosting() {
    stopResourcesOnly()
    _hostState.update {
      it.copy(isHosting = false, fps = 0f, activeViewers = 0)
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
    _hostState.update { it.copy(isHosting = false, fps = 0f, activeViewers = 0) }
    super.onDestroy()
  }

  companion object {
    const val ACTION_START = "com.example.service.ACTION_START_CAST"
    const val ACTION_STOP = "com.example.service.ACTION_STOP_CAST"
    const val EXTRA_RESULT_CODE = "result_code"
    const val EXTRA_RESULT_DATA = "result_data"
    private const val CHANNEL_ID = "termuxcast_host_channel"
    private const val NOTIFICATION_ID = 4201

    private val _hostState = MutableStateFlow(NativeHostState())
    val hostState: StateFlow<NativeHostState> = _hostState.asStateFlow()
  }
}
