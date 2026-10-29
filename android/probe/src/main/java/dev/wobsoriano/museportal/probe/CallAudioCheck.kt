package dev.wobsoriano.museportal.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.wobsoriano.muse.voice.CALL_FRAME_BYTES
import dev.wobsoriano.muse.voice.CALL_RATE
import dev.wobsoriano.museportal.audio.call.PortalAudio
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

/**
 * The call's own mic and speaker, PortalAudio, on the Portal: the mic's cadence at 24 kHz while the
 * speaker plays, how much still plays after a flush, the 5 s playback bound, and whether a voice
 * survives Muse talking over it, which decides half or full duplex. The mic is one app's at a time, so
 * stop Muse first. `--es only callaudio`; `--es skip talkover` leaves out the step that needs a person.
 */
internal fun callAudioCheck(context: Context, report: Report, prompt: (String) -> Unit, skip: Set<String>) =
    report.section("callaudio") {
      if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        put("recording", "skipped: RECORD_AUDIO denied")
        return@section
      }
      val audio = PortalAudio {}
      val opened = System.nanoTime()
      audio.open()
      put("openMs", (System.nanoTime() - opened) / 1_000_000)
      try {
        prompt("Stay quiet. Measuring the mic for 20 s")
        cadence(audio)
        flush(audio)
        overflow(audio)
        if ("talkover" in skip) put("talkover", "skipped") else talkOver(audio, prompt)
      } finally {
        audio.close()
        prompt("")
      }
      put("silenceReopens", audio.reopens)
    }

private const val CADENCE_MS = 20_000
private val SECOND_OF_SILENCE = ByteArray(CALL_RATE * 2)

/** Frame by frame, how evenly readMic returns while the speaker plays silence, as a call's would. */
private fun Report.Section.cadence(audio: PortalAudio) {
  val gaps = ArrayList<Double>()
  var samples = 0L
  var zeroFrames = 0
  var shortFrames = 0
  var frames = 0
  repeat(5) { audio.readMic() }
  val start = System.nanoTime()
  var last = start
  while (System.nanoTime() - start < CADENCE_MS * 1_000_000L) {
    if (audio.queuedMs < 1_000) audio.play(SECOND_OF_SILENCE)
    val f = audio.readMic() ?: error("the mic closed")
    val now = System.nanoTime()
    gaps += (now - last) / 1e6
    last = now
    frames++
    samples += f.size / 2
    if (f.size != CALL_FRAME_BYTES) shortFrames++
    if (f.all { it.toInt() == 0 }) zeroFrames++
  }
  val elapsed = (last - start) / 1e9
  gaps.sort()
  fun pct(p: Double) = "%.1f".format(gaps[((gaps.size - 1) * p).toInt()])
  put("cadence", "frames=$frames shortFrames=$shortFrames zeroFrames=$zeroFrames rate=${"%.0f".format(samples / elapsed)} Hz")
  put("cadenceGapMs", "mean=${"%.1f".format(gaps.average())} p50=${pct(0.5)} p95=${pct(0.95)} p99=${pct(0.99)} max=${pct(1.0)} over60=${gaps.count { it > 60 }}")
  put("playedWhileReading", "${audio.playedFrames * 1000L / CALL_RATE} ms")
  audio.flush()
}

/** Plays a soft tone, flushes it a second in, and watches the track's head until it stops moving. */
private fun Report.Section.flush(audio: PortalAudio) {
  audio.play(tone(3_000))
  Thread.sleep(1_000)
  val before = audio.playedFrames
  val t0 = System.nanoTime()
  audio.flush()
  val callMs = (System.nanoTime() - t0) / 1e6
  var head = audio.playedFrames
  var movedAt = t0
  while (System.nanoTime() - t0 < 600_000_000L) {
    Thread.sleep(2)
    val h = audio.playedFrames
    if (h != head) {
      head = h
      movedAt = System.nanoTime()
    }
  }
  // A flush may or may not reset the head, so a head below where it was counts from zero.
  val after = if (head >= before) head - before else head
  put(
      "flush",
      "callMs=${"%.1f".format(callMs)} playedAfterMs=${after * 1000L / CALL_RATE} headStoppedAfterMs=${(movedAt - t0) / 1_000_000} " +
          "headBefore=$before headAfter=$head queuedMsAfter=${audio.queuedMs}")
}

