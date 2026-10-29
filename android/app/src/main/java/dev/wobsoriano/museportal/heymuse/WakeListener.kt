package dev.wobsoriano.museportal.heymuse

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.wobsoriano.museportal.audio.Capture
import dev.wobsoriano.museportal.audio.Microphone
import dev.wobsoriano.museportal.wake.vosk.Decision
import dev.wobsoriano.museportal.wake.vosk.IdleReset
import dev.wobsoriano.museportal.wake.vosk.VoskWake
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/** What a wake turned into. */
enum class WakeTurn {
  /** A recording or a call already has the mic. */
  Refused,
  /** An ask whose question the listener records. */
  Record,
  /** A call, which opens a mic of its own once Muse picks up. */
  Call,
}

/** What the listener needs from the app to turn a wake into an ask. */
interface WakeHost {
  fun wakeAsk(): WakeTurn

  val shouldStopRecording: Boolean

  fun level(level: Float)

  fun recorded(capture: Capture)

  fun wakeState(state: WakeState)
}

/**
 * The always-on "Hey Muse" listener. One thread owns the mic, Vosk and [WakeFacts]: every change
 * arrives as a [WakeInput] on its queue, and after each one the thread opens or closes the mic, loads
 * or frees the model and starts or cancels the download to match what the facts want.
 */
class WakeListener(private val context: Context, private val host: WakeHost) {
  private val inputs = LinkedBlockingQueue<Any>()
  private val store = ModelStore(context.filesDir)
  private val audio = context.getSystemService(AudioManager::class.java)
  private val main = Handler(Looper.getMainLooper())
  private var worker: Thread? = null

  // Owned by the worker thread.
  private var facts = WakeFacts()
  private var mic: AudioRecord? = null
  private var model: Model? = null
  private var recognizer: Recognizer? = null
  private var download: Thread? = null
  @Volatile private var cancelDownload = false
  private val rule = VoskWake.heyMuse()
  private val idle = IdleReset()
  private var samples = 0L
  private val chunk = ShortArray(CHUNK)

  private val micLock = Object()
  private var micOpen = false
  /** Every session our mic has had, so the recording callback can tell our recordings from others'. */
  private val ownSessions = ConcurrentHashMap.newKeySet<Int>()

