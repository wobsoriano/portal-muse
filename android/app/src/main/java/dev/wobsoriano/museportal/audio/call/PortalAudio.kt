package dev.wobsoriano.museportal.audio.call

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import dev.wobsoriano.muse.voice.CALL_FRAME_BYTES
import dev.wobsoriano.muse.voice.CALL_FRAME_SAMPLES
import dev.wobsoriano.muse.voice.CALL_RATE
import dev.wobsoriano.muse.voice.CallAudio
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * The Portal's mic and speaker for a call: VOICE_RECOGNITION at 24 kHz, the source the wake word
 * already uses and whose echo the Portal's DSP removes, and a 24 kHz media track fed by a writer
 * thread from a 5 s queue. [awaitMic] blocks until whoever had the mic has let go of it.
 */
class PortalAudio(private val awaitMic: () -> Unit) : CallAudio {
  private val lock = Object()
  // Guarded by lock.
  private var closed = false
  private var rec: AudioRecord? = null
  private var track: AudioTrack? = null
  private var writer: Thread? = null
  private var speakerFailed = false
  private val queue = ArrayDeque<ByteArray>()
  private var queuedBytes = 0
  /** Bumped by every flush, so the writer drops what it took from the queue before it. */
  private var epoch = 0

  /** Held across a read, so the recorder is released only once no read is using it. */
  private val reading = ReentrantLock()
  private val frame = ShortArray(CALL_FRAME_SAMPLES)
  private var zeroSamples = 0L
  private var silenceLimit = SILENCE_REOPEN
  @Volatile private var muteUntil = 0L
  @Volatile internal var reopens = 0
    private set

  override fun open() {
    awaitMic()
    synchronized(lock) { if (closed) throw IOException("closed") }
    val r = openRecorder()
    val t =
        try {
          openTrack()
        } catch (e: IOException) {
          release(r)
          throw e
        }
    synchronized(lock) {
      if (closed) {
        release(r)
        t.release()
        throw IOException("closed")
      }
      rec = r
      track = t
      writer = thread(name = "muse-call-speaker") { write(t) }
    }
  }

  override fun readMic(): ByteArray? {
    reading.lock()
    try {
      val r = synchronized(lock) { if (closed) null else rec } ?: return null
      val n = r.read(frame, 0, frame.size)
      if (n <= 0) {
        if (synchronized(lock) { closed }) return null
        throw IOException("the microphone stopped ($n)")
      }
      var peak = 0
      for (i in 0 until n) peak = maxOf(peak, abs(frame[i].toInt()))
      if (silenced(peak, n)) reopen()
      val out = ByteArray(n * 2)
      if (SystemClock.elapsedRealtime() >= muteUntil) {
        for (i in 0 until n) {
          val s = frame[i].toInt()
          out[2 * i] = s.toByte()
          out[2 * i + 1] = (s shr 8).toByte()
        }
      }
      return out
    } finally {
      reading.unlock()
    }
  }

  /** The mic reads as silence for the next [ms], while the chime plays. */
  fun muteFor(ms: Int) {
    muteUntil = SystemClock.elapsedRealtime() + ms
  }

  /** Queues even before [open], so Muse's first words wait for the track rather than being lost. */
  override fun play(pcm: ByteArray) {
    synchronized(lock) {
      if (closed) return
      if (speakerFailed) throw IOException("the speaker stopped")
      if (queuedBytes + pcm.size > MAX_QUEUED_BYTES) throw IOException("playback full")
      queue.addLast(pcm)
      queuedBytes += pcm.size
      lock.notifyAll()
    }
  }

  override fun flush() {
    val t =
        synchronized(lock) {
          queue.clear()
          queuedBytes = 0
          epoch++
          track
        } ?: return
    // pause() also cuts short a write in progress, so little of it reaches the speaker after.
    runCatching {
      t.pause()
      t.flush()
      t.play()
    }
  }

  override fun close() {
    val r: AudioRecord?
    val t: AudioTrack?
    val w: Thread?
    synchronized(lock) {
      if (closed) return
      closed = true
      r = rec
      t = track
      w = writer
      queue.clear()
      queuedBytes = 0
      lock.notifyAll()
    }
    r?.let { runCatching { it.stop() } }
    if (t != null) {
      runCatching {
        t.pause()
        t.flush()
      }
      w?.join(JOIN_MS)
      if (w?.isAlive == true) Log.w(TAG, "the speaker's writer did not stop; leaving the track") else t.release()
    }
    if (reading.tryLock(JOIN_MS, TimeUnit.MILLISECONDS)) {
      try {
        synchronized(lock) { rec.also { rec = null } }?.let(::release)
      } finally {
        reading.unlock()
      }
    } else {
      Log.w(TAG, "a mic read did not return; leaving the recorder")
    }
  }

