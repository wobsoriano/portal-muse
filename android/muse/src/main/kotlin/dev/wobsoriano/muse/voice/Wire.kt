// Follows linux/src/musegadget/voice_call.py, voice_gate.py and streaming.py in Meta's Muse Gadget SDK
// (PR 275), Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache License,
// Version 2.0 (LICENSE at the root of this module). Rewritten in Kotlin.

package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.Buffered
import dev.wobsoriano.muse.MuseException
import dev.wobsoriano.muse.isValidUtf8
import dev.wobsoriano.muse.parseObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val MAX_LINE = 1 shl 20
/** One event's audio: 4 s. */
internal const val MAX_AUDIO_BYTES = CALL_RATE * 2 * 4

/** What the server said on the call's answer, parsed and shape-checked. What it means for the call is CallMachine's. */
internal sealed interface ServerEvent {
  /** Readiness. [sessionId] is never blank, and [threadId] is null or never blank. */
  class Session(val callId: String?, val sessionId: String, val threadId: String?) : ServerEvent

  class Hold(val held: Boolean) : ServerEvent

  class SpeechStarted(val itemId: String?) : ServerEvent

  class TurnStarted(val submissionId: String, val itemId: String) : ServerEvent

  class TurnSettled(val responseMessageId: String?) : ServerEvent

  class InputSuppressed(val itemId: String?) : ServerEvent

  class ResponseCreated(val id: String?) : ServerEvent

  /** response.done or response.completed: a response, even a cancelled one, is not the call ending. */
  class ResponseEnded(val id: String?) : ServerEvent

  /** A piece of the spoken reply's transcript. */
  class ReplyText(val delta: String) : ServerEvent

  /** The whole transcript of the spoken reply. */
  class ReplyDone(val transcript: String) : ServerEvent

  /** What Muse heard the caller say. */
  class Heard(val transcript: String) : ServerEvent

  /** session_control "stop": Muse ends the call, after a goodbye. Its call_id names the control, never the call. */
  data object Stop : ServerEvent

  /** [responseId] is the provider's, which only continuous audio carries. [pcm] is never empty. */
  class Audio(val pcm: ByteArray, val responseId: String?) : ServerEvent

  /** [code] only when it is a short machine code; the message is never kept. */
  class Error(val code: String?) : ServerEvent

  /** Unknown types are ignored, as the SDK ignores them. */
  data object Other : ServerEvent

  /** [why] is our own text. */
  class Malformed(val why: String) : ServerEvent
}

/** A protocol identifier: a nonempty string with no NUL, at most 128 bytes of UTF-8. */
internal fun identifier(v: JsonElement?): String? {
  val s = v.string() ?: return null
  if (s.isEmpty() || '\u0000' in s || !Charsets.UTF_8.newEncoder().canEncode(s)) return null
  return s.takeIf { it.toByteArray(Charsets.UTF_8).size <= 128 }
}

private val CODE = Regex("[a-z0-9_.-]{1,64}")

/** A server's error code, only when it is short and plain enough to log. */
internal fun shortCode(v: JsonElement?): String? = v.string()?.takeIf { CODE.matches(it) }

/**
 * What kind of record [o] is, for the call's tally: its type, and for an added item the item's type and
 * name, each a short code or "?". Never a value that could carry speech.
 */
internal fun recordKind(o: JsonObject): String {
  val type = shortCode(o["type"]) ?: "?"
  val item = o["item"] as? JsonObject ?: return type
  return "$type:${shortCode(item["type"]) ?: "?"}" + (if ("name" in item) ":${shortCode(item["name"]) ?: "?"}" else "")
}

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Python's == between a JSON value and an int, which is what the SDK's checks are. */
private fun JsonElement?.equalsInt(n: Int): Boolean {
  val p = this as? JsonPrimitive ?: return false
  if (p.isString) return false
  val v =
      when (p.content) {
        "true" -> 1.0
        "false" -> 0.0
        else -> p.content.toDoubleOrNull()
      }
  return v == n.toDouble()
}

private val BASE64 = Regex("[A-Za-z0-9+/]*={0,2}")

