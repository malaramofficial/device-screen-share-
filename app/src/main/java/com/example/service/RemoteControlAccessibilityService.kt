package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteControlAccessibilityService : AccessibilityService() {

  private val mainHandler = Handler(Looper.getMainLooper())

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    _isConnected.value = true
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    // No event processing required; service is used purely for global gesture & action dispatch.
  }

  override fun onInterrupt() {
    // No ongoing feedback to interrupt.
  }

  override fun onDestroy() {
    super.onDestroy()
    if (instance === this) {
      instance = null
    }
    _isConnected.value = false
  }

  fun performTap(x: Float, y: Float): Boolean {
    val safeX = x.coerceAtLeast(0f)
    val safeY = y.coerceAtLeast(0f)
    val path = Path().apply { moveTo(safeX, safeY) }
    val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    mainHandler.post {
      dispatchGesture(gesture, null, null)
    }
    return true
  }

  fun performSwipe(
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
    durationMs: Long,
  ): Boolean {
    val path =
      Path().apply {
        moveTo(x1.coerceAtLeast(0f), y1.coerceAtLeast(0f))
        lineTo(x2.coerceAtLeast(0f), y2.coerceAtLeast(0f))
      }
    val safeDuration = durationMs.coerceIn(80L, 1800L)
    val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
    val gesture = GestureDescription.Builder().addStroke(stroke).build()
    mainHandler.post {
      dispatchGesture(gesture, null, null)
    }
    return true
  }

  fun performNavAction(action: String): Boolean {
    val globalAction =
      when (action.lowercase()) {
        "back" -> GLOBAL_ACTION_BACK
        "home" -> GLOBAL_ACTION_HOME
        "recents" -> GLOBAL_ACTION_RECENTS
        "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
        "power" ->
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            GLOBAL_ACTION_LOCK_SCREEN
          } else {
            GLOBAL_ACTION_POWER_DIALOG
          }
        else -> return false
      }
    mainHandler.post {
      performGlobalAction(globalAction)
    }
    return true
  }

  companion object {
    @Volatile
    var instance: RemoteControlAccessibilityService? = null
      private set

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
  }
}
