package dev.wobsoriano.museportal

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Level
import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.BleEvents
import dev.wobsoriano.muse.core.CallUpdate
import dev.wobsoriano.muse.core.CoreConfig
import dev.wobsoriano.muse.core.CoreListener
import dev.wobsoriano.muse.core.MuseCore
import dev.wobsoriano.muse.core.PairStatus
import dev.wobsoriano.muse.core.Status
import dev.wobsoriano.muse.voice.CallCommand
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallOptions
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.Duplex
import dev.wobsoriano.museportal.ask.AskEffect
import dev.wobsoriano.museportal.ask.AskInput
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.ask.holdsMic
import dev.wobsoriano.museportal.ask.inFlight
import dev.wobsoriano.museportal.ask.next
import dev.wobsoriano.museportal.audio.Capture
import dev.wobsoriano.museportal.audio.Chime
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.audio.Speaker
import dev.wobsoriano.museportal.audio.call.PortalAudio
import dev.wobsoriano.museportal.call.CallGate
import dev.wobsoriano.museportal.ble.GattPeripheral
import dev.wobsoriano.museportal.device.NetworkInfo
import dev.wobsoriano.museportal.device.Settings
import dev.wobsoriano.museportal.heymuse.WakeHost
import dev.wobsoriano.museportal.heymuse.WakeInput
import dev.wobsoriano.museportal.heymuse.WakeListener
import dev.wobsoriano.museportal.heymuse.WakeState
import dev.wobsoriano.museportal.heymuse.WakeTurn
import dev.wobsoriano.museportal.audio.VoiceOption
import dev.wobsoriano.museportal.setup.Screen
import dev.wobsoriano.museportal.setup.SdkToken
import dev.wobsoriano.museportal.setup.SetupFacts
import dev.wobsoriano.museportal.setup.SetupProgress
import dev.wobsoriano.museportal.setup.screen
import dev.wobsoriano.museportal.ui.online
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CoreState {
  data object Running : CoreState

  /** The core failed; a fresh one starts in [inMs]. */
  data class Restarting(val error: String, val inMs: Long) : CoreState

  data object Stopped : CoreState
}

/** Everything the screen shows. */
data class MuseState(
    val core: CoreState = CoreState.Stopped,
    /** The last connection status, or null before the first one. */
    val connection: Status? = null,
    /** The last pairing status, or null when pairing was never opened. */
    val pairing: PairStatus? = null,
    /** Whether the core holds a pairing, kept across core restarts; null until it first says. */
    val paired: Boolean? = null,
    val ask: AskState = AskState.Idle,
    /** The answer is being read out. */
    val speaking: Boolean = false,
    val name: String = "",
    val named: Boolean = false,
    val hasToken: Boolean = false,
    /** The token as the screen may show it: `mgst_…` and its last four characters. */
    val tokenHint: String = "",
    val setup: SetupProgress = SetupProgress.NotStarted,
    val canSpeak: Boolean = false,
    /** The voices worth offering; empty when the engine offers no choice. */
    val voices: List<VoiceOption> = emptyList(),
    val voice: String? = null,
    val speechRate: Float = 1f,
    /** The "Hey Muse" switch, and what the listener is doing. */
    val wakeEnabled: Boolean = false,
    val wake: WakeState = WakeState.Off,
    val keepListening: Boolean = false,
    val conversationMode: Boolean = false,
    /** Our screen is in front, so the overlay isn't needed. */
    val inFront: Boolean = false,
    val version: String = "",
) {
  val screen: Screen
    get() = SetupFacts(hasToken, named, paired, setup).screen
}

/**
 * The one owner of the Muse core, the BLE peripheral, the microphone and the screen state. Lives for
 * the whole process; [MuseService] starts and stops it, and with it the "Hey Muse" listener. The core
 * runs in this process: it reaches the BLE peripheral through [GattPeripheral] and the screen through
 * [CoreListener].
 */
