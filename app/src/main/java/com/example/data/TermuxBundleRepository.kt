package com.example.data

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class DeviceTelemetry(
  val localIp: String,
  val candidateIps: List<String>,
  val screenWidth: Int,
  val screenHeight: Int,
  val densityDpi: Int,
  val androidRelease: String,
  val sdkInt: Int,
  val deviceModel: String,
)

data class ServerFileAsset(
  val id: String,
  val path: String,
  val language: String,
  val summary: String,
  val content: String,
) {
  val lineCount: Int
    get() = content.lines().size
}

sealed interface ServerProbeResult {
  data object Idle : ServerProbeResult

  data object Checking : ServerProbeResult

  data class Online(
    val url: String,
    val adbConnected: Boolean,
    val serial: String?,
    val width: Int,
    val height: Int,
    val preset: String,
    val clients: Int,
  ) : ServerProbeResult

  data class Offline(val url: String, val reason: String) : ServerProbeResult
}

class TermuxBundleRepository(private val context: Context) {

  private val prefs = context.getSharedPreferences("termux_cast_prefs", Context.MODE_PRIVATE)

  private val httpClient =
    OkHttpClient.Builder()
      .connectTimeout(2, TimeUnit.SECONDS)
      .readTimeout(2, TimeUnit.SECONDS)
      .build()

  fun readTelemetry(): DeviceTelemetry {
    val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val (width, height, dpi) =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bounds = wm.currentWindowMetrics.bounds
        val density = context.resources.displayMetrics.densityDpi
        Triple(bounds.width(), bounds.height(), density)
      } else {
        @Suppress("DEPRECATION")
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
        Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
      }

    val ips = detectLocalIpv4Candidates()
    return DeviceTelemetry(
      localIp = ips.firstOrNull() ?: "127.0.0.1",
      candidateIps = ips,
      screenWidth = width,
      screenHeight = height,
      densityDpi = dpi,
      androidRelease = Build.VERSION.RELEASE ?: "14",
      sdkInt = Build.VERSION.SDK_INT,
      deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
    )
  }

  private fun detectLocalIpv4Candidates(): List<String> {
    return try {
      val preferred = mutableListOf<String>()
      val others = mutableListOf<String>()
      val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val name = intf.name.lowercase()
        val addrs = Collections.list(intf.inetAddresses)
        for (addr in addrs) {
          if (addr is Inet4Address && !addr.isLoopbackAddress) {
            val host = addr.hostAddress ?: continue
            if (
              name.startsWith("wlan") ||
                name.startsWith("ap") ||
                name.startsWith("swlan") ||
                name.startsWith("eth") ||
                name.startsWith("rndis")
            ) {
              if (host !in preferred) preferred.add(host)
            } else if (addr.isSiteLocalAddress) {
              if (host !in others) others.add(host)
            }
          }
        }
      }
      (preferred + others).ifEmpty { listOf("127.0.0.1") }
    } catch (_: Exception) {
      listOf("127.0.0.1")
    }
  }

  fun loadBundledFiles(): List<ServerFileAsset> {
    val requirements = readAsset("termux/requirements.txt")
    val appPy = readAsset("termux/app.py")
    val indexHtml = readAsset("termux/templates/index.html")
    val installer = buildOneShotInstaller(requirements, appPy, indexHtml)

    return listOf(
      ServerFileAsset(
        id = "app_py",
        path = "app.py",
        language = "Python",
        summary = "Flask + Flask-SocketIO backend capturing ADB screencap frames and executing tap, swipe & keyevent commands.",
        content = appPy,
      ),
      ServerFileAsset(
        id = "index_html",
        path = "templates/index.html",
        language = "HTML / JS",
        summary = "Responsive HTML5 canvas stream viewer with pointer-to-device coordinate mapping and Back/Home/Recents bar.",
        content = indexHtml,
      ),
      ServerFileAsset(
        id = "requirements",
        path = "requirements.txt",
        language = "Text",
        summary = "Python dependencies optimized for Termux (Flask, Flask-SocketIO, simple-websocket, Pillow).",
        content = requirements,
      ),
      ServerFileAsset(
        id = "installer",
        path = "install_termuxcast.sh",
        language = "Bash",
        summary = "One-paste Termux script that creates ~/termux-cast/ and writes all three project files cleanly.",
        content = installer,
      ),
    )
  }

  private fun buildOneShotInstaller(
    requirements: String,
    appPy: String,
    indexHtml: String,
  ): String {
    return buildString {
      appendLine("#!/data/data/com.termux/files/usr/bin/bash")
      appendLine("set -e")
      appendLine("mkdir -p ~/termux-cast/templates")
      appendLine("cd ~/termux-cast")
      appendLine()
      appendLine("cat << 'EOF_REQ' > requirements.txt")
      appendLine(requirements.trimEnd())
      appendLine("EOF_REQ")
      appendLine()
      appendLine("cat << 'EOF_APP' > app.py")
      appendLine(appPy.trimEnd())
      appendLine("EOF_APP")
      appendLine()
      appendLine("cat << 'EOF_HTML' > templates/index.html")
      appendLine(indexHtml.trimEnd())
      appendLine("EOF_HTML")
      appendLine()
      appendLine("echo 'TermuxCast files written to ~/termux-cast/'")
    }
  }

  private fun readAsset(path: String): String {
    return context.assets.open(path).bufferedReader().use { it.readText() }
  }

  suspend fun probeServer(baseUrl: String): ServerProbeResult =
    withContext(Dispatchers.IO) {
      val normalized = baseUrl.trim().trimEnd('/')
      val target = "$normalized/api/status"
      try {
        val req = Request.Builder().url(target).get().build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            return@withContext ServerProbeResult.Offline(normalized, "HTTP ${resp.code}")
          }
          val body = resp.body?.string().orEmpty()
          val json = JSONObject(body)
          ServerProbeResult.Online(
            url = normalized,
            adbConnected = json.optBoolean("adb_connected", false),
            serial = json.optString("serial").takeIf { it.isNotBlank() && it != "null" },
            width = json.optInt("width", 1080),
            height = json.optInt("height", 2400),
            preset = json.optString("preset", "balanced"),
            clients = json.optInt("clients", 0),
          )
        }
      } catch (e: Exception) {
        ServerProbeResult.Offline(
          normalized,
          e.localizedMessage ?: "Connection refused (start python app.py in Termux first)",
        )
      }
    }

  fun getCompletedStepIds(): Set<String> {
    return prefs.getStringSet("completed_steps", emptySet())?.toSet() ?: emptySet()
  }

  fun setStepCompleted(stepId: String, completed: Boolean) {
    val updated = getCompletedStepIds().toMutableSet()
    if (completed) updated.add(stepId) else updated.remove(stepId)
    prefs.edit().putStringSet("completed_steps", updated).apply()
  }
}
