package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.voice.CallInput.AudioFailed
import dev.wobsoriano.muse.voice.CallInput.HangUp
import dev.wobsoriano.muse.voice.CallInput.Head
import dev.wobsoriano.muse.voice.CallInput.Interrupt
import dev.wobsoriano.muse.voice.CallInput.Mic
import dev.wobsoriano.muse.voice.CallInput.MicEnded
import dev.wobsoriano.muse.voice.CallInput.Server
import dev.wobsoriano.muse.voice.CallInput.Tick
import dev.wobsoriano.muse.voice.CallInput.WireEnded
import dev.wobsoriano.muse.voice.CallInput.WireFailed
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val CALL = "call-1"

/** [samples] of a square wave whose RMS is [amp]. */
private fun tone(amp: Int, samples: Int = CALL_FRAME_SAMPLES) =
    ByteArray(samples * 2).also {
      for (i in 0 until samples) {
        val v = if (i % 2 == 0) amp else -amp
        it[2 * i] = v.toByte()
        it[2 * i + 1] = (v shr 8).toByte()
      }
    }

private fun ms(ms: Int, amp: Int = 3000) = tone(amp, ms * CALL_RATE / 1000)

private fun audio(pcm: ByteArray, id: String? = "r1") = ServerEvent.Audio(pcm, id)

private fun ready(callId: String? = CALL) = ServerEvent.Session(callId, "s1", "t1")

/** One effect, written out so a test can say exactly what a step must do. */
private fun said(e: Effect): String =
    when (e) {
      is Effect.Send ->
          when (val l = e.line) {
            is ClientLine.Start -> "start ${l.callId} ${l.nodeId}"
            ClientLine.Offer -> "offer"
            is ClientLine.Append -> "append " + if (l.pcm.all { it == 0.toByte() }) "quiet" else "sound"
            is ClientLine.Cancel -> "cancel ${l.responseId}"
          }
      is Effect.Play -> "play ${e.pcm.size}"
      Effect.Flush -> "flush"
      Effect.OpenAudio -> "open"
      is Effect.Show -> "show ${e.view.phase}"
      is Effect.Finish -> "finish ${e.end}"
    }

private fun over(reason: OverReason) = listOf("show Ending", "finish ${CallEnd.Over(reason)}")

private fun declined(why: Decline) = listOf("finish ${CallEnd.Declined(why)}")

private fun appends(said: List<String>) = said.filter { it.startsWith("append") }

/**
 * A call driven by hand: every step at a time the test picks, the effects written out. A Show that
 * only moves the level is kept in [shown] and left out of what a step returns.
 */
private class Call(opts: CallOptions = CallOptions()) {
  val m = CallMachine(opts, "homelink-a1b2c3", CALL, 0)
  var now = 0L
  val shown = mutableListOf<CallView>()
  val begun = m.begin().map(::said)

  fun at(t: Long, input: CallInput): List<String> {
    now = t
    val out = mutableListOf<String>()
    for (e in m.step(input, t)) {
      if (e is Effect.Show) {
        val same = (shown.lastOrNull()?.phase ?: CallPhase.Connecting) == e.view.phase
        shown += e.view
        if (same) continue
      }
      out += said(e)
    }
    return out
  }

  fun next(input: CallInput) = at(now, input)

  fun server(e: ServerEvent) = next(Server(e))

  /** Ticks every 20 ms until [to], and returns everything the ticks did. */
  fun until(to: Long): List<String> {
    val got = mutableListOf<String>()
    while (now < to) got += at(minOf(now + 20, to), Tick)
    return got
  }

  fun live(): Call {
    next(Head(200))
    server(ready())
    return this
  }
}

private fun live(opts: CallOptions = CallOptions()) = Call(opts).live()

class CallMachineTest {
  // 4.1: Opening and Awaiting.

  @Test
  fun theStartAndTheOfferGoFirstAndAreEndedWhateverHappens() {
    val c = Call()
    assertEquals(listOf("start $CALL homelink-a1b2c3", "offer"), c.begun)
    assertTrue("a start line that may have gone is not ended", c.m.endCallDue)
    assertEquals(declined(Decline.Refused(403)), c.next(Head(403)))
    assertTrue(c.m.endCallDue)
    assertEquals("a finished call does nothing more", emptyList<String>(), c.next(HangUp))
  }

