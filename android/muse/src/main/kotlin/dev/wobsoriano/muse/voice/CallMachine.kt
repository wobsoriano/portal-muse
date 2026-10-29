// Follows linux/src/musegadget/voice_call.py in Meta's Muse Gadget SDK (PR 275), Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the root
// of this module). Rewritten in Kotlin as a reducer: one coroutine feeds it, so the SDK's send-lock
// admission check is the order of two statements here.

package dev.wobsoriano.muse.voice

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

internal const val FRAME_MS = 40L
/** The mic stays gated this long after the last audible frame, in half duplex. */
internal const val SETTLE_MS = 200L
/** How long a cut-off answer shows as Interrupted. */
internal const val INTERRUPTED_MS = 2_000L
/** Muse still shows as talking this long after its last audible frame: a reply streams in about as fast as it plays, so pauses and jitter would otherwise flicker the view. */
internal const val TALK_HOLD_MS = 800L
internal const val AUDIBLE_RMS = 300
internal const val MAX_PLAYBACK_BYTES = CALL_RATE * 2 * 5
internal const val MAX_TRACKED_TURNS = 64
internal const val MAX_SETTLED_TURNS = 1024
private const val SPEECH_TAIL_MS = 400L
private const val LEVEL_MS = 100L
private const val LEVEL_PEAK = 8000f
private const val NEVER = Long.MIN_VALUE / 4
/** After both sides said goodbye and Muse's has played, how long Muse gets to hang up before the device does. Muse took 3.4 s when measured. */
internal const val PARTING_MS = 8_000L

private val FAREWELL =
    Regex("""\b(good-?bye|bye(-bye)?|see (you|ya)|good ?night|take care|talk (to you )?(later|soon)|catch you later|have a (good|great|nice|lovely) (one|day|night|evening|weekend))\b""", RegexOption.IGNORE_CASE)

internal fun isFarewell(text: String) = FAREWELL.containsMatchIn(text)

/** Captions keep this much of a transcript, its end. */
internal const val MAX_CAPTION = 2_000

/** One protocol rule broke, and the call ends. [why] is our own text. */
internal class CallBroken(val why: String) : Exception(why)

internal sealed interface CallInput {
  class Head(val status: Int) : CallInput

  class Server(val event: ServerEvent) : CallInput

  /** The answer ended cleanly, its last record whole. */
  data object WireEnded : CallInput

  /** A reset, an overflow, a bad record, a failed send, or the connection gone. [why] is our own text. */
  class WireFailed(val why: String) : CallInput

  /** 1..[CALL_FRAME_SAMPLES] samples from the microphone. */
  class Mic(val pcm: ByteArray) : CallInput

  /** The microphone ended cleanly: quiet frames follow, so the server can hear the turn end. */
  data object MicEnded : CallInput

  class AudioFailed(val why: String) : CallInput

  data object HangUp : CallInput

  data object Interrupt : CallInput

  /** Time passed. Every input also counts as one. */
  data object Tick : CallInput
}

internal sealed interface Effect {
  class Send(val line: ClientLine) : Effect

  class Play(val pcm: ByteArray) : Effect

  data object Flush : Effect

  /** Once, at readiness: nothing is recorded or played before it. */
  data object OpenAudio : Effect

  data class Show(val view: CallView) : Effect

  /** Always the last effect; later steps return none. */
  data class Finish(val end: CallEnd) : Effect
}

internal fun rms(pcm: ByteArray, from: Int = 0, n: Int = pcm.size): Double {
  if (n < 2) return 0.0
  var sum = 0.0
  for (i in from until from + n - 1 step 2) {
    val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toDouble()
    sum += s * s
  }
  return sqrt(sum / (n / 2))
}

private fun peak(pcm: ByteArray): Int {
  var p = 0
  for (i in 0 until pcm.size - 1 step 2) p = maxOf(p, kotlin.math.abs((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)))
  return p
}

/** Milliseconds of PCM16 mono at [CALL_RATE]. */
private fun ms(bytes: Int) = bytes * 1000L / (CALL_RATE * 2)

