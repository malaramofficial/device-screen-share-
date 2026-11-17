import base64
import io
import os
import re
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from flask import Flask, jsonify, render_template, request
from flask_socketio import SocketIO, emit

try:
    from PIL import Image
    HAS_PILLOW = True
except ImportError:
    HAS_PILLOW = False

app = Flask(__name__)
app.config["SECRET_KEY"] = os.environ.get("SECRET_KEY", "termux-cast-local-secret")

# Threading mode + simple-websocket works reliably in Termux without C-extension build issues
socketio = SocketIO(app, cors_allowed_origins="*", async_mode="threading")

# Thread pool for non-blocking ADB input commands (tap, swipe, keyevent)
input_executor = ThreadPoolExecutor(max_workers=4)

KEYCODE_MAP = {
    "home": 3,          # KEYCODE_HOME
    "back": 4,          # KEYCODE_BACK
    "recents": 187,     # KEYCODE_APP_SWITCH
    "power": 26,        # KEYCODE_POWER
    "volume_up": 24,    # KEYCODE_VOLUME_UP
    "volume_down": 25,  # KEYCODE_VOLUME_DOWN
    "enter": 66,        # KEYCODE_ENTER
}

QUALITY_PRESETS = {
    "fast": {"scale": 0.45, "jpeg_quality": 48, "interval": 0.06},
    "balanced": {"scale": 0.60, "jpeg_quality": 65, "interval": 0.08},
    "sharp": {"scale": 0.85, "jpeg_quality": 82, "interval": 0.12},
}

state_lock = threading.Lock()
connected_clients = 0
stream_thread = None
stream_stop_event = threading.Event()
device_size = [1080, 2400]  # Updated dynamically via `adb shell wm size` & frame dimensions
current_preset = "balanced"
adb_serial = os.environ.get("ADB_SERIAL", "").strip() or None


def get_adb_base_cmd():
    """Return base adb command targeting configured serial if present."""
    if adb_serial:
        return ["adb", "-s", adb_serial]
    return ["adb"]


def get_connected_serial():
    """Find the first active ADB device serial if none is pinned."""
    global adb_serial
    try:
        res = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            text=True,
            timeout=3,
        )
        lines = [line.strip() for line in res.stdout.splitlines()[1:] if line.strip()]
        devices = [line.split()[0] for line in lines if "\tdevice" in line or " device" in line]
        if devices:
            if adb_serial not in devices:
                adb_serial = devices[0]
            return adb_serial
        return None
    except Exception:
        return None


def refresh_device_resolution():
    """Query physical screen resolution using `adb shell wm size`."""
    try:
        cmd = get_adb_base_cmd() + ["shell", "wm", "size"]
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=3)
        # Prefer Override size if set, otherwise Physical size
        matches = re.findall(r"(\d+)x(\d+)", res.stdout)
        if matches:
            w, h = int(matches[-1][0]), int(matches[-1][1])
            with state_lock:
                device_size[0] = w
                device_size[1] = h
            return w, h
    except Exception:
        pass
    with state_lock:
        return device_size[0], device_size[1]


def capture_screen_frame():
    """Capture screen via `adb exec-out screencap -p` and optionally compress to JPEG."""
    cmd = get_adb_base_cmd() + ["exec-out", "screencap", "-p"]
    proc = subprocess.run(cmd, capture_output=True, timeout=5)
    if proc.returncode != 0 or not proc.stdout:
        err = proc.stderr.decode("utf-8", errors="ignore").strip()
        raise RuntimeError(err or "ADB screencap returned empty output")

    raw_png = proc.stdout
    with state_lock:
        preset = QUALITY_PRESETS.get(current_preset, QUALITY_PRESETS["balanced"])

    if HAS_PILLOW:
        img = Image.open(io.BytesIO(raw_png))
        orig_w, orig_h = img.size
        with state_lock:
            device_size[0] = orig_w
            device_size[1] = orig_h

        scale = preset["scale"]
        if scale < 0.99:
            new_w = max(1, int(orig_w * scale))
            new_h = max(1, int(orig_h * scale))
            img = img.resize((new_w, new_h), Image.Resampling.BILINEAR)

        if img.mode != "RGB":
            img = img.convert("RGB")

        buf = io.BytesIO()
        img.save(buf, format="JPEG", quality=preset["jpeg_quality"], optimize=True)
        payload = buf.getvalue()
        mime = "image/jpeg"
        return payload, mime, orig_w, orig_h

    # Fallback without Pillow: send raw PNG bytes
    with state_lock:
        orig_w, orig_h = device_size[0], device_size[1]
    return raw_png, "image/png", orig_w, orig_h


def run_adb_shell(*args):
    """Execute an ADB shell command safely in background."""
    try:
        cmd = get_adb_base_cmd() + ["shell", *args]
        subprocess.run(cmd, capture_output=True, timeout=4)
    except Exception as exc:
        print(f"[ADB input error] {' '.join(args)} -> {exc}")