/** Queues a second at a time until play refuses, then checks a flush makes room again. */
private fun Report.Section.overflow(audio: PortalAudio) {
  var accepted = 0
  val t0 = System.nanoTime()
  val outcome =
      try {
        repeat(8) {
          audio.play(SECOND_OF_SILENCE)
          accepted++
        }
        "no refusal after 8 s"
      } catch (e: IOException) {
        "refused after $accepted s in ${(System.nanoTime() - t0) / 1_000_000} ms: ${e.message}"
      }
  put("overflow", "$outcome queuedMs=${audio.queuedMs}")
  audio.flush()
  put("afterFlush", "queuedMs=${audio.queuedMs} playsAgain=${runCatching { audio.play(SECOND_OF_SILENCE) }.isSuccess}")
  audio.flush()
}

/**
 * How loud a person counting is with the speaker silent, and while it plays speech-shaped noise. The
 * Portal's DSP removes its own speaker; if it also ducks the mic, the user cannot talk over Muse and
 * the call has to stay half duplex.
 */
private fun Report.Section.talkOver(audio: PortalAudio, prompt: (String) -> Unit) {
  for (s in 5 downTo 1) {
    prompt("Next: count out loud from across the room, one number a second, until told to stop. Starting in $s")
    Thread.sleep(1_000)
  }
  prompt("Count out loud now")
  listen(audio, 500)
  val alone = dbfs(rms(listen(audio, 4_000)))
  prompt("Keep counting while the Portal plays noise")
  val noise = speechNoise(6_000)
  val playing = dbfs(rms(listen(audio, 6_000, noise, leadMs = 400)))
  prompt("Stop counting and stay quiet")
  Thread.sleep(1_500)
  val room = dbfs(rms(listen(audio, 2_000)))
  val drop = alone - playing
  val verdict = if (drop <= 10 && playing - room >= 15) "full" else "half"
  put(
      "talkover",
      "aloneDbfs=${"%.1f".format(alone)} playingDbfs=${"%.1f".format(playing)} roomDbfs=${"%.1f".format(room)} " +
          "dropDb=${"%.1f".format(drop)} suggests=$verdict")
}

/** Reads [ms] of the mic, playing [feed] meanwhile with about a second queued; the first [leadMs] is left out. */
private fun listen(audio: PortalAudio, ms: Int, feed: ShortArray? = null, leadMs: Int = 0): ShortArray {
  val bytes = feed?.let { pcm -> ByteArray(pcm.size * 2) { i -> (pcm[i / 2].toInt() shr (8 * (i % 2))).toByte() } }
  var fed = 0
  val out = ShortArray(CALL_RATE * ms / 1000)
  var at = -CALL_RATE * leadMs / 1000
  while (at < out.size) {
    if (bytes != null && fed < bytes.size && audio.queuedMs < 1_000) {
      val n = minOf(bytes.size - fed, CALL_RATE * 2)
      audio.play(bytes.copyOfRange(fed, fed + n))
      fed += n
    }
    val f = audio.readMic() ?: error("the mic closed")
    for (i in 0 until f.size / 2) {
      if (at in out.indices) out[at] = ((f[2 * i].toInt() and 0xff) or (f[2 * i + 1].toInt() shl 8)).toShort()
      at++
    }
  }
  audio.flush()
  return out
}

private fun tone(ms: Int): ByteArray {
  val n = CALL_RATE * ms / 1000
  val out = ByteArray(n * 2)
  for (i in 0 until n) {
    val s = (2_000 * sin(2 * PI * 440.0 * i / CALL_RATE)).toInt()
    out[2 * i] = s.toByte()
    out[2 * i + 1] = (s shr 8).toByte()
  }
  return out
}
