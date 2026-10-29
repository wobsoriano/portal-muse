package dev.wobsoriano.museportal.call

import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.OverReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Whether "Hey Muse" tries a call or goes straight to a voice note. A call Muse would not take closes
 * the gate for a while, so every wake doesn't wait out a refusal first; a call that went live opens
 * it. Memory only: after a restart the first wake tries again.
 */
class CallGate(private val now: () -> Long = System::currentTimeMillis) {
  /** When calls are tried again, in [now]'s milliseconds; 0 while open. */
  @Volatile var until = 0L
    private set

  fun open(): Boolean = now() >= until

  fun record(end: CallEnd) {
    when (end) {
      is CallEnd.Declined -> until = now() + coolOff(end.why).inWholeMilliseconds
      is CallEnd.Over -> if (end.reason !is OverReason.Failed) until = 0
    }
  }

  fun reset() {
    until = 0
  }
}

internal fun coolOff(why: Decline): Duration =
    when (why) {
      Decline.Offline,
      Decline.TimedOut,
      is Decline.Dropped -> 2.minutes
      is Decline.Refused -> if (transient(why.status)) 2.minutes else 6.hours
      is Decline.Rejected -> 6.hours
      // A 401 is a bearer the control session's next round replaces.
      is Decline.Upgrade ->
          when (val status = why.status) {
            null -> 2.minutes
            401 -> 5.minutes
            else -> if (transient(status)) 2.minutes else 30.minutes
          }
    }

private fun transient(status: Int) = status == 408 || status == 425 || status == 429 || status in 500..599
