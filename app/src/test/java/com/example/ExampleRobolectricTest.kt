package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TermuxBundleRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("TermuxCast", appName)
  }

  @Test
  fun `bundled termux assets load cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = TermuxBundleRepository(context)
    val files = repo.loadBundledFiles()
    assertEquals(4, files.size)
    assertTrue(files.any { it.path == "app.py" && it.content.contains("Flask-SocketIO") || it.content.contains("flask_socketio") })
    assertTrue(files.any { it.path == "templates/index.html" && it.content.contains("screenCanvas") })
    assertTrue(files.any { it.path == "requirements.txt" && it.content.contains("Flask-SocketIO") })
  }
}
