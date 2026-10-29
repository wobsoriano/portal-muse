package dev.wobsoriano.muse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodecTest {
  @Test
  fun identityNames() {
    val id = Identity.new()
    assertTrue("Identity.new made ${id.mac}", id.valid)
    // Unicast and locally administered, as the SDK's generate_mac makes them.
    assertEquals("first octet of ${id.mac}", 0x02, id.mac.substring(0, 2).toInt(16) and 0x03)
    assertTrue(id.nodeId, Regex("^homelink-[0-9a-f]{6}$").matches(id.nodeId))
    assertTrue(id.bleName, Regex("^MuseGadget[0-9A-F]{6}$").matches(id.bleName))

    val fixed = Identity("02:11:22:a1:b2:c3")
    assertEquals("homelink-a1b2c3", fixed.nodeId)
    assertEquals("MuseGadgetA1B2C3", fixed.bleName)
    assertEquals("hatch-link:02:11:22:a1:b2:c3", fixed.deviceId)
    for (bad in listOf("", "02:11:22:A1:B2:C3", "02:11:22:a1:b2", "021122a1b2c3")) {
      assertFalse("$bad passed as an identity", Identity(bad).valid)
    }
  }

  @Test
  fun stateJson() {
    val st =
        State(
            identity = Identity("02:11:22:a1:b2:c3"),
            credentials =
                Credentials(
                    accessToken = "a",
                    refreshToken = "r",
                    tokenType = "device",
                    username = "u",
                    apiUrl = "https://old",
                    apiUrlV2 = "https://new",
                    noiseHost = "h",
                    accessTokenSavedAt = 7),
            sdkTokenReported = true,
        )
    val compact = kotlinx.serialization.json.Json { explicitNulls = false }
    val b = compact.encodeToString(State.serializer(), st)
    val want =
        """{"identity":{"mac":"02:11:22:a1:b2:c3"},"credentials":{"access_token":"a","refresh_token":"r","token_type":"device","username":"u","api_url":"https://old","api_url_v2":"https://new","noise_host":"h","access_token_saved_at":7},"sdk_token_reported":true}"""
    assertEquals(want, b)
    assertEquals(st, stateJson.decodeFromString(State.serializer(), b))
  }

  @Test
  fun frameRoundTrip() {
    for (f in
        listOf(
            Frame(stream = 1, kind = FrameKind.Request, verb = "POST", path = "/link-control"),
            Frame(
                stream = 2,
                kind = FrameKind.Request,
                verb = "POST",
                path = "/chat/stream",
                headers = listOf(Header("x-app-id", "musegadget"), Header("empty", "")),
                data = "{}".toByteArray(),
                endBody = true),
            Frame(stream = 3, kind = FrameKind.Response, status = 200, headers = listOf(Header("a", "b")), data = "ok".toByteArray(), endBody = true),
            Frame(stream = 3, kind = FrameKind.Response, status = 403),
            Frame(stream = 1L shl 40, kind = FrameKind.Body, data = byteArrayOf(0, 1, 2)),
            Frame(stream = 4, kind = FrameKind.Body, endBody = true),
            Frame(stream = 5, kind = FrameKind.Reset, code = RESET_CANCELED, reason = "canceled"),
            Frame(stream = 6, kind = FrameKind.Reset),
        )) {
      assertEquals(f, decodeFrame(encodeFrame(f)))
    }
  }

  // The bytes the SDK's encode_service_request and encode_response_envelope produce for these
  // frames, worked out from its field numbers.
  @Test
  fun envelopeBytes() {
    val request = encodeServiceRequest(Frame(stream = 1, kind = FrameKind.Request, verb = "POST", path = "/x", endBody = true))
    val want =
        byteArrayOf(
            0x12, 0x10, // ServiceRequest.payload
            0x08, 0x01, // ServiceFrame.stream_id
            0x12, 0x0c, // ServiceFrame.request
            0x0a, 0x04, 'P'.code.toByte(), 'O'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
            0x12, 0x02, '/'.code.toByte(), 'x'.code.toByte(),
            0x28, 0x01, // end_body
        )
    assertArrayEquals(want, request)

    val response =
        byteArrayOf(
            0x0a, 0x0b, // ServiceResponse.payload
            0x08, 0x07, // stream_id
            0x1a, 0x07, // ServiceFrame.response
            0x08, 0xc8.toByte(), 0x01, // status 200
            0x1a, 0x02, 'h'.code.toByte(), 'i'.code.toByte(),
        )
    assertEquals(Frame(stream = 7, kind = FrameKind.Response, status = 200, data = "hi".toByteArray()), decodeServiceResponse(response))
    assertFails("a request from the VM was accepted") {
      decodeServiceResponse(ProtoWriter().bytesField(1, request.copyOfRange(2, request.size)).toByteArray())
    }
    assertFails("an empty envelope was accepted") { decodeServiceResponse(EMPTY) }
  }

  @Test
  fun malformedProtoIsRefused() {
    for ((name, b) in
        mapOf(
            "truncated varint" to bytes(0x08, 0x80),
            "truncated bytes" to bytes(0x12, 0x05, 'a'.code),
            "field zero" to bytes(0x00, 0x00),
            "wrong wire type" to bytes(0x0a, 0x01, 0x00),
            "group wire type" to bytes(0x0b),
            "overlong varint" to bytes(0x08, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f),
            "bad utf8 in a verb" to bytes(0x12, 0x03, 0x0a, 0x01, 0xff),
        )) {
      assertFails("$name: decoded") { decodeFrame(b) }
    }
  }

  @Test
  fun chunksReassemble() {
    val envelope = ByteArray(3 * MAX_CHUNK_PAYLOAD + 17) { i -> (i shr 3).toByte() }
    val pieces = chunk(envelope)
    assertEquals(4, pieces.size)
    val small = chunk("one".toByteArray())
    assertEquals(1, small.size)

    // Another message's chunk between them, and the pieces out of order.
    var a = Assembler()
    for (i in listOf(2, 0)) assertNull("piece $i", a.add(pieces[i]))
    assertEquals("one", String(checkNotNull(a.add(small[0]))))
    assertNull(a.add(pieces[3]))
    assertArrayEquals(envelope, a.add(pieces[1]))

    // A repeated piece is malformed, and nothing after it is trusted.
    a = Assembler()
    a.add(pieces[0])
    assertFails("a repeated piece was accepted") { a.add(pieces[0]) }
    assertFails("the assembler carried on after a malformed piece") { a.add(small[0]) }
    assertFails("an oversized message was chunked") { chunk(ByteArray(MAX_TOTAL_CHUNKS * MAX_CHUNK_PAYLOAD + 1)) }
  }

  @Test
  fun controlMessagesSpanChunks() {
    val first = encodeControl(buildJsonObject {
      put("method", "link.invoke")
      put("id", "a")
      put("command", "echo")
    })
    val second = encodeControl(buildJsonObject {
      put("type", "evt")
      put("event", "link.unpaired")
    })
    val stream = first + byteArrayOf(0, 0, 0, 0) + second + byteArrayOf(5, 0, 0, 0) + "nope!".toByteArray()

    val d = ControlDecoder()
    val got = mutableListOf<ControlMessage>()
    for (i in stream.indices step 7) got += d.feed(stream.copyOfRange(i, minOf(stream.size, i + 7)))
    // The empty message is a keepalive and the one that is not JSON is dropped.
    assertEquals(2, got.size)
    assertEquals("echo", rawString(got[0].command))
    assertEquals("link.unpaired", rawString(got[1].event))
    assertFails("an oversized message was accepted") { ControlDecoder().feed(bytes(0xff, 0xff, 0xff, 0x7f)) }
  }

  @Test
  fun noiseUrl() {
    data class Case(val host: String, val vm: String, val plaintext: Boolean, val want: String)
    for (tc in
        listOf(
            Case("hatch.metaaivm.com", "vm 1", false, "wss://hatch.metaaivm.com/v1/noise?vm_id=vm%201"),
            Case("wss://host:8443", "a/b+c&d=é", false, "wss://host:8443/v1/noise?vm_id=a%2Fb%2Bc%26d%3D%C3%A9"),
            Case("host", "it's~(ok)!*._-", false, "wss://host/v1/noise?vm_id=it's~(ok)!*._-"),
            Case("ws://127.0.0.1:9", "v", true, "ws://127.0.0.1:9/v1/noise?vm_id=v"),
            Case("ws://127.0.0.1:9", "v", false, ""),
            Case("host/elsewhere", "v", false, ""),
            Case("user@host", "v", false, ""),
            Case("", "v", false, ""),
        )) {
      val got = runCatching { noiseUrl(tc.host, tc.vm, tc.plaintext) }
      assertEquals("noiseUrl(${tc.host}, ${tc.vm})", tc.want, got.getOrDefault(""))
      assertEquals(tc.want.isNotEmpty(), got.isSuccess)
    }
  }

  // The voice connection names its app in the upgrade query, as link_client.noise_url does.
  @Test
  fun noiseUrlAppId() {
    for ((app, want) in
        listOf(
            "hatch-web" to "wss://host/v1/noise?vm_id=vm%201&app_id=hatch-web",
            "1" to "wss://host/v1/noise?vm_id=vm%201&app_id=1",
            "9223372036854775807" to "wss://host/v1/noise?vm_id=vm%201&app_id=9223372036854775807",
            "007" to "wss://host/v1/noise?vm_id=vm%201&app_id=7",
            "0" to "",
            "-1" to "",
            "+5" to "",
            "9223372036854775808" to "",
            "00000000000000000001" to "",
            "1.0" to "",
            "١٢" to "",
            "hatch" to "",
            "" to "",
        )) {
      val got = runCatching { noiseUrl("host", "vm 1", false, app) }
      assertEquals("noiseUrl(app_id=$app)", want, got.getOrDefault(""))
      assertEquals(want.isNotEmpty(), got.isSuccess)
    }
  }

  @Test
  fun apiRootIsHttps() {
    for ((v2, want) in listOf("" to "https://api.muse.ai", "https://api.example/" to "https://api.example", "http://api.example" to "", "api.example" to "")) {
      // api_url is never consulted, whatever it holds.
      val got = runCatching { apiRoot(Credentials(apiUrl = "https://old.example/hatch", apiUrlV2 = v2), false) }
      assertEquals("apiRoot($v2)", want, got.getOrDefault(""))
      assertEquals(want.isNotEmpty(), got.isSuccess)
    }
  }

  private fun event(name: String, payload: JsonObject): ChatEvent =
      checkNotNull(ChatEvent.parse(buildJsonObject {
        put("type", "event")
        put("event", name)
        put("payload", payload)
      }.toString()))

  private fun payload(vararg fields: Pair<String, Any>) =
      JsonObject(
          fields.associate { (k, v) ->
            k to
                when (v) {
                  is String -> JsonPrimitive(v)
                  is Boolean -> JsonPrimitive(v)
                  else -> error(v)
                }
          })

  // The rules by which a turn picks out its own reply.
  @Test
  fun turnMatching() {
    val tn = Turn()
    // Before the acknowledgment: someone else's message, then our echo, then the reply starts.
    tn.add(event("delta.message_start", payload("message_id" to "other", "reply_to_message_id" to "")))
    tn.add(event("delta.text_append", payload("message_id" to "other", "text" to "not ours")))
    tn.add(event("message.user", payload("message_id" to "someone-else", "display_text" to "not ours either")))
    tn.add(event("message.user", payload("message_id" to "u1", "display_text" to "half", "display_text_ready" to false)))
    tn.add(event("message.user", payload("message_id" to "u1", "display_text" to "what I said")))
    tn.add(event("delta.message_start", payload("message_id" to "r1", "reply_to_message_id" to "")))
    tn.add(event("delta.text_append", payload("message_id" to "r1", "parent_message_id" to "r1", "text" to "Hel")))
    assertTrue("matched before the acknowledgment: ${tn.text()}", tn.text() == "" && !tn.whole())
    tn.acknowledged("u1")
    assertEquals("Hel", tn.text())
    assertEquals("what I said", tn.heard)
    assertFalse(tn.whole())

    tn.add(event("agent.status", payload("activity_code" to "working")))
    tn.add(event("delta.text_append", payload("message_id" to "r1", "parent_message_id" to "r1", "text" to "lo")))
    tn.add(event("delta.text_append", payload("message_id" to "never-started", "text" to "stray")))
    tn.add(event("delta.message_done", payload("message_id" to "r1", "status" to "completed")))
    assertEquals("Hello", tn.text())
    assertFalse("done but Muse still working", tn.whole())
    // A second message, linked by naming the first, extends the answer.
    tn.add(event("delta.message_start", payload("message_id" to "r2", "reply_to_message_id" to "r1")))
    tn.add(event("agent.status", payload("activity_code" to "idle")))
    assertFalse("whole with a message still open", tn.whole())
    // A stored message replaces the streamed text, and is done unless it says otherwise.
    tn.add(event("message.assistant", payload("message_id" to "r2", "display_text" to "draft", "display_text_ready" to false)))
    assertFalse("whole on a draft", tn.whole())
    tn.add(event("message.assistant", payload("message_id" to "r2", "content" to "And more.")))
    // One that names a parent this turn does not know is someone else's.
    tn.add(event("delta.message_start", payload("message_id" to "r3", "reply_to_message_id" to "elsewhere")))
    assertEquals("Hello\n\nAnd more.", tn.text())
    assertTrue(tn.whole())

    tn.add(event("task.status", payload("status" to "running")))
    assertFalse("whole while a task runs", tn.whole())
    tn.add(event("task.status", payload("status" to "completed")))
    assertTrue("not whole after the task completed", tn.whole())
  }

  // When the echo comes after the acknowledgment, any new parentless message follows ours.
  @Test
  fun turnMatchingAfterTheAcknowledgment() {
    val tn = Turn()
    tn.add(event("delta.message_start", payload("message_id" to "before", "reply_to_message_id" to "")))
    tn.acknowledged("u1")
    tn.add(event("delta.message_start", payload("message_id" to "r1", "reply_to_message_id" to "")))
    tn.add(event("delta.text_append", payload("message_id" to "before", "text" to "no")))
    tn.add(event("delta.text_append", payload("message_id" to "r1", "text" to "yes")))
    tn.add(event("delta.message_done", payload("message_id" to "r1")))
    assertEquals("yes", tn.text())
    assertTrue(tn.whole())
  }

  @Test
  fun transcriptLeavesOutTheRecordingLine() {
    assertEquals("What is 2 plus 2?", transcript("What is 2 plus 2?\n[file:audio/wav workspace/user/files/voice_note-64.wav]"))
    assertEquals("", transcript("[file:audio/wav x.wav]"))
  }

  @Test
  fun utf8IsCheckedAsGoChecksIt() {
    assertTrue(isValidUtf8("é ü 日本 😀".toByteArray()))
    for (bad in listOf(bytes(0xff), bytes(0xc0, 0x80), bytes(0xed, 0xa0, 0x80), bytes(0xe2, 0x82), bytes(0xf4, 0x90, 0x80, 0x80))) {
      assertFalse(bad.hex(), isValidUtf8(bad))
    }
  }

  private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
}

fun assertFails(message: String, block: () -> Unit) {
  try {
    block()
  } catch (e: Exception) {
    return
  }
  fail(message)
}
