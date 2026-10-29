package dev.wobsoriano.museportal.probe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Runs every check on launch and writes the report to files/probe-report.json. Headless use:
 * `adb install -g` (grants the permissions), `adb shell am start -n
 * dev.wobsoriano.museportal.probe/.ProbeActivity`, then `adb logcat -s MuseProbe`.
 */
class ProbeActivity : ComponentActivity() {
  private val main = Handler(Looper.getMainLooper())
  private val lines = mutableStateListOf<String>()
  private val banner = mutableStateOf("")
  private val running = AtomicBoolean(false)
  private val runningState = mutableStateOf(false)

  private val permissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { run() }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF101418), contentColor = Color(0xFFF2F4F6)) {
          ProbeScreen()
        }
      }
    }
    val missing = WANTED.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    if (missing.isEmpty()) {
      run()
    } else {
      banner.value = "Allow the microphone and location so every check can run"
      permissions.launch(missing.toTypedArray())
      // Headless runs have nobody to answer the dialog; go ahead with what is granted.
      main.postDelayed({ run() }, 15_000)
    }
  }

  private fun run() {
    if (!running.compareAndSet(false, true)) return
    lines.clear()
    runningState.value = true
    banner.value = "Running checks"
    val report = Report { line -> main.post { lines += line } }
    thread(name = "probe") {
      Log.i(Report.TAG, "probe started")
      val skip = intent.getStringExtra("skip")?.split(',')?.map { it.trim() }?.toSet().orEmpty()
      Checks(this, report, { text -> main.post { banner.value = text } }, skip).run(intent.getStringExtra("only"))
      val json = report.toJson()
      val files = listOfNotNull(filesDir, getExternalFilesDir(null)).map { File(it, "probe-report.json") }
      for (file in files) {
        try {
          file.writeText(json)
          report.put("report", "written", file.path)
        } catch (e: Exception) {
          report.put("report", "writeFailed", "${file.path}: $e")
        }
      }
      Log.i(Report.TAG, "probe finished")
      main.post {
        banner.value = "Done. Report in ${files.first().path}"
        runningState.value = false
        running.set(false)
      }
    }
  }

  @androidx.compose.runtime.Composable
  private fun ProbeScreen() {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("Muse probe", fontSize = 40.sp)
        Button(onClick = ::run, enabled = !runningState.value) { Text("Run again", fontSize = 24.sp) }
      }
      Text(banner.value, fontSize = 34.sp, color = Color(0xFFFFC66D))
      val listState = rememberLazyListState()
      LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }
      LazyColumn(state = listState) {
        items(lines) { Text(it, fontSize = 20.sp, fontFamily = FontFamily.Monospace) }
      }
    }
  }

  private companion object {
    val WANTED = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION)
  }
}
