package com.example.service

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class NativeScreenHttpServer(
  private val onRemoteInputLog: (String) -> Unit,
  private val onStatsChanged: (clients: Int, fps: Float) -> Unit,
) {

  private val running = AtomicBoolean(false)
  private val activeStreamClients = AtomicInteger(0)
  private val executor = Executors.newCachedThreadPool()

  private var serverSocket: ServerSocket? = null
  private var acceptThread: Thread? = null

  private val frameLock = Object()
  @Volatile private var latestJpeg: ByteArray? = null
  @Volatile private var frameSequence: Long = 0L
  @Volatile private var deviceWidth: Int = 1080
  @Volatile private var deviceHeight: Int = 2400
  @Volatile private var measuredFps: Float = 0f
  private var lastFrameNanos: Long = System.nanoTime()

  var boundPort: Int = 8080
    private set

  fun start(preferredPort: Int = 8080): Int {
    if (running.get()) return boundPort

    val socket = openAvailableSocket(preferredPort)
    serverSocket = socket
    boundPort = socket.localPort
    running.set(true)

    acceptThread =
      Thread(
          {
            while (running.get() && !socket.isClosed) {
              try {
                val client = socket.accept()
                executor.execute { handleClient(client) }
              } catch (_: Exception) {
                if (!running.get()) break
              }
            }
          },
          "NativeScreenHttpAccept",
        )
        .apply {
          isDaemon = true
          start()
        }

    return boundPort
  }

  private fun openAvailableSocket(preferredPort: Int): ServerSocket {
    for (candidate in preferredPort..(preferredPort + 10)) {
      try {
        return ServerSocket(candidate, 50, InetAddress.getByName("0.0.0.0"))
      } catch (_: Exception) {
        // Try next port
      }
    }
    return ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
  }

  fun stop() {
    running.set(false)
    synchronized(frameLock) { frameLock.notifyAll() }
    runCatching { serverSocket?.close() }
    serverSocket = null
    activeStreamClients.set(0)
    onStatsChanged(0, 0f)
  }

  fun updateFrame(jpegBytes: ByteArray, width: Int, height: Int) {
    val now = System.nanoTime()
    val dtSec = ((now - lastFrameNanos).coerceAtLeast(1_000_000L)) / 1_000_000_000f
    lastFrameNanos = now
    val instantFps = 1f / dtSec
    measuredFps = if (measuredFps == 0f) instantFps else (measuredFps * 0.75f + instantFps * 0.25f)

    synchronized(frameLock) {
      latestJpeg = jpegBytes
      deviceWidth = width
      deviceHeight = height
      frameSequence++
      frameLock.notifyAll()
    }
    onStatsChanged(activeStreamClients.get(), measuredFps)
  }

  private fun handleClient(socket: Socket) {
    socket.use { client ->
      client.soTimeout = 10_000
      val input = BufferedInputStream(client.getInputStream())
      val output = client.getOutputStream()

      val requestLine = readLine(input) ?: return
      val parts = requestLine.split(" ")
      if (parts.size < 2) return
      val method = parts[0].uppercase()
      val path = parts[1].substringBefore("?")

      var contentLength = 0
      while (true) {
        val header = readLine(input) ?: break
        if (header.isEmpty()) break
        val colonIdx = header.indexOf(':')
        if (colonIdx > 0) {
          val key = header.substring(0, colonIdx).trim().lowercase()
          val value = header.substring(colonIdx + 1).trim()
          if (key == "content-length") {
            contentLength = value.toIntOrNull() ?: 0
          }
        }
      }

      when {
        method == "GET" && path == "/" -> {
          sendBytes(output, "200 OK", "text/html; charset=utf-8", WEB_REMOTE_HTML.toByteArray(StandardCharsets.UTF_8))
        }
        method == "GET" && path == "/status" -> {
          val json =
            JSONObject()
              .put("width", deviceWidth)
              .put("height", deviceHeight)
              .put("fps", String.format(java.util.Locale.US, "%.1f", measuredFps).toDouble())
              .put("clients", activeStreamClients.get())
              .put("accessibility_enabled", RemoteControlAccessibilityService.isConnected.value)
              .toString()
          sendBytes(output, "200 OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
        }
        method == "GET" && path == "/frame.jpg" -> {
          val frame = latestJpeg
          if (frame == null) {
            sendBytes(output, "503 Service Unavailable", "text/plain", "Waiting for frame".toByteArray())
          } else {
            sendBytes(output, "200 OK", "image/jpeg", frame)
          }
        }
        method == "GET" && path == "/stream.mjpg" -> {
          client.soTimeout = 0
          streamMjpeg(output)
        }
        method == "POST" && path == "/input" -> {
          val bodyBytes = readFixedBytes(input, contentLength.coerceIn(0, 16_384))
          val responseJson = handleInputPost(String(bodyBytes, StandardCharsets.UTF_8))
          sendBytes(output, "200 OK", "application/json; charset=utf-8", responseJson.toByteArray(StandardCharsets.UTF_8))
        }
        else -> {
          sendBytes(output, "404 Not Found", "text/plain", "Not found".toByteArray())
        }
      }
    }
  }

  private fun streamMjpeg(output: OutputStream) {
    val header =
      "HTTP/1.1 200 OK\r\n" +
        "Connection: close\r\n" +
        "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
        "Pragma: no-cache\r\n" +
        "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n"
    output.write(header.toByteArray(StandardCharsets.US_ASCII))
    output.flush()

    val count = activeStreamClients.incrementAndGet()
    onStatsChanged(count, measuredFps)

    var lastSentSeq = -1L
    try {
      while (running.get()) {
        var bytesToSend: ByteArray? = null
        synchronized(frameLock) {
          while (running.get() && frameSequence == lastSentSeq) {
            frameLock.wait(800L)
          }
          if (!running.get()) return
          if (frameSequence != lastSentSeq && latestJpeg != null) {
            bytesToSend = latestJpeg
            lastSentSeq = frameSequence
          }
        }

        val frame = bytesToSend ?: continue
        val partHeader =
          "--frame\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${frame.size}\r\n\r\n"
        output.write(partHeader.toByteArray(StandardCharsets.US_ASCII))
        output.write(frame)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
      }
    } catch (_: Exception) {
      // Client disconnected
    } finally {
      val remaining = activeStreamClients.decrementAndGet().coerceAtLeast(0)
      onStatsChanged(remaining, measuredFps)
    }
  }

  private fun handleInputPost(rawJson: String): String {
    val service = RemoteControlAccessibilityService.instance
    if (service == null) {
      return JSONObject()
        .put("ok", false)
        .put("error", "Accessibility service not enabled on phone")
        .toString()
    }

    return try {
      val json = JSONObject(rawJson)
      when (json.optString("type")) {
        "tap" -> {
          val x = json.optDouble("x", 0.0).toFloat().coerceIn(0f, (deviceWidth - 1).coerceAtLeast(1).toFloat())
          val y = json.optDouble("y", 0.0).toFloat().coerceIn(0f, (deviceHeight - 1).coerceAtLeast(1).toFloat())
          service.performTap(x, y)
          onRemoteInputLog("Browser Tap -> (${x.toInt()}, ${y.toInt()})")
          JSONObject().put("ok", true).toString()
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
          onRemoteInputLog("Browser Swipe -> (${x1.toInt()},${y1.toInt()}) to (${x2.toInt()},${y2.toInt()})")
          JSONObject().put("ok", true).toString()
        }
        "key" -> {
          val action = json.optString("action", "")
          val handled = service.performNavAction(action)
          if (handled) {
            onRemoteInputLog("Browser Button -> ${action.uppercase()}")
          }
          JSONObject().put("ok", handled).toString()
        }
        else -> JSONObject().put("ok", false).put("error", "Unknown input type").toString()
      }
    } catch (e: Exception) {
      JSONObject().put("ok", false).put("error", e.message ?: "Invalid payload").toString()
    }
  }

  private fun sendBytes(output: OutputStream, status: String, contentType: String, data: ByteArray) {
    val headers =
      "HTTP/1.1 $status\r\n" +
        "Content-Type: $contentType\r\n" +
        "Content-Length: ${data.size}\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Connection: close\r\n\r\n"
    output.write(headers.toByteArray(StandardCharsets.US_ASCII))
    output.write(data)
    output.flush()
  }

  private fun readLine(input: InputStream): String? {
    val buffer = ByteArrayOutputStream()
    var prev = -1
    while (true) {
      val b = input.read()
      if (b == -1) {
        return if (buffer.size() == 0) null else buffer.toString("UTF-8")
      }
      if (b == '\n'.code) {
        val bytes = buffer.toByteArray()
        val end = if (prev == '\r'.code && bytes.isNotEmpty()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, StandardCharsets.UTF_8)
      }
      buffer.write(b)
      prev = b
    }
  }

  private fun readFixedBytes(input: InputStream, length: Int): ByteArray {
    if (length <= 0) return ByteArray(0)
    val data = ByteArray(length)
    var readTotal = 0
    while (readTotal < length) {
      val read = input.read(data, readTotal, length - readTotal)
      if (read == -1) break
      readTotal += read
    }
    return if (readTotal == length) data else data.copyOf(readTotal)
  }

  companion object {
    private const val WEB_REMOTE_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>TermuxCast Live Phone Control</title>
  <style>
    :root {
      --bg: #0b0f14; --surface: #131a22; --elevated: #1b2430; --border: #283546;
      --text: #eef4f8; --muted: #93a6bb; --accent: #10b981; --accent-soft: rgba(16,185,129,.18);
      --warn: #f59e0b;
    }
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
    html, body {
      margin: 0; padding: 0; height: 100%; overflow: hidden; color: var(--text);
      background: radial-gradient(circle at top, #13202d 0%, var(--bg) 60%);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    .shell {
      display: flex; flex-direction: column; height: 100dvh; max-width: 700px;
      margin: 0 auto; padding: 10px 12px; gap: 10px;
    }
    .topbar {
      display: flex; align-items: center; justify-content: space-between;
      background: var(--surface); border: 1px solid var(--border);
      border-radius: 14px; padding: 8px 12px; gap: 8px; flex-shrink: 0;
    }
    .brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 14px; }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 8px var(--accent); }
    .badges { display: flex; gap: 6px; font-family: monospace; font-size: 11px; color: var(--muted); }
    .badge { background: var(--elevated); border: 1px solid var(--border); padding: 3px 8px; border-radius: 999px; }
    .warn-banner {
      display: none; background: rgba(245,158,11,.15); border: 1px solid var(--warn); color: #fde68a;
      border-radius: 10px; padding: 8px 12px; font-size: 12px; flex-shrink: 0;
    }
    .warn-banner.show { display: block; }
    .stage {
      position: relative; flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center;
      background: #06090c; border: 1px solid var(--border); border-radius: 18px; overflow: hidden; padding: 6px;
    }
    #screenFeed {
      max-width: 100%; max-height: 100%; width: auto; height: auto;
      border-radius: 10px; touch-action: none; user-select: none; -webkit-user-drag: none; cursor: crosshair;
      box-shadow: 0 12px 32px rgba(0,0,0,.55);
    }
    .nav {
      display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; flex-shrink: 0;
      padding-bottom: env(safe-area-inset-bottom, 0px);
    }
    .nav button {
      height: 48px; background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
      color: var(--text); font-size: 13px; font-weight: 600; cursor: pointer;
    }
    .nav button:active { background: var(--accent-soft); border-color: var(--accent); color: var(--accent); }
    .ripple {
      position: absolute; width: 28px; height: 28px; border-radius: 50%;
      border: 2px solid var(--accent); background: var(--accent-soft);
      transform: translate(-50%,-50%) scale(.5); pointer-events: none;
      animation: rip .4s ease-out forwards;
    }
    @keyframes rip { to { transform: translate(-50%,-50%) scale(2.1); opacity: 0; } }
  </style>
</head>
<body>
  <div class="shell">
    <header class="topbar">
      <div class="brand"><span class="dot"></span><span>TermuxCast Direct</span></div>
      <div class="badges">
        <span id="resBadge" class="badge">-- x --</span>
        <span id="fpsBadge" class="badge">-- FPS</span>
      </div>
    </header>
    <div id="accessWarn" class="warn-banner">
      Touch control disabled: enable <strong>TermuxCast</strong> in Settings → Accessibility on the host phone.
    </div>
    <main id="stage" class="stage">
      <img id="screenFeed" src="/stream.mjpg" alt="Live Phone Screen" draggable="false" />
    </main>
    <nav class="nav">
      <button onclick="sendKey('back')">◀ Back</button>
      <button onclick="sendKey('home')">● Home</button>
      <button onclick="sendKey('recents')">■ Recents</button>
      <button onclick="sendKey('notifications')">▾ Shade</button>
    </nav>
  </div>
  <script>
    const feed = document.getElementById("screenFeed");
    const stage = document.getElementById("stage");
    const resBadge = document.getElementById("resBadge");
    const fpsBadge = document.getElementById("fpsBadge");
    const accessWarn = document.getElementById("accessWarn");
    let devW = 1080, devH = 2400, startPt = null;

    async function refreshStatus() {
      try {
        const res = await fetch("/status");
        const data = await res.json();
        devW = data.width || devW;
        devH = data.height || devH;
        resBadge.textContent = devW + " x " + devH;
        fpsBadge.textContent = (data.fps || 0) + " FPS";
        accessWarn.classList.toggle("show", !data.accessibility_enabled);
      } catch (_) {}
    }
    refreshStatus();
    setInterval(refreshStatus, 2000);

    function toDevice(clientX, clientY) {
      const r = feed.getBoundingClientRect();
      const nx = Math.max(0, Math.min(1, (clientX - r.left) / Math.max(1, r.width)));
      const ny = Math.max(0, Math.min(1, (clientY - r.top) / Math.max(1, r.height)));
      return { x: Math.round(nx * devW), y: Math.round(ny * devH) };
    }

    function ripple(cx, cy) {
      const sr = stage.getBoundingClientRect();
      const el = document.createElement("div");
      el.className = "ripple";
      el.style.left = (cx - sr.left) + "px";
      el.style.top = (cy - sr.top) + "px";
      stage.appendChild(el);
      el.addEventListener("animationend", () => el.remove());
    }

    async function postInput(payload) {
      try {
        await fetch("/input", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload)
        });
      } catch (_) {}
    }

    function sendKey(action) {
      postInput({ type: "key", action });
    }

    feed.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      feed.setPointerCapture(e.pointerId);
      const pt = toDevice(e.clientX, e.clientY);
      startPt = { x: pt.x, y: pt.y, cx: e.clientX, cy: e.clientY, t: performance.now() };
      ripple(e.clientX, e.clientY);
    });

    feed.addEventListener("pointerup", (e) => {
      if (!startPt) return;
      const end = toDevice(e.clientX, e.clientY);
      const dist = Math.hypot(e.clientX - startPt.cx, e.clientY - startPt.cy);
      if (dist < 12) {
        postInput({ type: "tap", x: end.x, y: end.y });
      } else {
        postInput({
          type: "swipe",
          x1: startPt.x, y1: startPt.y,
          x2: end.x, y2: end.y,
          duration: Math.max(120, Math.min(1200, Math.round(performance.now() - startPt.t)))
        });
        ripple(e.clientX, e.clientY);
      }
      startPt = null;
    });

    feed.addEventListener("pointercancel", () => { startPt = null; });
  </script>
</body>
</html>"""
  }
}