/** The speaker, modelled rather than asked: when what was queued will have played, and when its last audible frame ends. */
internal class Playhead(private val duplex: Duplex) {
  var end = NEVER
    private set

  var audibleEnd = NEVER
    private set

  fun queue(pcm: ByteArray, now: Long) {
    var at = maxOf(now, end)
    if ((at - now) * CALL_RATE * 2 / 1000 + pcm.size > MAX_PLAYBACK_BYTES) throw CallBroken("playback full")
    for (off in pcm.indices step CALL_FRAME_BYTES) {
      val n = minOf(CALL_FRAME_BYTES, pcm.size - off)
      at += ms(n)
      if (rms(pcm, off, n) >= AUDIBLE_RMS) audibleEnd = at
    }
    end = at
  }

  fun reset() {
    end = NEVER
    audibleEnd = NEVER
  }

  fun audible(now: Long) = now < audibleEnd

  fun talking(now: Long) = now < audibleEnd + TALK_HOLD_MS

  fun muting(now: Long) = duplex == Duplex.Half && now < audibleEnd + SETTLE_MS

  fun drained(now: Long) = now >= maxOf(end, audibleEnd + SETTLE_MS)
}

/** The caller's turns: submission id to caller input item. 64 active and 1024 settled at most; past that the call ends. */
internal class TurnLedger {
  private val active = LinkedHashMap<String, String>()
  private val settled = HashMap<String, String>()

  /** Muse is still working on something, which pauses the idle timer. */
  val busy
    get() = active.isNotEmpty()

  /** True for a new turn, false for a replay of a known one. */
  fun started(submission: String, item: String): Boolean {
    val previous = active[submission] ?: settled[submission]
    if (previous != null) {
      if (previous != item) throw CallBroken("conflicting turn")
      return false
    }
    if (active.size >= MAX_TRACKED_TURNS) throw CallBroken("too many turns")
    active[submission] = item
    return true
  }

  /**
   * Settles [responseMessage]'s turn, and the older ones aliasing its caller item: a delayed response
   * has its own submission id. Newer aliases still own work. False when nothing matched.
   */
  fun settled(responseMessage: String?): Boolean {
    val id = responseMessage ?: return false
    val item = active[id] ?: return false
    val done = ArrayList<String>()
    for ((key, of) in active) {
      if (of == item) done += key
      if (key == id) break
    }
    if (settled.size + done.size > MAX_SETTLED_TURNS) throw CallBroken("too many settled turns")
    for (key in done) settled[key] = checkNotNull(active.remove(key))
    return true
  }
}

/**
 * One call's whole protocol state, as a reducer: inputs in, effects out. It has no clock and no I/O;
 * [step]'s `now` is milliseconds on any monotonic base, [t0] the call's start on it, so tests script
 * time. Only the call's one coroutine calls it.
 */
private enum class Goodbye {
  None,
  Caller,
  Both,
}

internal class CallMachine(private val opts: CallOptions, private val nodeId: String, val callId: String, private val t0: Long) {
  sealed interface Phase {
    /** The start and the offer are sent; no head yet. */
    data object Opening : Phase

    /** A 2xx head, and no readiness yet. */
    data object Awaiting : Phase

    data object Live : Phase

    /**
     * Muse ended it, and the queued speech plays out first. Playback is at most 5 s ahead, so this
     * lasts at most the SDK's 5.2 s drain.
     */
    data class Draining(val reason: OverReason) : Phase

    data object Done : Phase
  }

  var phase: Phase = Phase.Opening
    private set

  /** From readiness, for the end-call. */
  var threadId: String? = null
    private set

  /** From [begin] on: a start line that may have gone may have admitted the call, so cleanup ends it. */
  var endCallDue = false
    private set

  var wasLive = false
    private set

  var advisoryErrors = 0
    private set

  /** Audio events the gate dropped for having no response id, which a server sending only legacy deltas makes all of them. */
  var unlined = 0
    private set