class MuseController(private val context: Context) {
  private val settings = Settings(context)
  private val _state =
      MutableStateFlow(
          MuseState(
              version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev",
              wakeEnabled = settings.wakeEnabled,
              keepListening = settings.keepListening,
              conversationMode = settings.conversationMode,
              voice = settings.voice,
              speechRate = settings.speechRate,
              setup = settings.setup)
              .withSettings())
  val state: StateFlow<MuseState> = _state.asStateFlow()

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  @Volatile private var core: MuseCore? = null
  private var supervisor: Job? = null

  private val ble =
      GattPeripheral(
          context,
          object : BleEvents {
            override fun advertising(ok: Boolean, error: String) {
              core?.advertising(ok, error)
            }

            override fun connected(device: String) {
              core?.connected(device)
            }

            override fun wrote(data: ByteArray, mtu: Int) {
              core?.wrote(data, mtu)
            }

            override fun disconnected() {
              core?.disconnected()
            }
          })

  private val listener =
      object : CoreListener {
        override fun status(status: Status) = _state.update { it.copy(connection = status, paired = status.phase != Phase.Unpaired) }

        override fun pairStatus(status: PairStatus) = _state.update { it.copy(pairing = status) }

        override fun reply(update: AskUpdate) = step(AskInput.Reply(update))

        override fun call(update: CallUpdate) {
          if (update is CallUpdate.Ended) {
            Log.i(TAG, "call ${update.id} ended: ${update.end}")
            callGate.record(update.end)
            lastCall = update.end
            callAudio = null
          }
          step(AskInput.Call(update))
        }
      }

  private val network = NetworkInfo(context) { sendConfig() }
  private val speaker =
      Speaker(
          context,
          settings.voice,
          settings.speechRate,
          onReady = { info -> _state.update { it.copy(canSpeak = info.ready, voices = info.voices) } }) { speaking ->
            this.speaking = speaking
            _state.update { it.copy(speaking = speaking) }
            if (!speaking) step(AskInput.Spoken(settings.keepListening)) else afterStep()
          }
  private var expiry: Job? = null
  private var expiring: AskState? = null
  private val chime = Chime()
  private val callGate = CallGate()
  /** The running call's mic and speaker, so its chime can be left out of what Muse hears. */
  @Volatile private var callAudio: PortalAudio? = null
  @Volatile private var lastCall: CallEnd? = null
  private var lastConfig: CoreConfig? = null
  @Volatile private var stopRecording = false
  @Volatile private var speaking = false
  private var busy = false
  private var started = false

  private val heyMuse =
      WakeListener(
          context,
          object : WakeHost {
            override fun wakeAsk() = wake(listenerRecords = true)

            override val shouldStopRecording
              get() = stopRecording

            override fun level(level: Float) = step(AskInput.Level(level))

            override fun recorded(capture: Capture) = this@MuseController.recorded(capture)

            override fun wakeState(state: WakeState) = _state.update { it.copy(wake = state) }
          })

  @Synchronized
  fun start() {
    if (started) return
    started = true
    network.start()
    supervisor = scope.launch { supervise() }
    heyMuse.start(settings.wakeEnabled, asking = busy)
  }

  @Synchronized
  fun stop() {
    if (!started) return
    started = false
    heyMuse.stop()
    network.stop()
    supervisor?.cancel()
    supervisor = null
    core?.close()
    core = null
    ble.unadvertise()
    _state.update { it.copy(core = CoreState.Stopped) }
  }

  /**
   * Runs the core, and starts a fresh one with backoff if it ever fails, as the app did when musecore
   * was a process that could die.
   */
  private suspend fun supervise() {
    var failures = 0
    while (true) {
      val c = newCore()
      val startedAt = System.currentTimeMillis()
      try {
        c.run()
        return
      } catch (e: CancellationException) {
        c.close()
        throw e
      } catch (e: Exception) {
        Log.e(TAG, "the Muse core failed", e)
        synchronized(this) { if (core === c) core = null }
        if (System.currentTimeMillis() - startedAt > HEALTHY_MS) failures = 0
        val delayMs = minOf(MAX_BACKOFF_MS, 1000L shl minOf(failures, 5))
        failures++
        _state.update { it.copy(core = CoreState.Restarting(e.message ?: e.toString(), delayMs)) }
        delay(delayMs)
      }
    }
  }