  @Test
  fun readinessOpensTheAudio() {
    val c = Call()
    assertEquals(emptyList<String>(), c.next(Head(200)))
    assertEquals(listOf("open", "show Listening"), c.server(ready()))
    assertEquals("t1", c.m.threadId)
    assertTrue(c.m.wasLive)
  }

  @Test
  fun anythingButOurReadinessBeforeItDeclines() {
    for ((name, input, want) in
        listOf(
            Triple("another call's readiness", Server(ready("call-2")), Decline.Dropped("voice identity")),
            Triple("readiness without a call id", Server(ready(null)), Decline.Dropped("voice identity")),
            Triple("an error", Server(ServerEvent.Error("not_admitted")), Decline.Rejected("not_admitted")),
            Triple("an error without a code", Server(ServerEvent.Error(null)), Decline.Rejected(null)),
            Triple("audio", Server(audio(ms(40))), Decline.Dropped("audio before ready")),
            Triple("a stop", Server(ServerEvent.Stop), Decline.Dropped("stop before ready")),
            Triple("a bad event", Server(ServerEvent.Malformed("input hold")), Decline.Dropped("input hold")),
            Triple("the end of the answer", WireEnded, Decline.Dropped("ended before ready")),
            Triple("a broken answer", WireFailed("reset"), Decline.Dropped("reset")),
        )) {
      val c = Call()
      c.next(Head(200))
      assertEquals(name, declined(want), c.next(input))
    }
    val early = Call()
    assertEquals("a broken answer before the head", declined(Decline.Dropped("overflow")), early.next(WireFailed("overflow")))
  }

  @Test
  fun beforeReadinessKeepalivesAndTheMicAreIgnored() {
    val c = Call()
    c.next(Head(200))
    for (input in listOf(Server(audio(ByteArray(1920))), Server(ServerEvent.Other), Mic(tone(5000)), MicEnded, Interrupt, Tick)) {
      assertEquals("$input", emptyList<String>(), c.next(input))
    }
    assertEquals(listOf("open", "show Listening"), c.server(ready()))
  }

  @Test
  fun readinessHasOneBudgetFromTheStart() {
    val c = Call(CallOptions(admit = 1.seconds))
    c.next(Head(200))
    assertEquals(emptyList<String>(), c.until(999))
    assertEquals(declined(Decline.TimedOut), c.at(1000, Tick))
    // The overall bound is the budget when it is the shorter.
    val d = Call(CallOptions(admit = 1.seconds, overall = 500.milliseconds))
    assertEquals(emptyList<String>(), d.until(499))
    assertEquals(declined(Decline.TimedOut), d.at(500, Tick))
  }

  @Test
  fun aHangUpEndsTheCallInEveryPhaseAndIsNeverADecline() {
    assertEquals(over(OverReason.HungUp), Call().next(HangUp))
    assertEquals(over(OverReason.HungUp), Call().also { it.next(Head(200)) }.next(HangUp))
    assertEquals(over(OverReason.HungUp), live().next(HangUp))
  }

  @Test
  fun eventsBeforeReadinessCount() {
    // A hold and a turn that come before readiness hold after it.
    val c = Call(CallOptions(idle = 1.seconds))
    c.next(Head(200))
    c.server(ServerEvent.Hold(true))
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    assertEquals(listOf("open", "show Thinking"), c.server(ready()))
    assertEquals("held", emptyList<String>(), c.next(Mic(tone(5000))))
    assertEquals("a turn pauses the idle timer", emptyList<String>(), c.until(5000))
  }

  // 4.1: Live.

  @Test
  fun aSecondReadinessFails() {
    assertEquals(over(OverReason.Failed("duplicate readiness")), live().server(ready()))
  }

  @Test
  fun errorsAfterReadinessAreAdvisoryUnlessTheSessionIsDead() {
    val c = live()
    assertEquals(emptyList<String>(), c.server(ServerEvent.Error("server_error")))
    assertEquals(emptyList<String>(), c.server(ServerEvent.Error(null)))
    assertEquals(2, c.m.advisoryErrors)
    assertEquals(over(OverReason.Failed("session dead")), c.server(ServerEvent.Error("session_dead")))
  }

