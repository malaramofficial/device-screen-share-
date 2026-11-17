package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeveloperMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.ServerProbeResult
import com.example.service.ScreenCastService
import com.example.ui.AdbCommandLogEntry
import com.example.ui.ControlCanvasMode
import com.example.ui.TermuxCastUiState
import com.example.ui.components.StatusPill
import com.example.ui.components.copyToClipboard
import com.example.ui.components.shareTextContent

@Composable
fun ControlStudioScreen(
  state: TermuxCastUiState,
  onRefreshTelemetry: () -> Unit,
  onUpdateServerUrl: (String) -> Unit,
  onProbeServer: () -> Unit,
  onSetControlMode: (ControlCanvasMode) -> Unit,
  onSimulatedTap: (Float, Float) -> Unit,
  onSimulatedSwipe: (Float, Float, Float, Float, Int) -> Unit,
  onKeyEvent: (String) -> Unit,
  onShowMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val haptics = LocalHapticFeedback.current
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        onRefreshTelemetry()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  val projectionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
      if (result.resultCode == Activity.RESULT_OK && result.data != null) {
        val intent =
          Intent(context, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, result.data)
          }
        ContextCompat.startForegroundService(context, intent)
        onRefreshTelemetry()
        onShowMessage("Screen Host started on ${state.nativeHostPrimaryUrl}")
      } else {
        onShowMessage("Screen capture permission was cancelled")
      }
    }

  val launchScreenCaptureConsent: () -> Unit = {
    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    projectionLauncher.launch(mgr.createScreenCaptureIntent())
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
      launchScreenCaptureConsent()
    }

  val startNativeHost: () -> Unit = {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      launchScreenCaptureConsent()
    }
  }

  val stopNativeHost: () -> Unit = {
    val intent =
      Intent(context, ScreenCastService::class.java).apply {
        action = ScreenCastService.ACTION_STOP
      }
    context.startService(intent)
    onShowMessage("Screen Host stopped")
  }

  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    // 1. Instant Native Wi-Fi / Hotspot Host Card (Zero Termux / Zero ADB)
    item {
      NativeHostCard(
        state = state,
        onStartHost = startNativeHost,
        onStopHost = stopNativeHost,
        onRefresh = {
          onRefreshTelemetry()
          onShowMessage("Network IP & permissions refreshed")
        },
        onOpenAccessibilitySettings = {
          val intent =
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          runCatching { context.startActivity(intent) }
        },
        onCopyUrl = { url ->
          copyToClipboard(context, "TermuxCast URL", url)
          onShowMessage("Copied $url")
        },
        onShareUrl = { url ->
          shareTextContent(
            context,
            "Open this URL in browser to view & control phone",
            url,
          )
        },
      )
    }

    // 2. Device Telemetry & Quick Developer Options Card
    item {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = state.telemetry.deviceModel.ifBlank { "Android Device" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "Android ${state.telemetry.androidRelease} (SDK ${state.telemetry.sdkInt})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = {
                onRefreshTelemetry()
                onShowMessage("Telemetry refreshed")
              },
              modifier = Modifier.size(48.dp).testTag("refresh_telemetry_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh device telemetry",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            StatusPill(
              label = "${state.telemetry.screenWidth} × ${state.telemetry.screenHeight} px",
              dotColor = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.weight(1f),
            )
            StatusPill(
              label = "Termux: ${state.browserStreamUrl}",
              dotColor = MaterialTheme.colorScheme.primary,
              modifier = Modifier.weight(1f),
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilledTonalButton(
              onClick = {
                copyToClipboard(context, "Stream URL", state.browserStreamUrl)
                onShowMessage("Copied ${state.browserStreamUrl}")
              },
              modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("copy_stream_url_button"),
            ) {
              Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Copy Termux URL", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            OutlinedButton(
              onClick = {
                val devIntent =
                  Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                  )
                runCatching { context.startActivity(devIntent) }
                  .onFailure {
                    val fallback =
                      Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(fallback) }
                  }
              },
              modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("open_dev_options_button"),
            ) {
              Icon(Icons.Rounded.DeveloperMode, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(6.dp))
              Text("Wireless ADB", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
    }

    // 3. Remote Coordinate Mapper & Live Web Viewer
    item {
      Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.CastConnected,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "Coordinate Tester & Web Preview",
              style = MaterialTheme.typography.titleMedium,
            )
          }

          SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
              selected = state.activeControlMode == ControlCanvasMode.COORDINATE_MAPPER,
              onClick = { onSetControlMode(ControlCanvasMode.COORDINATE_MAPPER) },
              shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
              modifier = Modifier.testTag("mode_mapper_button"),
            ) {
              Text("Touch Mapper")
            }
            SegmentedButton(
              selected = state.activeControlMode == ControlCanvasMode.LIVE_WEB_VIEWER,
              onClick = { onSetControlMode(ControlCanvasMode.LIVE_WEB_VIEWER) },
              shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
              modifier = Modifier.testTag("mode_live_web_button"),
            ) {
              Text("Live Web View")
            }
          }

          if (state.activeControlMode == ControlCanvasMode.COORDINATE_MAPPER) {
            InteractiveCoordinateCanvas(
              screenWidth = state.telemetry.screenWidth,
              screenHeight = state.telemetry.screenHeight,
              lastTapNormalized = state.lastTapNormalized,
              lastTapDevice = state.lastTapDevice,
              onTap = { nx, ny ->
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSimulatedTap(nx, ny)
              },
              onSwipe = { x1, y1, x2, y2, dur ->
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onSimulatedSwipe(x1, y1, x2, y2, dur)
              },
            )
          } else {
            LiveFlaskWebSection(
              url = state.serverUrlInput,
              probeResult = state.probeResult,
              onUrlChange = onUpdateServerUrl,
              onProbeServer = onProbeServer,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            VirtualNavKeyButton(
              label = "Back",
              icon = Icons.AutoMirrored.Rounded.ArrowBack,
              testTag = "virtual_back_button",
              onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onKeyEvent("back")
              },
              modifier = Modifier.weight(1f),
            )
            VirtualNavKeyButton(
              label = "Home",
              icon = Icons.Rounded.Home,
              testTag = "virtual_home_button",
              onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onKeyEvent("home")
              },
              modifier = Modifier.weight(1f),
            )
            VirtualNavKeyButton(
              label = "Recents",
              icon = Icons.Rounded.Layers,
              testTag = "virtual_recents_button",
              onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onKeyEvent("recents")
              },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    // 4. Live Command Log
    item {
      Text(
        text = "WebSocket -> ADB Execution Log",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    items(state.commandLogs, key = { it.id }) { entry ->
      AdbLogRow(entry = entry)
    }
  }
}