  @Synchronized
  private fun newCore(): MuseCore {
    // A fresh core has no pairing session, so drop whatever the old one left on the air.
    ble.unadvertise()
    _state.update { it.copy(core = CoreState.Running, connection = null, pairing = null) }
    val version = _state.value.version
    val c = MuseCore(FileStore(File(context.filesDir, "muse.json"), ::syncDir), version, listener, ble, logger)
    core = c
    val config = currentConfig()
    c.config(config)
    lastConfig = config
    return c
  }

  private fun MuseState.withSettings(): MuseState {
    val token = settings.sdkToken
    return copy(
        name = settings.name,
        named = settings.named,
        hasToken = token.isNotEmpty(),
        tokenHint = if (token.isEmpty()) "" else SdkToken.mask(token))
  }

  fun rename(name: String) {
    if (name.isBlank()) return
    settings.name = name
    _state.update { it.withSettings() }
    sendConfig()
  }

  /** A new token restarts a paired Portal's connection, so it reads as connecting until the core says otherwise. */
  fun saveToken(token: String) {
    val changed = token != settings.sdkToken
    settings.sdkToken = token
    _state.update { s ->
      s.withSettings().let { if (changed && s.paired == true) it.copy(connection = Status(Phase.Connecting)) else it }
    }
    sendConfig()
  }

  /** The setup screens are showing; from now on a restart resumes them rather than going home. */
  fun setupShown() {
    if (settings.setup != SetupProgress.NotStarted) return
    settings.setup = SetupProgress.Started
    _state.update { it.copy(setup = SetupProgress.Started) }
  }

  fun finishSetup() {
    settings.setup = SetupProgress.Finished
    _state.update { it.copy(setup = SetupProgress.Finished) }
  }

  fun setKeepListening(on: Boolean) {
    settings.keepListening = on
    _state.update { it.copy(keepListening = on) }
  }

  /** Toggling it forgets any refusal, so the next wake tries a call. */
  fun setConversationMode(on: Boolean) {
    settings.conversationMode = on
    callGate.reset()
    _state.update { it.copy(conversationMode = on) }
  }

  fun setDuplex(duplex: Duplex) {
    settings.duplex = duplex
  }

  fun setVoice(name: String?, rate: Float) {
    settings.voice = name
    settings.speechRate = rate
    speaker.choose(name, rate)
    _state.update { it.copy(voice = name, speechRate = rate) }
  }

  fun testVoice() {
    if (_state.value.ask.inFlight) return
    speaker.speak("Hi, I'm Muse. This is how I sound.")
  }

  fun setInFront(inFront: Boolean) {
    _state.update { it.copy(inFront = inFront) }
    if (!inFront) step(AskInput.LeftApp)
  }

