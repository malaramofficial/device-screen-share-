package com.example.service

import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

enum class RelayConnectionStatus {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR,
}

data class InternetRelayState(
  val enabled: Boolean = false,
  val status: RelayConnectionStatus = RelayConnectionStatus.DISCONNECTED,
  val relayBaseUrl: String = "",
  val roomId: String = "",
  val publicViewUrl: String = "",
  val viewerCount: Int = 0,
  val errorMessage: String? = null,
)

class InternetRelayClient(
  private val onStateChanged: (InternetRelayState) -> Unit,
  private val onRemoteInputLog: (String) -> Unit,
  private val onFrameRequested: () -> Unit,
) {
  private val client =
    OkHttpClient.Builder()
      .connectTimeout(25, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.SECONDS)
      .pingInterval(20, TimeUnit.SECONDS)
      .build()

  private val mainHandler = Handler(Looper.getMainLooper())
  private val active = AtomicBoolean(false)

  @Volatile private var currentSocket: WebSocket? = null
  @Volatile private var currentBaseUrl: String = ""
  @Volatile private var currentRoomId: String = ""
  @Volatile private var deviceWidth: Int = 1080
  @Volatile private var deviceHeight: Int = 2400
  @Volatile private var currentState = InternetRelayState()

  fun configure(enabled: Boolean, baseUrl: String, roomId: String, width: Int, height: Int) {
    deviceWidth = width
    deviceHeight = height
    val cleanBase = normalizeHttpBase(baseUrl)
    val cleanRoom = roomId.trim()

    if (!enabled || cleanBase.isBlank() || cleanRoom.isBlank()) {
      disconnect(
        relayBaseUrl = cleanBase,
        roomId = cleanRoom,
        error =
          if (enabled && cleanBase.isBlank()) "Enter your Relay Server URL to enable Internet mode"
          else null,
      )
      return
    }

    if (
      active.get() &&
        cleanBase == currentBaseUrl &&
        cleanRoom == currentRoomId &&
        currentState.status == RelayConnectionStatus.CONNECTED
    ) {
      onFrameRequested()
      return
    }

    currentBaseUrl = cleanBase
    currentRoomId = cleanRoom
    active.set(true)
    openSocket()
  }

  private fun openSocket() {
    if (!active.get()) return
    runCatching { currentSocket?.close(1000, "Reconnecting") }
    currentSocket = null

    val wsBase =
      when {
        currentBaseUrl.startsWith("https://") -> "wss://" + currentBaseUrl.removePrefix("https://")
        currentBaseUrl.startsWith("http://") -> "ws://" + currentBaseUrl.removePrefix("http://")
        else -> "wss://$currentBaseUrl"
      }
    val wsUrl = "$wsBase/ws/host/$currentRoomId"
    val publicUrl = "$currentBaseUrl/view/$currentRoomId"

    updateState(
      InternetRelayState(
        enabled = true,
        status = RelayConnectionStatus.CONNECTING,
        relayBaseUrl = currentBaseUrl,
        roomId = currentRoomId,
        publicViewUrl = publicUrl,
        viewerCount = 0,
        errorMessage = null,
      )
    )

    val request = Request.Builder().url(wsUrl).build()
    currentSocket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!active.get()) {
              webSocket.close(1000, "Stopped")
              return
            }
            webSocket.send(
              JSONObject().put("width", deviceWidth).put("height", deviceHeight).toString()
            )
            updateState(
              currentState.copy(
                enabled = true,
                status = RelayConnectionStatus.CONNECTED,
                publicViewUrl = publicUrl,
                errorMessage = null,
              )
            )
            onFrameRequested()
          }

          override fun onMessage(webSocket: WebSocket, text: String) {
            handleServerMessage(text)
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!active.get()) return
            updateState(
              currentState.copy(
                status = RelayConnectionStatus.ERROR,
                errorMessage = t.localizedMessage ?: "Relay connection failed",
              )
            )
            scheduleReconnect()
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!active.get()) return
            updateState(
              currentState.copy(
                status = RelayConnectionStatus.DISCONNECTED,
                viewerCount = 0,
              )
            )
            scheduleReconnect()
          }
        },
      )
  }

  private fun scheduleReconnect() {
    mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
    mainHandler.postAtTime(
      { if (active.get()) openSocket() },
      RECONNECT_TOKEN,
      android.os.SystemClock.uptimeMillis() + 3000L,
    )
  }

  fun sendFrame(jpegBytes: ByteArray, width: Int, height: Int) {
    deviceWidth = width
    deviceHeight = height
    val ws = currentSocket ?: return
    if (!active.get() || currentState.status != RelayConnectionStatus.CONNECTED) return
    if (ws.queueSize() > 512_000L) return
    ws.send(jpegBytes.toByteString(0, jpegBytes.size))
  }

  private fun handleServerMessage(raw: String) {
    try {
      val json = JSONObject(raw)
      val type = json.optString("type")
      if (type == "viewer_count") {
        val count = json.optInt("count", 0)
        updateState(currentState.copy(viewerCount = count))
        if (count > 0) {
          onFrameRequested()
        }
        return
      }
      if (type == "request_frame") {
        onFrameRequested()
        return
      }

      val service = RemoteControlAccessibilityService.instance
      if (service == null) {
        onRemoteInputLog("Internet input ignored (Accessibility Off)")
        return
      }

      when (type) {
        "tap" -> {
          val x =
            json
              .optDouble("x", 0.0)
              .toFloat()
              .coerceIn(0f, (deviceWidth - 1).coerceAtLeast(1).toFloat())
          val y =
            json
              .optDouble("y", 0.0)
              .toFloat()
              .coerceIn(0f, (deviceHeight - 1).coerceAtLeast(1).toFloat())
          service.performTap(x, y)
          onRemoteInputLog("Internet Tap -> (${x.toInt()}, ${y.toInt()})")
        }
        "swipe" -> {
          val maxW = (deviceWidth - 1).coerceAtLeast(1).toFloat()
          val maxH = (deviceHeight - 1).coerceAtLeast(1).toFloat()
          val x1 = json.optDouble("x1", 0.0).toFloat().coerceIn(0f, maxW)
          val y1 = json.optDouble("y1", 0.0).toFloat().coerceIn(0f, maxH)
          val x2 = json.optDouble("x2", 0.0).toFloat().coerceIn(0f, maxW)
          val y2 = json.optDouble("y2", 0.0).toFloat().coerceIn(0f, maxH)
          val duration = json.optLong("duration", 220L)
          service.performSwipe(x1, y1, x2, y2, duration)
          onRemoteInputLog(
            "Internet Swipe -> (${x1.toInt()},${y1.toInt()}) to (${x2.toInt()},${y2.toInt()})"
          )
        }
        "key" -> {
          val action = json.optString("action", "")
          if (service.performNavAction(action)) {
            onRemoteInputLog("Internet Button -> ${action.uppercase()}")
          }
        }
      }
    } catch (_: Exception) {
      // Ignore malformed payloads
    }
  }

  fun disconnect(
    relayBaseUrl: String = currentBaseUrl,
    roomId: String = currentRoomId,
    error: String? = null,
  ) {
    active.set(false)
    mainHandler.removeCallbacksAndMessages(RECONNECT_TOKEN)
    runCatching { currentSocket?.close(1000, "Closed") }
    currentSocket = null
    updateState(
      InternetRelayState(
        enabled = false,
        status = if (error != null) RelayConnectionStatus.ERROR else RelayConnectionStatus.DISCONNECTED,
        relayBaseUrl = relayBaseUrl,
        roomId = roomId,
        publicViewUrl =
          if (relayBaseUrl.isNotBlank() && roomId.isNotBlank()) "$relayBaseUrl/view/$roomId" else "",
        viewerCount = 0,
        errorMessage = error,
      )
    )
  }

  private fun updateState(newState: InternetRelayState) {
    currentState = newState
    onStateChanged(newState)
  }

  companion object {
    private val RECONNECT_TOKEN = Any()

    fun normalizeHttpBase(raw: String): String {
      val trimmed = raw.trim().trimEnd('/')
      if (trimmed.isBlank()) return ""
      return when {
        trimmed.startsWith("wss://") -> "https://" + trimmed.removePrefix("wss://")
        trimmed.startsWith("ws://") -> "http://" + trimmed.removePrefix("ws://")
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        else -> "https://$trimmed"
      }
    }
  }
}
