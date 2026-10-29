package dev.wobsoriano.muse

import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Records every frame of the streams under /test/, for the test to answer. */
private class Recorder : Desk {
  val frames = LinkedBlockingQueue<Pair<FakeVm, Frame>>()

  override fun claims(request: Frame) = request.path.startsWith("/test/")

  override fun frame(vm: FakeVm, f: Frame) {
    frames.add(vm to f)
  }

  fun next(what: String): Frame = frames.receive(what).second
}

class StreamTest {
  private val fake = FakeMuse()
  private val desk = Recorder().also { fake.desk = it }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  @After
  fun stop() {
    scope.cancel()
    fake.close()
    assertTrue("the fake VM failed: ${fake.failures}", fake.failures.isEmpty())
  }

  /** A voice connection to the fake, up, and the VM's end of it. */
  private fun connect(): Pair<Session, FakeVm> {
    val s =
        Session(
            SessionParams(
                url = noiseUrl(fake.url.removePrefix("https://"), "vm 1", false, "hatch-web"),
                bearer = "vmtok-0",
                userAgent = "test",
                http = fake.http(),
                nodeId = "homelink-a1b2c3",
                register = null,
                commands = emptyMap(),
                log = Logger.None,
                t = Timing(),
                registered = {},
                knockEvery = Duration.ZERO,
                knocks = 0))
    scope.launch { s.run() }
    runBlocking { withTimeout(5.seconds) { s.up.await() } }
    return s to fake.vms.receive("the VM")
  }

