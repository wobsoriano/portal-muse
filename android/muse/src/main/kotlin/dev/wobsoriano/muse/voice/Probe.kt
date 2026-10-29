package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.EMPTY
import dev.wobsoriano.muse.Header
import dev.wobsoriano.muse.MuseException
import dev.wobsoriano.muse.Session
import dev.wobsoriano.muse.parseObject
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject

private val PROBE_TIMEOUT = 10.seconds

/** One request, as its status and its error code; null and null when it never got an answer. Nothing else of it is kept. */
internal suspend fun reach(session: Session?, verb: String, path: String, headers: List<Header>, body: ByteArray = EMPTY): ProbeReport.Reached {
  if (session == null) return ProbeReport.Reached(null, null)
  return try {
    val r = session.request(verb, path, headers, body, PROBE_TIMEOUT)
    ProbeReport.Reached(r.status, errorCode(r.body))
  } catch (e: MuseException) {
    ProbeReport.Reached(null, null)
  }
}

/** error.code, error when it is a string, or code, whichever comes first, and only as a short code. */
internal fun errorCode(body: ByteArray): String? {
  val o = parseObject(String(body, Charsets.UTF_8)) ?: return null
  val error = o["error"]
  return (error as? JsonObject)?.let { shortCode(it["code"]) } ?: shortCode(error) ?: shortCode(o["code"])
}

/** No microphone and no speaker: the probe only asks whether a call is admitted. */
internal class SilentAudio : CallAudio {
  private val closed = CountDownLatch(1)

  override fun open() {}

  override fun readMic(): ByteArray? {
    closed.await()
    return null
  }

  override fun play(pcm: ByteArray) {}

  override fun flush() {}

  override fun close() = closed.countDown()
}