  /** What support needs to know, with nothing secret in it. */
  fun diagnostics(): String {
    val s = _state.value
    return listOf(
            "Muse for Portal ${s.version}",
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
            "Name: ${s.name}",
            "Token: ${if (s.hasToken) "set" else "not set"}",
            "Core: ${s.core}",
            "Connection: ${s.connection?.phase ?: "unknown"}${s.connection?.error?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}",
            "Pairing: ${s.pairing?.let { "${it.state} ${it.name} ${it.error}".trim() } ?: "not opened"}",
            "Hey Muse: ${if (s.wakeEnabled) s.wake.toString() else "off"}",
            "Keep listening: ${s.keepListening}",
            "Conversation mode: ${if (s.conversationMode) "on, ${settings.duplex} duplex" else "off"}",
            "Calls: ${if (callGate.open()) "tried on a wake" else "voice notes until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(callGate.until))}"}, last ${lastCall ?: "none"}",
            "TTS: ${if (s.canSpeak) "ready, ${s.voices.size} voices, voice ${s.voice ?: "default"}, rate ${s.speechRate}" else "none"}",
            "Wi-Fi: ${network.snapshot().ssid.ifEmpty { "unknown" }}",
        )
        .joinToString("\n")
  }

  /** Debug: asks Muse a few questions and logs each one's statuses and codes, never a body or a token. */
  fun probe(realtime: Boolean) {
    val c = core
    if (c == null) Log.i(PROBE_TAG, "the Muse core is not running") else c.probe(realtime) { line -> Log.i(PROBE_TAG, line) }
  }

  /** Network facts may have changed after a permission grant. */
  fun refreshConfig() = sendConfig()

  fun pair() {
    core?.pairStart()
  }

  fun stopPairing() {
    core?.pairStop()
  }

  fun unpair() {
    core?.unpair()
  }

  fun tapMic() = step(AskInput.Tap)

  /** Closes the turn from the card over other apps; the state goes idle first so no follow-up starts. */
  fun dismiss() {
    step(AskInput.Dismissed)
    speaker.stop()
  }

  fun setWakeEnabled(on: Boolean) {
    settings.wakeEnabled = on
    _state.update { it.copy(wakeEnabled = on) }
    heyMuse.setEnabled(on)
  }

  val wakeEnabled: Boolean
    get() = settings.wakeEnabled

  /**
   * "Hey Muse" was heard: chimes and starts an ask, unless the mic is already taken. With
   * [listenerRecords] our listener records it on the mic that heard the wake; otherwise (a
   * portal-wake hand-off) the question gets a mic of its own.
   */
  fun wake(listenerRecords: Boolean): WakeTurn {
    if (_state.value.ask.holdsMic) return WakeTurn.Refused
    speaker.stop()
    stopRecording = false
    if (callAllowed()) {
      step(AskInput.WakeCall(UUID.randomUUID().toString()))
      return WakeTurn.Call
    }
    chime.play()
    step(AskInput.Wake(listenerRecords), skipMs = WakeListener.CHIME_SKIP_MS)
    return WakeTurn.Record
  }

  /** An ask in flight would have the core refuse a call as busy, which would close the gate for nothing. */
  private fun callAllowed(): Boolean {
    val s = _state.value
    return settings.conversationMode && callGate.open() && s.online && !s.ask.inFlight
  }

  private fun startCall(id: String) {
    speaker.stop()
    val c = core ?: return listener.call(CallUpdate.Ended(id, CallEnd.Declined(Decline.Offline)))
    val audio = PortalAudio { heyMuse.awaitMicFree(MIC_HANDOFF_MS) }
    callAudio = audio
    Log.i(TAG, "calling $id, ${settings.duplex} duplex")
    c.call(id, audio, CallOptions(duplex = settings.duplex, overall = CALL_LIMIT, admit = CALL_ADMIT))
  }

  fun ask(text: String) {
    val id = UUID.randomUUID().toString()
    val c = core
    step(if (c != null) AskInput.Sent(id).also { c.ask(id, text = text) } else AskInput.NotSent("Muse is not running"))
  }

  @Synchronized
  private fun sendConfig() {
    val c = core ?: return
    val config = currentConfig()
    if (config != lastConfig) {
      c.config(config)
      lastConfig = config
    }
  }

  private fun currentConfig() = CoreConfig(settings.name, settings.sdkToken, network.snapshot().ssid)

  private fun step(input: AskInput, skipMs: Int = 0) {
    var effect: AskEffect? = null
    _state.update { s ->
      val next = s.ask.next(input)
      effect = next.effect
      s.copy(ask = next.state)
    }
    when (val e = effect) {
      AskEffect.StartRecording -> record(skipMs)
      AskEffect.ListenForReply -> {
        chime.play()
        record(WakeListener.CHIME_SKIP_MS, waitForSpeechMs = REPLY_WAIT_MS)
      }
      AskEffect.FallBack -> {
        chime.play()
        record(WakeListener.CHIME_SKIP_MS)
      }
      AskEffect.StopRecording -> stopRecording = true
      is AskEffect.Speak -> speaker.speak(e.text)
      is AskEffect.StartCall -> startCall(e.id)
      AskEffect.HangUp -> core?.callCommand(CallCommand.HangUp)
      AskEffect.Interrupt -> core?.callCommand(CallCommand.Interrupt)
      AskEffect.CallReady -> {
        chime.play()
        callAudio?.muteFor(WakeListener.CHIME_SKIP_MS)
      }
      null -> Unit
    }
    afterStep()
  }

  private fun afterStep() {
    updateBusy()
    scheduleExpiry()
  }

  /** A settled answer or a failure goes back to idle after a while once nothing is being said. */
  @Synchronized
  private fun scheduleExpiry() {
    val ask = _state.value.ask
    val resting = !speaking && (ask is AskState.Failed || (ask is AskState.Answered && ask.complete))
    if (resting && ask === expiring) return
    expiry?.cancel()
    expiry = null
    expiring = if (resting) ask else null
    if (!resting) return
    val after = if (ask is AskState.Failed) FAILURE_SHOWN_MS else ANSWER_SHOWN_MS
    expiry =
        scope.launch {
          delay(after)
          step(AskInput.Expired)
        }
  }

  /** Tells the wake listener when an ask starts and when its answer has been spoken. */
  @Synchronized
  private fun updateBusy() {
    val now = _state.value.ask.inFlight || speaking
    if (now == busy) return
    busy = now
    heyMuse.post(WakeInput.Asking(now))
  }

  private fun record(skipMs: Int, waitForSpeechMs: Int = Int.MAX_VALUE) {
    speaker.stop()
    stopRecording = false
    thread(name = "muse-mic") {
      heyMuse.awaitMicFree(MIC_HANDOFF_MS)
      recorded(Microphone.record(skipMs, { stopRecording }, waitForSpeechMs) { level -> step(AskInput.Level(level)) })
    }
  }

  private fun recorded(capture: Capture) {
    // A recording dropped while it ran (the user left the app) is thrown away, not asked.
    if (_state.value.ask !is AskState.Recording) return
    val input =
        when (capture) {
          is Capture.Failed -> AskInput.NotSent(capture.reason)
          is Capture.Wav -> {
            val id = UUID.randomUUID().toString()
            Log.i(TAG, "asking $id with ${capture.bytes.size} bytes of audio")
            val c = core
            if (c != null) AskInput.Sent(id).also { c.ask(id, wav = capture.bytes) } else AskInput.NotSent("Muse is not running")
          }
        }
    step(input)
  }

  private companion object {
    const val TAG = "MuseApp"
    const val PROBE_TAG = "MuseProbe"
    const val HEALTHY_MS = 60_000L
    const val MAX_BACKOFF_MS = 30_000L
    const val MIC_HANDOFF_MS = 1_000L
    /** The Portal's budget for a call to be picked up; a refusal costs it once per cool-off. */
    val CALL_ADMIT = 12.seconds
    /** The ESP32 gadget's limit; the SDK's own default is 5 minutes. */
    val CALL_LIMIT = 15.minutes
    const val REPLY_WAIT_MS = 6_000
    const val ANSWER_SHOWN_MS = 20_000L
    const val FAILURE_SHOWN_MS = 8_000L

    val logger = Logger { level, message ->
      when (level) {
        Level.Debug -> Log.d("MuseCore", message)
        Level.Info -> Log.i("MuseCore", message)
        Level.Warn -> Log.w("MuseCore", message)
        Level.Error -> Log.e("MuseCore", message)
      }
    }

    /** Makes a rename in [dir] durable, as musecore's store did by syncing the directory. */
    fun syncDir(dir: File) {
      try {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
          Os.fsync(fd)
        } finally {
          Os.close(fd)
        }
      } catch (e: ErrnoException) {
        throw IOException("sync ${dir.path}: ${e.message}", e)
      }
    }
  }
}
