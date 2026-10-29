// Ported from linux/src/musegadget/link_client.py in Meta's Muse Gadget SDK, Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the
// root of this module). Modified: rewritten in Kotlin, and extended with a chat subscription that waits for the
// reply, a voice note upload, and an early "settled" signal. The SDK's Linux client has none of
// these. They speak the protocol that the SDK's ESP32 firmware speaks (esp32/components/muse, under
// the same license).

package dev.wobsoriano.muse

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal const val NOISE_PATH = "/v1/noise"
internal const val CONTROL_PATH = "/link-control"
internal const val CHAT_PATH = "/chat/stream"
internal const val SUBSCRIBE_PATH = "/chat/subscribe"
internal const val APP_ID = "musegadget"

private const val MAX_CONCURRENT_INVOKES = 4
private const val MAX_INBOUND_MESSAGE = 4 shl 20
private const val MAX_RESPONSE_BYTES = 1 shl 20
private const val MAX_EVENT_LINE = 256 shl 10
/** The most the VM takes in one control message. */
private const val MAX_DEVICE_MESSAGE = 256 shl 10
/** The size the reference firmware sends a voice note's body in. */
internal const val BODY_CHUNK = 16 shl 10
internal const val MAX_ASK_TEXT = 16 shl 10
internal const val MAX_VOICE_NOTE = 600 shl 10
/** One Noise message, which is 64 KB at most, with room to spare. */
private const val MAX_WS_MESSAGE = 128 shl 10

private val DEFAULT_INVOKE_TIMEOUT = 30.seconds

/** Every wait in the client, gathered so the tests can shrink them. */
internal data class Timing(
    val handshake: Duration = 20.seconds,
    /** How often the WebSocket is pinged; a ping unanswered by the next one ends the connection. */
    val ping: Duration = 15.seconds,
    val request: Duration = 60.seconds,
    val firstReply: Duration = 5.minutes,
    val turnCap: Duration = 15.minutes,
    /** Pause after a complete answer before Settled. */
    val settle: Duration = 600.milliseconds,
    /** Pause after a complete answer before ask returns. */
    val final: Duration = 3.seconds,
    val backoffBase: Duration = 2.seconds,
    val backoffMax: Duration = 60.seconds,
    /** Least wait after the VM refuses the bearer. */
    val authFloor: Duration = 15.seconds,
    /** A session registered this long clears the backoff. */
    val healthy: Duration = 30.seconds,
    /** Least time between two spells of asking a refusing door again. */
    val knockRest: Duration = 10.minutes,
    /** Device access tokens live about four hours. */
    val refreshAge: Duration = 180.minutes,
    val tokenRetry: Duration = 5.minutes,
)

/** How a session ended, which decides what the service loop does next. */
internal enum class Outcome {
  /** The connection ended: reconnect. */
  Closed,
  /** The edge refused the VM bearer: fetch a new one. */
  AuthRejected,
  /** Authenticated but not allowed right now. */
  Forbidden,
  /** Muse removed this device. */
  Unpaired,
}

internal class Ended(val outcome: Outcome, val error: Throwable?)

/**
 * The session's address. [noiseHost] may carry a wss:// scheme, and ws:// in tests. [appId] names a
 * voice connection's app, which is fixed for the whole connection; the device's own session has none.
 */
internal fun noiseUrl(noiseHost: String, vmId: String, allowPlaintext: Boolean, appId: String? = null): String {
  var scheme = "wss"
  var host = noiseHost
  if (noiseHost.startsWith("wss://")) {
    host = noiseHost.removePrefix("wss://")
  } else if (noiseHost.startsWith("ws://") && allowPlaintext) {
    scheme = "ws"
    host = noiseHost.removePrefix("ws://")
  }
  // Noise XX does not say who the VM is. TLS does, so the host has to be one TLS can check.
  if (host.isEmpty() || host.any { it in "/?#@ " }) {
    throw MuseException("muse: the Noise host in the credentials is not a host")
  }
  val url = "$scheme://$host$NOISE_PATH?vm_id=${encodeURIComponent(vmId)}"
  return if (appId == null) url else "$url&app_id=${encodeURIComponent(validAppId(appId))}"
}

