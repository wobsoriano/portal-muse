package dev.wobsoriano.muse.core

import dev.wobsoriano.muse.Credentials
import dev.wobsoriano.muse.FakeMuse
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Identity
import dev.wobsoriano.muse.State
import dev.wobsoriano.muse.receive
import dev.wobsoriano.muse.voice.CallCommand
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.muse.voice.CallView
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.FakeAudio
import dev.wobsoriano.muse.voice.FakeVoice
import dev.wobsoriano.muse.voice.OverReason
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val ONLINE = """{"type":"status","phase":"online","error":""}"""

/** A call through the core, against the fake VM. */
class CoreCallTest {
  @get:Rule val tmp = TemporaryFolder()
  private val fake = FakeMuse()
  private val voice = FakeVoice().also { fake.desk = it }
  private val apps = mutableListOf<App>()

  @After
  fun stop() {
    apps.forEach { it.close() }
    fake.close()
    assertTrue("the fake VM failed: ${fake.failures}", fake.failures.isEmpty())
  }

  /** A core paired with the fake, and online. */
  private fun online(): App {
    val state =
        State(
            identity = Identity("02:11:22:a1:b2:c3"),
            credentials =
                Credentials(
                    accessToken = "access-0",
                    refreshToken = "hatch_refresh:refresh-0",
                    tokenType = "device",
                    username = "someone",
                    apiUrlV2 = fake.url,
                    noiseHost = fake.url.removePrefix("https://"),
                    accessTokenSavedAt = System.currentTimeMillis() / 1000))
    runBlocking { FileStore(File(tmp.root, "muse.json")).save(state) }
    val a = App(tmp.root, fake.http()).also { apps += it }
    a.core.config(CoreConfig("Test", "", ""))
    while (a.next() != ONLINE) {}
    return a
  }

  private fun App.update(): CallUpdate = calls.receive("a call update")

  /** The next Ended, past any Phase. */
  private fun App.ended(): CallUpdate.Ended {
    while (true) (update() as? CallUpdate.Ended)?.let { return it }
  }

  private fun App.reply(id: String, kind: String): String {
    while (true) {
      val l = next()
      if (l.contains("\"type\":\"reply\"") && l.contains("\"id\":\"$id\"") && l.contains("\"kind\":\"$kind\"")) return l
    }
  }

  @Test
  fun aCallRunsThroughTheCore() {
    val a = online()
    val audio = FakeAudio()
    a.core.call("c1", audio)
    val c = voice.calls.receive("the call")
    c.head()
    c.ready()
    assertEquals(CallUpdate.Phase("c1", CallView(CallPhase.Listening)), a.update())
    a.core.callCommand(CallCommand.HangUp)
    assertEquals(CallUpdate.Ended("c1", CallEnd.Over(OverReason.HungUp, endAcked = true)), a.ended())
    assertTrue("the audio was still open when the call was reported over", "close" in audio.events)
  }

  @Test
  fun whileACallRunsAnotherCallAndAnAskAreRefused() {
    val a = online()
    a.core.call("c1", FakeAudio())
    val c = voice.calls.receive("the call")
    c.head()
    c.ready()
    a.update()
    val second = FakeAudio()
    a.core.call("c2", second)
    assertEquals(CallUpdate.Ended("c2", CallEnd.Declined(Decline.Dropped("busy"))), a.update())
    assertTrue("a refused call touched its audio: ${second.events}", second.events.isEmpty())
    a.core.ask("q1", text = "hello")
    assertTrue(a.reply("q1", "error").contains("already asking"))
    a.core.callCommand(CallCommand.HangUp)
    assertEquals("c1", a.ended().id)
    // Once it is over, the core takes the next thing at once.
    a.core.ask("q2", text = "back")
    assertTrue(a.reply("q2", "done").contains("you said: back"))
  }

  @Test
  fun aCallWithoutAClientIsDeclined() {
    val a = App(tmp.root).also { apps += it }
    val audio = FakeAudio()
    a.core.call("c1", audio)
    assertEquals(CallUpdate.Ended("c1", CallEnd.Declined(Decline.Offline)), a.update())
    assertTrue(audio.events.isEmpty())
    a.core.callCommand(CallCommand.HangUp)
  }

  @Test
  fun closingWaitsForTheCallsCleanup() {
    val a = online()
    val audio = FakeAudio()
    a.core.call("c1", audio)
    val c = voice.calls.receive("the call")
    c.head()
    c.ready()
    a.update()
    a.close()
    assertEquals("the call was not ended before the core stopped", c.callId, voice.endCalls.poll()?.let { (it["call_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content })
    assertTrue("close" in audio.events)
    assertEquals(CallUpdate.Ended("c1", CallEnd.Over(OverReason.Failed("canceled"))), a.calls.last())
  }

  @Test
  fun aProbeSaysEachAnswerInALine() {
    val a = online()
    val lines = LinkedBlockingQueue<String>()
    a.core.probe(realtime = true) { lines.add(it) }
    voice.calls.receive("the probe's call").head(403)
    assertEquals(
        listOf("history=404", "fs_read=404", "realtime=${CallEnd.Declined(Decline.Refused(403))}"),
        List(3) { lines.receive("a probe line") })
    a.core.probe { lines.add(it) }
    assertEquals(listOf("history=404", "fs_read=404"), List(2) { lines.receive("a probe line") })
  }
}
