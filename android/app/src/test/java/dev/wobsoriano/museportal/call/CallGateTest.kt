package dev.wobsoriano.museportal.call

import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.OverReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.Assert.assertEquals
import org.junit.Test

class CallGateTest {
  private var clock = 1_000_000L
  private val gate = CallGate { clock }

  /** Whether the gate, closed by [why] at the clock's time, is open just before and just after [wait]. */
  private fun openAround(why: Decline, wait: Duration): Pair<Boolean, Boolean> {
    gate.reset()
    val start = clock
    gate.record(CallEnd.Declined(why))
    clock = start + wait.inWholeMilliseconds - 1
    val before = gate.open()
    clock = start + wait.inWholeMilliseconds
    return before to gate.open()
  }

  @Test
  fun aGateStartsOpen() {
    assertEquals(true, gate.open())
  }

  @Test
  fun eachRefusalCoolsOffForItsOwnWhile() {
    val table =
        listOf(
            Decline.Offline to 2.minutes,
            Decline.TimedOut to 2.minutes,
            Decline.Dropped("busy") to 2.minutes,
            Decline.Upgrade(null) to 2.minutes,
            Decline.Refused(408) to 2.minutes,
            Decline.Refused(425) to 2.minutes,
            Decline.Refused(429) to 2.minutes,
            Decline.Refused(503) to 2.minutes,
            Decline.Refused(403) to 6.hours,
            Decline.Refused(404) to 6.hours,
            Decline.Rejected("not_admitted") to 6.hours,
            Decline.Rejected(null) to 6.hours,
            Decline.Upgrade(401) to 5.minutes,
            Decline.Upgrade(403) to 30.minutes,
            Decline.Upgrade(502) to 2.minutes,
        )
    for ((why, wait) in table) assertEquals("$why", false to true, openAround(why, wait))
  }

  @Test
  fun aCallThatWentLiveOpensTheGate() {
    gate.record(CallEnd.Declined(Decline.Refused(403)))
    gate.record(CallEnd.Over(OverReason.RemoteStop, endAcked = true))
    assertEquals(true, gate.open())
  }

  @Test
  fun aCallThatFailedLeavesTheGateAsItWas() {
    gate.record(CallEnd.Declined(Decline.Refused(403)))
    gate.record(CallEnd.Over(OverReason.Failed("send failed")))
    assertEquals(false, gate.open())
    gate.reset()
    gate.record(CallEnd.Over(OverReason.Failed("send failed")))
    assertEquals(true, gate.open())
  }

  @Test
  fun resettingOpensAClosedGate() {
    gate.record(CallEnd.Declined(Decline.Rejected(null)))
    assertEquals(false, gate.open())
    gate.reset()
    assertEquals(true, gate.open())
  }
}
