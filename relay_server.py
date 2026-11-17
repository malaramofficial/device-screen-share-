import json
import os
import threading
from collections import defaultdict

from flask import Flask, jsonify, render_template_string
from flask_sock import Sock

app = Flask(__name__)
sock = Sock(app)

rooms_lock = threading.Lock()
host_sockets = {}                  # room_id -> ws
viewer_sockets = defaultdict(set)  # room_id -> set(ws)
latest_meta = {}                   # room_id -> {"width": int, "height": int}
latest_frame = {}                  # room_id -> bytes (last JPEG frame)


VIEWER_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
  <title>TermuxCast Internet Remote — {{ room_id }}</title>
  <style>
    :root {
      --bg: #0b0f14; --surface: #131a22; --elevated: #1b2430; --border: #283546;
      --text: #eef4f8; --muted: #93a6bb; --accent: #10b981; --accent-soft: rgba(16,185,129,.18);
      --warn: #f59e0b; --danger: #ef4444;
    }
    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
    html, body {
      margin: 0; padding: 0; height: 100%; overflow: hidden; color: var(--text);
      background: radial-gradient(circle at top, #13202d 0%, var(--bg) 60%);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    }
    .shell {
      display: flex; flex-direction: column; height: 100dvh; max-width: 720px;
      margin: 0 auto; padding: 10px 12px; gap: 10px;
    }
    .topbar {
      display: flex; align-items: center; justify-content: space-between;
      background: var(--surface); border: 1px solid var(--border);
      border-radius: 14px; padding: 8px 12px; gap: 8px; flex-shrink: 0;
    }
    .brand { display: flex; align-items: center; gap: 8px; font-weight: 700; font-size: 14px; }
    .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--warn); flex-shrink: 0; }
    .dot.online { background: var(--accent); box-shadow: 0 0 8px var(--accent); }
    .dot.offline { background: var(--danger); }
    .badges { display: flex; gap: 6px; font-family: monospace; font-size: 11px; color: var(--muted); }
    .badge { background: var(--elevated); border: 1px solid var(--border); padding: 3px 8px; border-radius: 999px; }
    .stage {
      position: relative; flex: 1; min-height: 0; display: flex; align-items: center; justify-content: center;
      background: #06090c; border: 1px solid var(--border); border-radius: 18px; overflow: hidden; padding: 6px;
    }
    #screenCanvas {
      max-width: 100%; max-height: 100%; width: auto; height: auto;
      border-radius: 10px; touch-action: none; user-select: none; cursor: crosshair;
      box-shadow: 0 12px 32px rgba(0,0,0,.55);
    }
    .overlay {
      position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
      text-align: center; padding: 24px; gap: 8px; color: var(--muted); background: rgba(11,15,20,.88);
    }
    .overlay.hidden { display: none; }
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
      <div class="brand"><span id="dot" class="dot"></span><span>TermuxCast Relay ({{ room_id }})</span></div>
      <div class="badges">
        <span id="resBadge" class="badge">-- x --</span>
        <span id="fpsBadge" class="badge">0 FPS</span>
      </div>
    </header>
    <main id="stage" class="stage">
      <canvas id="screenCanvas" width="540" height="1200"></canvas>
      <div id="overlay" class="overlay">
        <strong id="overlayTitle">Connecting to Internet Relay...</strong>
        <span id="overlayDesc">Waiting for host phone ({{ room_id }}) to stream frames.</span>
      </div>
    </main>
    <nav class="nav">
      <button onclick="sendKey('back')">◀ Back</button>
      <button onclick="sendKey('home')">● Home</button>
      <button onclick="sendKey('recents')">■ Recents</button>
      <button onclick="sendKey('notifications')">▾ Shade</button>
    </nav>
  </div>
  <script>
    const roomId = {{ room_id|tojson }};
    const proto = location.protocol === "https:" ? "wss:" : "ws:";
    const wsUrl = `${proto}//${location.host}/ws/viewer/${encodeURIComponent(roomId)}`;
    const canvas = document.getElementById("screenCanvas");
    const ctx = canvas.getContext("2d");
    const stage = document.getElementById("stage");
    const dot = document.getElementById("dot");
    const overlay = document.getElementById("overlay");
    const overlayTitle = document.getElementById("overlayTitle");
    const overlayDesc = document.getElementById("overlayDesc");
    const resBadge = document.getElementById("resBadge");
    const fpsBadge = document.getElementById("fpsBadge");

    let ws = null;
    let devW = 1080, devH = 2400;
    let hasFrame = false;
    let startPt = null;
    let frameTimestamps = [];
    let isDrawing = false;
    let pendingBlob = null;

    function drawBlobFrame(blob) {
      if (isDrawing) {
        pendingBlob = blob;
        return;
      }
      isDrawing = true;
      const url = URL.createObjectURL(blob);
      const img = new Image();
      img.onload = () => {
        if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
          canvas.width = img.naturalWidth;
          canvas.height = img.naturalHeight;
        }
        ctx.drawImage(img, 0, 0);
        URL.revokeObjectURL(url);
        if (!hasFrame) {
          hasFrame = true;
          overlay.classList.add("hidden");
        }
        const now = performance.now();
        frameTimestamps.push(now);
        frameTimestamps = frameTimestamps.filter((t) => now - t < 1000);
        fpsBadge.textContent = `${frameTimestamps.length} FPS`;
        isDrawing = false;
        if (pendingBlob) {
          const next = pendingBlob;
          pendingBlob = null;
          drawBlobFrame(next);
        }
      };
      img.onerror = () => {
        URL.revokeObjectURL(url);
        isDrawing = false;
      };
      img.src = url;
    }

    function connect() {
      ws = new WebSocket(wsUrl);
      ws.binaryType = "arraybuffer";
      ws.onopen = () => {
        dot.className = "dot online";
        overlayTitle.textContent = "Relay Connected";
        overlayDesc.textContent = "Waiting for live screen frame from phone...";
      };
      ws.onclose = () => {
        dot.className = "dot offline";
        overlay.classList.remove("hidden");
        overlayTitle.textContent = "Relay Disconnected";
        overlayDesc.textContent = "Reconnecting in 2 seconds...";
        setTimeout(connect, 2000);
      };
      ws.onmessage = (ev) => {
        if (typeof ev.data === "string") {
          try {
            const msg = JSON.parse(ev.data);
            if (msg.width && msg.height) {
              devW = msg.width; devH = msg.height;
              resBadge.textContent = `${devW} x ${devH}`;
            }
            if (msg.type === "host_offline") {
              overlay.classList.remove("hidden");
              overlayTitle.textContent = "Host phone offline";
              overlayDesc.textContent = "Waiting for the phone to start screen sharing...";
            }
          } catch (_) {}
          return;
        }
        const blob = ev.data instanceof Blob ? ev.data : new Blob([ev.data], { type: "image/jpeg" });
        drawBlobFrame(blob);
      };
    }
    connect();

    function toDevice(cx, cy) {
      const r = canvas.getBoundingClientRect();
      return {
        x: Math.round(Math.max(0, Math.min(1, (cx - r.left) / Math.max(1, r.width))) * devW),
        y: Math.round(Math.max(0, Math.min(1, (cy - r.top) / Math.max(1, r.height))) * devH),
      };
    }
    function ripple(cx, cy) {
      const sr = stage.getBoundingClientRect();
      const el = document.createElement("div");
      el.className = "ripple";
      el.style.left = `${cx - sr.left}px`;
      el.style.top = `${cy - sr.top}px`;
      stage.appendChild(el);
      el.addEventListener("animationend", () => el.remove());
    }
    function sendCmd(obj) {
      if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(obj));
      }
    }
    function sendKey(action) { sendCmd({ type: "key", action }); }

    canvas.addEventListener("pointerdown", (e) => {
      e.preventDefault();
      canvas.setPointerCapture(e.pointerId);
      const pt = toDevice(e.clientX, e.clientY);
      startPt = { x: pt.x, y: pt.y, cx: e.clientX, cy: e.clientY, t: performance.now() };
      ripple(e.clientX, e.clientY);
    });
    canvas.addEventListener("pointerup", (e) => {
      if (!startPt) return;
      const end = toDevice(e.clientX, e.clientY);
      const dist = Math.hypot(e.clientX - startPt.cx, e.clientY - startPt.cy);
      if (dist < 12) {
        sendCmd({ type: "tap", x: end.x, y: end.y });
      } else {
        sendCmd({
          type: "swipe",
          x1: startPt.x, y1: startPt.y, x2: end.x, y2: end.y,
          duration: Math.max(120, Math.min(1200, Math.round(performance.now() - startPt.t)))
        });
        ripple(e.clientX, e.clientY);
      }
      startPt = null;
    });
    canvas.addEventListener("pointercancel", () => { startPt = null; });
  </script>
