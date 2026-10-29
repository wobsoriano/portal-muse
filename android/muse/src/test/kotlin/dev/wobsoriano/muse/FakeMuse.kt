package dev.wobsoriano.muse

import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Stands in for the Muse API and a VM, over TLS on one port. Its event shapes were captured from the
 * real service: change it only to match something observed, never to make a test pass.
 */
class FakeMuse : AutoCloseable {
  private val lock = Any()
  private val events = mutableListOf<String>() // API calls, upgrades and Store saves, in the order they happened
  var access = "access-0"
  var refresh = "refresh-0"
  private var rotation = 0
  private var bearer = "vmtok-0"
  /**
   * How many upgrades the front door answers 403 before the VM is behind it, as the real one does
   * after a quiet spell; knockEveryMs is the hint fetch_vms then carries.
   */
  var coldUpgrades = 0
  var knockEveryMs = 0
  /** How many times fetch_vms says to ask; 0 is 80. */
  var knockTries = 0
  /** How many WebSocket upgrades to refuse, and with what status. */
  var rejectUpgrades = 0
  var rejectStatus = 0
  val refreshBodies = mutableListOf<JsonObject>()
  var subscribes = 0
    get() = synchronized(lock) { field }

  private var chatCount = 0
  /** When set, stops each reply half way until released. */
  @Volatile var hold: CountDownLatch? = null
  /** When set, answers the streams it claims in place of the VM. */
  @Volatile internal var desk: Desk? = null

  val vms = LinkedBlockingQueue<FakeVm>()
  /** What went wrong on the VM side, which fails the test as t.Errorf does in Go. */
  val failures = LinkedBlockingQueue<String>()
  private val server = MockWebServer()
  private val serverCerts: HandshakeCertificates
  val clientCerts: HandshakeCertificates

  init {
    val root = HeldCertificate.Builder().certificateAuthority(0).build()
    val leaf = HeldCertificate.Builder().signedBy(root).addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("localhost").build()
    serverCerts = HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build()
    clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build()
    server.useHttps(serverCerts.sslSocketFactory(), false)
    server.dispatcher =
        object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse =
              when {
                request.method == "POST" && request.path == "/device_token/refresh" -> handleRefresh(request)
                request.method == "GET" && request.path == "/fetch_vms" -> handleFetch(request)
                request.method == "GET" && request.path?.startsWith("/v1/noise") == true -> handleNoise(request)
                else -> MockResponse().setResponseCode(404)
              }
        }
    server.start(InetAddress.getByName("127.0.0.1"), 0)
  }

  val url
    get() = "https://127.0.0.1:${server.port}"

  fun http(): OkHttpClient = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager).build()

  override fun close() {
    server.shutdown()
  }

  fun record(event: String) = synchronized(lock) { events.add(event) }

  fun log(): List<String> = synchronized(lock) { events.toList() }

  private fun handleRefresh(r: RecordedRequest): MockResponse {
    val body = parseObject(r.body.readUtf8()) ?: JsonObject(emptyMap())
    val reply: JsonObject
    synchronized(lock) {
      events.add("refresh " + r.getHeader("Authorization"))
      refreshBodies.add(body)
      if (r.getHeader("Authorization") != "Bearer hatch_refresh:$refresh") return MockResponse().setResponseCode(401)
      rotation++
      access = "access-$rotation"
      refresh = "refresh-$rotation"
      reply = buildJsonObject {
        putJsonObject("payload") {
          put("access_token", access)
          put("refresh_token", refresh)
        }
      }
    }
    return MockResponse().setBody(reply.toString() + "\n")
  }

  private fun handleFetch(r: RecordedRequest): MockResponse {
    val ok: Boolean
    val bearer: String
    val every: Int
    val tries: Int
    synchronized(lock) {
      events.add("fetch " + r.getHeader("Authorization"))
      ok = r.getHeader("Authorization") == "Bearer $access" && r.getHeader("X-API-Version") == "1.0.0"
      bearer = this.bearer
      every = knockEveryMs
      tries = if (knockTries == 0) 80 else knockTries
    }
    if (!ok) return MockResponse().setResponseCode(401)
    val answer = buildJsonObject {
      if (every > 0) {
        put("retry_after_ms", every)
        put("max_retry_count", tries)
      }
      put(
          "vm_list",
          JsonArray(
              listOf(
                  buildJsonObject {
                    put("vm_ws_url", "wss://ignored")
                    put("vm_auth_token", "skip")
                    put("vm_name", "other")
                    put("vm_id", "vm0")
                  },
                  buildJsonObject {
                    put("vm_ws_url", "wss://ignored")
                    put("vm_auth_token", bearer)
                    put("vm_name", "test")
                    put("vm_id", "vm 1")
                    put("default", true)
                  })))
    }
    return MockResponse().setBody(answer.toString() + "\n")
  }

  private fun handleNoise(r: RecordedRequest): MockResponse {
    var status = 0
    synchronized(lock) {
      when {
        coldUpgrades > 0 -> {
          coldUpgrades--
          status = 403
        }
        rejectUpgrades > 0 -> {
          rejectUpgrades--
          status = rejectStatus
          // A refused bearer is spent: the next fetch hands out another.
          bearer += "+"
        }
        r.getHeader("Authorization") != "Bearer $bearer" -> status = 401
      }
      events.add("upgrade ${r.getHeader("Authorization")} -> $status")
    }
    if (status != 0) return MockResponse().setResponseCode(status)
    val vm = FakeVm(this, r.path!!.substringAfter('?', ""))
    return MockResponse().withWebSocketUpgrade(vm.listener)
  }

  internal fun nextUserId(): String = synchronized(lock) { "u${++chatCount}" }

  internal fun subscribed() = synchronized(lock) { subscribes++ }
}

