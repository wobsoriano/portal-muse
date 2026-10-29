package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.AskInput
import dev.wobsoriano.muse.Client
import dev.wobsoriano.muse.Config
import dev.wobsoriano.muse.Harness
import dev.wobsoriano.muse.Header
import dev.wobsoriano.muse.MemStore
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.bool
import dev.wobsoriano.muse.long
import dev.wobsoriano.muse.receive
import dev.wobsoriano.muse.str
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val ISO_MS = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}\+00:00""")

private fun tone(amp: Int, samples: Int = CALL_FRAME_SAMPLES) =
    ByteArray(samples * 2).also {
      for (i in 0 until samples) {
        val v = if (i % 2 == 0) amp else -amp
        it[2 * i] = v.toByte()
        it[2 * i + 1] = (v shr 8).toByte()
      }
    }

/** Waits up to 5 s for [cond], which another thread makes true. */
private fun eventually(what: String, cond: () -> Boolean) {
  val until = System.nanoTime() + 5_000_000_000
  while (!cond()) {
    if (System.nanoTime() > until) throw AssertionError(what)
    Thread.sleep(10)
  }
}

class CallTest {
  private val harnesses = mutableListOf<Harness>()
  private val voice = FakeVoice()

  @After
  fun stop() {
    for (h in harnesses) h.close()
  }

  /** A client registered with the fake, whose VM has a voice desk. */
  private fun online(): Harness {
    val h = Harness().also { harnesses += it }
    h.fake.desk = voice
    h.until(Phase.Online)
    h.vm()
    return h
  }

  private class Running(val end: Deferred<CallEnd>, val audio: FakeAudio, val control: Channel<CallCommand>, val views: LinkedBlockingQueue<CallView>) {
    fun await(): CallEnd = runBlocking { withTimeout(10.seconds) { end.await() } }

    fun until(phase: CallPhase) {
      while (true) if ((views.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("never $phase")).phase == phase) return
    }
  }

  private fun call(h: Harness, opts: CallOptions = CallOptions(), audio: FakeAudio = FakeAudio()): Running {
    val control = Channel<CallCommand>(Channel.UNLIMITED)
    val views = LinkedBlockingQueue<CallView>()
    return Running(h.scope.async { h.client.call(audio, opts, control) { views.add(it) } }, audio, control, views)
  }

  /** A call that is ready, with the start and the offer read. */
  private fun ready(h: Harness, opts: CallOptions = CallOptions()): Pair<Running, FakeCall> {
    val r = call(h, opts)
    val c = voice.calls.receive("the call")
    c.next("hatch.voice.call.start")
    c.next("hatch.voice.capability.offer")
    c.head()
    c.ready()
    r.until(CallPhase.Listening)
    return r to c
  }

  @Test
  fun aCallBorrowsTheSessionsVmAndBearerAndNeverRegisters() {
    val h = online()
    val r = call(h)
    val c = voice.calls.receive("the call")
    assertEquals("vm_id=vm%201&app_id=hatch-web", c.vm.query)
    assertNull("a call opened ${c.vm.controlOpen}", c.vm.controlOpen)
    assertNull("a call registered", c.vm.registers.poll())
    assertTrue("path = ${c.request.path}", Regex("/voice/realtime\\?call_id=[0-9a-f-]{36}").matches(c.request.path))
    assertEquals(listOf(Header("Content-Type", "application/x-ndjson; charset=utf-8")), c.request.headers)
    val start = c.lines.receive("the start")
    assertEquals("hatch.voice.call.start", start.str("type"))
    assertEquals(c.callId, start.str("call_id"))
    assertEquals("mcu-wearable", start.str("device"))
    assertEquals("homelink-a1b2c3", start.str("device_id"))
    assertEquals("audio", start.str("call_media_type"))
    assertTrue("proactive_greeting_enabled is false", start.containsKey("proactive_greeting_enabled") && !start.bool("proactive_greeting_enabled"))
    assertFalse(start.containsKey("thread_id"))
    val offer = c.lines.receive("the offer")
    assertEquals("hatch.voice.capability.offer", offer.str("type"))
    assertEquals(1L, offer.long("end_call_stop"))
    r.control.trySend(CallCommand.HangUp)
    assertEquals(CallEnd.Over(OverReason.HungUp, endAcked = true), r.await())
    // The same bearer twice, and no refresh or second fetch: the call never touched the tokens.
    assertEquals(listOf("fetch Bearer access-0", "upgrade Bearer vmtok-0 -> 0", "upgrade Bearer vmtok-0 -> 0"), h.fake.log())
    // The device's own session is still the registered one.
    assertEquals("you said: still here", runBlocking { h.client.ask(AskInput.Text("still here")) }.text)
  }

  @Test
  fun aWholeCall() {
    val h = online()
    val (r, c) = ready(h)
    assertEquals("open", r.audio.events.receive("the audio opening"))
    val spoken = listOf(tone(3000), tone(4000), tone(5000, 480))
    for (f in spoken) r.audio.mic.add(f)
    for (f in spoken) {
      val append = c.next("input_audio_buffer.append")
      assertArrayEquals(f, Base64.getDecoder().decode(append.str("audio")))
      assertTrue("send_timestamp_ms = ${append["send_timestamp_ms"]}", Math.abs(append.long("send_timestamp_ms") - System.currentTimeMillis()) < 10_000)
    }

    val reply = tone(3000, 2400)
    c.send(continuousAudio(reply))
    assertArrayEquals(reply, r.audio.played.receive("the reply"))
    c.send("""{"type":"input_audio_buffer.speech_started","item_id":"i1"}""")
    assertEquals("flush", r.audio.events.receive("the barge-in"))

    c.send(STOP)
    val end = voice.endCalls.receive("the end-call")
    assertEquals(c.callId, end.str("call_id"))
    assertEquals("t1", end.str("thread_id"))
    assertTrue("started ${end.str("call_started_at")}", ISO_MS.matches(end.str("call_started_at")))
    assertTrue("ended ${end.str("call_ended_at")}", ISO_MS.matches(end.str("call_ended_at")))
    assertEquals(CallEnd.Over(OverReason.RemoteStop, endAcked = true), r.await())
    assertTrue("the upload was not ended", c.halfClosed)
    assertFalse("a stream over both ways was reset", c.reset)
    assertTrue("the audio was not closed", "close" in r.audio.events)
    val shown = generateSequence { r.views.poll() }.map { it.phase }.toList()
    assertTrue("shown $shown", CallPhase.Talking in shown && shown.last() == CallPhase.Ending)
    assertTrue("no end-of-call line:\n${h.logs}", h.logs.contains("muse: call over reason=RemoteStop end_call_acked=true advisory_errors=0 unlined_audio=0 shed_frames=0 records=hatch.voice.session=1,continuous_audio_video.delta=1,input_audio_buffer.speech_started=1,conversation.item.added:session_control:stop=1 last="))
    h.noSecrets()
  }

  @Test
  fun aCanceledCallIsStillEnded() {
    val h = online()
    val (r, c) = ready(h)
    r.end.cancel()
    voice.endCalls.receive("the end-call")
    assertTrue(runCatching { r.await() }.exceptionOrNull() is CancellationException)
    // The whole cleanup ran, past the acknowledgement's round trip, and not only up to its first wait.
    eventually("the upload was not ended after the acknowledgement") { c.halfClosed }
    assertTrue("the audio was not closed", "close" in r.audio.events)
  }

  @Test
  fun aRefusedCallIsDeclinedAndStillEnded() {
    val h = online()
    val r = call(h)
    val c = voice.calls.receive("the call")
    c.head(403)
    assertEquals(CallEnd.Declined(Decline.Refused(403)), r.await())
    assertEquals(c.callId, voice.endCalls.receive("the end-call").str("call_id"))
    assertEquals("a declined call opened the audio", listOf("close"), generateSequence { r.audio.events.poll() }.toList())
  }

  @Test
  fun anErrorBeforeReadinessRejects() {
    val h = online()
    val r = call(h)
    val c = voice.calls.receive("the call")
    c.head()
    c.send("""{"type":"error","error":{"code":"voice_not_admitted","message":"not for you"}}""")
    assertEquals(CallEnd.Declined(Decline.Rejected("voice_not_admitted")), r.await())
    assertFalse("the server's message reached the log", h.logs.contains("not for you"))
  }

  @Test
  fun anUnacknowledgedEndIsReportedAndTheStreamReset() {
    val h = online()
    voice.endCallReply = 500 to """{"ok":false}"""
    val (r, c) = ready(h)
    r.control.trySend(CallCommand.HangUp)
    assertEquals(CallEnd.Over(OverReason.HungUp, endAcked = false), r.await())
    eventually("the stream was left open") { c.reset }
  }

  @Test
  fun anUnconfirmedEndGoesAgainBeforeTheNextCall() {
    val h = online()
    voice.endCallReply = 500 to """{"ok":false}"""
    val (r, c) = ready(h)
    r.control.trySend(CallCommand.HangUp)
    val first = voice.endCalls.receive("the end-call")
    assertEquals(CallEnd.Over(OverReason.HungUp, endAcked = false), r.await())

    voice.endCallReply = 200 to """{"ok":true,"result":{"ok":true}}"""
    val next = call(h)
    assertEquals("the same end-call, unchanged", first, voice.endCalls.receive("the retried end-call"))
    assertEquals(c.callId, first.str("call_id"))
    val d = voice.calls.receive("the next call")
    d.next("hatch.voice.call.start")
    next.control.trySend(CallCommand.HangUp)
    assertEquals(d.callId, voice.endCalls.receive("the next call's end-call").str("call_id"))
    next.await()

    // Confirmed, it is not owed any more.
    val third = call(h)
    val e = voice.calls.receive("a third call")
    third.control.trySend(CallCommand.HangUp)
    assertEquals(e.callId, voice.endCalls.receive("the third call's end-call").str("call_id"))
  }

  @Test
  fun aRefusedEndIsNotTriedAgain() {
    val h = online()
    voice.endCallReply = 404 to """{"ok":false}"""
    val (r, c) = ready(h)
    r.control.trySend(CallCommand.HangUp)
    voice.endCalls.receive("the end-call")
    r.await()
    voice.endCallReply = 200 to """{"ok":true,"result":{"ok":true}}"""
    val next = call(h)
    val d = voice.calls.receive("the next call")
    next.control.trySend(CallCommand.HangUp)
    assertEquals(d.callId, voice.endCalls.receive("the next call's end-call").str("call_id"))
    assertNotEquals(c.callId, d.callId)
  }

  @Test
  fun anUnansweredEndIsBoundedByTheCleanup() {
    val h = online()
    voice.endCallReply = null
    val (r, c) = ready(h, CallOptions(cleanup = 1.seconds))
    val asked = System.nanoTime()
    r.control.trySend(CallCommand.HangUp)
    assertEquals(CallEnd.Over(OverReason.HungUp, endAcked = false), r.await())
    val took = (System.nanoTime() - asked) / 1_000_000
    assertTrue("the cleanup took $took ms", took < 3000)
    eventually("the stream was left open") { c.reset }
  }

  @Test
  fun aDroppedConnectionFailsTheCall() {
    val h = online()
    val (r, c) = ready(h)
    c.vm.drop()
    val end = r.await()
    assertTrue("end = $end", end is CallEnd.Over && end.reason is OverReason.Failed && !end.endAcked)
  }

  @Test
  fun aCallNeedsARegisteredSession() {
    val client = Client(Config(store = MemStore(null)))
    assertEquals(CallEnd.Declined(Decline.Offline), runBlocking { client.call(FakeAudio()) })
  }

  @Test
  fun oneCallAtATime() {
    val h = online()
    val (r, _) = ready(h)
    assertEquals(CallEnd.Declined(Decline.Dropped("busy")), runBlocking { h.client.call(FakeAudio()) })
    r.control.trySend(CallCommand.HangUp)
    r.await()
    assertNull("the busy call reached the VM", voice.calls.poll())
  }

  @Test
  fun readinessHasABudget() {
    val h = online()
    val r = call(h, CallOptions(admit = 1500.milliseconds))
    val c = voice.calls.receive("the call")
    c.head()
    assertEquals(CallEnd.Declined(Decline.TimedOut), r.await())
    assertEquals(c.callId, voice.endCalls.receive("the end-call").str("call_id"))
  }

  @Test
  fun aRefusedUpgradeDeclines() {
    val h = online()
    h.fake.rejectUpgrades = 1
    h.fake.rejectStatus = 403
    val r = call(h)
    assertEquals(CallEnd.Declined(Decline.Upgrade(403)), r.await())
    assertNull("a call that never connected was ended", voice.endCalls.poll())
  }

  @Test
  fun aMicThatCannotOpenFailsTheCall() {
    val h = online()
    val r = call(h, audio = FakeAudio(failOpen = true))
    val c = voice.calls.receive("the call")
    c.head()
    c.ready()
    assertEquals(CallEnd.Over(OverReason.Failed("could not open the audio"), endAcked = true), r.await())
  }
}
