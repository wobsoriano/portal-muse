package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.Buffered
import dev.wobsoriano.muse.MuseException
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

/** The parsed event, written out so a table can say what each line must parse to. */
private fun said(e: ServerEvent): String =
    when (e) {
      is ServerEvent.Session -> "session call=${e.callId} session=${e.sessionId} thread=${e.threadId}"
      is ServerEvent.Hold -> "hold ${e.held}"
      is ServerEvent.SpeechStarted -> "speech ${e.itemId}"
      is ServerEvent.TurnStarted -> "turn ${e.submissionId} ${e.itemId}"
      is ServerEvent.TurnSettled -> "settled ${e.responseMessageId}"
      is ServerEvent.InputSuppressed -> "suppressed ${e.itemId}"
      is ServerEvent.ResponseCreated -> "created ${e.id}"
      is ServerEvent.ResponseEnded -> "ended ${e.id}"
      is ServerEvent.ReplyText -> "reply text ${e.delta}"
      is ServerEvent.ReplyDone -> "reply done ${e.transcript}"
      is ServerEvent.Heard -> "heard ${e.transcript}"
      ServerEvent.Stop -> "stop"
      is ServerEvent.Audio -> "audio ${e.pcm.size} ${e.responseId}"
      is ServerEvent.Error -> "error ${e.code}"
      ServerEvent.Other -> "other"
      is ServerEvent.Malformed -> "malformed"
    }

class WireTest {
  @Test
  fun recordsAreSplitAtNewlinesWhateverTheChunks() {
    val text = "{\"type\":\"a\",\"t\":\"é😀\"}\r\n\n  \n{\"type\":\"b\"}\n"
    val bytes = text.toByteArray()
    // One byte at a time splits every line, and the UTF-8 of é and the emoji, at every point.
    val s = NdjsonSplitter()
    val got = bytes.flatMap { s.feed(byteArrayOf(it)) }
    s.finish()
    assertEquals(listOf(obj("""{"type":"a","t":"é😀"}"""), obj("""{"type":"b"}""")), got)
    assertEquals(got, NdjsonSplitter().feed(bytes))
  }

  @Test
  fun badRecordsThrow() {
    for ((name, line) in
        listOf(
            "not JSON" to "{\"type\":}\n".toByteArray(),
            "an array" to "[1]\n".toByteArray(),
            "a number" to "5\n".toByteArray(),
            "bad UTF-8" to byteArrayOf('{'.code.toByte(), '"'.code.toByte(), 0xC3.toByte(), '"'.code.toByte(), ':'.code.toByte(), '1'.code.toByte(), '}'.code.toByte(), '\n'.code.toByte()),
        )) {
      assertTrue("$name was taken", runCatching { NdjsonSplitter().feed(line) }.exceptionOrNull() is MuseException)
    }
    for (tail in listOf("{\"type\":\"a\"}", " ")) {
      val s = NdjsonSplitter()
      s.feed(tail.toByteArray())
      assertTrue("an answer ending in \"$tail\" finished", runCatching { s.finish() }.exceptionOrNull() is MuseException)
    }
  }

  @Test
  fun aLineIsAtMostAMebibyte() {
    val pad = "x".repeat(MAX_LINE - "{\"p\":\"\"}\n".length)
    val fits = "{\"p\":\"$pad\"}\n".toByteArray()
    assertEquals(MAX_LINE, fits.size)
    val s = NdjsonSplitter()
    assertEquals(1, (s.feed(fits.copyOfRange(0, 1000)) + s.feed(fits.copyOfRange(1000, fits.size))).size)
    val over = "{\"p\":\"${pad}y\"}\n".toByteArray()
    val t = NdjsonSplitter()
    assertTrue("a longer line was taken", runCatching { t.feed(over.copyOfRange(0, 1000)); t.feed(over.copyOfRange(1000, over.size)) }.exceptionOrNull() is MuseException)
  }