  @Test
  fun aHoldStopsTheMicUntilItIsLifted() {
    val c = live()
    c.server(ServerEvent.Hold(true))
    assertEquals(emptyList<String>(), c.next(Mic(tone(5000))))
    c.server(ServerEvent.Hold(false))
    assertEquals(listOf("append sound"), c.next(Mic(tone(5000))))
  }

  @Test
  fun aReplyPlaysAndTheViewFollowsIt() {
    val c = live()
    assertEquals(listOf("play 9600", "show Talking"), c.at(1000, Server(audio(ms(200)))))
    assertEquals(emptyList<String>(), c.until(1999))
    assertEquals(listOf("show Listening"), c.at(2000, Tick))
  }

  @Test
  fun pausesInAStreamingReplyKeepShowingTalking() {
    val c = live()
    c.at(1000, Server(audio(ms(200))))
    assertEquals("a 700 ms gap", emptyList<String>(), c.until(1900))
    assertEquals(listOf("play 9600"), c.at(1900, Server(audio(ms(200)))))
    assertEquals(emptyList<String>(), c.until(2899))
    assertEquals(listOf("show Listening"), c.at(2900, Tick))
  }

  @Test
  fun zeroKeepalivesNeitherPlayNorKeepTheCallAlive() {
    val c = live(CallOptions(idle = 1.seconds))
    assertEquals(emptyList<String>(), c.at(500, Server(audio(ByteArray(1920)))))
    assertEquals(emptyList<String>(), c.until(999))
    assertEquals(over(OverReason.Idle), c.at(1000, Tick))
    // Once a reply has played, silence is part of it and plays.
    val d = live()
    d.server(audio(ms(40)))
    assertEquals(listOf("play 1920"), d.server(audio(ByteArray(1920))))
  }

  @Test
  fun speechCutsOffTheReply() {
    val c = live()
    c.at(1000, Server(audio(ms(1000))))
    assertEquals(listOf("flush", "show Interrupted"), c.at(1100, Server(ServerEvent.SpeechStarted("i1"))))
    assertEquals("the cut-off reply stays stale", emptyList<String>(), c.server(audio(ms(40))))
    assertEquals(emptyList<String>(), c.until(3099))
    assertEquals(listOf("show Listening"), c.at(3100, Tick))
    // Speech while Muse is quiet interrupts nothing that shows.
    val d = live()
    assertEquals(listOf("flush"), d.at(1000, Server(ServerEvent.SpeechStarted("i1"))))
  }

  @Test
  fun aNewCallersReplyWaitsForItsTurn() {
    val c = live()
    c.server(ServerEvent.SpeechStarted("i1"))
    assertEquals("held until its turn", emptyList<String>(), c.server(audio(ms(200), "r2")))
    assertEquals(emptyList<String>(), c.server(audio(ms(100), "r2")))
    assertEquals(listOf("play 14400", "show Talking"), c.server(ServerEvent.TurnStarted("m1", "i1")))
    assertEquals(listOf("play 1920"), c.server(audio(ms(40), "r2")))
  }

  @Test
  fun anotherCallersTurnThrowsTheHeldReplyAway() {
    val c = live()
    c.server(ServerEvent.SpeechStarted("i1"))
    c.server(audio(ms(200), "r2"))
    assertEquals(listOf("show Thinking"), c.server(ServerEvent.TurnStarted("m9", "i9")))
    assertEquals(emptyList<String>(), c.server(ServerEvent.TurnStarted("m1", "i1")))
    assertEquals("r2 went stale with the other turn", emptyList<String>(), c.server(audio(ms(40), "r2")))
  }

  @Test
  fun aHeldReplyAndThePlaybackAreFiveSecondsAtMost() {
    val c = live()
    c.server(ServerEvent.SpeechStarted("i1"))
    repeat(5) { assertEquals(emptyList<String>(), c.server(audio(ms(1000), "r2"))) }
    assertEquals(over(OverReason.Failed("unconfirmed reply too long")), c.server(audio(ms(40), "r2")))

    val d = live()
    repeat(5) { d.server(audio(ms(1000))) }
    assertEquals(over(OverReason.Failed("playback full")), d.server(audio(ms(40))))
    // What has played makes room.
    val e = live()
    repeat(5) { e.at(1000, Server(audio(ms(1000)))) }
    assertEquals(listOf("play 1920"), e.at(1040, Server(audio(ms(40)))))
  }

