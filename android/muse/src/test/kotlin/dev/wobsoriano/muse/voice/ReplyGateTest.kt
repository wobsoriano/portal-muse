package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.voice.ReplyGate.Flush
import dev.wobsoriano.muse.voice.ReplyGate.Verdict.Drop
import dev.wobsoriano.muse.voice.ReplyGate.Verdict.Hold
import dev.wobsoriano.muse.voice.ReplyGate.Verdict.Play
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val nothing = Flush(playing = false, waiting = false)
private val everything = Flush(playing = true, waiting = true)

private fun speech(item: String?) = ServerEvent.SpeechStarted(item)

private fun turn(submission: String, item: String) = ServerEvent.TurnStarted(submission, item)

class ReplyGateTest {
  @Test
  fun everythingPlaysUntilAnInterruption() {
    val g = ReplyGate()
    assertEquals(Play, g.audio("r1"))
    assertEquals(Play, g.audio(null))
    assertTrue(g.playing)
    assertEquals(nothing, g.takeFlush())
  }

  @Test
  fun aNewCallersReplyWaitsForItsTurnAndThenPlays() {
    val g = ReplyGate()
    g.audio("r1")
    g.on(speech("i1"))
    assertEquals("speech flushes everything", everything, g.takeFlush())
    assertEquals("the interrupted reply is stale", Drop, g.audio("r1"))
    assertEquals("audio without lineage is muted once fenced", Drop, g.audio(null))
    assertEquals("an unconfirmed reply is held", Hold, g.audio("r2"))
    assertTrue(g.waiting)
    g.on(turn("m1", "i1"))
    assertEquals(nothing, g.takeFlush())
    assertFalse("the confirmed reply is no longer waiting", g.waiting)
    assertTrue(g.playing)
    assertEquals(Play, g.audio("r2"))
    // A later response continues the confirmed turn, and the one before goes quiet for it.
    assertEquals(Play, g.audio("r3"))
    assertEquals(Flush(playing = true, waiting = false), g.takeFlush())
    assertEquals(Drop, g.audio("r2"))
    assertEquals(Play, g.audio("r3"))
    assertEquals("still muted without lineage", Drop, g.audio(null))
  }

  @Test
  fun aTurnConfirmedBeforeAnyReplyPlaysItsFirstResponseFresh() {
    val g = ReplyGate()
    g.on(speech("i1"))
    g.takeFlush()
    g.on(turn("m1", "i1"))
    assertEquals(Play, g.audio("r1"))
    assertEquals("nothing was playing", nothing, g.takeFlush())
    assertEquals(Play, g.audio("r2"))
    assertEquals("the second response replaces the first", Flush(playing = true, waiting = false), g.takeFlush())
    assertEquals(Drop, g.audio("r1"))
  }

  @Test
  fun anotherCallersTurnBlocksTheWaitingReply() {
    val g = ReplyGate()
    g.on(speech("i1"))
    g.takeFlush()
    assertEquals(Hold, g.audio("r1"))
    g.on(turn("m9", "i9"))
    assertEquals(Flush(playing = false, waiting = true), g.takeFlush())
    assertEquals(Drop, g.audio("r1"))
    assertEquals("a new response is stale while blocked", Drop, g.audio("r2"))
    // The right turn unblocks it.
    g.on(turn("m1", "i1"))
    assertEquals(Play, g.audio("r3"))
  }

  @Test
  fun aRepeatedSpeechStartChangesNothing() {
    val g = ReplyGate()
    g.on(speech("i1"))
    g.on(turn("m1", "i1"))
    g.takeFlush()
    assertEquals(Play, g.audio("r1"))
    g.on(speech("i1"))
    assertEquals(nothing, g.takeFlush())
    assertEquals(Play, g.audio("r1"))
  }

  @Test
  fun anInterruptionWithoutAnItemStaysMutedUntilSpeechNamesOne() {
    for (interrupt in listOf<(ReplyGate) -> Unit>({ it.interrupt() }, { it.on(speech(null)) })) {
      val g = ReplyGate()
      g.audio("r1")
      interrupt(g)
      assertEquals(everything, g.takeFlush())
      assertEquals(Drop, g.audio("r1"))
      assertEquals(Drop, g.audio("r2"))
      g.on(turn("m1", "i1"))
      assertEquals("a turn cannot confirm a caller nobody named", Drop, g.audio("r3"))
      g.on(speech("i1"))
      g.on(turn("m1", "i1"))
      assertEquals(Play, g.audio("r4"))
    }
  }

  @Test
  fun aSuppressedInputInterrupts() {
    val g = ReplyGate()
    g.on(speech("i1"))
    g.on(turn("m1", "i1"))
    g.takeFlush()
    g.audio("r1")
    g.on(ServerEvent.InputSuppressed("i2"))
    assertEquals("another item's suppression", nothing, g.takeFlush())
    g.on(ServerEvent.InputSuppressed("i1"))
    assertEquals(everything, g.takeFlush())
    assertEquals(Drop, g.audio("r1"))
    assertEquals(Drop, g.audio("r2"))
  }

  @Test
  fun theOpenResponseIsTheLastCreatedAndNotEnded() {
    val g = ReplyGate()
    g.on(ServerEvent.ResponseCreated("r1"))
    assertEquals("r1", g.openResponse)
    g.on(ServerEvent.ResponseEnded("r0"))
    assertEquals("r1", g.openResponse)
    g.on(ServerEvent.ResponseCreated(null))
    assertEquals("r1", g.openResponse)
    g.on(ServerEvent.ResponseEnded("r1"))
    assertNull(g.openResponse)
    g.on(ServerEvent.ResponseCreated("r2"))
    g.interrupt()
    assertNull("a barge-in cancels it once", g.openResponse)
  }

  @Test
  fun lineageIsNeverForgotten() {
    val g = ReplyGate()
    for (i in 1..MAX_IDS) g.audio("r$i")
    assertEquals(Play, g.audio("r1"))
    assertTrue(runCatching { g.audio("r${MAX_IDS + 1}") }.exceptionOrNull() is GateOverflow)

    val h = ReplyGate()
    for (i in 1..MAX_IDS) h.on(speech("i$i"))
    assertTrue(runCatching { h.on(speech("i${MAX_IDS + 1}")) }.exceptionOrNull() is GateOverflow)
  }
}