/**
 * A test's own end of some streams: every frame of a stream whose request it claims goes to [frame], on
 * the VM's reader thread, and the test answers with [FakeVm.write].
 */
internal interface Desk {
  fun claims(request: Frame): Boolean

  fun frame(vm: FakeVm, f: Frame)
}

/** One POST /chat/stream as the VM saw it. */
class Chat(val body: JsonObject, val headers: Map<String, String>, val chunks: Int, val inRequest: Boolean, val wavSha: String)

private class Upload(val request: Frame, var body: ByteArray, var chunks: Int = 0)

class FakeVm(private val f: FakeMuse, val query: String) {
  @Volatile lateinit var ws: WebSocket
  private val wlock = Any()
  private var hs: NoiseXX? = NoiseXX(false, DhKey.generate(RandomSource.Secure), RandomSource.Secure)
  private var send: CipherState? = null
  private var recv: CipherState? = null
  private val chunks = Assembler()
  private var control = 0L
  @Volatile internal var controlOpen: Frame? = null
  private var controlBuf = ByteArray(0)
  private var subscription = 0L
  private val uploads = HashMap<Long, Upload>()
  private val desked = HashSet<Long>()

  val registers = LinkedBlockingQueue<JsonObject>()
  val results = LinkedBlockingQueue<JsonObject>()
  val chats = LinkedBlockingQueue<Chat>()
  /** Hears each time the held half of a reply has gone out. */
  val released = LinkedBlockingQueue<Unit>()
  private val failures
    get() = f.failures