  @Test
  fun audioWithoutLineageIsCountedWhenTheGateDropsIt() {
    val c = live()
    assertEquals(listOf("play 1920", "show Talking"), c.server(audio(ms(40), null)))
    c.server(ServerEvent.SpeechStarted("i1"))
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    assertEquals(emptyList<String>(), c.server(audio(ms(40), null)))
    assertEquals(emptyList<String>(), c.server(audio(ms(40), null)))
    assertEquals(2, c.m.unlined)
  }

  @Test
  fun aBargeInCancelsTheOpenResponse() {
    val c = live()
    c.server(ServerEvent.ResponseCreated("r1"))
    c.at(1000, Server(audio(ms(1000))))
    assertEquals(listOf("flush", "cancel r1", "show Interrupted"), c.at(1200, Interrupt))
    assertEquals("muted until a new caller's turn", emptyList<String>(), c.server(audio(ms(40))))
    assertEquals("nothing open to cancel", listOf("flush"), c.at(1300, Interrupt))
    val d = live()
    d.server(ServerEvent.ResponseCreated("r1"))
    d.server(ServerEvent.ResponseEnded("r1"))
    assertEquals(listOf("flush"), d.next(Interrupt))
  }

  @Test
  fun aResponseEndingIsNotTheCallEnding() {
    val c = live(CallOptions(idle = 1.seconds))
    c.at(800, Server(ServerEvent.ResponseCreated("r1")))
    assertEquals(emptyList<String>(), c.at(900, Server(ServerEvent.ResponseEnded("r1"))))
    assertEquals("a response renews the idle window", emptyList<String>(), c.until(1899))
    assertEquals(over(OverReason.Idle), c.at(1900, Tick))
  }

  @Test
  fun anActiveTurnPausesTheIdleTimerAndItsSettlingRenewsIt() {
    val c = live(CallOptions(idle = 1.seconds))
    assertEquals(listOf("show Thinking"), c.at(100, Server(ServerEvent.TurnStarted("m1", "i1"))))
    assertEquals(emptyList<String>(), c.until(9000))
    assertEquals("an unknown settlement retires nothing", emptyList<String>(), c.server(ServerEvent.TurnSettled("m9")))
    assertEquals(emptyList<String>(), c.server(ServerEvent.TurnSettled(null)))
    assertEquals(listOf("show Listening"), c.at(9000, Server(ServerEvent.TurnSettled("m1"))))
    assertEquals(emptyList<String>(), c.until(9999))
    assertEquals(over(OverReason.Idle), c.at(10000, Tick))
  }

  @Test
  fun settlingRetiresOlderAliasesOfItsItemOnly() {
    val c = live()
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    c.server(ServerEvent.TurnStarted("m2", "i1"))
    c.server(ServerEvent.TurnStarted("m3", "i2"))
    c.server(ServerEvent.TurnStarted("m4", "i1"))
    c.server(ServerEvent.TurnSettled("m2"))
    assertEquals(CallPhase.Thinking, c.shown.last().phase)
    c.server(ServerEvent.TurnSettled("m3"))
    assertEquals("m4 aliases i1 but is newer than m2, so it still works", CallPhase.Thinking, c.shown.last().phase)
    assertEquals(listOf("show Listening"), c.server(ServerEvent.TurnSettled("m4")))
    assertEquals("m1 went with m2", emptyList<String>(), c.server(ServerEvent.TurnSettled("m1")))
  }