  @Test
  fun eventsParseAsTheSdkReadsThem() {
    val pcm = ByteArray(1920) { (it % 50).toByte() }
    val a = b64(pcm)
    for ((line, want) in
        listOf(
            """{"type":"hatch.voice.session","call_id":"c1","session_id":"s1","thread_id":"t1"}""" to "session call=c1 session=s1 thread=t1",
            """{"type":"hatch.voice.session","call_id":"c1","session_id":"s1","thread_id":null}""" to "session call=c1 session=s1 thread=null",
            """{"type":"hatch.voice.session","call_id":7,"session_id":"s1"}""" to "session call=null session=s1 thread=null",
            """{"type":"hatch.voice.session","call_id":"c1","session_id":" "}""" to "malformed",
            """{"type":"hatch.voice.session","call_id":"c1"}""" to "malformed",
            """{"type":"hatch.voice.session","call_id":"c1","session_id":"s1","thread_id":""}""" to "malformed",
            """{"type":"hatch.voice.session","call_id":"c1","session_id":"s1","thread_id":5}""" to "malformed",
            """{"type":"hatch.voice.switch_input_hold","held":true}""" to "hold true",
            """{"type":"hatch.voice.switch_input_hold","held":false}""" to "hold false",
            """{"type":"hatch.voice.switch_input_hold","held":"true"}""" to "malformed",
            """{"type":"hatch.voice.switch_input_hold"}""" to "malformed",
            """{"type":"input_audio_buffer.speech_started","item_id":"i1"}""" to "speech i1",
            """{"type":"response.output_audio_transcript.delta","delta":"Hi "}""" to "reply text Hi ",
            """{"type":"response.output_audio_transcript.delta","delta":5}""" to "other",
            """{"type":"response.output_audio_transcript.done","transcript":"Hi there."}""" to "reply done Hi there.",
            """{"type":"conversation.item.input_audio_transcription.completed","transcript":"what time is it"}""" to "heard what time is it",
            """{"type":"conversation.item.input_audio_transcription.completed"}""" to "other",
            """{"type":"input_audio_buffer.speech_started","item_id":""}""" to "speech null",
            """{"type":"hatch.voice.turn_started","submission_message_id":"m1","item_id":"i1"}""" to "turn m1 i1",
            """{"type":"hatch.voice.turn_started","submission_message_id":"m1"}""" to "malformed",
            """{"type":"hatch.voice.turn_settled","response_message_id":"m1"}""" to "settled m1",
            """{"type":"hatch.voice.turn_settled"}""" to "settled null",
            """{"type":"hatch.voice.input_suppressed","item_id":"i1"}""" to "suppressed i1",
            """{"type":"response.created","response":{"id":"r1"}}""" to "created r1",
            """{"type":"response.created","response":"r1"}""" to "created null",
            """{"type":"response.done","response":{"id":"r1","status":"cancelled"}}""" to "ended r1",
            """{"type":"response.completed","response":{"id":"r1"}}""" to "ended r1",
            """{"type":"conversation.item.added","item":{"type":"session_control","name":" Stop ","call_id":"ctl-1"}}""" to "stop",
            """{"type":"conversation.item.added","item":{"type":"session_control","name":"stop","call_id":""}}""" to "other",
            """{"type":"conversation.item.added","item":{"type":"session_control","name":"stop"}}""" to "other",
            """{"type":"conversation.item.added","item":{"type":"session_control","name":"pause","call_id":"ctl-1"}}""" to "other",
            """{"type":"conversation.item.added","item":{"type":"message","name":"stop","call_id":"ctl-1"}}""" to "other",
            """{"type":"conversation.item.added","item":{"type":"session_control","name":"stop","call_id":"${"c".repeat(129)}"}}""" to "other",
            """{"type":"error","error":{"code":"session_dead","message":"Bearer secret"}}""" to "error session_dead",
            """{"type":"error","error":{"code":"Has Spaces","message":"x"}}""" to "error null",
            """{"type":"error","error":"boom"}""" to "malformed",
            """{"type":"error"}""" to "malformed",
            """{"type":"session.created","session":{"id":"synthetic"}}""" to "other",
            """{"type":"future.synthetic.event","extra":{"ignored":true}}""" to "other",
            """{"no":"type"}""" to "malformed",
            """{"type":5}""" to "malformed",
            // Audio: continuous carries the provider's response id, the legacy deltas none.
            """{"type":"continuous_audio_video.delta","response_id":"r1","audio_delta":{"audio_frames":"$a","format":{"type":"","rate":24000}}}""" to "audio 1920 r1",
            """{"type":"continuous_audio_video.delta","response_id":"r1","audio_delta":{"audio_frames":"$a","format":{"type":"audio/pcm","rate":24000.0},"num_channels":1}}""" to "audio 1920 r1",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":null}}""" to "audio 1920 null",
            """{"type":"continuous_audio_video.delta","response_id":"r1"}""" to "other",
            """{"type":"continuous_audio_video.delta","audio_delta":null}""" to "other",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":""}}""" to "other",
            """{"type":"continuous_audio_video.delta","audio_delta":"x"}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":{"rate":16000}}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":{"type":"audio/opus","rate":24000}}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":{"type":null,"rate":24000}}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":{"type":""}}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","format":"pcm"}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","num_channels":2}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":"$a","num_channels":null}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{"audio_frames":5}}""" to "malformed",
            """{"type":"continuous_audio_video.delta","audio_delta":{}}""" to "malformed",
            """{"type":"response.output_audio.delta","response_id":"r1","delta":"$a"}""" to "audio 1920 null",
            """{"type":"response.audio.delta","delta":"$a"}""" to "audio 1920 null",
            """{"type":"response.audio.delta"}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AAA"}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AA"}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AA=A"}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AA AA=="}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AA-_"}""" to "malformed",
            """{"type":"response.audio.delta","delta":"AAA="}""" to "audio 2 null",
            """{"type":"response.audio.delta","delta":"AA=="}""" to "malformed",
        )) {
      assertEquals(line, want, said(parseServerEvent(obj(line))))
    }
  }