internal fun parseServerEvent(o: JsonObject): ServerEvent {
  val type = o["type"].string() ?: return ServerEvent.Malformed("event without a type")
  return when (type) {
    "error" -> {
      val e = o["error"] as? JsonObject ?: return ServerEvent.Malformed("error event")
      ServerEvent.Error(shortCode(e["code"]))
    }
    "hatch.voice.session" -> {
      val session = o["session_id"].string()?.takeIf { it.isNotBlank() } ?: return ServerEvent.Malformed("voice identity")
      val thread =
          when (val t = o["thread_id"]) {
            null,
            JsonNull -> null
            else -> t.string()?.takeIf { it.isNotBlank() } ?: return ServerEvent.Malformed("voice identity")
          }
      ServerEvent.Session(o["call_id"].string(), session, thread)
    }
    "hatch.voice.switch_input_hold" -> {
      val held = o["held"] as? JsonPrimitive
      if (held == null || held.isString || held.content !in setOf("true", "false")) return ServerEvent.Malformed("input hold")
      ServerEvent.Hold(held.content == "true")
    }
    "input_audio_buffer.speech_started" -> ServerEvent.SpeechStarted(identifier(o["item_id"]))
    "conversation.item.added" -> if (isStop(o["item"])) ServerEvent.Stop else ServerEvent.Other
    "hatch.voice.turn_started" -> {
      val submission = identifier(o["submission_message_id"])
      val item = identifier(o["item_id"])
      if (submission == null || item == null) ServerEvent.Malformed("turn identity") else ServerEvent.TurnStarted(submission, item)
    }
    "hatch.voice.turn_settled" -> ServerEvent.TurnSettled(identifier(o["response_message_id"]))
    "hatch.voice.input_suppressed" -> ServerEvent.InputSuppressed(identifier(o["item_id"]))
    "response.created" -> ServerEvent.ResponseCreated(responseId(o))
    "response.output_audio_transcript.delta" -> o["delta"].string()?.let(ServerEvent::ReplyText) ?: ServerEvent.Other
    "response.output_audio_transcript.done" -> o["transcript"].string()?.let(ServerEvent::ReplyDone) ?: ServerEvent.Other
    "conversation.item.input_audio_transcription.completed" -> o["transcript"].string()?.let(ServerEvent::Heard) ?: ServerEvent.Other
    "response.done",
    "response.completed" -> ServerEvent.ResponseEnded(responseId(o))
    "continuous_audio_video.delta" -> continuousAudio(o)
    "response.output_audio.delta",
    "response.audio.delta" -> audio(o["delta"], null)
    else -> ServerEvent.Other
  }
}

private fun responseId(o: JsonObject): String? = (o["response"] as? JsonObject)?.let { identifier(it["id"]) }

private fun isStop(item: JsonElement?): Boolean {
  if (item !is JsonObject || item["type"].string() != "session_control") return false
  val control = item["call_id"].string() ?: return false
  if (control.isEmpty() || control.codePointCount(0, control.length) > 128) return false
  return item["name"].string()?.trim()?.lowercase() == "stop"
}

private fun continuousAudio(o: JsonObject): ServerEvent {
  val a =
      when (val d = o["audio_delta"]) {
        null,
        JsonNull -> return ServerEvent.Other
        is JsonObject -> d
        else -> return ServerEvent.Malformed("audio delta")
      }
  val format = a["format"]
  if (format != null && format != JsonNull) {
    if (format !is JsonObject) return ServerEvent.Malformed("audio format")
    val kind = format["type"]
    if (kind != null && kind.string() !in setOf("", "audio/pcm") || !format["rate"].equalsInt(CALL_RATE)) {
      return ServerEvent.Malformed("audio format")
    }
  }
  if ("num_channels" in a && !a["num_channels"].equalsInt(1)) return ServerEvent.Malformed("audio channels")
  return audio(a["audio_frames"], identifier(o["response_id"]))
}

private fun audio(encoded: JsonElement?, responseId: String?): ServerEvent {
  val s = encoded.string() ?: return ServerEvent.Malformed("audio data")
  if (s.length > (MAX_AUDIO_BYTES + 2) / 3 * 4) return ServerEvent.Malformed("audio too long")
  // The SDK's b64decode(validate=True): the alphabet, padding at the end only, and whole quanta.
  if (s.length % 4 != 0 || !BASE64.matches(s)) return ServerEvent.Malformed("audio base64")
  val pcm = Base64.getDecoder().decode(s)
  if (pcm.size % 2 != 0 || pcm.size > MAX_AUDIO_BYTES) return ServerEvent.Malformed("audio length")
  return if (pcm.isEmpty()) ServerEvent.Other else ServerEvent.Audio(pcm, responseId)
}