  private val idleMs = opts.idle.inWholeMilliseconds
  private val overallMs = opts.overall.inWholeMilliseconds
  private val readyMs = minOf(opts.admit.inWholeMilliseconds, overallMs)
  private val gate = ReplyGate()
  private val turns = TurnLedger()
  private val playhead = Playhead(opts.duplex)
  private val waitingPcm = ByteArrayOutputStream()
  private var receivedAudio = false
  private var held = false
  private var micEnded = false
  private var nextQuiet = 0L
  private var activity = t0
  private var lastSpeech = NEVER
  private var interruptedUntil = NEVER
  private var micPeak = 0
  private var level = 0f
  private var levelAt = NEVER
  /**
   * Who has said goodbye in the last exchange. Muse may take seconds to hang up after its own goodbye, or
   * not at all, so once both have, the call stops listening, shows it is ending, and gives Muse
   * [PARTING_MS] after its goodbye has played.
   */
  private var goodbye = Goodbye.None
  private var partedAt = NEVER
  private var heard = ""
  private var reply = ""
  private var shown = CallView(CallPhase.Connecting)

  fun begin(): List<Effect> {
    endCallDue = true
    return listOf(Effect.Send(ClientLine.Start(callId, nodeId)), Effect.Send(ClientLine.Offer))
  }

  fun step(input: CallInput, now: Long): List<Effect> {
    if (phase == Phase.Done) return emptyList()
    val out = ArrayList<Effect>()
    try {
      timers(now, out)
      if (phase != Phase.Done) handle(input, now, out)
      if (phase != Phase.Done) show(now, out)
    } catch (e: CallBroken) {
      fail(e.why, now, out)
    } catch (e: GateOverflow) {
      fail("too many ids", now, out)
    }
    return out
  }

  private fun timers(now: Long, out: MutableList<Effect>) {
    when (val p = phase) {
      Phase.Opening,
      Phase.Awaiting -> if (now - t0 >= readyMs) declined(Decline.TimedOut, out)
      Phase.Live ->
          when {
            now - t0 >= overallMs -> over(OverReason.TooLong, now, out)
            goodbye == Goodbye.Both && playhead.drained(now) -> {
              if (partedAt == NEVER) partedAt = now
              if (now - partedAt >= PARTING_MS) over(OverReason.Goodbye, now, out)
            }
            !turns.busy && now - activity >= idleMs -> over(OverReason.Idle, now, out)
            micEnded && !gated(now) && now >= nextQuiet -> {
              out += Effect.Send(ClientLine.Append(ByteArray(CALL_FRAME_BYTES)))
              // Paced from the last one, unless a gate held them so long that the pace starts again.
              nextQuiet = if (now - nextQuiet >= FRAME_MS) now + FRAME_MS else nextQuiet + FRAME_MS
            }
          }
      is Phase.Draining ->
          when {
            now - t0 >= overallMs -> over(OverReason.TooLong, now, out)
            playhead.drained(now) -> over(p.reason, now, out)
          }
      Phase.Done -> Unit
    }
  }

  private fun handle(input: CallInput, now: Long, out: MutableList<Effect>) {
    val p = phase
    when (input) {
      is CallInput.Head ->
          if (p == Phase.Opening) {
            if (input.status in 200..299) phase = Phase.Awaiting else declined(Decline.Refused(input.status), out)
          }
      is CallInput.Server -> if (p == Phase.Awaiting || p == Phase.Live) server(input.event, now, out)
      CallInput.WireEnded ->
          when (p) {
            Phase.Live -> drain(OverReason.RemoteEnd, now, out)
            Phase.Opening,
            Phase.Awaiting -> declined(Decline.Dropped("ended before ready"), out)
            else -> Unit
          }
      // While draining nothing more is read, so the answer's end or failure changes nothing.
      is CallInput.WireFailed -> if (p !is Phase.Draining) fail(input.why, now, out)
      is CallInput.Mic -> mic(input.pcm, now, out)
      CallInput.MicEnded ->
          if (p == Phase.Live || p is Phase.Draining) {
            micEnded = true
            nextQuiet = now
          }
      is CallInput.AudioFailed -> fail(input.why, now, out)
      CallInput.HangUp -> over(if (p is Phase.Draining) p.reason else OverReason.HungUp, now, out)
      CallInput.Interrupt -> if (p == Phase.Live) bargeIn(now, out)
      CallInput.Tick -> Unit
    }
  }

