package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.Desk
import dev.wobsoriano.muse.FakeVm
import dev.wobsoriano.muse.Frame
import dev.wobsoriano.muse.FrameKind
import dev.wobsoriano.muse.parseObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject

/**
 * The VM's voice desk: POST /voice/realtime and /voice/end-call. Unlike FakeMuse it was not captured
 * from a real VM: its shapes come from voice_call.py and the SDK's README (PR 275), so change it to
 * match the first call that is observed, and never to make a test pass.
 */
internal class FakeVoice : Desk {
  /** How the end-call is answered; null leaves it unanswered. */
  @Volatile var endCallReply: Pair<Int, String>? = 200 to """{"ok":true,"result":{"ok":true}}"""
  val calls = LinkedBlockingQueue<FakeCall>()
  val endCalls = LinkedBlockingQueue<JsonObject>()
  private val open = ConcurrentHashMap<Pair<FakeVm, Long>, FakeCall>()

  override fun claims(request: Frame) = request.path.startsWith("/voice/")

  override fun frame(vm: FakeVm, f: Frame) {
    when {
      f.kind == FrameKind.Request && f.path == "/voice/end-call" -> {
        endCalls.add(parseObject(String(f.data)) ?: JsonObject(emptyMap()))
        endCallReply?.let { (status, body) -> vm.write(Frame(stream = f.stream, kind = FrameKind.Response, status = status, data = body.toByteArray(), endBody = true)) }
      }
      f.kind == FrameKind.Request -> FakeCall(vm, f).also {
        open[vm to f.stream] = it
        calls.add(it)
      }
      else -> open[vm to f.stream]?.take(f)
    }
  }
}

/** One POST /voice/realtime as the VM saw it, and the VM's side of its answer. */
internal class FakeCall(val vm: FakeVm, val request: Frame) {
  val stream = request.stream
  val callId = request.path.substringAfter("call_id=")
  /** Each line the device sent, in order. */
  val lines = LinkedBlockingQueue<JsonObject>()
  @Volatile var halfClosed = false
  @Volatile var reset = false
  /** Ends the answer once the device ends its upload, as a server done with the call would. */
  @Volatile var endOnHalfClose = true
  private val buf = ByteArrayOutputStream()

  fun take(f: Frame) {
    if (f.kind == FrameKind.Reset) {
      reset = true
      return
    }
    buf.write(f.data)
    val all = buf.toByteArray()
    var start = 0
    for (i in all.indices) {
      if (all[i] != '\n'.code.toByte()) continue
      lines.add(parseObject(String(all, start, i - start)) ?: JsonObject(emptyMap()))
      start = i + 1
    }
    buf.reset()
    buf.write(all, start, all.size - start)
    if (f.endBody) {
      halfClosed = true
      if (endOnHalfClose) end()
    }
  }

  fun head(status: Int = 200) = vm.write(Frame(stream = stream, kind = FrameKind.Response, status = status))

  fun send(vararg events: String) = vm.write(Frame(stream = stream, kind = FrameKind.Body, data = events.joinToString("") { "$it\n" }.toByteArray()))

  fun ready(thread: String? = "t1") =
      send("""{"type":"hatch.voice.session","call_id":"$callId","session_id":"s1"${if (thread == null) "" else ",\"thread_id\":\"$thread\""}}""")

  fun end() = vm.write(Frame(stream = stream, kind = FrameKind.Body, endBody = true))

  /** The next line whose type is [type], skipping others. */
  fun next(type: String): JsonObject {
    while (true) {
      val l = lines.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no $type line")
      if ((l["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content == type) return l
    }
  }
}

internal fun continuousAudio(pcm: ByteArray, response: String = "r1") =
    """{"type":"continuous_audio_video.delta","response_id":"$response","audio_delta":{"audio_frames":"${Base64.getEncoder().encodeToString(pcm)}","format":{"type":"","rate":24000},"num_channels":1}}"""

internal const val STOP = """{"type":"conversation.item.added","item":{"type":"session_control","name":"stop","call_id":"control-1"}}"""

/** A microphone the test speaks into and a speaker it listens to. An empty frame on [mic] ends the mic. */
internal class FakeAudio(private val failOpen: Boolean = false) : CallAudio {
  val mic = LinkedBlockingQueue<ByteArray>()
  val played = LinkedBlockingQueue<ByteArray>()
  /** open, flush and close, in order. */
  val events = LinkedBlockingQueue<String>()
  @Volatile private var closed = false

  override fun open() {
    if (failOpen) throw IOException("no microphone")
    events.add("open")
  }

  override fun readMic(): ByteArray? {
    while (!closed) {
      val f = mic.poll(20, TimeUnit.MILLISECONDS) ?: continue
      return f.takeIf { it.isNotEmpty() }
    }
    return null
  }

  override fun play(pcm: ByteArray) {
    played.add(pcm)
  }

  override fun flush() {
    events.add("flush")
  }

  override fun close() {
    closed = true
    events.add("close")
  }
}