/**
 * Splits a fragmented UTF-8 NDJSON answer into objects, bounded per line. LF ends every record, the
 * last one too; CRLF and blank lines pass. Anything else malformed throws.
 */
internal class NdjsonSplitter(private val maxLine: Int = MAX_LINE) {
  private val pending = ByteArrayOutputStream()

  fun feed(chunk: ByteArray): List<JsonObject> {
    val out = ArrayList<JsonObject>()
    var at = 0
    while (at < chunk.size) {
      var nl = at
      while (nl < chunk.size && chunk[nl] != '\n'.code.toByte()) nl++
      val end = if (nl < chunk.size) nl + 1 else chunk.size
      if (pending.size() + end - at > maxLine) throw MuseException("muse: a voice record is too long")
      pending.write(chunk, at, end - at)
      at = end
      if (nl < chunk.size) {
        val line = pending.toByteArray()
        pending.reset()
        if (line.all { it.toInt().toChar() in " \t\n\r\u000b\u000c" }) continue
        if (!isValidUtf8(line)) throw MuseException("muse: a voice record is not UTF-8")
        val record =
            try {
              Json.parseToJsonElement(String(line, Charsets.UTF_8))
            } catch (e: Exception) {
              throw MuseException("muse: a voice record is not JSON")
            }
        out += record as? JsonObject ?: throw MuseException("muse: a voice record is not an object")
      }
    }
    return out
  }

  /** Throws if the answer ended inside a record. */
  fun finish() {
    if (pending.size() > 0) throw MuseException("muse: the voice answer ended inside a record")
  }
}

/** One line of the call's body. */
internal sealed interface ClientLine {
  class Start(val callId: String, val nodeId: String) : ClientLine

  /** Says this client ends the call on a session_control stop, so Muse may hang up after a goodbye. */
  data object Offer : ClientLine

  class Append(val pcm: ByteArray) : ClientLine

  class Cancel(val responseId: String) : ClientLine

  /** Compact JSON and a newline. [wallMs] stamps an append. */
  fun encode(wallMs: Long): ByteArray {
    val o =
        when (this) {
          is Start ->
              buildJsonObject {
                put("type", "hatch.voice.call.start")
                put("call_id", callId)
                put("device", "mcu-wearable")
                put("device_id", nodeId)
                put("call_media_type", "audio")
                put("proactive_greeting_enabled", false)
              }
          Offer ->
              buildJsonObject {
                put("type", "hatch.voice.capability.offer")
                put("end_call_stop", 1)
              }
          is Append ->
              buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", Base64.getEncoder().encodeToString(pcm))
                put("send_timestamp_ms", wallMs)
              }
          is Cancel ->
              buildJsonObject {
                put("type", "response.cancel")
                put("response_id", responseId)
              }
        }
    return (o.toString() + "\n").toByteArray(Charsets.UTF_8)
  }
}

private val ISO_MS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx").withZone(ZoneOffset.UTC)

/** The end-call body, with Python's isoformat(timespec="milliseconds") times; the end is at least a millisecond after the start. */
internal fun endCallBody(callId: String, startedMs: Long, endedMs: Long, threadId: String?): ByteArray =
    buildJsonObject {
          put("call_id", callId)
          put("call_started_at", ISO_MS.format(Instant.ofEpochMilli(startedMs)))
          put("call_ended_at", ISO_MS.format(Instant.ofEpochMilli(maxOf(endedMs, startedMs + 1))))
          if (threadId != null) put("thread_id", threadId)
        }
        .toString()
        .toByteArray(Charsets.UTF_8)

private fun JsonElement?.isTrue() = this is JsonPrimitive && !isString && content == "true"

/** A 2xx whose body says {"ok": true, "result": {"ok": true}}. */
internal fun endCallAcknowledged(r: Buffered): Boolean {
  if (r.status !in 200..299) return false
  val body = parseObject(String(r.body, Charsets.UTF_8)) ?: return false
  return body["ok"].isTrue() && (body["result"] as? JsonObject)?.get("ok").isTrue()
}