  private fun server(e: ServerEvent, now: Long, out: MutableList<Effect>) {
    val live = phase == Phase.Live
    when (e) {
      is ServerEvent.Malformed -> return fail(e.why, now, out)
      is ServerEvent.Error -> {
        // After readiness an error is about one operation, not the call. The message is never kept.
        when {
          !live -> declined(Decline.Rejected(e.code), out)
          e.code == "session_dead" -> over(OverReason.Failed("session dead"), now, out)
          else -> advisoryErrors++
        }
        return
      }
      is ServerEvent.Session -> return ready(e, now, out)
      is ServerEvent.Hold -> held = e.held
      is ServerEvent.SpeechStarted -> activity = now
      ServerEvent.Stop -> return if (live) drain(OverReason.RemoteStop, now, out) else declined(Decline.Dropped("stop before ready"), out)
      is ServerEvent.TurnStarted -> if (turns.started(e.submissionId, e.itemId)) activity = now
      // An unidentified or unknown settlement cannot retire newer work.
      is ServerEvent.TurnSettled -> if (turns.settled(e.responseMessageId)) activity = now else return
      is ServerEvent.ResponseCreated -> {
        reply = ""
        activity = now
      }
      is ServerEvent.ResponseEnded -> activity = now
      is ServerEvent.ReplyText -> {
        if (live) reply = (reply + e.delta).takeLast(MAX_CAPTION)
        activity = now
      }
      is ServerEvent.ReplyDone -> {
        if (live) {
          reply = e.transcript.takeLast(MAX_CAPTION)
          if (goodbye == Goodbye.Caller && isFarewell(e.transcript)) goodbye = Goodbye.Both
        }
        activity = now
      }
      is ServerEvent.Heard -> {
        if (live) {
          heard = e.transcript.takeLast(MAX_CAPTION)
          reply = ""
          if (goodbye != Goodbye.Both) goodbye = if (isFarewell(e.transcript)) Goodbye.Caller else Goodbye.None
        }
        activity = now
      }
      is ServerEvent.InputSuppressed,
      is ServerEvent.Audio,
      ServerEvent.Other -> Unit
    }
    val wasWaiting = gate.waiting
    val audible = playhead.audible(now)
    gate.on(e)
    applyFlush(audible, now, out)
    if (wasWaiting && !gate.waiting) {
      if (gate.playing && waitingPcm.size() > 0) queue(waitingPcm.toByteArray(), now, out)
      waitingPcm.reset()
    }
    if (e is ServerEvent.Audio) audio(e, now, out)
  }

  private fun ready(e: ServerEvent.Session, now: Long, out: MutableList<Effect>) {
    if (phase == Phase.Live) return over(OverReason.Failed("duplicate readiness"), now, out)
    if (e.callId != callId) return declined(Decline.Dropped("voice identity"), out)
    threadId = e.threadId
    activity = now
    phase = Phase.Live
    wasLive = true
    out += Effect.OpenAudio
  }

  private fun audio(e: ServerEvent.Audio, now: Long, out: MutableList<Effect>) {
    // Zero keepalives may come before any reply, even before readiness. They neither start playback nor renew a deadline.
    if (e.pcm.all { it == 0.toByte() } && !receivedAudio && waitingPcm.size() == 0) {
      if (phase == Phase.Live) {
        val audible = playhead.audible(now)
        gate.audio(e.responseId)
        applyFlush(audible, now, out)
      }
      return
    }
    if (phase != Phase.Live) return declined(Decline.Dropped("audio before ready"), out)
    val audible = playhead.audible(now)
    val verdict = gate.audio(e.responseId)
    applyFlush(audible, now, out)
    when (verdict) {
      ReplyGate.Verdict.Drop -> if (e.responseId == null) unlined++
      ReplyGate.Verdict.Hold -> {
        if (waitingPcm.size() + e.pcm.size > MAX_PLAYBACK_BYTES) throw CallBroken("unconfirmed reply too long")
        waitingPcm.write(e.pcm)
      }
      ReplyGate.Verdict.Play -> queue(e.pcm, now, out)
    }
  }