def stream_loop():
    """Continuously capture frames and broadcast over Socket.IO while clients are connected."""
    print("[Stream] Background capture loop started")
    refresh_device_resolution()
    last_ts = time.perf_counter()
    fps_ema = 0.0

    while not stream_stop_event.is_set():
        with state_lock:
            active = connected_clients > 0
            interval = QUALITY_PRESETS.get(current_preset, QUALITY_PRESETS["balanced"])["interval"]

        if not active:
            break

        frame_start = time.perf_counter()
        try:
            frame_bytes, mime, dev_w, dev_h = capture_screen_frame()
            now = time.perf_counter()
            dt = max(0.001, now - last_ts)
            last_ts = now
            instant_fps = 1.0 / dt
            fps_ema = instant_fps if fps_ema == 0.0 else (fps_ema * 0.7 + instant_fps * 0.3)

            b64_data = base64.b64encode(frame_bytes).decode("ascii")
            socketio.emit(
                "screen_frame",
                {
                    "image": b64_data,
                    "mime": mime,
                    "width": dev_w,
                    "height": dev_h,
                    "fps": round(fps_ema, 1),
                    "kb": round(len(frame_bytes) / 1024.0, 1),
                },
            )
        except Exception as exc:
            serial = get_connected_serial()
            socketio.emit(
                "adb_status",
                {
                    "connected": serial is not None,
                    "serial": serial,
                    "error": str(exc),
                },
            )
            socketio.sleep(1.0)
            continue

        elapsed = time.perf_counter() - frame_start
        sleep_time = max(0.01, interval - elapsed)
        socketio.sleep(sleep_time)

    print("[Stream] Background capture loop stopped")


def ensure_stream_running():
    global stream_thread
    with state_lock:
        if stream_thread is None or not stream_thread.is_alive():
            stream_stop_event.clear()
            stream_thread = socketio.start_background_task(stream_loop)


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/api/status")
def api_status():
    serial = get_connected_serial()
    w, h = refresh_device_resolution() if serial else tuple(device_size)
    return jsonify(
        {
            "adb_connected": serial is not None,
            "serial": serial,
            "width": w,
            "height": h,
            "pillow_enabled": HAS_PILLOW,
            "clients": connected_clients,
            "preset": current_preset,
        }
    )


@socketio.on("connect")
def handle_connect():
    global connected_clients
    with state_lock:
        connected_clients += 1
        count = connected_clients
    serial = get_connected_serial()
    w, h = refresh_device_resolution() if serial else tuple(device_size)
    emit(
        "adb_status",
        {
            "connected": serial is not None,
            "serial": serial,
            "width": w,
            "height": h,
            "preset": current_preset,
            "clients": count,
        },
    )
    ensure_stream_running()


@socketio.on("disconnect")
def handle_disconnect():
    global connected_clients
    with state_lock:
        connected_clients = max(0, connected_clients - 1)
        if connected_clients == 0:
            stream_stop_event.set()


@socketio.on("tap")
def handle_tap(data):
    with state_lock:
        dev_w, dev_h = device_size

    try:
        x = int(round(float(data.get("x", 0))))
        y = int(round(float(data.get("y", 0))))
    except (TypeError, ValueError):
        return

    x = max(0, min(dev_w - 1, x))
    y = max(0, min(dev_h - 1, y))
    input_executor.submit(run_adb_shell, "input", "tap", str(x), str(y))


@socketio.on("swipe")
def handle_swipe(data):
    with state_lock:
        dev_w, dev_h = device_size

    try:
        x1 = int(round(float(data.get("x1", 0))))
        y1 = int(round(float(data.get("y1", 0))))
        x2 = int(round(float(data.get("x2", 0))))
        y2 = int(round(float(data.get("y2", 0))))
        duration = int(data.get("duration_ms", 220))
    except (TypeError, ValueError):
        return

    x1 = max(0, min(dev_w - 1, x1))
    y1 = max(0, min(dev_h - 1, y1))
    x2 = max(0, min(dev_w - 1, x2))
    y2 = max(0, min(dev_h - 1, y2))
    duration = max(50, min(2000, duration))

    input_executor.submit(
        run_adb_shell,
        "input",
        "swipe",
        str(x1),
        str(y1),
        str(x2),
        str(y2),
        str(duration),
    )


@socketio.on("keyevent")
def handle_keyevent(data):
    action = str(data.get("action", "")).lower().strip()
    keycode = KEYCODE_MAP.get(action)
    if keycode is None:
        return
    input_executor.submit(run_adb_shell, "input", "keyevent", str(keycode))


@socketio.on("send_text")
def handle_send_text(data):
    text = str(data.get("text", ""))
    if not text:
        return
    escaped = (
        text.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace(" ", "%s")
        .replace("'", "\\'")
        .replace("&", "\\&")
        .replace("<", "\\<")
        .replace(">", "\\>")
        .replace("|", "\\|")
        .replace(";", "\\;")
    )
    input_executor.submit(run_adb_shell, "input", "text", escaped)


@socketio.on("stream_config")
def handle_stream_config(data):
    global current_preset
    preset = str(data.get("preset", "balanced")).lower()
    if preset in QUALITY_PRESETS:
        with state_lock:
            current_preset = preset
        emit("adb_status", {"preset": current_preset}, broadcast=True)


@socketio.on("adb_connect")
def handle_adb_connect(data):
    global adb_serial
    target = str(data.get("target", "")).strip()
    if not target:
        return
    try:
        res = subprocess.run(["adb", "connect", target], capture_output=True, text=True, timeout=6)
        output = (res.stdout + " " + res.stderr).strip()
        serial = get_connected_serial()
        if serial:
            refresh_device_resolution()
        emit(
            "adb_status",
            {
                "connected": serial is not None,
                "serial": serial,
                "message": output,
            },
        )
    except Exception as exc:
        emit("adb_status", {"connected": False, "error": str(exc)})


if __name__ == "__main__":
    host = os.environ.get("HOST", "0.0.0.0")
    port = int(os.environ.get("PORT", "5000"))
    print(f"Starting TermuxCast server on http://{host}:{port}")
    print(f"Pillow JPEG compression: {'ENABLED' if HAS_PILLOW else 'DISABLED (raw PNG fallback)'}")
    socketio.run(app, host=host, port=port, allow_unsafe_werkzeug=True)
