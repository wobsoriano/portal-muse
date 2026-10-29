// Ported from linux/src/musegadget/streaming.py in Meta's Muse Gadget SDK (PR 275), Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the root
// of this module). Modified: rewritten in Kotlin, with the answer handed over a channel.

package dev.wobsoriano.muse

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

internal const val MAX_INBOUND_CHUNKS = 256
internal const val MAX_INBOUND_BYTES = 2 shl 20
internal const val MAX_SEND_BYTES = 64 shl 10

/** A stream that broke on its own, without taking the session down. [why] is our own text. */
internal class StreamFailed(val why: String) : MuseException("muse: stream: $why")

/** A request answered whole. */
internal class Buffered(val status: Int, val body: ByteArray)

/** One stream's answer, in order: a Head, Chunks, an End. A failure closes the channel with a [StreamFailed] instead. */
internal sealed interface Inbound {
  class Head(val status: Int) : Inbound

  /** [release] gives its bytes back to the stream's budget once they have been read. */
  class Chunk(val data: ByteArray, private val sink: DuplexSink) : Inbound {
    private var released = false

    fun release() {
      if (released) return
      released = true
      sink.released(data.size)
    }
  }

  data object End : Inbound
}

/**
 * Takes one stream's answer on the reader, with the session's lock held, so it only checks, counts and
 * enqueues: a consumer that falls behind (256 chunks or 2 MiB unread) fails its own stream, never the
 * reader. A body before the head, a second head, a status outside 100..599 or anything after the end
 * fails the stream too, as do a reset and the end of the connection.
 */
internal class DuplexSink : Sink {
  val inbox = Channel<Inbound>(MAX_INBOUND_CHUNKS + 2)
  private val chunks = AtomicInteger()
  private val bytes = AtomicInteger()
  private var head = false
  @Volatile var ended = false
    private set

  @Volatile var failed = false
    private set

  override fun onFrame(f: Frame) {
    if (failed) return
    if (ended) return fail("data after the end")
    when (f.kind) {
      FrameKind.Reset -> return fail("reset")
      FrameKind.Response -> {
        if (head || f.status !in 100..599) return fail("bad or second head")
        head = true
        inbox.trySend(Inbound.Head(f.status))
      }
      else -> if (f.kind != FrameKind.Body || !head) return fail("body before head")
    }
    if (f.data.isNotEmpty()) {
      if (chunks.get() >= MAX_INBOUND_CHUNKS || bytes.get() + f.data.size > MAX_INBOUND_BYTES) return fail("overflow")
      chunks.incrementAndGet()
      bytes.addAndGet(f.data.size)
      inbox.trySend(Inbound.Chunk(f.data, this))
    }
    if (f.endBody) {
      ended = true
      inbox.trySend(Inbound.End)
    }
  }

  override fun onClosed(why: String) = fail(why)

  private fun fail(why: String) {
    failed = true
    inbox.close(StreamFailed(why))
  }

  fun released(n: Int) {
    chunks.decrementAndGet()
    bytes.addAndGet(-n)
  }
}

/**
 * One request whose body and answer run at once. Its sending side belongs to whoever opened it, and the
 * answer arrives on [inbound]. Not safe for concurrent use.
 */
internal class DuplexStream(private val session: Session, val id: Long, private val sink: DuplexSink, private var uploaded: Boolean) {
  private var closed = false

  val inbound: ReceiveChannel<Inbound>
    get() = sink.inbox

  /** Bytes the connection has queued and not yet written; a live sender sheds above a bound instead of queueing. */
  val backlog: Long
    get() = session.backlog

  /** One body chunk of at most [MAX_SEND_BYTES]. Throws MuseException once the stream, its upload or the connection is over. */
  fun send(data: ByteArray) {
    require(data.size <= MAX_SEND_BYTES) { "muse: a body chunk is at most 64 KiB" }
    if (closed || uploaded) throw StreamFailed("the upload is closed")
    if (sink.failed) throw StreamFailed("the answer failed")
    session.write(Frame(stream = id, kind = FrameKind.Body, data = data))
  }

  /** Ends the upload, as an empty chunk with endBody: the wire has no half-close of its own. Idempotent. */
  fun halfClose() {
    if (closed || uploaded) return
    uploaded = true
    session.write(Frame(stream = id, kind = FrameKind.Body, endBody = true))
  }

  /** Forgets the stream, resetting it first if [reset], by default unless both directions are over. Idempotent, and never throws. */
  fun close(reset: Boolean = !(uploaded && sink.ended)) {
    if (closed) return
    closed = true
    session.forget(id)
    if (!reset) return
    try {
      session.write(Frame(stream = id, kind = FrameKind.Reset, code = RESET_CANCELED, reason = "client closed stream"))
    } catch (_: MuseException) {}
  }
}