  private val recordings =
      object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) = postOthers(configs)
      }

  private val packages =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (intent.data?.schemeSpecificPart == PORTAL_WAKE) post(WakeInput.PortalWake(portalWakeInstalled(context)))
        }
      }

  @Synchronized
  fun start(enabled: Boolean, asking: Boolean) {
    if (worker != null) return
    post(WakeInput.PortalWake(portalWakeInstalled(context)))
    postOthers(audio.activeRecordingConfigurations)
    post(WakeInput.Asking(asking))
    post(WakeInput.Enabled(enabled, store.installed()))
    audio.registerAudioRecordingCallback(recordings, main)
    context.registerReceiver(
        packages,
        IntentFilter().apply {
          addAction(Intent.ACTION_PACKAGE_ADDED)
          addAction(Intent.ACTION_PACKAGE_REMOVED)
          addDataScheme("package")
        })
    worker = thread(name = "muse-wake") { run() }
  }

  @Synchronized
  fun stop() {
    val w = worker ?: return
    worker = null
    audio.unregisterAudioRecordingCallback(recordings)
    context.unregisterReceiver(packages)
    inputs.put(QUIT)
    w.join(3_000)
  }

  fun setEnabled(on: Boolean) = post(WakeInput.Enabled(on, store.installed()))

  fun post(input: WakeInput) = inputs.put(input)

  /** Blocks until the listener has let go of the mic, for at most [timeoutMs], so tap-to-talk can take it. */
  fun awaitMicFree(timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    synchronized(micLock) {
      while (micOpen) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) {
          Log.w(TAG, "the listener still holds the mic")
          return
        }
        micLock.wait(left)
      }
    }
  }

  private fun postOthers(configs: List<AudioRecordingConfiguration>) {
    post(WakeInput.OthersRecording(configs.any { it.clientAudioSessionId !in ownSessions }))
  }

  private fun run() {
    facts = WakeFacts()
    try {
      while (true) {
        val input = if (mic != null && recognizer != null) inputs.poll() else inputs.take()
        when {
          input == null -> listen()
          input === QUIT -> return
          input === DOWNLOAD_ENDED -> {
            download = null
            reconcile()
          }
          else -> apply(input as WakeInput)
        }
      }
    } finally {
      cancelDownload = true
      closeMic()
      closeEngine()
    }
  }

  private fun apply(input: WakeInput) {
    val before = facts.state
    facts = facts.next(input)
    if (input !is WakeInput.DownloadProgress) Log.i(TAG, "$input -> ${facts.state}")
    if (facts.state != before) host.wakeState(facts.state)
    reconcile()
  }

  /** Brings the resources in line with [facts]. Each step feeds its outcome back through [apply]. */
  private fun reconcile() {
    if (facts.wantsDownload && download == null) startDownload()
    if (!facts.wantsDownload && download != null) cancelDownload = true
    if (!facts.wantsMic && mic != null) closeMic()
    if (!facts.wantsEngine && model != null) {
      closeEngine()
      apply(WakeInput.EngineClosed)
      return
    }
    if (facts.wantsMic && mic == null) {
      val error = openMic()
      if (error != null) {
        apply(WakeInput.MicFailed(error))
        return
      }
    }
    if (facts.wantsEngine && model == null) apply(loadEngine())
  }

  private fun startDownload() {
    cancelDownload = false
    val cancelled = { cancelDownload }
    download =
        thread(name = "muse-wake-download") {
          val outcome =
              try {
                store.install(::httpOpen, cancelled) { post(WakeInput.DownloadProgress(it)) }
                WakeInput.Downloaded
              } catch (e: InstallCancelled) {
                WakeInput.DownloadStopped
              } catch (e: InstallFailed) {
                Log.w(TAG, "model download failed", e)
                WakeInput.DownloadFailed(e.message ?: "Could not download the speech model")
              } catch (e: Exception) {
                Log.e(TAG, "model download failed", e)
                WakeInput.DownloadFailed("Could not download the speech model: $e")
              }
          post(outcome)
          inputs.put(DOWNLOAD_ENDED)
        }
  }

  @SuppressLint("MissingPermission")
  private fun openMic(): String? {
    val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return "The microphone is not available"
    val rec =
        try {
          AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, BUFFER_BYTES))
        } catch (e: SecurityException) {
          return "Microphone permission is off"
        } catch (e: IllegalArgumentException) {
          return "The microphone is not available"
        }
    if (rec.state != AudioRecord.STATE_INITIALIZED) {
      rec.release()
      return "The microphone is not available"
    }
    ownSessions += rec.audioSessionId
    try {
      rec.startRecording()
    } catch (e: IllegalStateException) {
      rec.release()
      return "Could not start the microphone"
    }
    if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      rec.release()
      return "Another app is using the microphone"
    }
    synchronized(micLock) {
      mic = rec
      micOpen = true
    }
    recognizer?.let { restart(it) }
    return null
  }

  private fun closeMic() {
    val rec = mic ?: return
    runCatching { rec.stop() }
    rec.release()
    synchronized(micLock) {
      mic = null
      micOpen = false
      micLock.notifyAll()
    }
  }

  private fun loadEngine(): WakeInput {
    val start = System.nanoTime()
    return try {
      LibVosk.setLogLevel(LogLevel.WARNINGS)
      val m = Model(store.dir.path)
      val r = Recognizer(m, RATE.toFloat(), rule.grammarJson)
      r.setWords(true)
      model = m
      recognizer = r
      restart(r)
      Log.i(TAG, "model loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
      WakeInput.EngineLoaded
    } catch (e: IOException) {
      Log.e(TAG, "model would not load; discarding it", e)
      store.discard()
      WakeInput.EngineFailed("The speech model would not load", modelBroken = true)
    } catch (e: Throwable) {
      Log.e(TAG, "Vosk is unavailable", e)
      WakeInput.EngineFailed("Speech recognition is not available on this device", modelBroken = false)
    }
  }

  private fun closeEngine() {
    recognizer?.close()
    recognizer = null
    model?.close()
    model = null
  }

  /**
   * Starts decoding afresh. portal-wake primes the decoder with a second of silence after creating
   * or resetting it; without it the first word after a reset is dropped.
   */
  private fun restart(r: Recognizer) {
    r.reset()
    val silence = ShortArray(CHUNK)
    repeat(10) { r.acceptWaveForm(silence, silence.size) }
    idle.flushed(samples / 16)
  }

  private var levelPeak = 0
  private var levelFrom = 0L
  private var zeroSamples = 0L
  private var silenceLimit = SILENCE_REOPEN

  /**
   * The Portal's audio policy silences an app's recording when its screen leaves the foreground, and
   * the recording then reads exact zeros, which no real room produces. A mic opened again in the
   * background is heard, so after [silenceLimit] of zeros the mic is reopened. A reopen that is
   * still silent doubles the wait, so a mic that stays silenced isn't reopened in a tight loop.
   */
  private fun reopenedForSilence(chunkPeak: Int, n: Int): Boolean {
    if (chunkPeak > 0) {
      zeroSamples = 0
      silenceLimit = SILENCE_REOPEN
      return false
    }
    zeroSamples += n
    if (zeroSamples < silenceLimit) return false
    Log.i(TAG, "the mic has read exact silence for ${zeroSamples / 16_000} s; reopening it")
    zeroSamples = 0
    silenceLimit = minOf(silenceLimit * 2, SILENCE_REOPEN_MAX)
    closeMic()
    reconcile()
    return true
  }

  /** Decodes one chunk of audio, and turns a wake into an ask. */
  private fun listen() {
    val rec = mic ?: return
    val r = recognizer ?: return
    val n = rec.read(chunk, 0, chunk.size)
    if (n <= 0) {
      apply(WakeInput.MicFailed("The microphone stopped working ($n)"))
      return
    }
    samples += n
    var chunkPeak = 0
    for (i in 0 until n) chunkPeak = maxOf(chunkPeak, kotlin.math.abs(chunk[i].toInt()))
    levelPeak = maxOf(levelPeak, chunkPeak)
    if (reopenedForSilence(chunkPeak, n)) return
    if (samples - levelFrom >= LEVEL_EVERY) {
      // A level of 0 means the system is handing us silence, as Android does to a mic it has idled.
      Log.i(TAG, "mic peak $levelPeak over ${(samples - levelFrom) / 16_000} s")
      levelPeak = 0
      levelFrom = samples
    }
    val action = idle.decide(samples / 16) { JSONObject(r.partialResult).optString("partial").isNotBlank() }
    if (action != IdleReset.Action.KEEP) restart(r)
    if (!r.acceptWaveForm(chunk, n)) return
    idle.flushed(samples / 16)
    when (val d = rule.decide(r.result)) {
      is Decision.Wake -> wake(rec, d)
      is Decision.NearMiss -> Log.i(TAG, "near miss: ${d.reason} in ${d.heard.joinToString(" ") { "${it.word}(${it.conf})" }}")
      is Decision.Ignore -> Unit
    }
  }

  /** Records the question on the mic that heard the wake, or hands the mic to a call, then lets go of it until the turn is over. */
  private fun wake(rec: AudioRecord, d: Decision.Wake) {
    Log.i(TAG, "wake: ${d.heard.joinToString(" ") { "${it.word}(${it.conf})" }}")
    when (host.wakeAsk()) {
      WakeTurn.Refused -> Unit
      WakeTurn.Call -> closeMic()
      WakeTurn.Record -> {
        val capture = Microphone.capture(rec, CHIME_SKIP_MS, { host.shouldStopRecording }, onLevel = host::level)
        closeMic()
        host.recorded(capture)
      }
    }
  }

  private fun httpOpen(url: String): Download {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    if (c.responseCode != 200) {
      c.disconnect()
      throw IOException("HTTP ${c.responseCode}")
    }
    return Download(c.inputStream, c.contentLengthLong)
  }

  companion object {
    private const val TAG = "MuseWake"
    private const val LEVEL_EVERY = 60 * 16_000L
    private const val SILENCE_REOPEN = 2 * 16_000L
    private const val SILENCE_REOPEN_MAX = 64 * 16_000L
    private const val RATE = 16_000
    private const val CHUNK = RATE / 10
    /** Four seconds: room for the audio that arrives while the model loads. */
    private const val BUFFER_BYTES = RATE * 2 * 4
    /** The chime, which the question's recording leaves out. */
    const val CHIME_SKIP_MS = 300
    private const val PORTAL_WAKE = "com.portal.wake"
    private val QUIT = Any()
    /** The download thread finished, so another may start without sharing its files. */
    private val DOWNLOAD_ENDED = Any()

    fun portalWakeInstalled(context: Context): Boolean =
        try {
          context.packageManager.getPackageInfo(PORTAL_WAKE, 0)
          true
        } catch (e: PackageManager.NameNotFoundException) {
          false
        }
  }
}
