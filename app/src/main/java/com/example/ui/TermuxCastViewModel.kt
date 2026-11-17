package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DeviceTelemetry
import com.example.data.ServerFileAsset
import com.example.data.ServerProbeResult
import com.example.data.TermuxBundleRepository
import com.example.service.InternetRelayClient
import com.example.service.NativeHostState
import com.example.service.RemoteControlAccessibilityService
import com.example.service.ScreenCastService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdbCommandLogEntry(
  val id: Long,
  val timestamp: String,
  val wsEvent: String,
  val wsPayload: String,
  val adbCommand: String,
)

data class SetupStepItem(
  val id: String,
  val stepNumber: Int,
  val title: String,
  val description: String,
  val command: String?,
  val tip: String? = null,
)

data class TermuxCastUiState(
  val telemetry: DeviceTelemetry,
  val nativeHostState: NativeHostState = NativeHostState(),
  val accessibilityEnabled: Boolean = false,
  val relayUrlInput: String = "",
  val relayEnabled: Boolean = false,
  val roomId: String = "",
  val serverUrlInput: String,
  val probeResult: ServerProbeResult = ServerProbeResult.Idle,
  val activeControlMode: ControlCanvasMode = ControlCanvasMode.COORDINATE_MAPPER,
  val lastTapNormalized: Pair<Float, Float>? = null,
  val lastTapDevice: Pair<Int, Int>? = null,
  val lastSwipeDevice: Pair<Pair<Int, Int>, Pair<Int, Int>>? = null,
  val commandLogs: List<AdbCommandLogEntry> = emptyList(),
  val setupSteps: List<SetupStepItem> = emptyList(),
  val completedStepIds: Set<String> = emptySet(),
  val serverFiles: List<ServerFileAsset> = emptyList(),
  val selectedFileId: String = "app_py",
) {
  val selectedFile: ServerFileAsset?
    get() = serverFiles.firstOrNull { it.id == selectedFileId } ?: serverFiles.firstOrNull()

  val browserStreamUrl: String
    get() = "http://${telemetry.localIp}:5000"

  val nativeHostPrimaryUrl: String
    get() = "http://${telemetry.localIp}:${nativeHostState.port}"

  val nativeHostAllUrls: List<String>
    get() = telemetry.candidateIps.map { ip -> "http://$ip:${nativeHostState.port}" }

  val publicInternetUrl: String
    get() {
      val activeUrl = nativeHostState.internetRelay.publicViewUrl
      if (activeUrl.isNotBlank()) return activeUrl
      val normalized = InternetRelayClient.normalizeHttpBase(relayUrlInput)
      return if (normalized.isNotBlank() && roomId.isNotBlank()) "$normalized/view/$roomId" else ""
    }
}

enum class ControlCanvasMode {
  COORDINATE_MAPPER,
  LIVE_WEB_VIEWER,
}

class TermuxCastViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = TermuxBundleRepository(application.applicationContext)
  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
  private var logSequence = 0L

  private val _uiState: MutableStateFlow<TermuxCastUiState>

  init {
    val telemetry = repository.readTelemetry()
    val files = repository.loadBundledFiles()
    val completed = repository.getCompletedStepIds()
    val steps = buildSetupSteps(telemetry.localIp)
    val initialAccess =
      isAccessibilityEnabledInSettings() || RemoteControlAccessibilityService.isConnected.value
    val savedRelayUrl = repository.getRelayUrl()
    val savedRelayEnabled = repository.isRelayEnabled()
    val savedRoomId = repository.getOrCreateRoomId()

    _uiState =
      MutableStateFlow(
        TermuxCastUiState(
          telemetry = telemetry,
          nativeHostState = ScreenCastService.hostState.value,
          accessibilityEnabled = initialAccess,
          relayUrlInput = savedRelayUrl,
          relayEnabled = savedRelayEnabled,
          roomId = savedRoomId,
          serverUrlInput = "http://127.0.0.1:5000",
          setupSteps = steps,
          completedStepIds = completed,
          serverFiles = files,
          commandLogs =
            listOf(
              AdbCommandLogEntry(
                id = ++logSequence,
                timestamp = timeFormat.format(Date()),
                wsEvent = "adb_status",
                wsPayload = """{"width":${telemetry.screenWidth},"height":${telemetry.screenHeight}}""",
                adbCommand = "adb shell wm size -> ${telemetry.screenWidth}x${telemetry.screenHeight}",
              )
            ),
        )
      )

    viewModelScope.launch {
      combine(ScreenCastService.hostState, RemoteControlAccessibilityService.isConnected) {
        host,
        connected ->
        host to (connected || isAccessibilityEnabledInSettings())
      }.collect { (host, accessEnabled) ->
        _uiState.update { state ->
          state.copy(nativeHostState = host, accessibilityEnabled = accessEnabled)
        }
      }
    }
  }

  val uiState: StateFlow<TermuxCastUiState> = _uiState.asStateFlow()

  fun refreshTelemetry() {
    val telemetry = repository.readTelemetry()
    val accessEnabled =
      RemoteControlAccessibilityService.isConnected.value || isAccessibilityEnabledInSettings()
    _uiState.update { state ->
      state.copy(
        telemetry = telemetry,
        accessibilityEnabled = accessEnabled,
        setupSteps = buildSetupSteps(telemetry.localIp),
      )
    }
  }

  private fun isAccessibilityEnabledInSettings(): Boolean {
    return try {
      val app = getApplication<Application>()
      val expected = ComponentName(app, RemoteControlAccessibilityService::class.java)
      val enabled =
        Settings.Secure.getString(
          app.contentResolver,
          Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
      enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    } catch (_: Exception) {
      false
    }
  }

  fun updateRelayUrlInput(url: String) {
    _uiState.update { it.copy(relayUrlInput = url) }
    repository.saveRelayConfig(url, _uiState.value.relayEnabled)
  }

  fun applyInternetRelay(enabled: Boolean) {
    val currentUrl = _uiState.value.relayUrlInput
    val roomId = _uiState.value.roomId
    repository.saveRelayConfig(currentUrl, enabled)
    _uiState.update { it.copy(relayEnabled = enabled) }

    if (_uiState.value.nativeHostState.isHosting) {
      val app = getApplication<Application>()
      val intent =
        Intent(app, ScreenCastService::class.java).apply {
          action = ScreenCastService.ACTION_CONFIGURE_RELAY
          putExtra(ScreenCastService.EXTRA_RELAY_ENABLED, enabled)
          putExtra(ScreenCastService.EXTRA_RELAY_URL, currentUrl)
          putExtra(ScreenCastService.EXTRA_RELAY_ROOM_ID, roomId)
        }
      app.startService(intent)
    }
  }

  fun regenerateRoomId() {
    val nextId = repository.regenerateRoomId()
    _uiState.update { it.copy(roomId = nextId) }
    if (_uiState.value.nativeHostState.isHosting && _uiState.value.relayEnabled) {
      applyInternetRelay(true)
    }
  }

  fun updateServerUrl(url: String) {
    _uiState.update { it.copy(serverUrlInput = url) }
  }

  fun setControlMode(mode: ControlCanvasMode) {
    _uiState.update { it.copy(activeControlMode = mode) }
  }

  fun probeTermuxServer() {
    val url = _uiState.value.serverUrlInput
    _uiState.update { it.copy(probeResult = ServerProbeResult.Checking) }
    viewModelScope.launch {
      val result = repository.probeServer(url)
      _uiState.update { it.copy(probeResult = result) }
    }
  }

  fun recordSimulatedTap(normX: Float, normY: Float) {
    val telemetry = _uiState.value.telemetry
    val devX = (normX.coerceIn(0f, 1f) * telemetry.screenWidth).toInt().coerceIn(0, telemetry.screenWidth - 1)
    val devY = (normY.coerceIn(0f, 1f) * telemetry.screenHeight).toInt().coerceIn(0, telemetry.screenHeight - 1)

    val entry =
      AdbCommandLogEntry(
        id = ++logSequence,
        timestamp = timeFormat.format(Date()),
        wsEvent = "tap",
        wsPayload = """{"x":$devX,"y":$devY}""",
        adbCommand = "adb shell input tap $devX $devY",
      )

    _uiState.update { state ->
      state.copy(
        lastTapNormalized = normX to normY,
        lastTapDevice = devX to devY,
        lastSwipeDevice = null,
        commandLogs = (listOf(entry) + state.commandLogs).take(14),
      )
    }
  }

  fun recordSimulatedSwipe(
    startNormX: Float,
    startNormY: Float,
    endNormX: Float,
    endNormY: Float,
    durationMs: Int = 220,
  ) {
    val telemetry = _uiState.value.telemetry
    val x1 = (startNormX.coerceIn(0f, 1f) * telemetry.screenWidth).toInt().coerceIn(0, telemetry.screenWidth - 1)
    val y1 = (startNormY.coerceIn(0f, 1f) * telemetry.screenHeight).toInt().coerceIn(0, telemetry.screenHeight - 1)
    val x2 = (endNormX.coerceIn(0f, 1f) * telemetry.screenWidth).toInt().coerceIn(0, telemetry.screenWidth - 1)
    val y2 = (endNormY.coerceIn(0f, 1f) * telemetry.screenHeight).toInt().coerceIn(0, telemetry.screenHeight - 1)

    val entry =
      AdbCommandLogEntry(
        id = ++logSequence,
        timestamp = timeFormat.format(Date()),
        wsEvent = "swipe",
        wsPayload = """{"x1":$x1,"y1":$y1,"x2":$x2,"y2":$y2,"duration_ms":$durationMs}""",
        adbCommand = "adb shell input swipe $x1 $y1 $x2 $y2 $durationMs",
      )

    _uiState.update { state ->
      state.copy(
        lastTapNormalized = endNormX to endNormY,
        lastTapDevice = x2 to y2,
        lastSwipeDevice = (x1 to y1) to (x2 to y2),
        commandLogs = (listOf(entry) + state.commandLogs).take(14),
      )
    }
  }

  fun recordKeyEvent(action: String) {
    val keycode =
      when (action) {
        "back" -> 4
        "home" -> 3
        "recents" -> 187
        else -> 3
      }
    val entry =
      AdbCommandLogEntry(
        id = ++logSequence,
        timestamp = timeFormat.format(Date()),
        wsEvent = "keyevent",
        wsPayload = """{"action":"$action"}""",
        adbCommand = "adb shell input keyevent $keycode",
      )
    _uiState.update { state ->
      state.copy(commandLogs = (listOf(entry) + state.commandLogs).take(14))
    }
  }

  fun toggleSetupStep(stepId: String) {
    val currentlyDone = stepId in _uiState.value.completedStepIds
    val nextDone = !currentlyDone
    repository.setStepCompleted(stepId, nextDone)
    _uiState.update { state ->
      val updated =
        if (nextDone) state.completedStepIds + stepId else state.completedStepIds - stepId
      state.copy(completedStepIds = updated)
    }
  }

  fun selectFile(fileId: String) {
    _uiState.update { it.copy(selectedFileId = fileId) }
  }

  private fun buildSetupSteps(localIp: String): List<SetupStepItem> =
    listOf(
      SetupStepItem(
        id = "step_pkg",
        stepNumber = 1,
        title = "Install Python, Pillow & ADB in Termux",
        description =
          "Install Termux from F-Droid or GitHub Releases (not the outdated Play Store build), then update packages and install Python, Pillow for fast JPEG compression, and android-tools (adb).",
        command = "pkg update -y && pkg upgrade -y && pkg install -y python python-pillow android-tools",
        tip = "Installing python-pillow via pkg avoids compiling C extensions inside pip.",
      ),
      SetupStepItem(
        id = "step_adb",
        stepNumber = 2,
        title = "Enable Wireless Debugging & Pair Local ADB",
        description =
          "Open Developer Options -> Wireless debugging -> Pair device with pairing code. Keep the pairing dialog visible in split-screen or notification shade and run pair + connect inside Termux.",
        command = "adb pair 127.0.0.1:<PAIR_PORT> <PAIR_CODE>\nadb connect 127.0.0.1:<DEBUG_PORT>\nadb devices",
        tip = "Verify `adb devices` prints `127.0.0.1:<PORT>    device` before starting Flask.",
      ),
      SetupStepItem(
        id = "step_files",
        stepNumber = 3,
        title = "Create Project Folder & Write Server Files",
        description =
          "Create ~/termux-cast/templates/ and copy app.py, templates/index.html, and requirements.txt — or tap 'Copy 1-Paste Installer' below and paste directly into Termux.",
        command = "mkdir -p ~/termux-cast/templates && cd ~/termux-cast",
        tip = "Use the Server Files tab to inspect, copy, or share each file individually.",
      ),
      SetupStepItem(
        id = "step_pip",
        stepNumber = 4,
        title = "Install Flask & Flask-SocketIO Dependencies",
        description =
          "Install Flask, Flask-SocketIO, python-socketio, and simple-websocket inside your Termux workspace.",
        command = "cd ~/termux-cast && pip install -r requirements.txt",
        tip = "Uses threading async_mode + simple-websocket so no greenlet build is required.",
      ),
      SetupStepItem(
        id = "step_run",
        stepNumber = 5,
        title = "Launch Server & Open Browser on Another Device",
        description =
          "Run the Flask-SocketIO server on port 5000, then open your phone's LAN IP in any browser on your laptop, tablet, or second phone on the same Wi-Fi.",
        command = "cd ~/termux-cast && python app.py\n# Open in browser: http://$localIp:5000",
        tip = "Set PORT=8080 python app.py if port 5000 is already in use.",
      ),
    )
}