  @Test
  fun anEventCarriesAtMostFourSecondsOfAudio() {
    val most = ByteArray(MAX_AUDIO_BYTES) { 1 }
    val e = parseServerEvent(obj("""{"type":"response.audio.delta","delta":"${b64(most)}"}"""))
    assertArrayEquals(most, (e as ServerEvent.Audio).pcm)
    val over = ByteArray(MAX_AUDIO_BYTES + 2)
    assertEquals("malformed", said(parseServerEvent(obj("""{"type":"response.audio.delta","delta":"${b64(over)}"}"""))))
  }

  @Test
  fun identifiers() {
    for ((v, want) in
        listOf(
            JsonPrimitive("a") to "a",
            JsonPrimitive("x".repeat(128)) to "x".repeat(128),
            JsonPrimitive("x".repeat(129)) to null,
            // 42 three-byte characters are 126 bytes; one more is 129.
            JsonPrimitive("€".repeat(42)) to "€".repeat(42),
            JsonPrimitive("€".repeat(43)) to null,
            JsonPrimitive("") to null,
            JsonPrimitive("a\u0000b") to null,
            JsonPrimitive("\ud800") to null,
            JsonPrimitive(5) to null,
        )) {
      assertEquals("identifier($v)", want, identifier(v))
    }
    assertNull(identifier(null))
  }

  @Test
  fun clientLines() {
    assertEquals(
        """{"type":"hatch.voice.call.start","call_id":"c1","device":"mcu-wearable","device_id":"homelink-a1b2c3","call_media_type":"audio","proactive_greeting_enabled":false}""" + "\n",
        String(ClientLine.Start("c1", "homelink-a1b2c3").encode(0)))
    assertEquals("""{"type":"hatch.voice.capability.offer","end_call_stop":1}""" + "\n", String(ClientLine.Offer.encode(0)))
    assertEquals(
        """{"type":"input_audio_buffer.append","audio":"AQID","send_timestamp_ms":1761590096789}""" + "\n",
        String(ClientLine.Append(byteArrayOf(1, 2, 3)).encode(1761590096789)))
    assertEquals("""{"type":"response.cancel","response_id":"r1"}""" + "\n", String(ClientLine.Cancel("r1").encode(0)))
  }

  @Test
  fun endCall() {
    // 2025-10-27T18:34:56.789Z, as Python's isoformat writes it, and an end before the start becomes a millisecond after it.
    val started = 1761590096789L
    assertEquals(
        obj("""{"call_id":"c1","call_started_at":"2025-10-27T18:34:56.789+00:00","call_ended_at":"2025-10-27T18:34:56.790+00:00","thread_id":"t1"}"""),
        obj(String(endCallBody("c1", started, started - 5000, "t1"))))
    assertEquals(
        obj("""{"call_id":"c1","call_started_at":"2025-10-27T18:34:56.789+00:00","call_ended_at":"2025-10-27T18:35:06.000+00:00"}"""),
        obj(String(endCallBody("c1", started, started + 9211, null))))

    for ((status, body, want) in
        listOf(
            Triple(200, """{"ok":true,"result":{"ok":true}}""", true),
            Triple(204, """{"ok":true,"result":{"ok":true,"call_id":"c1"}}""", true),
            Triple(500, """{"ok":true,"result":{"ok":true}}""", false),
            Triple(200, """{"ok":false,"result":{"ok":true}}""", false),
            Triple(200, """{"ok":true}""", false),
            Triple(200, """{"ok":true,"result":{"ok":false}}""", false),
            Triple(200, """{"ok":"true","result":{"ok":true}}""", false),
            Triple(200, """{"ok":1,"result":{"ok":true}}""", false),
            Triple(200, """{"ok":true,"response":{"ok":true,"result":{"ok":true}}}""", false),
            Triple(200, "", false),
            Triple(200, "not json", false),
        )) {
      assertEquals("$status $body", want, endCallAcknowledged(Buffered(status, body.toByteArray())))
    }
  }

  @Test
  fun aRecordsKindNamesItsTypesOnly() {
    assertEquals("response.done", recordKind(obj("""{"type":"response.done","response":{"id":"r1"}}""")))
    assertEquals("conversation.item.added:session_control:stop", recordKind(obj("""{"type":"conversation.item.added","item":{"type":"session_control","name":"stop","call_id":"x"}}""")))
    assertEquals("conversation.item.added:message", recordKind(obj("""{"type":"conversation.item.added","item":{"type":"message","content":"hello there"}}""")))
    assertEquals("what was said never shows", "?:?:?", recordKind(obj("""{"type":"I said hi","item":{"type":"Hi Muse","name":"my name is Rob"}}""")))
  }

  @Test
  fun shortCodes() {
    assertEquals("not_found", shortCode(JsonPrimitive("not_found")))
    assertEquals("v1.e-2", shortCode(JsonPrimitive("v1.e-2")))
    for (bad in listOf("", "Bearer abc", "UPPER", "x".repeat(65), "a\nb")) assertNull(bad, shortCode(JsonPrimitive(bad)))
    assertNull(shortCode(JsonPrimitive(404)))
    assertFalse(shortCode(JsonObject(emptyMap())) != null)
  }
}