  internal val listener =
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          ws = webSocket
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          ws = webSocket
          try {
            onBinary(bytes.toByteArray())
          } catch (e: Exception) {
            failures.add("fake VM: $e")
            webSocket.cancel()
          }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {}
      }

  private fun onBinary(data: ByteArray) {
    val handshake = hs
    if (handshake != null) {
      val read = handshake.read(data)
      if (read.session == null) {
        ws.send(handshake.write().message.toByteString())
      } else {
        val (s, r) = read.session
        synchronized(wlock) { send = s }
        recv = r
        hs = null
        f.vms.add(this)
      }
      return
    }
    val plain = checkNotNull(recv).decrypt(EMPTY, data)
    val envelope = chunks.add(plain) ?: return
    onFrame(decodeServiceRequest(envelope))
  }

  /** The VM's side of encodeServiceRequest. */
  private fun decodeServiceRequest(b: ByteArray): Frame {
    var payload = EMPTY
    val r = ProtoReader(b)
    while (r.next()) if (r.field == 2) payload = r.bytes(r.wire) else r.skip(r.wire)
    if (r.failed) throw ProtoException()
    return decodeFrame(payload)
  }

  internal fun write(frame: Frame) {
    synchronized(wlock) {
      for (piece in chunk(ProtoWriter().bytesField(1, encodeFrame(frame)).toByteArray())) {
        // The device may already have gone, which is some tests' whole point.
        ws.send(checkNotNull(send).encrypt(EMPTY, piece).toByteString())
      }
    }
  }

  /** Drops the device, as a VM that went away does. MockWebServer cannot cut its side of a socket, so it closes it. */
  fun drop() {
    ws.close(1001, "going away")
  }

  /** Sends one control message to the device. */
  fun message(m: JsonObject) = write(Frame(stream = control, kind = FrameKind.Body, data = encodeControl(m)))

  private fun onFrame(fr: Frame) {
    val desk = f.desk
    if (desk != null && (fr.stream in desked || fr.kind == FrameKind.Request && desk.claims(fr))) {
      desked += fr.stream
      desk.frame(this, fr)
      return
    }
    val up = uploads[fr.stream]
    when {
      fr.kind == FrameKind.Request && fr.path == CONTROL_PATH -> {
        control = fr.stream
        controlOpen = fr
        write(Frame(stream = fr.stream, kind = FrameKind.Response, status = 200))
      }
      fr.kind == FrameKind.Request && fr.endBody -> answer(fr.stream, fr, fr.data, 0)
      fr.kind == FrameKind.Request -> uploads[fr.stream] = Upload(fr, fr.data)
      fr.kind == FrameKind.Body && fr.stream == control -> onControl(fr.data)
      up != null && fr.kind == FrameKind.Reset -> {
        uploads.remove(fr.stream)
        f.record("reset ${fr.reason}")
      }
      up != null -> {
        up.body += fr.data
        up.chunks++
        if (fr.endBody) {
          uploads.remove(fr.stream)
          answer(fr.stream, up.request, up.body, up.chunks)
        }
      }
    }
  }

  private fun onControl(data: ByteArray) {
    controlBuf += data
    while (controlBuf.size >= 4) {
      val n = (controlBuf[0].toInt() and 0xFF) or ((controlBuf[1].toInt() and 0xFF) shl 8) or ((controlBuf[2].toInt() and 0xFF) shl 16) or ((controlBuf[3].toInt() and 0xFF) shl 24)
      if (controlBuf.size < 4 + n) return
      val m = parseObject(String(controlBuf, 4, n, Charsets.UTF_8)) ?: run {
        failures.add("fake VM control message")
        JsonObject(emptyMap())
      }
      controlBuf = controlBuf.copyOfRange(4 + n, controlBuf.size)
      when (rawString(m["method"])) {
        "link.register" -> {
          registers.add(m)
          message(buildJsonObject {
            put("type", "res")
            put("id", m["id"] ?: JsonPrimitive(""))
            put("ok", true)
          })
        }
        "link.result" -> results.add(m)
      }
    }
  }

  private fun answer(stream: Long, request: Frame, body: ByteArray, chunkCount: Int) {
    val headers = request.headers.associate { it.key to it.value }
    when ("${request.verb} ${request.path}") {
      "POST $SUBSCRIBE_PATH" -> {
        f.subscribed()
        subscription = stream
        write(Frame(stream = stream, kind = FrameKind.Response, status = 200, data = "{\"type\":\"ack\"}\n".toByteArray()))
      }
      "POST $CHAT_PATH" -> {
        val parsed = parseObject(String(body, Charsets.UTF_8)) ?: run {
          failures.add("fake VM chat body")
          JsonObject(emptyMap())
        }
        var heard = rawString(parsed["message"])
        var wavSha = ""
        var seenBody = parsed
        val items = parsed["items"] as? JsonArray
        if (items != null && items.isNotEmpty()) {
          val item = items[0] as JsonObject
          val wav = Base64.getDecoder().decode(item["data_base64"]!!.jsonPrimitive.content)
          wavSha = MessageDigest.getInstance("SHA-256").digest(wav).hex()
          seenBody = JsonObject(parsed + ("items" to JsonArray(listOf(JsonObject(item - "data_base64")))))
          // Stands in for the transcript the real service makes of the audio.
          heard = "a voice note"
        }
        chats.add(Chat(seenBody, headers, chunkCount, request.endBody, wavSha))
        val userId = f.nextUserId()
        var held: ByteArray? = null
        var hold: CountDownLatch? = null
        if (subscription != 0L) {
          // Events race the acknowledgment on the real service, so they go first here.
          val r = streamReply(userId, heard, "you said: $heard")
          held = r.first
          hold = r.second
        }
        val ack = buildJsonObject {
          put("ok", true)
          putJsonObject("result") { put("message_id", userId) }
        }
        write(Frame(stream = stream, kind = FrameKind.Response, status = 200, data = ack.toString().toByteArray(), endBody = true))
        if (hold != null && held != null) {
          Thread {
                hold.await()
                streamBytes(held)
                released.add(Unit)
              }
              .start()
        }
      }
      else -> write(Frame(stream = stream, kind = FrameKind.Response, status = 404, endBody = true))
    }
  }

  /** Sends the events of one answer. If replies are being held, it stops half way and returns the rest with the latch that releases it. */
  private fun streamReply(userId: String, heard: String, text: String): Pair<ByteArray?, CountDownLatch?> {
    var seq = 0
    fun line(name: String, payload: JsonObject): ByteArray {
      seq++
      return (buildJsonObject {
            put("type", "event")
            put("seq", seq)
            put("event", name)
            put("payload", payload)
          }
              .toString() + "\n")
          .toByteArray()
    }
    fun p(vararg kv: Pair<String, String>) = JsonObject(kv.associate { (k, v) -> k to JsonPrimitive(v) as JsonElement })
    // The shapes the real service sends: the reply names no parent, its text chunks name themselves
    // as parent, and "done" carries no text.
    val replyId = "assistant-msg-$userId"
    val half = text.length / 2
    val first =
        listOf(
            line("delta.message_start", p("message_id" to "earlier", "reply_to_message_id" to "")),
            line("delta.text_append", p("message_id" to "earlier", "text" to "not for this device")),
            line("message.user", p("message_id" to userId, "display_text" to heard)),
            line("agent.status", p("activity_code" to "working")),
            line("delta.message_start", p("message_id" to replyId, "reply_to_message_id" to "")),
            line("delta.text_append", p("message_id" to replyId, "parent_message_id" to replyId, "text" to text.substring(0, half))),
        )
    val second =
        listOf(
            line("delta.text_append", p("message_id" to replyId, "parent_message_id" to replyId, "text" to text.substring(half))),
            line("agent.status", p("activity_code" to "online")),
            line("delta.message_done", p("message_id" to replyId, "reply_to_message_id" to "", "status" to "completed")),
        )
    val hold = f.hold
    if (hold != null) {
      streamBytes(first.reduce(ByteArray::plus))
      return second.reduce(ByteArray::plus) to hold
    }
    streamBytes((first + second).reduce(ByteArray::plus))
    return null to null
  }

  /** Sends chat stream data split mid-line, so the device has to put lines back together. */
  fun streamBytes(data: ByteArray) {
    var start = 0
    while (start < data.size) {
      write(Frame(stream = subscription, kind = FrameKind.Body, data = data.copyOfRange(start, minOf(data.size, start + 97))))
      start += 97
    }
  }
}

/** A Store in memory that records every save in the fake's event log, and can be told to fail. */
class MemStore(private val f: FakeMuse?) : Store {
  private val lock = Any()
  var state = State()
    get() = synchronized(lock) { field }
    set(value) = synchronized(lock) { field = value }

  var failSaves = 0
  val saved = mutableListOf<State>()

  override suspend fun load(): State = synchronized(lock) { state }

  override suspend fun save(state: State) {
    synchronized(lock) {
      val access = state.credentials?.accessToken ?: "none"
      if (failSaves > 0) {
        failSaves--
        f?.record("save failed $access")
        throw IOException("short write")
      }
      f?.record("save $access")
      this.state = state
      saved.add(state)
    }
  }
}

fun <T> LinkedBlockingQueue<T>.receive(what: String): T = poll(5, TimeUnit.SECONDS) ?: throw AssertionError("timed out waiting for $what")