  @Test
  fun turnsFailClosed() {
    val c = live()
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    assertEquals("a replay is fine", emptyList<String>(), c.server(ServerEvent.TurnStarted("m1", "i1")))
    assertEquals(over(OverReason.Failed("conflicting turn")), c.server(ServerEvent.TurnStarted("m1", "i2")))

    val d = live()
    for (i in 1..MAX_TRACKED_TURNS) d.server(ServerEvent.TurnStarted("m$i", "i$i"))
    assertEquals(over(OverReason.Failed("too many turns")), d.server(ServerEvent.TurnStarted("m0", "i0")))

    val e = live()
    for (i in 1..MAX_SETTLED_TURNS) {
      e.server(ServerEvent.TurnStarted("m$i", "i$i"))
      e.server(ServerEvent.TurnSettled("m$i"))
    }
    e.server(ServerEvent.TurnStarted("m0", "i0"))
    assertEquals(over(OverReason.Failed("too many settled turns")), e.server(ServerEvent.TurnSettled("m0")))
    // A settled turn's replay is still known.
    val f = live()
    f.server(ServerEvent.TurnStarted("m1", "i1"))
    f.server(ServerEvent.TurnSettled("m1"))
    assertEquals(over(OverReason.Failed("conflicting turn")), f.server(ServerEvent.TurnStarted("m1", "i2")))
  }

  @Test
  fun tooManyIdsEndTheCall() {
    val c = live()
    for (i in 1..MAX_IDS) c.server(audio(ms(1, amp = 0) + byteArrayOf(1, 0), "r$i"))
    assertEquals(over(OverReason.Failed("too many ids")), c.server(audio(ms(1), "r0")))
  }

  @Test
  fun aStopPlaysOutTheSpeechAndThenEnds() {
    val c = live()
    c.at(1000, Server(audio(ms(400))))
    assertEquals(listOf("show Ending"), c.at(1100, Server(ServerEvent.Stop)))
    assertEquals("nothing more is heard or said", emptyList<String>(), c.next(Mic(tone(5000))))
    assertEquals(emptyList<String>(), c.server(audio(ms(40))))
    assertEquals(emptyList<String>(), c.next(Interrupt))
    assertEquals(emptyList<String>(), c.next(WireFailed("reset")))
    // The last audible frame ends at 1400, and the settle follows it.
    assertEquals(emptyList<String>(), c.until(1599))
    assertEquals(listOf("finish ${CallEnd.Over(OverReason.RemoteStop)}"), c.at(1600, Tick))
    // With nothing queued it ends at once.
    assertEquals(over(OverReason.RemoteStop), live().server(ServerEvent.Stop))
  }

  @Test
  fun theEndOfTheAnswerPlaysOutTheSpeechToo() {
    val c = live()
    c.at(1000, Server(audio(ms(100))))
    assertEquals(listOf("show Ending"), c.at(1000, WireEnded))
    assertEquals(listOf("finish ${CallEnd.Over(OverReason.RemoteEnd)}"), c.until(1300))
  }

  @Test
  fun aHangUpWhileDrainingKeepsTheRemoteReason() {
    val c = live()
    c.server(audio(ms(1000)))
    c.server(ServerEvent.Stop)
    assertEquals(listOf("finish ${CallEnd.Over(OverReason.RemoteStop)}"), c.next(HangUp))
  }

  @Test
  fun failuresAfterReadinessEndTheCall() {
    for ((input, why) in
        listOf(
            WireFailed("reset") to "reset",
            AudioFailed("speaker") to "speaker",
            Server(ServerEvent.Malformed("audio base64")) to "audio base64",
            Mic(ByteArray(0)) to "bad mic frame",
            Mic(ByteArray(3)) to "bad mic frame",
            Mic(ByteArray(CALL_FRAME_BYTES + 2)) to "bad mic frame",
        )) {
      assertEquals("$input", over(OverReason.Failed(why)), live().next(input))
    }
  }

  @Test
  fun theCallLastsOverallAtMostEvenMidTurn() {
    val c = live(CallOptions(overall = 2.seconds))
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    assertEquals(emptyList<String>(), c.until(1999))
    assertEquals(over(OverReason.TooLong), c.at(2000, Tick))
  }

  @Test
  fun theCallEndsWhenIdle() {
    val c = live(CallOptions(idle = 1.seconds))
    assertEquals(emptyList<String>(), c.until(999))
    assertEquals(over(OverReason.Idle), c.at(1000, Tick))
  }

  // 4.2: what happens to a mic frame.

