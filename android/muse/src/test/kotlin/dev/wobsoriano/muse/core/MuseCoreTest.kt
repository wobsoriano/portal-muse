package dev.wobsoriano.muse.core

import dev.wobsoriano.muse.Credentials
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Identity
import dev.wobsoriano.muse.State
import dev.wobsoriano.muse.pairing.Advertisement
import dev.wobsoriano.muse.pairing.encodeChunks
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

/**
 * The app's side, driving a real core. Every call the core makes is written down as the line musecore
 * printed for it on stdout, so the expected values below are core_test.go's own.
 */
class App(dir: File, http: OkHttpClient? = null) : CoreListener, BlePeripheral {
  val lines = LinkedBlockingQueue<String>()
  val calls = LinkedBlockingQueue<CallUpdate>()
  val core = MuseCore(FileStore(File(dir, "muse.json")), "dev", this, this, http = http)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val job: Job = scope.launch { core.run() }
  private var closed = false

  private fun line(type: String, vararg fields: Pair<String, Any>) {
    lines.add(
          buildJsonObject {
                put("type", type)
                for ((k, v) in fields) {
                  when (v) {
                    is String -> put(k, v)
                    is Int -> put(k, v)
                    is JsonArray -> put(k, v)
                  }
                }
              }
              .toString())
  }

  override fun status(status: Status) = line("status", "phase" to status.phase.label, "error" to status.error)

  override fun pairStatus(status: PairStatus) = line("pair.status", "state" to status.state.name.lowercase(), "name" to status.name, "error" to status.error)

  override fun reply(update: AskUpdate) = line("reply", "id" to update.id, "kind" to update.kind.name.lowercase(), "text" to update.text, "error" to update.error)

  override fun call(update: CallUpdate) {
    calls.add(update)
  }

  override fun advertise(ad: Advertisement) =
      line(
          "pair.advertise",
          "name" to ad.name,
          "service" to ad.service,
          "rx" to ad.rx,
          "tx" to ad.tx,
          "manufacturerId" to ad.manufacturerId,
          "manufacturerData" to b64(ad.manufacturerData))

  override fun unadvertise() = line("pair.unadvertise")

  override fun notify(packets: List<ByteArray>) = line("ble.notify", "packets" to JsonArray(packets.map { JsonPrimitive(b64(it)) }))

  override fun disconnect() = line("ble.disconnect")

  fun next(): String = lines.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no line from the core")

  fun expect(vararg want: String) {
    for (w in want) assertEquals(w, next())
  }

  /** Takes the next want.size lines, which may come in any order. */
  fun expectAnyOrder(vararg want: String) {
    assertEquals(want.sorted(), List(want.size) { next() }.sorted())
  }

  /** Closes the core and returns what it said on its way out. */
  fun close(): List<String> {
    if (closed) return emptyList()
    closed = true
    core.close()
    runBlocking { withTimeout(5.seconds) { job.join() } }
    scope.cancel()
    return generateSequence { lines.poll() }.toList()
  }
}

private val configNoToken = CoreConfig("Test", "", "")
private val configToken = CoreConfig("Test", "mgst_test", "Home")
private const val UNPAIRED = """{"type":"status","phase":"unpaired","error":""}"""

private fun advertise(name: String) =
    """{"type":"pair.advertise","name":"$name","service":"7fdd3d1c-38ea-46cf-8b46-314ecf5f240c",""" +
        """"rx":"4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01","tx":"d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c",""" +
        """"manufacturerId":65535,"manufacturerData":"AA=="}"""

private fun pairStatus(state: String, name: String) = """{"type":"pair.status","state":"$state","name":"$name","error":""}"""

class MuseCoreTest {
  @get:Rule val tmp = TemporaryFolder()
  private val apps = mutableListOf<App>()

  private fun start(dir: File) = App(dir).also { apps += it }

  @After
  fun stop() {
    apps.forEach { it.close() }
  }

  private fun readState(dir: File): State = FileStore(File(dir, "muse.json")).read()

  /** Configures the core with a token and opens pairing, and returns the advertised name. */
  private fun openPairing(a: App, dir: File): String {
    a.core.config(configToken)
    a.expect(UNPAIRED)
    a.core.pairStart()
    val line = a.next()
    val name = readState(dir).identity.bleName
    assertEquals(advertise(name), line)
    a.expect(pairStatus("open", name))
    return name
  }

  @Test
  fun configWithNoStateIsUnpaired() {
    val dir = tmp.newFolder()
    val a = start(dir)
    a.core.config(configNoToken)
    a.expect(UNPAIRED)
    assertEquals("said more on the way out", emptyList<String>(), a.close())
    assertFalse("a config wrote the state file", File(dir, "muse.json").exists())
  }

  @Test
  fun pairingNeedsAnSdkToken() {
    val a = start(tmp.newFolder())
    a.core.pairStart()
    a.core.config(configNoToken)
    a.core.pairStart()
    val failed = """{"type":"pair.status","state":"failed","name":"","error":"no Muse SDK token: the Muse app will not pair without one"}"""
    a.expect(failed, UNPAIRED, failed)
  }

