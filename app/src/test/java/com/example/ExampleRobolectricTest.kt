package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TermuxBundleRepository
import com.example.service.InternetRelayClient
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
  fun `bundled termux and relay assets load cleanly`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = TermuxBundleRepository(context)
    val files = repo.loadBundledFiles()
    assertEquals(6, files.size)
    assertTrue(files.any { it.path == "app.py" && it.content.contains("flask_socketio") })
    assertTrue(files.any { it.path == "templates/index.html" && it.content.contains("screenCanvas") })
    assertTrue(files.any { it.path == "requirements.txt" && it.content.contains("Flask-SocketIO") })
    assertTrue(files.any { it.path == "relay_server.py" && it.content.contains("/ws/host/") })
    assertTrue(files.any { it.path == "requirements_relay.txt" && it.content.contains("flask-sock") })
  }

  @Test
  fun `internet relay url normalization handles ws wss and bare hosts`() {
    assertEquals("https://demo.onrender.com", InternetRelayClient.normalizeHttpBase("wss://demo.onrender.com/"))
    assertEquals("http://192.168.1.5:10000", InternetRelayClient.normalizeHttpBase("ws://192.168.1.5:10000"))
    assertEquals("https://relay.example.com", InternetRelayClient.normalizeHttpBase("relay.example.com"))
  }

  @Test
  fun `default relay url points to render relay and is enabled`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val repo = TermuxBundleRepository(context)
    assertEquals("https://termuxcast-internet-relay.onrender.com", repo.getRelayUrl())
    assertTrue(repo.isRelayEnabled())
  }
}