  /** Frames the track has played so far; for the probe. */
  internal val playedFrames: Int
    get() = synchronized(lock) { track }?.let { runCatching { it.playbackHeadPosition }.getOrNull() } ?: 0

  /** Audio queued but not yet written to the track, in ms; for the probe. */
  internal val queuedMs: Int
    get() = synchronized(lock) { queuedBytes } * 1000 / (CALL_RATE * 2)

  private fun write(t: AudioTrack) {
    var chunk: ByteArray? = null
    var at = 0
    var mine = 0
    while (true) {
      val c: ByteArray
      synchronized(lock) {
        if (chunk != null && mine != epoch) chunk = null
        while (!closed && chunk == null && queue.isEmpty()) lock.wait()
        if (closed) return
        if (chunk == null) {
          chunk = queue.removeFirst()
          at = 0
          mine = epoch
        }
        c = chunk!!
      }
      val wrote = t.write(c, at, minOf(CALL_FRAME_BYTES, c.size - at))
      if (wrote < 0) {
        Log.w(TAG, "the speaker stopped ($wrote)")
        synchronized(lock) { speakerFailed = true }
        return
      }
      synchronized(lock) { if (mine == epoch) queuedBytes -= wrote }
      at += wrote
      if (at >= c.size) chunk = null else if (wrote == 0) Thread.sleep(5)
    }
  }

  /**
   * As WakeListener does: the Portal's audio policy hands an app's recorder exact zeros once its screen
   * leaves the front, which no real room produces, and a recorder opened again is heard. A reopen that
   * stays silent doubles the wait.
   */
  private fun silenced(peak: Int, n: Int): Boolean {
    if (peak > 0) {
      zeroSamples = 0
      silenceLimit = SILENCE_REOPEN
      return false
    }
    zeroSamples += n
    if (zeroSamples < silenceLimit) return false
    Log.i(TAG, "the mic has read exact silence for ${zeroSamples / CALL_RATE} s; reopening it")
    zeroSamples = 0
    silenceLimit = minOf(silenceLimit * 2, SILENCE_REOPEN_MAX)
    return true
  }

  private fun reopen() {
    val old = synchronized(lock) { if (closed) return else rec.also { rec = null } } ?: return
    release(old)
    val fresh = openRecorder()
    synchronized(lock) { if (closed) release(fresh) else rec = fresh }
    reopens++
  }

  @SuppressLint("MissingPermission")
  private fun openRecorder(): AudioRecord {
    val minBuf = AudioRecord.getMinBufferSize(CALL_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) throw IOException("24 kHz recording is not supported ($minBuf)")
    val r =
        try {
          AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, CALL_RATE, AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, RECORD_BUFFER_BYTES))
        } catch (e: SecurityException) {
          throw IOException("microphone permission is off", e)
        } catch (e: IllegalArgumentException) {
          throw IOException("the microphone is not available", e)
        }
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release()
      throw IOException("the microphone is not available")
    }
    try {
      r.startRecording()
    } catch (e: IllegalStateException) {
      r.release()
      throw IOException("could not start the microphone", e)
    }
    if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
      r.release()
      throw IOException("another app is using the microphone")
    }
    return r
  }

  private fun openTrack(): AudioTrack {
    val minBuf = AudioTrack.getMinBufferSize(CALL_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) throw IOException("24 kHz playback is not supported ($minBuf)")
    val t =
        try {
          AudioTrack.Builder()
              .setAudioAttributes(
                  AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
              .setAudioFormat(
                  AudioFormat.Builder()
                      .setSampleRate(CALL_RATE)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setTransferMode(AudioTrack.MODE_STREAM)
              .setBufferSizeInBytes(maxOf(minBuf, TRACK_LEAD_BYTES))
              .build()
        } catch (e: UnsupportedOperationException) {
          throw IOException("the speaker is not available", e)
        } catch (e: IllegalArgumentException) {
          throw IOException("the speaker is not available", e)
        }
    if (t.state != AudioTrack.STATE_INITIALIZED) {
      t.release()
      throw IOException("the speaker is not available")
    }
    try {
      t.play()
    } catch (e: IllegalStateException) {
      t.release()
      throw IOException("could not start the speaker", e)
    }
    return t
  }

  private fun release(r: AudioRecord) {
    runCatching { r.stop() }
    r.release()
  }

  private companion object {
    const val TAG = "MuseCall"
    const val MAX_QUEUED_BYTES = CALL_RATE * 2 * 5
    const val RECORD_BUFFER_BYTES = CALL_RATE * 2 * 320 / 1000
    /** Two frames: the writer stays about 80 ms ahead of the speaker, so a flush leaves little behind. */
    const val TRACK_LEAD_BYTES = CALL_FRAME_BYTES * 2
    const val SILENCE_REOPEN = 2L * CALL_RATE
    const val SILENCE_REOPEN_MAX = 64L * CALL_RATE
    const val JOIN_MS = 1_000L
  }
}
