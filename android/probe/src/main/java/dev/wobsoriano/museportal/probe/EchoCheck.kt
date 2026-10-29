package dev.wobsoriano.museportal.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val RATE = 24_000
private const val PLAY_MS = 3_000
private const val QUIET_MS = 1_000

/** One way the app could play Muse and listen at once. */
private class Duplex(val name: String, val source: Int, val usage: Int, val communicationMode: Boolean, val aec: Boolean)

private val DUPLEXES =
    listOf(
        Duplex("MIC+media", MediaRecorder.AudioSource.MIC, AudioAttributes.USAGE_MEDIA, false, false),
        Duplex("UNPROCESSED+media", MediaRecorder.AudioSource.UNPROCESSED, AudioAttributes.USAGE_MEDIA, false, false),
        Duplex("CAMCORDER+media", MediaRecorder.AudioSource.CAMCORDER, AudioAttributes.USAGE_MEDIA, false, false),
        Duplex("VOICE_RECOGNITION+media", MediaRecorder.AudioSource.VOICE_RECOGNITION, AudioAttributes.USAGE_MEDIA, false, false),
        Duplex("VOICE_COMMUNICATION+media", MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioAttributes.USAGE_MEDIA, false, false),
        Duplex("VOICE_COMMUNICATION+media+aec", MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioAttributes.USAGE_MEDIA, false, true),
        Duplex("VOICE_COMMUNICATION+call", MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION, true, false),
        Duplex("VOICE_COMMUNICATION+call+aec", MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioAttributes.USAGE_VOICE_COMMUNICATION, true, true),
    )

/**
 * How much of its own speaker each way of listening hears: it records a quiet second, then records
 * while playing three seconds of speech-shaped noise at 24 kHz, the rate of a Muse voice call.
 * `echoDb` is how far the recording rose above the quiet room; `cancelledDb` is how much less echo
 * than plain MIC that way heard. Run it with `--es only echo` in a quiet room; it is loud.
 */
@SuppressLint("MissingPermission")
internal fun echoCheck(context: Context, report: Report, prompt: (String) -> Unit) =
    report.section("echo") {
      put("aecAvailable", AcousticEchoCanceler.isAvailable())
      put("nsAvailable", NoiseSuppressor.isAvailable())
      put("agcAvailable", AutomaticGainControl.isAvailable())
      val am = context.getSystemService(AudioManager::class.java)
      put("musicVolume", "${am.getStreamVolume(AudioManager.STREAM_MUSIC)}/${am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}")
      put("callVolume", "${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/${am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)}")
      put("minRecordBuffer24k", AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
      put("minTrackBuffer24k", AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
      if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        put("recording", "skipped: RECORD_AUDIO denied")
        return@section
      }
      val signal = speechNoise()
      var plainMicEcho: Double? = null
      for (d in DUPLEXES) {
        prompt("Stay quiet. Testing ${d.name}")
        get(d.name) {
          val (quiet, playing, played) = measure(am, d, signal)
          val echo = playing - quiet
          if (d.name == "MIC+media") plainMicEcho = echo
          val cancelled = plainMicEcho?.let { "%.1f".format(it - echo) } ?: "n/a"
          "quietDbfs=${"%.1f".format(quiet)} playingDbfs=${"%.1f".format(playing)} echoDb=${"%.1f".format(echo)} cancelledDb=$cancelled playedFrames=$played"
        }
        Thread.sleep(500)
      }
      prompt("")
    }

@SuppressLint("MissingPermission")
private fun measure(am: AudioManager, d: Duplex, signal: ShortArray): Triple<Double, Double, Int> {
  val oldMode = am.mode
  if (d.communicationMode) am.mode = AudioManager.MODE_IN_COMMUNICATION
  val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
  require(minBuf > 0) { "24 kHz recording unsupported ($minBuf)" }
  val rec = AudioRecord(d.source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RATE))
  val aec = if (d.aec && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
  aec?.enabled = true
  val track =
      AudioTrack.Builder()
          .setAudioAttributes(AudioAttributes.Builder().setUsage(d.usage).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
          .setAudioFormat(
              AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
          .setTransferMode(AudioTrack.MODE_STATIC)
          .setBufferSizeInBytes(signal.size * 2)
          .build()
  try {
    check(rec.state == AudioRecord.STATE_INITIALIZED) { "recorder init failed" }
    if (d.aec) check(aec?.enabled == true) { "AEC did not attach" }
    check(track.write(signal, 0, signal.size) == signal.size) { "track write failed" }
    check(track.state == AudioTrack.STATE_INITIALIZED) { "track init failed" }
    rec.startRecording()
    check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "mic busy" }
    read(rec, 300)
    val quiet = rms(read(rec, QUIET_MS))
    track.play()
    read(rec, 300)
    val playing = rms(read(rec, PLAY_MS - 600))
    return Triple(dbfs(quiet), dbfs(playing), track.playbackHeadPosition)
  } finally {
    runCatching { track.stop() }
    track.release()
    runCatching { rec.stop() }
    aec?.release()
    rec.release()
    am.mode = oldMode
  }
}

private fun read(rec: AudioRecord, ms: Int): ShortArray {
  val out = ShortArray(RATE * ms / 1000)
  var at = 0
  while (at < out.size) {
    val n = rec.read(out, at, out.size - at)
    check(n > 0) { "read returned $n" }
    at += n
  }
  return out
}

internal fun rms(pcm: ShortArray): Double = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)

internal fun dbfs(amplitude: Double) = 20 * log10(maxOf(amplitude, 1e-9) / 32768.0)

/** Band-limited noise that swells and fades four times a second, roughly the rhythm of syllables. */
internal fun speechNoise(ms: Int = PLAY_MS): ShortArray {
  val random = Random(7)
  var fast = 0.0
  var slow = 0.0
  return ShortArray(RATE * ms / 1000) { i ->
    val white = random.nextDouble(-1.0, 1.0)
    fast += 0.25 * (white - fast)
    slow += 0.02 * (white - slow)
    val band = fast - slow
    val swell = 0.55 + 0.45 * sin(2 * PI * 4.0 * i / RATE)
    (band * swell * 3.0 * 16_000).coerceIn(-28_000.0, 28_000.0).toInt().toShort()
  }
}
