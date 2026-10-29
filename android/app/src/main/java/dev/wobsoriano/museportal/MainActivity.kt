package dev.wobsoriano.museportal

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.PairState
import dev.wobsoriano.muse.core.PairStatus
import dev.wobsoriano.muse.core.Status
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.muse.voice.Duplex
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.avatar.AvatarLoad
import dev.wobsoriano.museportal.setup.Screen
import dev.wobsoriano.museportal.setup.SetupStep
import dev.wobsoriano.museportal.ui.HomeScreen
import dev.wobsoriano.museportal.ui.MuseTheme
import dev.wobsoriano.museportal.ui.Palette
import dev.wobsoriano.museportal.ui.SettingsActions
import dev.wobsoriano.museportal.ui.SettingsScreen
import dev.wobsoriano.museportal.ui.SetupActions
import dev.wobsoriano.museportal.ui.SetupScreen
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
  private val permissions =
      registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { muse.refreshConfig() }
  private var canOverlay by mutableStateOf(false)
  private var settings by mutableStateOf(false)
  /** A debug build's stand-in for what the screen shows, set by intent extras; null shows the real thing. */
  private val preview = MutableStateFlow<Preview?>(null)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    MuseService.start(this)
    applyExtras(intent)
    val missing =
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION).filter {
          checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
    if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

    setContent {
      MuseTheme {
        Surface(Modifier.fillMaxSize(), color = Palette.Ground, contentColor = Palette.Text) {
          val real by muse.state.collectAsStateWithLifecycle()
          val previewing by preview.collectAsStateWithLifecycle()
          val avatar by avatars.load.collectAsStateWithLifecycle()
          val p = previewing
          // A preview is for screenshots, so it shows a made-up token rather than the real last four.
          val state = p?.state?.invoke(real)?.copy(tokenHint = "mgst_…Ab12") ?: real
          val screen = p?.screen ?: state.screen
          App(screen, state, avatar, readOnly = p != null)
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    canOverlay = android.provider.Settings.canDrawOverlays(this)
    muse.setInFront(true)
  }

  override fun onPause() {
    muse.setInFront(false)
    super.onPause()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    applyExtras(intent)
  }

  @Composable
  private fun App(screen: Screen, state: MuseState, avatar: AvatarLoad, readOnly: Boolean) {
    LaunchedEffect(screen is Screen.Setup, readOnly) { if (screen is Screen.Setup && !readOnly) muse.setupShown() }
    val page = if (screen == Screen.Home && settings) Page.Settings else Page.Of(screen)
    BackHandler(page == Page.Settings) { settings = false }
    val set = (avatar as? AvatarLoad.Loaded)?.set
    AnimatedContent(page, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }, label = "page") { shown ->
      when (shown) {
        Page.Settings ->
            SettingsScreen(state, canOverlay, avatar, avatars.dir?.path ?: "Android/data/$packageName/files/muse-avatar", settingsActions { settings = false })
        is Page.Of ->
            when (val s = shown.screen) {
              Screen.Loading -> Box(Modifier.fillMaxSize())
              is Screen.Setup -> SetupScreen(s.step, state, canOverlay, set, if (readOnly) PreviewSetup else setupActions)
              Screen.Home ->
                  HomeScreen(state, set, onTap = { if (!readOnly) muse.tapMic() }, onEndCall = { if (!readOnly) muse.dismiss() }, onSettings = { settings = true })
            }
      }
    }
  }

  private sealed interface Page {
    data object Settings : Page

    data class Of(val screen: Screen) : Page
  }

  private fun requestOverlay() {
    try {
      startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    } catch (e: ActivityNotFoundException) {
      Toast.makeText(this, "This Portal has no setting for that", Toast.LENGTH_LONG).show()
    }
  }

  private val setupActions =
      object : SetupActions {
        override fun saveToken(token: String) = muse.saveToken(token)

        override fun rename(name: String) = muse.rename(name)

        override fun pair() = muse.pair()

        override fun setWakeEnabled(on: Boolean) = muse.setWakeEnabled(on)

        override fun requestOverlay() = this@MainActivity.requestOverlay()

        override fun finish() = muse.finishSetup()
      }

  private fun settingsActions(back: () -> Unit) =
      object : SettingsActions {
        override fun back() = back()

        override fun setWakeEnabled(on: Boolean) = muse.setWakeEnabled(on)

        override fun setKeepListening(on: Boolean) = muse.setKeepListening(on)

        override fun setConversationMode(on: Boolean) = muse.setConversationMode(on)

        override fun requestOverlay() = this@MainActivity.requestOverlay()

        override fun setVoice(name: String?, rate: Float) = muse.setVoice(name, rate)

        override fun testVoice() = muse.testVoice()

        override fun rename(name: String) = muse.rename(name)

        override fun saveToken(token: String) = muse.saveToken(token)

        override fun unpair() = muse.unpair()

        override fun copyDiagnostics() {
          getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Muse diagnostics", muse.diagnostics()))
          Toast.makeText(this@MainActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }
      }

  // Lets adb set the token without the Portal's on-screen keyboard, ask without the mic and flip the
  // wake word: `am start -n .../.MainActivity --es sdkToken mgst_... --es ask "what time is it" --ez heyMuse true`.
  // A debug build also previews screens without touching saved state: `--es previewSetup pair`,
  // `--es previewHome thinking` (or `call`), `--ez noAvatar true`, `--es page settings`, and sets a
  // call's duplex with `--es duplex full|half`.
  private fun applyExtras(intent: Intent?) {
    intent?.getStringExtra("name")?.let(muse::rename)
    intent?.getStringExtra("sdkToken")?.let(muse::saveToken)
    intent?.getStringExtra("ask")?.let(muse::ask)
    if (intent?.hasExtra("heyMuse") == true) muse.setWakeEnabled(intent.getBooleanExtra("heyMuse", false))
    intent?.getStringExtra("page")?.let { settings = it == "settings" }
    if (!BuildConfig.DEBUG || intent == null) return
    if (intent.getBooleanExtra("noAvatar", false)) avatars.hide() else if (intent.hasExtra("noAvatar")) avatars.reload()
    intent.getStringExtra("duplex")?.let { d -> Duplex.entries.find { it.name.equals(d, ignoreCase = true) }?.let(muse::setDuplex) }
    preview.value = Preview.of(intent.getStringExtra("previewSetup"), intent.getStringExtra("previewHome"))
  }
}

/** Fake screens for screenshots: the shown state is made up, and nothing the user saved changes. */
private class Preview(val screen: Screen?, val state: ((MuseState) -> MuseState)?) {
  companion object {
    fun of(setup: String?, home: String?): Preview? {
      val step = SetupStep.entries.find { it.name.equals(setup, ignoreCase = true) }
      if (step != null) {
        return Preview(Screen.Setup(step)) { s ->
          s.copy(pairing = PairStatus(PairState.Open, "MuseGadget3F9A2C"), wakeEnabled = false, name = "Portal")
        }
      }
      val heard = "what's the weather like tomorrow"
      val answer = "Tomorrow looks mild and dry, with a high of 21 degrees and a light breeze in the afternoon."
      val ask: (MuseState) -> MuseState =
          when (home?.lowercase()) {
            "ready" -> { s -> s.copy(ask = AskState.Idle, speaking = false) }
            "hearing" -> { s -> s.copy(ask = AskState.Recording(0.55f)) }
            "thinking" -> { s -> s.copy(ask = AskState.Heard("p", heard)) }
            "speaking" -> { s -> s.copy(ask = AskState.Answered("p", heard, answer, complete = true), speaking = true) }
            "answered" -> { s -> s.copy(ask = AskState.Answered("p", heard, answer, complete = true), speaking = false) }
            "reply" -> { s -> s.copy(ask = AskState.Recording(0.3f, followUp = true)) }
            "call" -> { s -> s.copy(ask = AskState.Calling("p", CallPhase.Listening, 0.5f)) }
            "captions" -> { s -> s.copy(ask = AskState.Calling("p", CallPhase.Talking, heard = heard, reply = "$answer In the evening it cools down to 12 degrees, so bring a jacket if you go out. On Saturday rain moves in from the west around noon, and it should clear up by Sunday morning in time for the market.")) }
            "offline" -> { s -> s.copy(ask = AskState.Idle, connection = Status(Phase.Offline, "no internet")) }
            else -> return null
          }
      return Preview(Screen.Home, ask)
    }
  }
}

private object PreviewSetup : SetupActions {
  override fun saveToken(token: String) = Unit

  override fun rename(name: String) = Unit

  override fun pair() = Unit

  override fun setWakeEnabled(on: Boolean) = Unit

  override fun requestOverlay() = Unit

  override fun finish() = Unit
}