  @Test
  fun halfDuplexGatesTheMicWhileMuseIsAudibleAndForTheSettle() {
    val c = live()
    c.at(1000, Server(audio(ms(40))))
    assertEquals(emptyList<String>(), appends(c.at(1000, Mic(tone(5000)))))
    assertEquals(emptyList<String>(), appends(c.at(1239, Mic(tone(5000)))))
    assertEquals(listOf("append sound"), appends(c.at(1240, Mic(tone(5000)))))
    // Silence does not gate it.
    val d = live()
    d.server(audio(ms(40)))
    d.at(1000, Server(audio(ms(1000, amp = 100))))
    assertEquals(listOf("append sound"), appends(d.at(1000, Mic(tone(5000)))))
  }

  @Test
  fun fullDuplexNeverGatesTheMic() {
    val c = live(CallOptions(duplex = Duplex.Full))
    c.at(1000, Server(audio(ms(1000))))
    assertEquals(listOf("append sound"), appends(c.at(1100, Mic(tone(5000)))))
  }

  @Test
  fun speechRenewsTheIdleWindowAndQuietDoesNot() {
    val c = live(CallOptions(idle = 1.seconds))
    c.at(500, Mic(tone(AUDIBLE_RMS)))
    c.at(800, Mic(tone(AUDIBLE_RMS - 1)))
    assertEquals(emptyList<String>(), c.until(1499))
    assertEquals(over(OverReason.Idle), c.at(1500, Tick))
  }

  @Test
  fun theThresholdSendsQuietFramesAsSilenceAfterTheSpeechTail() {
    val c = live(CallOptions(micThreshold = 1000))
    assertEquals(listOf("append quiet"), c.at(100, Mic(tone(999))))
    assertEquals(listOf("append sound"), c.at(200, Mic(tone(1000))))
    assertEquals(listOf("append sound"), c.at(600, Mic(tone(500))))
    assertEquals(listOf("append quiet"), c.at(601, Mic(tone(500))))
  }

  @Test
  fun quietFramesFollowTheEndOfTheMicAndPauseWhileGated() {
    val c = live()
    c.at(1000, MicEnded)
    val quiet = c.until(2000).filter { it == "append quiet" }
    assertTrue("${quiet.size} quiet frames in a second", quiet.size in 24..26)
    c.at(2000, Server(audio(ms(1000))))
    assertEquals("none while Muse talks and the settle runs", emptyList<String>(), c.until(3199).filter { it.startsWith("append") })
    assertTrue("they come back after", c.until(3400).count { it == "append quiet" } in 4..6)
  }

  // 4.4: what the call shows.

  @Test
  fun theViewTakesTheFirstThatHolds() {
    val c = live()
    c.at(100, Server(ServerEvent.TurnStarted("m1", "i1")))
    c.at(200, Server(ServerEvent.SpeechStarted("i2")))
    c.at(300, Server(ServerEvent.TurnStarted("m2", "i2")))
    c.at(400, Server(audio(ms(400), "r2")))
    c.at(600, Interrupt)
    c.until(2700)
    c.server(ServerEvent.TurnSettled("m2"))
    c.server(ServerEvent.TurnSettled("m1"))
    c.server(ServerEvent.Stop)
    assertEquals(
        listOf(CallPhase.Listening, CallPhase.Thinking, CallPhase.Talking, CallPhase.Interrupted, CallPhase.Thinking, CallPhase.Listening, CallPhase.Ending),
        c.shown.map { it.phase })
  }

  @Test
  fun captionsFollowWhatMuseHeardAndSays() {
    val c = live()
    val caption = { c.shown.last().let { it.heard to it.reply } }
    c.server(ServerEvent.Heard("what time is it"))
    assertEquals("what time is it" to "", caption())
    c.server(ServerEvent.ResponseCreated("r1"))
    c.server(ServerEvent.ReplyText("It's "))
    c.server(ServerEvent.ReplyText("noon"))
    assertEquals("what time is it" to "It's noon", caption())
    c.server(ServerEvent.ReplyDone("It's noon."))
    assertEquals("It's noon.", caption().second)
    c.server(ServerEvent.ResponseCreated("r2"))
    assertEquals("a new reply starts blank", "", caption().second)
    c.server(ServerEvent.ReplyText("x".repeat(MAX_CAPTION + 10)))
    assertEquals(MAX_CAPTION, caption().second.length)

    val d = live()
    d.server(ServerEvent.ReplyText("Once upon"))
    d.at(1000, Server(audio(ms(1000))))
    assertEquals(listOf("flush", "show Interrupted"), d.at(1100, Server(ServerEvent.SpeechStarted("i1"))))
    assertEquals("a cut-off reply's words go with it", "", d.shown.last().reply)
  }