  @Test
  fun pairingAdvertisesAPersistedIdentity() {
    val dir = tmp.newFolder()
    val a = start(dir)
    val name = openPairing(a, dir)
    assertTrue("BLE name $name", Regex("^MuseGadget[0-9A-F]{6}$").matches(name))
    val id = readState(dir).identity

    a.core.pairStart()
    a.core.pairStop()
    a.expect("""{"type":"pair.unadvertise"}""", pairStatus("closed", name))

    a.core.pairStart()
    a.expect(advertise(name), pairStatus("open", name))
    assertEquals("on the way out", listOf("""{"type":"pair.unadvertise"}""", pairStatus("closed", name)), a.close())

    val again = start(dir)
    again.core.config(configToken)
    again.core.pairStart()
    again.expect(UNPAIRED, advertise(name), pairStatus("open", name))
    assertEquals("identity changed across a restart", id, readState(dir).identity)
  }

  // Without the phone's half of the handshake, a plaintext command that needs a session is as far as a
  // write goes: it must reach the Controller, and its answer must come back as a notification.
  @Test
  fun writesReachTheControllerAndAnswersComeBackAsNotifications() {
    val dir = tmp.newFolder()
    val a = start(dir)
    val name = openPairing(a, dir)

    a.core.advertising(true, "")
    a.core.connected("phone")
    a.core.wrote("""{"action":"wifi_scan"}""".toByteArray(), 185)
    a.expectAnyOrder(pairStatus("connected", name), """{"type":"ble.notify","packets":["${b64("error_encryption_required".toByteArray())}"]}""")
    a.core.disconnected()
    a.expect(pairStatus("open", name))
  }

  @Test
  fun aChunkedWriteIsReassembled() {
    val dir = tmp.newFolder()
    val a = start(dir)
    val name = openPairing(a, dir)
    a.core.connected("phone")
    for (p in encodeChunks("""{"action":"get_device_info"}""".toByteArray(), 23)) a.core.wrote(p, 23)
    assertEquals(pairStatus("connected", name), a.next())
    val packets = Json.parseToJsonElement(a.next()).jsonObject["packets"] as JsonArray
    val info = packets.map { Base64.getDecoder().decode(it.jsonPrimitive.content).let { p -> p.copyOfRange(3, p.size) } }.reduce(ByteArray::plus)
    assertEquals("device_info", Json.parseToJsonElement(String(info)).jsonObject["type"]?.jsonPrimitive?.content)
  }

  @Test
  fun advertisingFailureClosesPairing() {
    val dir = tmp.newFolder()
    val a = start(dir)
    val name = openPairing(a, dir)
    a.core.advertising(false, "no adapter")
    a.expect(
        """{"type":"pair.status","state":"failed","name":"$name","error":"could not advertise: no adapter"}""",
        """{"type":"pair.unadvertise"}""",
        pairStatus("closed", name))
  }

  @Test
  fun askNeedsExactlyOneOfTextAndWav() {
    val a = start(tmp.newFolder())
    a.core.config(configNoToken)
    a.expect(UNPAIRED)
    a.core.ask("both", text = "hi", wav = Base64.getDecoder().decode("UklGRg=="))
    a.core.ask("neither")
    a.core.ask("text", text = "hi")
    a.expect(
        """{"type":"reply","id":"both","kind":"error","text":"","error":"an ask carries exactly one of text and wav"}""",
        """{"type":"reply","id":"neither","kind":"error","text":"","error":"an ask carries exactly one of text and wav"}""",
        """{"type":"reply","id":"text","kind":"error","text":"","error":"muse: not connected"}""",
    )
  }

  // A paired state whose API is a closed local port keeps the client running and offline, with no
  // network.
  @Test
  fun unpairForgetsTheTokensAndKeepsTheIdentity() {
    val dir = tmp.newFolder()
    val id = Identity.new()
    val paired =
        State(
            identity = id,
            credentials =
                Credentials(
                    accessToken = "a",
                    refreshToken = "r",
                    tokenType = "device",
                    username = "u",
                    apiUrlV2 = "https://127.0.0.1:1",
                    accessTokenSavedAt = System.currentTimeMillis() / 1000))
    FileStore(File(dir, "muse.json")).write(paired)
    val a = start(dir)
    a.core.config(configNoToken)
    a.expect("""{"type":"status","phase":"connecting","error":""}""")
    val offline = Json.parseToJsonElement(a.next()).jsonObject
    assertTrue(
        "want offline with an error, got $offline",
        offline["phase"]?.jsonPrimitive?.content == "offline" && offline["error"]?.jsonPrimitive?.content.orEmpty().isNotEmpty())

    a.core.ask("1", text = "hi")
    a.expect("""{"type":"reply","id":"1","kind":"error","text":"","error":"muse: not connected"}""")

    a.core.unpair()
    a.expect(UNPAIRED)
    val st = readState(dir)
    assertTrue("after unpair: $st", st.credentials == null && st.identity == id)
    assertEquals("said more on the way out", emptyList<String>(), a.close())
  }

  // The device musecore paired: its muse.json, written by the Go store, makes the core start the client
  // on the first config instead of asking to pair.
  @Test
  fun aDevicePairedByMusecoreStaysPaired() {
    val dir = tmp.newFolder()
    val text = checkNotNull(javaClass.getResource("/vectors/muse-go-paired.json")).readText()
    File(dir, "muse.json").writeText(text.replace("https://api.example", "https://127.0.0.1:1"))
    val a = start(dir)
    a.core.config(configNoToken)
    a.expect("""{"type":"status","phase":"connecting","error":""}""")
    assertNull("asked to pair", a.lines.firstOrNull { it == UNPAIRED })
  }
}