</body>
</html>"""


@app.route("/")
def home():
    return (
        "<h2>TermuxCast Internet Relay Active</h2>"
        "<p>Enter this server URL inside the TermuxCast Android app to get your live <code>/view/&lt;room_id&gt;</code> browser link.</p>"
    )


@app.route("/health")
def health():
    with rooms_lock:
        active_hosts = len(host_sockets)
    return jsonify({"ok": True, "active_hosts": active_hosts})


@app.route("/view/<room_id>")
def view_room(room_id):
    return render_template_string(VIEWER_HTML, room_id=room_id)


@sock.route("/ws/host/<room_id>")
def ws_host(ws, room_id):
    with rooms_lock:
        host_sockets[room_id] = ws
        viewers = list(viewer_sockets[room_id])
        viewer_count = len(viewers)
    for v in viewers:
        try:
            v.send(json.dumps({"type": "host_online"}))
        except Exception:
            pass
    if viewer_count > 0:
        try:
            ws.send(json.dumps({"type": "viewer_count", "count": viewer_count}))
            ws.send(json.dumps({"type": "request_frame"}))
        except Exception:
            pass

    try:
        while True:
            msg = ws.receive()
            if msg is None:
                break
            if isinstance(msg, str):
                try:
                    meta = json.loads(msg)
                    if "width" in meta and "height" in meta:
                        with rooms_lock:
                            latest_meta[room_id] = {"width": meta["width"], "height": meta["height"]}
                except Exception:
                    pass
            elif isinstance(msg, (bytes, bytearray)):
                with rooms_lock:
                    latest_frame[room_id] = bytes(msg)
            with rooms_lock:
                viewers = list(viewer_sockets[room_id])
            for v in viewers:
                try:
                    v.send(msg)
                except Exception:
                    with rooms_lock:
                        viewer_sockets[room_id].discard(v)
    finally:
        with rooms_lock:
            if host_sockets.get(room_id) is ws:
                host_sockets.pop(room_id, None)
            viewers = list(viewer_sockets[room_id])
        for v in viewers:
            try:
                v.send(json.dumps({"type": "host_offline"}))
            except Exception:
                pass


@sock.route("/ws/viewer/<room_id>")
def ws_viewer(ws, room_id):
    with rooms_lock:
        viewer_sockets[room_id].add(ws)
        meta = latest_meta.get(room_id)
        cached_frame = latest_frame.get(room_id)
        host_ws = host_sockets.get(room_id)
        count = len(viewer_sockets[room_id])

    if meta:
        try:
            ws.send(json.dumps({"type": "meta", **meta}))
        except Exception:
            pass
    if cached_frame:
        try:
            ws.send(cached_frame)
        except Exception:
            pass
    if host_ws:
        try:
            host_ws.send(json.dumps({"type": "viewer_count", "count": count}))
            host_ws.send(json.dumps({"type": "request_frame"}))
        except Exception:
            pass

    try:
        while True:
            cmd = ws.receive()
            if cmd is None:
                break
            with rooms_lock:
                host_ws = host_sockets.get(room_id)
            if host_ws:
                try:
                    host_ws.send(cmd)
                except Exception:
                    pass
    finally:
        with rooms_lock:
            viewer_sockets[room_id].discard(ws)
            host_ws = host_sockets.get(room_id)
            count = len(viewer_sockets[room_id])
        if host_ws:
            try:
                host_ws.send(json.dumps({"type": "viewer_count", "count": count}))
            except Exception:
                pass


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "10000"))
    app.run(host="0.0.0.0", port=port)