  // Goodbyes: Muse may hang up seconds after its own, or not at all.

  /** The caller says [said], and Muse answers [answer] with a second of speech from t=1000. */
  private fun exchange(said: String, answer: String): Call {
    val c = live()
    c.server(ServerEvent.Heard(said))
    c.server(ServerEvent.TurnStarted("m1", "i1"))
    c.server(ServerEvent.ResponseCreated("r1"))
    c.at(1000, Server(audio(ms(1000))))
    c.server(ServerEvent.ReplyDone(answer))
    return c
  }

  @Test
  fun onceBothSayGoodbyeTheCallStopsListeningAndShowsItIsEnding() {
    val c = exchange("Thanks, bye!", "Bye! Have a great day.")
    assertEquals("nothing goes up once both said goodbye", emptyList<String>(), appends(c.next(Mic(tone(5000)))))
    assertEquals("Muse's goodbye still shows as talking", emptyList<String>(), c.until(2799))
    assertEquals(listOf("show Ending"), c.at(2800, Tick))
  }

  @Test
  fun museStillHangsUpItself() {
    val c = exchange("ok goodbye", "Goodbye!")
    c.until(5600)
    assertEquals(listOf("finish ${CallEnd.Over(OverReason.RemoteStop)}"), c.server(ServerEvent.Stop))
  }

  @Test
  fun ifMuseDoesNotHangUpTheDeviceDoesAfterItsGoodbyePlayed() {
    val c = exchange("bye Muse", "Take care, bye!")
    // The goodbye has played at 2200: the mic's settle included.
    assertEquals(emptyList<String>(), c.until(10_199).filter { it.startsWith("finish") })
    assertEquals(listOf("finish ${CallEnd.Over(OverReason.Goodbye)}"), c.at(10_200, Tick))
  }

  @Test
  fun oneSidedGoodbyesKeepTheCallGoing() {
    for ((said, answer) in listOf("bye" to "Before you go, one more thing.", "what's a good riddle" to "Here's one. Bye the way...", "tell me more" to "Goodbye!")) {
      val c = exchange(said, answer)
      assertFalse("$said / $answer", "show Ending" in c.until(2800))
      assertEquals("$said / $answer", listOf("append sound"), c.next(Mic(tone(5000))))
      assertEquals("$said / $answer", emptyList<String>(), c.until(20_000).filter { it.startsWith("finish") })
    }
  }

  @Test
  fun cuttingOffMusesGoodbyeKeepsTheCall() {
    val c = exchange("bye", "Bye!")
    c.at(1500, Interrupt)
    assertEquals(listOf("append sound"), c.next(Mic(tone(5000))))
    assertEquals(emptyList<String>(), c.until(20_000).filter { it.startsWith("finish") })
  }

  @Test
  fun farewells() {
    for (s in listOf("Thanks, bye!", "Goodbye.", "good-bye", "bye-bye", "See you tomorrow", "Good night!", "Take care.", "Talk to you later", "Have a great day!"))
        assertTrue(s, isFarewell(s))
    for (s in listOf("maybe later", "standby mode", "I'll tell you later", "byte", "goodbyes are hard to write"))
        assertFalse(s, isFarewell(s))
  }

  @Test
  fun aViewNeverPrintsWhatWasSaid() {
    val v = CallView(CallPhase.Talking, 0.5f, heard = "my secret", reply = "your secret")
    assertFalse(v.toString(), "secret" in v.toString())
  }

  @Test
  fun theLevelFollowsTheMicTenTimesASecond() {
    val c = live()
    for (t in 0L until 1000L step 20) c.at(t, Mic(tone(4000, 480)))
    val levels = c.shown.filter { it.level > 0f }
    assertEquals(0.5f, levels.first().level, 0.001f)
    assertTrue("${levels.size} level changes", levels.size <= 1)
    c.until(1200)
    assertEquals("silence brings it down", 0f, c.shown.last().level)
  }
}