  private fun queue(pcm: ByteArray, now: Long, out: MutableList<Effect>) {
    receivedAudio = true
    playhead.queue(pcm, now)
    // Audible playback is activity until it has played.
    activity = maxOf(activity, playhead.audibleEnd)
    out += Effect.Play(pcm)
  }

  private fun applyFlush(wasAudible: Boolean, now: Long, out: MutableList<Effect>) {
    val f = gate.takeFlush()
    if (f.playing) {
      waitingPcm.reset()
      playhead.reset()
      receivedAudio = false
      reply = ""
      // Cut off mid-goodbye, the caller has more to say.
      goodbye = Goodbye.None
      partedAt = NEVER
      activity = minOf(activity, now)
      if (wasAudible) interruptedUntil = now + INTERRUPTED_MS
      out += Effect.Flush
    } else if (f.waiting) {
      waitingPcm.reset()
    }
  }

  /** Fences the interrupted reply until a new caller turn, and cancels its response as a best effort. */
  private fun bargeIn(now: Long, out: MutableList<Effect>) {
    val open = gate.openResponse
    val audible = playhead.audible(now)
    gate.interrupt()
    applyFlush(audible, now, out)
    if (open != null) out += Effect.Send(ClientLine.Cancel(open))
  }

  private fun drain(reason: OverReason, now: Long, out: MutableList<Effect>) {
    phase = Phase.Draining(reason)
    if (playhead.drained(now)) over(reason, now, out)
  }

  private fun gated(now: Long) = phase != Phase.Live || held || goodbye == Goodbye.Both || playhead.muting(now)

  private fun mic(pcm: ByteArray, now: Long, out: MutableList<Effect>) {
    if (phase != Phase.Live && phase !is Phase.Draining) return
    if (pcm.isEmpty() || pcm.size > CALL_FRAME_BYTES || pcm.size % 2 != 0) throw CallBroken("bad mic frame")
    // Live capture that is gated is dropped, never sent late.
    if (gated(now)) return
    val r = rms(pcm)
    if (r >= maxOf(AUDIBLE_RMS, opts.micThreshold)) {
      activity = now
      lastSpeech = now
    }
    // Filtered frames go as silence, never as nothing: the server's speech detection owns the turn's end.
    val quiet = opts.micThreshold > 0 && r < opts.micThreshold && now - lastSpeech > SPEECH_TAIL_MS
    val sent = if (quiet) ByteArray(pcm.size) else pcm
    micPeak = maxOf(micPeak, peak(sent))
    out += Effect.Send(ClientLine.Append(sent))
  }

  private fun view(now: Long): CallPhase =
      when {
        phase is Phase.Draining || phase == Phase.Done -> CallPhase.Ending
        phase != Phase.Live -> CallPhase.Connecting
        playhead.talking(now) -> CallPhase.Talking
        goodbye == Goodbye.Both -> CallPhase.Ending
        now < interruptedUntil -> CallPhase.Interrupted
        turns.busy -> CallPhase.Thinking
        else -> CallPhase.Listening
      }

  private fun show(now: Long, out: MutableList<Effect>) {
    if (now - levelAt >= LEVEL_MS) {
      level = (micPeak / LEVEL_PEAK).coerceIn(0f, 1f)
      micPeak = 0
      levelAt = now
    }
    val v = CallView(view(now), level, heard, reply)
    if (v != shown) {
      shown = v
      out += Effect.Show(v)
    }
  }

  private fun over(reason: OverReason, now: Long, out: MutableList<Effect>) {
    phase = Phase.Done
    show(now, out)
    out += Effect.Finish(CallEnd.Over(reason))
  }

  private fun declined(why: Decline, out: MutableList<Effect>) {
    phase = Phase.Done
    out += Effect.Finish(CallEnd.Declined(why))
  }

  private fun fail(why: String, now: Long, out: MutableList<Effect>) {
    if (phase == Phase.Done) return
    if (wasLive) over(OverReason.Failed(why), now, out) else declined(Decline.Dropped(why), out)
  }
}