  private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(5.seconds) { block() } }

  /** Everything the stream says until its end or its failure, chunks as text, and the failure. */
  private fun DuplexStream.drain(): Pair<List<String>, Throwable?> {
    val got = mutableListOf<String>()
    return got to await { readInto(got) }
  }

  /** Reads past any end until the stream fails, and returns why. */
  private fun DuplexStream.failure(): Throwable? =
      await {
        var r = inbound.receiveCatching()
        while (!r.isClosed) {
          (r.getOrNull() as? Inbound.Chunk)?.release()
          r = inbound.receiveCatching()
        }
        r.exceptionOrNull()
      }

  private suspend fun DuplexStream.readInto(got: MutableList<String>): Throwable? {
    while (true) {
      val r = inbound.receiveCatching()
      when (val i = r.getOrNull()) {
        null -> return r.exceptionOrNull()
        is Inbound.Head -> got += "head ${i.status}"
        is Inbound.Chunk -> got += String(i.data).also { i.release() }
        Inbound.End -> {
          got += "end"
          return null
        }
      }
    }
  }

  @Test
  fun aVoiceConnectionNamesItsAppAndNeverRegisters() {
    val (s, vm) = connect()
    assertEquals("vm_id=vm%201&app_id=hatch-web", vm.query)
    s.openStream("POST", "/test/first", emptyList())
    assertEquals("/test/first", desk.next("the first request").path)
    // A control stream would have been the first thing sent, so it would be open by now.
    assertNull("a voice connection opened ${vm.controlOpen}", vm.controlOpen)
    assertNull("a voice connection registered", vm.registers.poll())
    assertEquals(listOf("upgrade Bearer vmtok-0 -> 0"), fake.log())
  }

  @Test
  fun bothDirectionsRunAtOnce() {
    val (s, vm) = connect()
    val stream = s.openStream("POST", "/test/duplex", listOf(Header("Content-Type", "application/x-ndjson; charset=utf-8")))
    val req = desk.next("the request")
    assertTrue("request = $req", req.kind == FrameKind.Request && req.verb == "POST" && !req.endBody && req.data.isEmpty())
    assertEquals(listOf(Header("Content-Type", "application/x-ndjson; charset=utf-8")), req.headers)
    stream.send("one\n".toByteArray())
    val body = desk.next("the body")
    assertTrue("body = $body", body.kind == FrameKind.Body && !body.endBody)
    assertArrayEquals("one\n".toByteArray(), body.data)

    // The answer streams while the upload is still open.
    vm.write(Frame(stream = stream.id, kind = FrameKind.Response, status = 200, data = "a\nb".toByteArray()))
    vm.write(Frame(stream = stream.id, kind = FrameKind.Body, data = "c\n".toByteArray()))
    stream.send("two\n".toByteArray())
    assertArrayEquals("two\n".toByteArray(), desk.next("the second body").data)
    stream.halfClose()
    val eof = desk.next("the half-close")
    assertTrue("half-close = $eof", eof.kind == FrameKind.Body && eof.endBody && eof.data.isEmpty())
    assertTrue("a send after the half-close went out", runCatching { stream.send("late".toByteArray()) }.exceptionOrNull() is MuseException)
    vm.write(Frame(stream = stream.id, kind = FrameKind.Body, endBody = true))
    val (got, err) = stream.drain()
    assertEquals(listOf("head 200", "a\nb", "c\n", "end"), got)
    assertTrue("err = $err", err == null)

    // A stream over both ways closes without a reset; one still open is reset.
    stream.close()
    val open = s.openStream("POST", "/test/abandoned", emptyList())
    open.close()
    assertEquals("/test/abandoned", desk.next("the next request").path)
    val reset = desk.next("the reset")
    assertTrue("reset = $reset", reset.kind == FrameKind.Reset && reset.stream == open.id && reset.code == RESET_CANCELED)
  }

  @Test
  fun aBrokenAnswerFailsItsStream() {
    val (s, vm) = connect()
    for ((name, frames) in
        listOf(
            "body before head" to listOf(Frame(kind = FrameKind.Body, data = "x".toByteArray())),
            "second head" to listOf(Frame(kind = FrameKind.Response, status = 200), Frame(kind = FrameKind.Response, status = 200)),
            "status 99" to listOf(Frame(kind = FrameKind.Response, status = 99)),
            "data after the end" to listOf(Frame(kind = FrameKind.Response, status = 200, endBody = true), Frame(kind = FrameKind.Body, data = "x".toByteArray())),
            "reset" to listOf(Frame(kind = FrameKind.Response, status = 200), Frame(kind = FrameKind.Reset, code = 2, reason = "gone")),
        )) {
      val stream = s.openStream("POST", "/test/$name", emptyList())
      desk.next("the request")
      for (f in frames) vm.write(f.withStream(stream.id))
      val err = stream.failure()
      assertTrue("$name: err = $err", err is StreamFailed)
      stream.close()
      desk.next("the reset")
    }
  }

  // The reader hands frames on without waiting for anyone, so a stream nobody reads fills, fails on
  // its own, and every other stream on the connection goes on.
  @Test
  fun aConsumerThatFallsBehindFailsOnlyItsOwnStream() {
    val (s, vm) = connect()
    for ((name, sizes) in listOf("chunks" to List(300) { 10 }, "bytes" to List(3) { 1 shl 20 })) {
      val parked = s.openStream("POST", "/test/parked-$name", emptyList())
      desk.next("the parked request")
      vm.write(Frame(stream = parked.id, kind = FrameKind.Response, status = 200))
      for (n in sizes) vm.write(Frame(stream = parked.id, kind = FrameKind.Body, data = ByteArray(n)))

      val answer = scope.async { s.request("GET", "/test/after-$name", emptyList(), timeout = 5.seconds) }
      val req = desk.next("the request after the flood")
      vm.write(Frame(stream = req.stream, kind = FrameKind.Response, status = 200, data = "still here".toByteArray(), endBody = true))
      assertEquals("still here", String(await { answer.await() }.body))

      val (got, err) = parked.drain()
      assertTrue("$name: err = $err", err is StreamFailed && err.why == "overflow")
      assertTrue("$name: got ${got.size} items", got.size <= MAX_INBOUND_CHUNKS + 1)
    }
  }

  @Test
  fun theEndOfTheConnectionClosesItsStreams() {
    val (s, vm) = connect()
    val stream = s.openStream("POST", "/test/held", emptyList())
    desk.next("the request")
    vm.drop()
    val (_, err) = stream.drain()
    assertTrue("err = $err", err is StreamFailed && err.why == "connection closed")
    assertTrue("a stream opened after the end", runCatching { s.openStream("POST", "/test/late", emptyList()) }.exceptionOrNull() is MuseException)
  }

  @Test
  fun aBufferedRequest() {
    val (s, vm) = connect()
    val ok = scope.async { s.request("POST", "/test/ok", listOf(Header("Content-Type", "application/json")), "{}".toByteArray(), 5.seconds) }
    val req = desk.next("the request")
    assertTrue("request = $req", req.endBody && String(req.data) == "{}")
    vm.write(Frame(stream = req.stream, kind = FrameKind.Response, status = 201, data = "{\"ok\":".toByteArray()))
    vm.write(Frame(stream = req.stream, kind = FrameKind.Body, data = "true}".toByteArray(), endBody = true))
    val got = await { ok.await() }
    assertEquals(201, got.status)
    assertEquals("{\"ok\":true}", String(got.body))

    val big = scope.async { runCatching { s.request("GET", "/test/big", emptyList(), timeout = 5.seconds) }.exceptionOrNull() }
    val bigReq = desk.next("the big request")
    vm.write(Frame(stream = bigReq.stream, kind = FrameKind.Response, status = 200, data = ByteArray(1 shl 20)))
    vm.write(Frame(stream = bigReq.stream, kind = FrameKind.Body, data = ByteArray(1), endBody = true))
    assertTrue("an answer over 1 MiB was taken", await { big.await() } is MuseException)
    // It ended before it was read whole, so it is reset rather than left open.
    val bigReset = desk.next("the big request's reset")
    assertTrue("reset = $bigReset", bigReset.kind == FrameKind.Reset && bigReset.stream == bigReq.stream)

    val slow = scope.async { runCatching { s.request("GET", "/test/slow", emptyList(), timeout = 200.milliseconds) }.exceptionOrNull() }
    val slowReq = desk.next("the slow request")
    assertTrue("an unanswered request returned", await { slow.await() } is MuseException)
    val reset = desk.next("the slow request's reset")
    if (reset.kind != FrameKind.Reset || reset.stream != slowReq.stream || reset.code != RESET_CANCELED) fail("reset = $reset")
  }
}