/** "hatch-web", or a positive int64 in ASCII digits, written without leading zeros as the SDK writes it. */
internal fun validAppId(id: String): String {
  if (id == "hatch-web") return id
  val n = id.takeIf { it.isNotEmpty() && it.length <= 19 && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
  if (n == null || n <= 0) throw MuseException("muse: the app id must be hatch-web or a positive 64-bit integer")
  return n.toString()
}

/**
 * Escapes as JavaScript's function of that name does, which is what the reference firmware uses and
 * so what the edge is known to accept.
 */
internal fun encodeURIComponent(s: String): String {
  val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"
  val b = StringBuilder()
  for (byte in s.toByteArray(Charsets.UTF_8)) {
    val c = byte.toInt() and 0xFF
    if (c < 0x80 && c.toChar() in safe) b.append(c.toChar()) else b.append("%%%02X".format(c))
  }
  return b.toString()
}

private val uuidRandom = SecureRandom()

internal fun uuid4(): String {
  val u = ByteArray(16).also(uuidRandom::nextBytes)
  u[6] = ((u[6].toInt() and 0x0F) or 0x40).toByte()
  u[8] = ((u[8].toInt() and 0x3F) or 0x80).toByte()
  val h = u.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
  return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

/** Frames one control message: its length as a little-endian u32, then the JSON. */
internal fun encodeControl(message: JsonElement): ByteArray {
  val data = message.toString().toByteArray(Charsets.UTF_8)
  val n = data.size
  return byteArrayOf(n.toByte(), (n ushr 8).toByte(), (n ushr 16).toByte(), (n ushr 24).toByte()) + data
}

/**
 * Anything the VM says on the control stream. Every field is kept raw: the id is echoed back exactly
 * as it came, and a field of an unexpected type must not cost the message.
 */
internal class ControlMessage(
    val id: JsonElement?,
    val method: JsonElement?,
    val event: JsonElement?,
    val command: JsonElement?,
    val params: JsonElement?,
    val timeoutMs: JsonElement?,
    val error: JsonElement?,
)

/** Splits the control stream back into messages. One may span body chunks. */
internal class ControlDecoder {
  private var buf = ByteArray(0)

  fun feed(data: ByteArray): List<ControlMessage> {
    buf += data
    val out = mutableListOf<ControlMessage>()
    var at = 0
    while (buf.size - at >= 4) {
      val n =
          (buf[at].toLong() and 0xFF) or
              ((buf[at + 1].toLong() and 0xFF) shl 8) or
              ((buf[at + 2].toLong() and 0xFF) shl 16) or
              ((buf[at + 3].toLong() and 0xFF) shl 24)
      if (n > MAX_INBOUND_MESSAGE) throw MuseException("muse: control message too large")
      if (buf.size - at < 4 + n) break
      val raw = String(buf, at + 4, n.toInt(), Charsets.UTF_8)
      at += 4 + n.toInt()
      // An empty message is a keepalive. One that is not a JSON object is dropped, as the SDK does.
      val m = if (n > 0) parseObject(raw) else null
      if (m != null) {
        out += ControlMessage(m["id"], m["method"], m["event"], m["command"], m["params"], m["timeout_ms"], m["error"])
      }
    }
    // What is left moves to a buffer of its own, so the consumed bytes can be freed.
    buf = buf.copyOfRange(at, buf.size)
    return out
  }
}

/** Takes the frames of one stream. It is called with the session's lock held. */
internal interface Sink {
  fun onFrame(f: Frame)

  /** The connection is gone and no frame follows. */
  fun onClosed(why: String) {}
}

/** Collects the answer to one request stream. */
internal class RequestSink : Sink {
  var status = 0
  private val body = ByteArrayOutputStream()
  var error: Throwable? = null
  private var over = false
  val done = CompletableDeferred<Unit>()

  val bytes: ByteArray
    get() = body.toByteArray()

  private fun finish(err: Throwable?) {
    error = err
    over = true
    done.complete(Unit)
  }

  override fun onFrame(f: Frame) {
    if (over) return
    if (f.kind == FrameKind.Reset) return finish(MuseException("muse: stream reset: ${f.reason}"))
    if (f.kind == FrameKind.Response) status = f.status
    body.write(f.data)
    if (body.size() > MAX_RESPONSE_BYTES) finish(MuseException("muse: response too large")) else if (f.endBody) finish(null)
  }
}

/** One invoke, from either stream. [args] is the raw parameters, which a handler gets as they came. */
internal class Invoke(val id: JsonElement?, val command: String, val args: String?, val timeoutMs: JsonElement?)

internal class SessionParams(
    val url: String,
    val bearer: String,
    val userAgent: String,
    val http: OkHttpClient,
    val nodeId: String,
    /**
     * The link.register params. Null for a voice connection, which opens no /link-control and never
     * registers, so the device's own session stays the registered one.
     */
    val register: JsonObject?,
    val commands: Map<String, Command>,
    val log: Logger,
    val t: Timing,
    /** Called once Muse accepts the registration. */
    val registered: () -> Unit,
    /** The API's word on asking a refusing front door again ([Vm]). */
    val knockEvery: Duration,
    val knocks: Int,
)

/** What the OkHttp listener saw, in order. */
private sealed interface WsEvent {
  class Open(val response: Response) : WsEvent

  class Binary(val bytes: ByteArray) : WsEvent

  data object Text : WsEvent

  class Gone(val error: Throwable?, val response: Response?, val reason: String) : WsEvent
}

/** One connection to a VM, from the WebSocket upgrade until it drops. */
internal class Session(private val p: SessionParams) {
  /** How many times the door was asked again, read once run has returned. */
  @Volatile var knocked = 0
    private set

  // sendLock orders everything written: Noise numbers each message, so sealing one and writing it
  // must not interleave with another, and stream ids must reach the VM in the order they were taken.
  private val sendLock = Any()
  private var sendCipher: CipherState? = null
  private var nextStream = 0L
  @Volatile private var ws: WebSocket? = null

  // Touched only by the reader.
  private var recvCipher: CipherState? = null
  private val chunks = Assembler()
  @Volatile private var control = 0L
  private var registerId = ""

  // lock guards these.
  private val lock = Any()
  private val streams = HashMap<Long, Sink>()
  /** Set once the connection is gone, after which no stream can be opened. */
  private var gone = false
  private var sub: Subscription? = null
  private var turn: Turn? = null
  /** System.nanoTime when Muse accepted the registration, or null before. */
  var registeredAt: Long? = null
    get() = synchronized(lock) { field }
    private set

  /** Completes when the session is over, which frees everything waiting on it. */
  private val ended = CompletableDeferred<Unit>()
  /** Completes once the Noise session is established. A session that ends first leaves it incomplete, and run says why. */
  val up = CompletableDeferred<Unit>()

  /** Bytes the WebSocket has queued and not yet written. */
  val backlog: Long
    get() = ws?.queueSize() ?: 0L
  /** Ends with the session and takes the running commands with it. */
  private val invoking = SupervisorJob()
  private val invokeScope = CoroutineScope(invoking + Dispatchers.Default)
  private val slots = Semaphore(MAX_CONCURRENT_INVOKES)

  private sealed interface Dialed {
    class Open(val ws: WebSocket, val events: Channel<WsEvent>) : Dialed

    class Failed(val status: Int?, val error: Throwable) : Dialed
  }

  private suspend fun dial(): Dialed {
    val events = Channel<WsEvent>(Channel.UNLIMITED)
    val request =
        Request.Builder().url(p.url).header("Authorization", "Bearer ${p.bearer}").header("User-Agent", p.userAgent).build()
    val ws =
        p.http.newWebSocket(
            request,
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: Response) {
                events.trySend(WsEvent.Open(response))
              }

              override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                events.trySend(WsEvent.Binary(bytes.toByteArray()))
              }

              override fun onMessage(webSocket: WebSocket, text: String) {
                events.trySend(WsEvent.Text)
              }

              override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.trySend(WsEvent.Gone(null, null, "closed by the VM ($code)"))
                webSocket.cancel()
              }

              override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.trySend(WsEvent.Gone(t, response, t.message ?: t.toString()))
              }
            })
    try {
      return when (val first = withTimeoutOrNull(p.t.handshake) { events.receive() }) {
        is WsEvent.Open -> Dialed.Open(ws, events)
        is WsEvent.Gone -> Dialed.Failed(first.response?.code, MuseException(first.reason, first.error))
        null -> {
          ws.cancel()
          Dialed.Failed(null, MuseException("i/o timeout"))
        }
        else -> {
          ws.cancel()
          Dialed.Failed(null, MuseException("unexpected message before the upgrade"))
        }
      }
    } catch (e: CancellationException) {
      ws.cancel()
      throw e
    }
  }

  /** Serves until the connection ends, and says why. It throws only when the caller's coroutine is canceled. */
  suspend fun run(): Ended {
    var dialed = dial()
    // A VM nothing has reached for a while is not behind its front door yet, and the door says 403
    // until it is: seen on a Show 5, some eleven seconds of asking twice a second after an hour
    // connected. Asked once a minute it never opened, because whatever brings the VM there had let go
    // again by then. The API says how often to ask and how many times, so that is what is done, with
    // the bearer it gave. Only so often, though (Client.knocks): Meta's own firmware waits a minute
    // after a 403 so as not to load the door, and a device refused for a real reason must not ask it
    // eighty times every round.
    var knocks = 0
    try {
      while (dialed is Dialed.Failed && dialed.status == 403 && knocks < p.knocks) {
        delay(p.knockEvery)
        knocks++
        dialed = dial()
      }
    } finally {
      knocked = knocks
    }
    if (knocks > 0) p.log.info("muse: the VM's door was asked again", "times" to knocks, "opened" to (dialed is Dialed.Open))
    when (dialed) {
      is Dialed.Failed -> {
        ended.complete(Unit)
        return when (dialed.status) {
          401 -> Ended(Outcome.AuthRejected, MuseException("muse: the VM refused the connection: HTTP 401"))
          403 -> Ended(Outcome.Forbidden, MuseException("muse: the VM refused the connection: HTTP 403"))
          else -> Ended(Outcome.Closed, MuseException("muse: connect: ${dialed.error.message}", dialed.error))
        }
      }
      is Dialed.Open -> {
        ws = dialed.ws
        try {
          return serve(dialed.ws, dialed.events)
        } finally {
          ended.complete(Unit)
          dialed.ws.cancel()
          synchronized(lock) {
            gone = true
            for (s in streams.values) s.onClosed("connection closed")
            streams.clear()
          }
          withContext(NonCancellable) { invoking.cancelAndJoin() }
        }
      }
    }
  }

  private suspend fun serve(ws: WebSocket, events: Channel<WsEvent>): Ended {
    try {
      withTimeout(p.t.handshake) { handshake(ws, events) }
    } catch (e: TimeoutCancellationException) {
      return Ended(Outcome.Closed, MuseException("muse: Noise handshake: i/o timeout"))
    } catch (e: MuseException) {
      return Ended(Outcome.Closed, MuseException("muse: Noise handshake: ${e.message}", e))
    }
    p.log.info("muse: Noise session established")
    up.complete(Unit)

    // Without a control stream, control stays 0 and every frame goes to the stream it names.
    if (p.register != null) {
      try {
        control = open(Frame(kind = FrameKind.Request, verb = "POST", path = CONTROL_PATH), null)
        registerId = uuid4()
        sendControl(
            buildJsonObject {
              put("type", "req")
              put("id", registerId)
              put("method", "link.register")
              put("params", p.register)
            })
      } catch (e: MuseException) {
        return Ended(Outcome.Closed, e)
      }
      p.log.info("muse: sent link.register", "node" to p.nodeId)
    }

    val controlStream = ControlDecoder()
    while (true) {
      val data =
          when (val ev = events.receive()) {
            is WsEvent.Binary -> ev.bytes
            WsEvent.Text -> continue
            is WsEvent.Gone -> return Ended(Outcome.Closed, MuseException("muse: connection closed: ${ev.reason}", ev.error))
            is WsEvent.Open -> continue
          }
      if (data.size > MAX_WS_MESSAGE) return Ended(Outcome.Closed, MuseException("muse: connection closed: read limit exceeded"))
      val plain =
          try {
            checkNotNull(recvCipher).decrypt(EMPTY, data)
          } catch (e: NoiseException) {
            return Ended(Outcome.Closed, MuseException("muse: a message from the VM did not decrypt"))
          }
      val f =
          try {
            val envelope = chunks.add(plain) ?: continue
            decodeServiceResponse(envelope)
          } catch (e: MuseException) {
            return Ended(Outcome.Closed, e)
          }
      if (f.stream != control) {
        synchronized(lock) { streams[f.stream]?.onFrame(f) }
        continue
      }
      if (f.kind == FrameKind.Reset) return Ended(Outcome.Closed, MuseException("muse: control stream reset: ${f.reason}"))
      if (f.kind == FrameKind.Response && f.status >= 400) {
        val err = MuseException("muse: the VM refused the control stream: HTTP ${f.status}")
        return Ended(if (f.status == 403) Outcome.Forbidden else Outcome.Closed, err)
      }
      val messages =
          try {
            controlStream.feed(f.data)
          } catch (e: MuseException) {
            return Ended(Outcome.Closed, e)
          }
      for (m in messages) handle(m)?.let { return it }
      if (f.endBody) return Ended(Outcome.Closed, MuseException("muse: control stream ended by the VM"))
    }
  }

  /**
   * Noise XX as the initiator. The bearer already said who the device is at the upgrade, so the
   * static key is made fresh each time and message 3 carries no payload.
   */
  private suspend fun handshake(ws: WebSocket, events: Channel<WsEvent>) {
    val hs = NoiseXX(true, DhKey.generate(RandomSource.Secure), RandomSource.Secure)
    fun write(msg: ByteArray) {
      if (!ws.send(msg.toByteString())) throw MuseException("the connection is closed")
    }
    write(hs.write().message)
    val msg2 =
        when (val ev = events.receive()) {
          is WsEvent.Binary -> ev.bytes
          WsEvent.Text -> throw MuseException("got a text frame")
          is WsEvent.Gone -> throw MuseException(ev.reason, ev.error)
          is WsEvent.Open -> throw MuseException("opened twice")
        }
    try {
      hs.read(msg2)
      val last = hs.write()
      val (send, recv) = checkNotNull(last.session)
      synchronized(sendLock) { sendCipher = send }
      recvCipher = recv
      write(last.message)
    } catch (e: NoiseException) {
      throw MuseException(e.message ?: "noise", e)
    }
  }

  /**
   * Starts a stream with a request frame, and returns the stream's id. [to] hears the answer, and is
   * in place before the request leaves so that no frame of the answer can beat it.
   */
  private fun open(f: Frame, to: Sink?): Long =
      synchronized(sendLock) {
        nextStream++
        val id = nextStream
        if (to != null) {
          synchronized(lock) {
            if (gone) throw MuseException("muse: send: not connected")
            streams[id] = to
          }
        }
        try {
          writeLocked(f.withStream(id))
        } catch (e: MuseException) {
          forget(id)
          throw e
        }
        id
      }

  internal fun write(f: Frame) = synchronized(sendLock) { writeLocked(f) }

  private fun writeLocked(f: Frame) {
    val ws = ws ?: throw MuseException("muse: send: not connected")
    val cipher = sendCipher ?: throw MuseException("muse: send: not connected")
    for (piece in chunk(encodeServiceRequest(f))) {
      val sent =
          try {
            ws.send(cipher.encrypt(EMPTY, piece).toByteString())
          } catch (e: NoiseException) {
            false
          }
      if (!sent) {
        // The far side can no longer follow the numbering, so the session is over.
        ws.cancel()
        throw MuseException("muse: send: the connection is closed")
      }
    }
  }

  internal fun forget(stream: Long) {
    synchronized(lock) { streams.remove(stream) }
  }

  /**
   * Opens a stream whose answer is read as it arrives. Without [endBody] its body stays open for
   * [DuplexStream.send]. Throws MuseException when the connection is gone.
   */
  fun openStream(verb: String, path: String, headers: List<Header>, body: ByteArray = EMPTY, endBody: Boolean = false): DuplexStream {
    val sink = DuplexSink()
    val id = open(Frame(kind = FrameKind.Request, verb = verb, path = path, headers = headers, data = body, endBody = endBody), sink)
    return DuplexStream(this, id, sink, endBody)
  }

  /** One request answered whole, at most MAX_RESPONSE_BYTES, within [timeout]. One that fails or runs out of time is reset. */
  suspend fun request(verb: String, path: String, headers: List<Header>, body: ByteArray = EMPTY, timeout: Duration = p.t.request): Buffered {
    val stream = openStream(verb, path, headers, body, endBody = true)
    var answered = false
    try {
      val got = withTimeoutOrNull(timeout) { whole(stream) } ?: throw MuseException("muse: no answer in time")
      answered = true
      return got
    } finally {
      stream.close(reset = !answered)
    }
  }

  private suspend fun whole(stream: DuplexStream): Buffered {
    var status = 0
    val got = ByteArrayOutputStream()
    while (true) {
      when (val i = stream.inbound.receive()) {
        is Inbound.Head -> status = i.status
        is Inbound.Chunk -> {
          got.write(i.data)
          i.release()
          if (got.size() > MAX_RESPONSE_BYTES) throw MuseException("muse: response too large")
        }
        Inbound.End -> return Buffered(status, got.toByteArray())
      }
    }
  }

  private fun sendControl(message: JsonElement) = write(Frame(stream = control, kind = FrameKind.Body, data = encodeControl(message)))

  /** Acts on one control message, and returns how the session ends when the message ends it. */
  private fun handle(m: ControlMessage): Ended? {
    if (rawString(m.id) == registerId && !truthyJson(m.method)) {
      if (truthyJson(m.error)) {
        // The SDK logs this and stays connected, registered for nothing. Ending the session instead
        // lets the service loop show the failure and try again.
        return Ended(Outcome.Closed, MuseException("muse: Muse rejected the registration"))
      }
      synchronized(lock) { registeredAt = System.nanoTime() }
      p.log.info("muse: registered with Muse")
      p.registered()
      return null
    }
    when (rawString(m.event)) {
      "link.unpaired",
      "node.unpaired" -> return Ended(Outcome.Unpaired, UnpairedException())
    }
    if (rawString(m.method) == "link.invoke" && truthyJson(m.id)) {
      launchInvoke(Invoke(m.id, rawString(m.command), m.params?.toString(), m.timeoutMs))
    }
    return null
  }

  /**
   * Takes a client.invoke event as the invoke it is. Called with the lock held, so it only hands the
   * command on.
   *
   * client.invoke is how a command reaches the device when the turn it belongs to is the device's
   * own: an event on the chat stream, where one from a turn started in the Muse app is link.invoke on
   * the control stream. Meta's SDK handles only the second, and a gadget that sends voice notes with
   * its device_id is sent the first: a Show 5 asked aloud to change its volume ran nothing, and Muse
   * waited out node_invoke_timeout. The answer is the same link.result, with the event's invoke_id.
   */
  private fun clientInvoked(e: ChatEvent) {
    val id = e.str("invoke_id")
    if (id.isEmpty()) return
    launchInvoke(Invoke(JsonPrimitive(id), e.str("command_id"), e.str("params_json"), e.payload["timeout_ms"]))
  }

  private fun launchInvoke(inv: Invoke) {
    invokeScope.launch { slots.withPermit { invoke(inv) } }
  }

  private suspend fun invoke(inv: Invoke) {
    p.log.info("muse: invoke", "command" to inv.command)
    var payload: String? = null
    var failure: String? = null
    try {
      payload = runCommand(inv)
      if (payload.toByteArray(Charsets.UTF_8).size > MAX_DEVICE_MESSAGE - 1024) {
        failure = "the result is ${payload.toByteArray(Charsets.UTF_8).size} bytes, more than Muse takes"
      }
    } catch (e: TimeoutCancellationException) {
      failure = "context deadline exceeded"
    } catch (e: CancellationException) {
      throw e
    } catch (e: CommandFailed) {
      failure = e.message ?: ""
    } catch (e: Throwable) {
      // A handler that throws has failed one command. It must not take the session, and every other
      // command in flight, down with it.
      failure = "panic: ${e.message}"
    }
    val result = buildJsonObject {
      put("method", "link.result")
      put("id", inv.id ?: JsonNull)
      put("ok", failure == null)
      if (failure == null) put("payload", stateJson.parseToJsonElement(payload!!)) else if (failure.isNotEmpty()) put("error", failure)
    }
    try {
      sendControl(result)
    } catch (e: MuseException) {
      p.log.warn("muse: could not send a command's result", "command" to inv.command, "err" to e.message)
    }
  }

  private suspend fun runCommand(inv: Invoke): String {
    val command = p.commands[inv.command] ?: throw CommandFailed("unsupported command: ${inv.command}")
    val args = inv.args?.takeIf { it.startsWith("{") } ?: "{}"
    var timeout = command.timeout
    val ms = (inv.timeoutMs as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    if (ms != null && ms > 0) timeout = ms.toLong().milliseconds
    if (timeout <= Duration.ZERO) timeout = DEFAULT_INVOKE_TIMEOUT
    val value = withTimeout(timeout) { checkNotNull(command.handler).handle(args, timeout) }
    if (value == null || value == JsonNull) return "{}"
    return value.toString()
  }

  private fun jsonHeaders(vararg extra: Header) =
      listOf(Header("Content-Type", "application/json"), Header("x-request-id", uuid4()), Header("x-app-id", APP_ID)) + extra

  /** The chat stream: a response that never ends, one JSON event to a line. */
  private inner class Subscription : Sink {
    var id = 0L
    private var buf = ByteArray(0)
    var closed = false
      private set

    var reason = ""
      private set

    fun close(why: String) {
      closed = true
      reason = why
      turn?.poke()
    }

    override fun onFrame(f: Frame) {
      if (closed) return
      if (f.kind == FrameKind.Reset) return close(f.reason)
      if (f.kind == FrameKind.Response && f.status >= 400) return close("HTTP ${f.status}")
      buf += f.data
      var start = 0
      while (true) {
        val end = (start until buf.size).firstOrNull { buf[it] == '\n'.code.toByte() } ?: break
        // Lines of any other type are the acknowledgment of the subscription itself.
        val e = ChatEvent.parse(String(buf, start, end - start, Charsets.UTF_8))
        if (e != null && e.type == "event") {
          if (e.event == CLIENT_INVOKE) clientInvoked(e) else turn?.add(e)
        }
        start = end + 1
      }
      buf = buf.copyOfRange(start, buf.size)
      if (buf.size > MAX_EVENT_LINE) close("event line too long") else if (f.endBody) close("ended by Muse")
    }
  }

  /** Opens the stream Muse's chat events arrive on. It is opened once and lasts the session, and is only opened again if it closed. */
  private fun subscribe() {
    synchronized(lock) {
      val old = sub
      if (old != null && !old.closed) return
      if (old != null) streams.remove(old.id)
    }
    val s = Subscription()
    val id =
        open(
            Frame(
                kind = FrameKind.Request,
                verb = "POST",
                path = SUBSCRIBE_PATH,
                headers = jsonHeaders(Header("accept", "application/x-ndjson")),
                data = "{}".toByteArray(),
                endBody = true),
            s)
    synchronized(lock) {
      s.id = id
      sub = s
    }
  }

  /** Posts the message. The answer to the post is only an acknowledgment naming the message. */
  private suspend fun sendChat(input: AskInput, ack: RequestSink): Long {
    val head = Frame(kind = FrameKind.Request, verb = "POST", path = CHAT_PATH, headers = jsonHeaders())
    // device_id is what has Muse take the turn as this device's and send its commands back here
    // (Meta's Linux client, send_chat). The firmware whose voice note upload this copies leaves it
    // out, and without it Muse took "increase the volume", said to the device, to be about the phone.
    when (input) {
      is AskInput.Text -> {
        val body = buildJsonObject {
          put("message", input.message)
          put("output_modality", "text")
          put("device_id", p.nodeId)
        }
        return open(Frame(head.stream, head.kind, head.verb, head.path, head.headers, data = body.toString().toByteArray(), endBody = true), ack)
      }
      is AskInput.VoiceNote -> {
        // The recording rides as a base64 attachment, the way Meta's voice gadgets send it, with the
        // body in pieces after a request that carries none.
        val body =
            buildJsonObject {
                  put("message", "")
                  put("output_modality", "text")
                  put("device_id", p.nodeId)
                  put(
                      "items",
                      buildJsonArray {
                        add(
                            buildJsonObject {
                              put("type", "file")
                              put("mime_type", "audio/wav")
                              put("filename", "voice_note.wav")
                              put("data_base64", Base64.getEncoder().encodeToString(input.wav))
                            })
                      })
                }
                .toString()
                .toByteArray()
        val id = open(head, ack)
        var start = 0
        while (start < body.size) {
          if (!currentCoroutineContext().isActive) {
            // Half a recording is no use to Muse, so the stream is withdrawn.
            write(Frame(stream = id, kind = FrameKind.Reset, code = RESET_CANCELED, reason = "canceled"))
            forget(id)
            currentCoroutineContext().ensureActive()
          }
          val end = minOf(body.size, start + BODY_CHUNK)
          write(Frame(stream = id, kind = FrameKind.Body, data = body.copyOfRange(start, end), endBody = end == body.size))
          start += BODY_CHUNK
        }
        return id
      }
    }
  }

  /** The turn as the asker last saw it. */
  private class Snapshot(val text: String, val heard: String, val whole: Boolean, val quiet: Duration, val subOpen: Boolean, val subClosed: String)

  private fun snapshot(t: Turn): Snapshot =
      synchronized(lock) {
        val s = sub
        Snapshot(t.text(), t.heard, t.whole(), (System.nanoTime() - t.lastEvent).nanoseconds, s != null && !s.closed, s?.reason ?: "")
      }

  private fun dropped(err: Throwable): Throwable =
      if (ended.isCompleted) OfflineException("the connection dropped before the answer was complete") else err

  private sealed interface Woke {
    data object Ended : Woke

    data object Again : Woke
  }

  /**
   * Sends one message and waits out the answer. [on] runs on the caller's coroutine, between the
   * waits of this function, so nothing can reach it once ask has returned.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  suspend fun ask(input: AskInput, on: suspend (ReplyEvent) -> Unit): Reply {
    val t = Turn()
    synchronized(lock) {
      when {
        registeredAt == null -> throw OfflineException()
        turn != null -> throw BusyException()
      }
      turn = t
    }
    var stream = 0L
    try {
      try {
        subscribe()
      } catch (e: MuseException) {
        throw dropped(e)
      }
      val ack = RequestSink()
      try {
        stream = sendChat(input, ack)
      } catch (e: MuseException) {
        throw dropped(e)
      }
      val acked =
          select<Boolean?> {
            ack.done.onAwait { true }
            ended.onAwait { null }
            onTimeout(p.t.request) { false }
          }
      when (acked) {
        null -> throw dropped(OfflineException())
        false -> throw MuseException("muse: Muse did not acknowledge the message")
        true -> Unit
      }
      ack.error?.let { throw it }
      if (ack.status < 200 || ack.status >= 300) throw MuseException("muse: Muse did not take the message: HTTP ${ack.status}")
      val body = parseObject(String(ack.bytes, Charsets.UTF_8))
      var messageId = rawString(body?.get("message_id"))
      (body?.get("result") as? JsonObject)?.let { messageId = rawString(it["message_id"]) }
      if (messageId.isEmpty()) {
        // Without it no reply can ever be matched, and the references would wait five minutes to say so.
        throw MuseException("muse: Muse acknowledged the message without naming it")
      }
      synchronized(lock) { t.acknowledged(messageId) }

      val started = System.nanoTime()
      var shown = ""
      var heard = ""
      var settled = ""
      while (true) {
        val snap = snapshot(t)
        currentCoroutineContext().ensureActive()
        if (input is AskInput.VoiceNote && snap.heard != heard) {
          heard = snap.heard
          on(ReplyEvent.Heard(heard))
        }
        if (snap.text != shown) {
          shown = snap.text
          on(ReplyEvent.Text(shown))
        }
        val elapsed = (System.nanoTime() - started).nanoseconds
        var next = 250.milliseconds
        when {
          snap.whole && snap.quiet >= p.t.final -> return Reply(shown, heard)
          snap.whole && snap.quiet >= p.t.settle && settled != shown -> {
            settled = shown
            on(ReplyEvent.Settled(shown))
            continue
          }
          snap.whole && settled != shown -> next = p.t.settle - snap.quiet
          snap.whole -> next = p.t.final - snap.quiet
          !snap.subOpen -> throw dropped(MuseException("muse: chat stream closed: ${snap.subClosed}"))
          shown.isEmpty() && elapsed > p.t.firstReply -> throw MuseException("muse: Muse did not reply")
          elapsed > p.t.turnCap -> {
            if (shown.isEmpty()) throw MuseException("muse: Muse did not finish replying")
            return Reply(shown, heard)
          }
        }
        val woke =
            select<Woke> {
              ended.onAwait { Woke.Ended }
              t.wake.onReceive { Woke.Again }
              onTimeout(next) { Woke.Again }
            }
        if (woke == Woke.Ended) throw dropped(OfflineException())
      }
    } finally {
      synchronized(lock) { turn = null }
      if (stream != 0L) forget(stream)
    }
  }

  private companion object {
    const val CLIENT_INVOKE = "client.invoke"
  }
}