@Composable
private fun NativeHostCard(
  state: TermuxCastUiState,
  onStartHost: () -> Unit,
  onStopHost: () -> Unit,
  onRefresh: () -> Unit,
  onOpenAccessibilitySettings: () -> Unit,
  onCopyUrl: (String) -> Unit,
  onShareUrl: (String) -> Unit,
) {
  val host = state.nativeHostState
  val isHosting = host.isHosting

  Surface(
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.surface,
    border =
      BorderStroke(
        width = 1.5.dp,
        color =
          if (isHosting) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outline,
      ),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Instant Wi-Fi / Hotspot Host",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text =
              if (isHosting) "Open this URL in any browser on the same Wi-Fi or Hotspot"
              else "Stream this phone's screen & allow browser touch control without Termux",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        StatusPill(
          label = if (isHosting) "LIVE" else "READY",
          dotColor =
            if (isHosting) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary,
        )
      }

      // Prominent Instant URL Box
      Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "BROWSER URL",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = state.nativeHostPrimaryUrl,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          IconButton(
            onClick = { onCopyUrl(state.nativeHostPrimaryUrl) },
            modifier = Modifier.size(48.dp).testTag("copy_native_url_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.ContentCopy,
              contentDescription = "Copy browser URL",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(
            onClick = { onShareUrl(state.nativeHostPrimaryUrl) },
            modifier = Modifier.size(48.dp).testTag("share_native_url_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Share,
              contentDescription = "Share browser URL",
              tint = MaterialTheme.colorScheme.secondary,
            )
          }
        }
      }

      if (state.nativeHostAllUrls.size > 1) {
        Text(
          text = "Other detected IPs: ${state.nativeHostAllUrls.drop(1).joinToString("  •  ")}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
        )
      }

      // Accessibility Control Status Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusPill(
          label =
            if (state.accessibilityEnabled) "Touch Control: Enabled"
            else "Touch Control: Off",
          dotColor =
            if (state.accessibilityEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.weight(1f),
        )
        if (!state.accessibilityEnabled) {
          OutlinedButton(
            onClick = onOpenAccessibilitySettings,
            modifier = Modifier.heightIn(min = 48.dp).testTag("enable_accessibility_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.AccessibilityNew,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("Enable Touch")
          }
        } else {
          IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Refresh,
              contentDescription = "Refresh status",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      if (isHosting) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          StatusPill(
            label = "Viewers: ${host.activeViewers}",
            dotColor = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
          )
          StatusPill(
            label = "${String.format(java.util.Locale.US, "%.1f", host.fps)} FPS",
            dotColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
          )
        }
        host.lastInputEvent?.let { evt ->
          Text(
            text = "Last Browser Action: $evt",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
          )
        }
      }

      host.errorMessage?.let { err ->
        Text(
          text = err,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.error,
        )
      }

      Button(
        onClick = if (isHosting) onStopHost else onStartHost,
        colors =
          if (isHosting) {
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError,
            )
          } else {
            ButtonDefaults.buttonColors()
          },
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag("toggle_native_host_button"),
      ) {
        Icon(
          imageVector = if (isHosting) Icons.Rounded.StopCircle else Icons.Rounded.Cast,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = if (isHosting) "Stop Screen Host" else "Start Screen Host & Get Live Stream",
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun InteractiveCoordinateCanvas(
  screenWidth: Int,
  screenHeight: Int,
  lastTapNormalized: Pair<Float, Float>?,
  lastTapDevice: Pair<Int, Int>?,
  onTap: (Float, Float) -> Unit,
  onSwipe: (Float, Float, Float, Float, Int) -> Unit,
) {
  var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
  var dragStart by remember { mutableStateOf<Offset?>(null) }
  var dragCurrent by remember { mutableStateOf<Offset?>(null) }
  var dragStartTime by remember { mutableStateOf(0L) }
  val rippleProgress = remember { Animatable(1f) }

  LaunchedEffect(lastTapNormalized) {
    if (lastTapNormalized != null) {
      rippleProgress.snapTo(0f)
      rippleProgress.animateTo(1f, animationSpec = tween(durationMillis = 420))
    }
  }

  val ratio =
    if (screenWidth > 0 && screenHeight > 0) {
      (screenWidth.toFloat() / screenHeight.toFloat()).coerceIn(0.38f, 0.75f)
    } else {
      0.45f
    }

  val primaryColor = MaterialTheme.colorScheme.primary
  val secondaryColor = MaterialTheme.colorScheme.secondary
  val outlineColor = MaterialTheme.colorScheme.outline
  val bgSurface = MaterialTheme.colorScheme.background

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(
      modifier =
        Modifier.fillMaxWidth(0.62f)
          .aspectRatio(ratio)
          .background(bgSurface, MaterialTheme.shapes.medium)
          .onSizeChanged { canvasSize = it }
          .pointerInput(screenWidth, screenHeight) {
            detectTapGestures { offset ->
              val w = size.width.coerceAtLeast(1).toFloat()
              val h = size.height.coerceAtLeast(1).toFloat()
              onTap((offset.x / w).coerceIn(0f, 1f), (offset.y / h).coerceIn(0f, 1f))
            }
          }
          .pointerInput(screenWidth, screenHeight) {
            detectDragGestures(
              onDragStart = { start ->
                dragStart = start
                dragCurrent = start
                dragStartTime = System.currentTimeMillis()
              },
              onDrag = { change, _ ->
                dragCurrent = change.position
              },
              onDragEnd = {
                val s = dragStart
                val e = dragCurrent
                if (s != null && e != null) {
                  val w = size.width.coerceAtLeast(1).toFloat()
                  val h = size.height.coerceAtLeast(1).toFloat()
                  val elapsed = (System.currentTimeMillis() - dragStartTime).toInt().coerceIn(120, 1200)
                  onSwipe(
                    (s.x / w).coerceIn(0f, 1f),
                    (s.y / h).coerceIn(0f, 1f),
                    (e.x / w).coerceIn(0f, 1f),
                    (e.y / h).coerceIn(0f, 1f),
                    elapsed,
                  )
                }
                dragStart = null
                dragCurrent = null
              },
              onDragCancel = {
                dragStart = null
                dragCurrent = null
              },
            )
          }
          .testTag("interactive_coordinate_canvas"),
    ) {
      Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
          brush =
            Brush.radialGradient(
              colors = listOf(primaryColor.copy(alpha = 0.14f), Color.Transparent),
              center = Offset(size.width * 0.5f, size.height * 0.35f),
              radius = size.maxDimension * 0.7f,
            )
        )

        val cols = 4
        val rows = 8
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        for (i in 1 until cols) {
          val x = size.width * i / cols
          drawLine(
            color = outlineColor.copy(alpha = 0.55f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
            pathEffect = dash,
          )
        }
        for (j in 1 until rows) {
          val y = size.height * j / rows
          drawLine(
            color = outlineColor.copy(alpha = 0.55f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = dash,
          )
        }

        drawRoundRect(
          color = outlineColor,
          style = Stroke(width = 2.dp.toPx()),
        )

        val s = dragStart
        val c = dragCurrent
        if (s != null && c != null) {
          drawLine(
            color = secondaryColor,
            start = s,
            end = c,
            strokeWidth = 3.dp.toPx(),
          )
          drawCircle(color = secondaryColor, radius = 7.dp.toPx(), center = s)
          drawCircle(color = primaryColor, radius = 7.dp.toPx(), center = c)
        }

        lastTapNormalized?.let { (nx, ny) ->
          val cx = nx * size.width
          val cy = ny * size.height
          val radius = 10.dp.toPx() + (rippleProgress.value * 26.dp.toPx())
          val alpha = (1f - rippleProgress.value).coerceIn(0.18f, 1f)

          drawCircle(
            color = primaryColor.copy(alpha = alpha),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.dp.toPx()),
          )
          drawCircle(
            color = primaryColor,
            radius = 5.dp.toPx(),
            center = Offset(cx, cy),
          )
          drawLine(
            color = primaryColor.copy(alpha = 0.45f),
            start = Offset(0f, cy),
            end = Offset(size.width, cy),
            strokeWidth = 1.dp.toPx(),
          )
          drawLine(
            color = primaryColor.copy(alpha = 0.45f),
            start = Offset(cx, 0f),
            end = Offset(cx, size.height),
            strokeWidth = 1.dp.toPx(),
          )
        }
      }

      if (lastTapNormalized == null) {
        Column(
          modifier = Modifier.align(Alignment.Center).padding(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.TouchApp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
          )
          Text(
            text = "Tap or swipe here\nto test coordinates",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    val coordLabel =
      lastTapDevice?.let { (dx, dy) -> "Mapped Target: X=$dx, Y=$dy px" }
        ?: "Canvas ready ($screenWidth × $screenHeight)"
    Text(
      text = coordLabel,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveFlaskWebSection(
  url: String,
  probeResult: ServerProbeResult,
  onUrlChange: (String) -> Unit,
  onProbeServer: () -> Unit,
) {
  var loadedUrl by remember { mutableStateOf(url) }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text("Server URL") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(1f).testTag("server_url_input"),
      )
      Button(
        onClick = {
          loadedUrl = url
          onProbeServer()
        },
        modifier = Modifier.heightIn(min = 52.dp).testTag("probe_server_button"),
      ) {
        if (probeResult is ServerProbeResult.Checking) {
          CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
        } else {
          Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("Connect")
        }
      }
    }

    when (probeResult) {
      is ServerProbeResult.Online -> {
        StatusPill(
          label =
            "Server Online • ADB ${if (probeResult.adbConnected) "Ready" else "Pending"} • ${probeResult.width}x${probeResult.height}",
          dotColor = MaterialTheme.colorScheme.primary,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      is ServerProbeResult.Offline -> {
        StatusPill(
          label = "Offline: ${probeResult.reason}",
          dotColor = MaterialTheme.colorScheme.error,
          modifier = Modifier.fillMaxWidth(),
        )
      }
      else -> {
        StatusPill(
          label = "Enter http://127.0.0.1:8080 or Termux :5000 URL",
          dotColor = MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.background,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.fillMaxWidth().height(340.dp),
    ) {
      AndroidView(
        factory = { ctx ->
          WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()
            loadUrl(loadedUrl)
          }
        },
        update = { webView ->
          if (webView.url != loadedUrl) {
            webView.loadUrl(loadedUrl)
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

@Composable
private fun VirtualNavKeyButton(
  label: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  testTag: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FilledTonalButton(
    onClick = onClick,
    colors =
      ButtonDefaults.filledTonalButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
      ),
    modifier = modifier.heightIn(min = 50.dp).testTag(testTag),
  ) {
    Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(6.dp))
    Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
  }
}

@Composable
private fun AdbLogRow(entry: AdbCommandLogEntry) {
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "WS: ${entry.wsEvent} ${entry.wsPayload}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = entry.timestamp,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "$ ${entry.adbCommand}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
      )
    }
  }
}
