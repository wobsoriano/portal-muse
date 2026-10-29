package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.Client
import dev.wobsoriano.muse.Config
import dev.wobsoriano.muse.Desk
import dev.wobsoriano.muse.FakeVm
import dev.wobsoriano.muse.Frame
import dev.wobsoriano.muse.FrameKind
import dev.wobsoriano.muse.Harness
import dev.wobsoriano.muse.MemStore
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.parseObject
import dev.wobsoriano.muse.receive
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VM's answers to the probe's two plain requests, with bodies that must never reach the log, and its voice desk. */
private class Front(val voice: FakeVoice) : Desk {
  val seen = LinkedBlockingQueue<Frame>()

  override fun claims(request: Frame) = voice.claims(request) || request.path.startsWith("/chat/history") || request.path == "/fs/read"

  override fun frame(vm: FakeVm, f: Frame) {
    val answer =
        when {
          f.kind == FrameKind.Request && f.path.startsWith("/chat/history") -> 200 to """{"messages":[{"text":"a private message"}]}"""
          f.kind == FrameKind.Request && f.path == "/fs/read" -> 404 to """{"error":{"code":"not_found","message":"no such file: gadget-data/secret"}}"""
          else -> return voice.frame(vm, f)
        }
    seen.add(f)
    vm.write(Frame(stream = f.stream, kind = FrameKind.Response, status = answer.first, data = answer.second.toByteArray(), endBody = true))
  }
}

class ProbeTest {
  private val harnesses = mutableListOf<Harness>()

  @After
  fun stop() {
    for (h in harnesses) h.close()
  }

  @Test
  fun theProbeKeepsStatusesAndCodesOnly() {
    val h = Harness().also { harnesses += it }
    val voice = FakeVoice()
    val front = Front(voice)
    h.fake.desk = front
    h.until(Phase.Online)
    h.vm()
    val report = h.scope.async { h.client.probe(realtime = true) }
    val history = front.seen.receive("the history request")
    assertTrue("history = $history", history.verb == "GET" && history.path == "/chat/history?limit=1")
    val headers = history.headers.associate { it.key to it.value }
    assertTrue("headers = $headers", headers["x-app-id"] == "musegadget" && !headers["x-request-id"].isNullOrEmpty())
    val read = front.seen.receive("the read")
    val path = (parseObject(String(read.data))?.get("path") as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
    assertTrue("read $path", read.verb == "POST" && path.startsWith("gadget-data/probe-"))
    val c = voice.calls.receive("the probe's call")
    c.head()
    c.ready()
    assertEquals(
        ProbeReport(ProbeReport.Reached(200, null), ProbeReport.Reached(404, "not_found"), CallEnd.Over(OverReason.HungUp, endAcked = true)),
        runBlocking { withTimeout(10.seconds) { report.await() } })
    assertNull("the probe's call sent audio", generateSequence { c.lines.poll() }.firstOrNull { it["type"].toString().contains("append") })
    for (secret in listOf("private message", "gadget-data/secret")) assertFalse("$secret reached the log", h.logs.contains(secret))
    h.noSecrets()
  }

  @Test
  fun anOfflineProbeReachesNothing() {
    val client = Client(Config(store = MemStore(null)))
    val none = ProbeReport.Reached(null, null)
    assertEquals(ProbeReport(none, none, null), runBlocking { client.probe() })
    assertEquals(ProbeReport(none, none, CallEnd.Declined(Decline.Offline)), runBlocking { client.probe(realtime = true) })
  }

  @Test
  fun errorCodes() {
    for ((body, want) in
        listOf(
            """{"error":{"code":"not_found","message":"x"},"code":"other"}""" to "not_found",
            """{"error":"forbidden"}""" to "forbidden",
            """{"code":"rate.limited"}""" to "rate.limited",
            """{"error":{"code":"Not Found"}}""" to null,
            """{"error":{"message":"no code"}}""" to null,
            """{"ok":true}""" to null,
            "<html>" to null,
            "" to null,
        )) {
      assertEquals(body, want, errorCode(body.toByteArray()))
    }
  }
}
