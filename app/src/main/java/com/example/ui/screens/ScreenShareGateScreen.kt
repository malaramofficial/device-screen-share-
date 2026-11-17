package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.service.ScreenCastService
import com.example.ui.TermuxCastUiState
import com.example.ui.components.StatusPill

@Composable
fun ScreenShareGateScreen(
  state: TermuxCastUiState,
  onRefreshTelemetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var autoPromptAttempted by rememberSaveable { mutableStateOf(false) }
  var promptDeniedMessage by remember { mutableStateOf<String?>(null) }

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
        promptDeniedMessage = null
        val intent =
          Intent(context, ScreenCastService::class.java).apply {
            action = ScreenCastService.ACTION_START
            putExtra(ScreenCastService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCastService.EXTRA_RESULT_DATA, result.data)
            putExtra(ScreenCastService.EXTRA_RELAY_ENABLED, state.relayEnabled)
            putExtra(ScreenCastService.EXTRA_RELAY_URL, state.relayUrlInput)
            putExtra(ScreenCastService.EXTRA_RELAY_ROOM_ID, state.roomId)
          }
        ContextCompat.startForegroundService(context, intent)
      } else {
        promptDeniedMessage =
          "Screen sharing permission is required to open TermuxCast. Tap below and select \"Start now\"."
      }
    }

  val launchProjectionConsent: () -> Unit = {
    val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    projectionLauncher.launch(mgr.createScreenCaptureIntent())
  }

  val notificationPermissionLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
      launchProjectionConsent()
    }

  val requestScreenShareGate: () -> Unit = {
    if (
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
      launchProjectionConsent()
    }
  }

  LaunchedEffect(Unit) {
    if (!autoPromptAttempted && !state.nativeHostState.isHosting) {
      autoPromptAttempted = true
      requestScreenShareGate()
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                MaterialTheme.colorScheme.background,
              )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = MaterialTheme.shapes.large,
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      modifier = Modifier.widthIn(max = 460.dp).fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Box(
          modifier =
            Modifier.size(72.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = "Screen Share Required",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(34.dp),
          )
        }

        Text(
          text = "Screen Share Required",
          style = MaterialTheme.typography.headlineMedium,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurface,
        )

        Text(
          text =
            "TermuxCast requires active screen casting before opening. Approve the Android screen share popup to generate your live Wi-Fi & Internet control URL.",
          style = MaterialTheme.typography.bodyMedium,
          textAlign = TextAlign.Center,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusPill(
          label =
            if (state.accessibilityEnabled) "Browser Touch Control: Enabled"
            else "Browser Touch Control: Off (Optional)",
          dotColor =
            if (state.accessibilityEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.tertiary,
          modifier = Modifier.fillMaxWidth(),
        )

        val err = promptDeniedMessage ?: state.nativeHostState.errorMessage
        if (!err.isNullOrBlank()) {
          Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
          ) {
            Text(
              text = err,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
          }
        }

        Button(
          onClick = requestScreenShareGate,
          modifier =
            Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("gate_allow_screen_share_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Cast,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(8.dp))
          Text(
            text = "Allow Screen Share & Open App",
            fontWeight = FontWeight.Bold,
          )
        }

        if (!state.accessibilityEnabled) {
          OutlinedButton(
            onClick = {
              val intent =
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK
                )
              runCatching { context.startActivity(intent) }
            },
            modifier =
              Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("gate_enable_accessibility_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.AccessibilityNew,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Enable Touch Control in Accessibility")
          }
        }

        Spacer(Modifier.height(2.dp))
        Text(
          text = "Local IP ready: ${state.nativeHostPrimaryUrl}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}
