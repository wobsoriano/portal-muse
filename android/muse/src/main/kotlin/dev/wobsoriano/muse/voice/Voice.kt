// The realtime call follows linux/src/musegadget/voice_call.py and voice_gate.py in Meta's Muse
// Gadget SDK (PR 275), Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache
// License, Version 2.0 (LICENSE at the root of this module). Rewritten in Kotlin.

package dev.wobsoriano.muse.voice

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A call's audio is PCM16LE mono at this rate, both ways. */
const val CALL_RATE = 24_000

/** 40 ms: the most one append carries. */
const val CALL_FRAME_SAMPLES = 960

const val CALL_FRAME_BYTES = CALL_FRAME_SAMPLES * 2

/**
 * The device's side of a call: one owner of the microphone and the speaker from [open] to [close].
 * The call opens it once, when the server says the call is ready, and closes it before the call is
 * reported over, so whoever had the microphone before can take it back as soon as the call returns.
 */
interface CallAudio {
  /** Starts both devices, off the main thread; may block about a second for the mic. Throws IOException, leaving nothing open. */
  fun open()

  /** Blocks for the next 1..[CALL_FRAME_SAMPLES] samples, or returns null once [close]d. Throws IOException if the recorder dies. */
  fun readMic(): ByteArray?

  /** Queues for the speaker without blocking. Throws IOException once 5 s is already queued. */
  fun play(pcm: ByteArray)

  /** Drops whatever is queued or playing, without blocking. */
  fun flush()

  /** Releases both devices and unblocks [readMic]. Idempotent, and safe before or during [open]. */
  fun close()
}

/** [Half] mutes the mic while Muse is audible and for 200 ms after; [Full] relies on the device's echo cancelling. */
enum class Duplex {
  Half,
  Full,
}

enum class CallCommand {
  HangUp,
  /** Stops Muse mid-answer, as talking over it would. */
  Interrupt,
}

data class CallOptions(
    val duplex: Duplex = Duplex.Half,
    /** 0..32767; 0 sends every frame, otherwise quieter frames go as silence after 400 ms. */
    val micThreshold: Int = 0,
    val idle: Duration = 45.seconds,
    val overall: Duration = 300.seconds,
    /** From the start of the call until the server says it is ready, connecting included. */
    val admit: Duration = 35.seconds,
    val cleanup: Duration = 10.seconds,
    /** "hatch-web", or a positive 64-bit integer. */
    val appId: String = "hatch-web",
) {
  init {
    require(micThreshold in 0..32767) { "mic threshold must be between 0 and 32767" }
    require(idle.isPositive() && overall.isPositive() && admit.isPositive() && cleanup.isPositive()) { "timeouts must be positive" }
    require(appId == "hatch-web" || (appId.length in 1..19 && appId.all { it in '0'..'9' } && (appId.toLongOrNull() ?: 0) > 0)) {
      "app id must be hatch-web or a positive 64-bit integer"
    }
  }
}

enum class CallPhase {
  Connecting,
  Listening,
  /** Muse is working on something and says nothing yet. */
  Thinking,
  Talking,
  /** Muse was cut off and is waiting for what comes next. */
  Interrupted,
  Ending,
}

/**
 * What the call shows. [level] is the microphone's, 0..1. [heard] is what Muse heard the caller say
 * last, and [reply] what Muse is saying, as far as it has said it; both are blank until Muse sends a
 * transcript, and neither is in [toString], so a logged view never carries speech.
 */
data class CallView(val phase: CallPhase, val level: Float = 0f, val heard: String = "", val reply: String = "") {
  override fun toString() = "CallView(phase=$phase, level=$level, heard=${heard.length} chars, reply=${reply.length} chars)"
}

/** Why a call never went live. Nothing was said, so the caller may fall back to a voice note. */
sealed interface Decline {
  /** No registered session to borrow the VM and its bearer from. */
  data object Offline : Decline

  /** The voice connection was refused at the upgrade ([status]), or never opened (null). */
  data class Upgrade(val status: Int?) : Decline

  /** The server answered the call with a non-2xx head: not admitted. */
  data class Refused(val status: Int) : Decline

  /** The server sent an error before the call was ready. [code] is a short machine code, never its message. */
  data class Rejected(val code: String?) : Decline

  data object TimedOut : Decline

  /** The call broke before it was ready. [why] is our own text, never the server's. */
  data class Dropped(val why: String) : Decline
}

sealed interface OverReason {
  /** Muse ended it, for example after a goodbye. */
  data object RemoteStop : OverReason

  data object RemoteEnd : OverReason

  data object Idle : OverReason

  /** The caller and Muse said goodbye, and Muse did not hang up in time, so the device did. */
  data object Goodbye : OverReason

  data object TooLong : OverReason

  data object HungUp : OverReason

  /** [why] is our own text, never the server's. */
  data class Failed(val why: String) : OverReason
}

sealed interface CallEnd {
  data class Declined(val why: Decline) : CallEnd

  /** A live call ended. [endAcked]: the server confirmed the end-call; false is logged, not thrown. */
  data class Over(val reason: OverReason, val endAcked: Boolean = false) : CallEnd
}

/** What the server says to a few requests, as statuses and short codes only. */
data class ProbeReport(val history: Reached, val fsRead: Reached, val realtime: CallEnd?) {
  data class Reached(val status: Int?, val code: String?)
}
