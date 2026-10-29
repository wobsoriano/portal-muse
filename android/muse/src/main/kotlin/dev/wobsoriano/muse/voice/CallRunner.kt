// Follows linux/src/musegadget/voice_call.py in Meta's Muse Gadget SDK (PR 275), Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the root
// of this module). Rewritten in Kotlin.

package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.DuplexStream
import dev.wobsoriano.muse.Ended
import dev.wobsoriano.muse.Header
import dev.wobsoriano.muse.Inbound
import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.MuseException
import dev.wobsoriano.muse.Outcome
import dev.wobsoriano.muse.Session
import dev.wobsoriano.muse.StreamFailed
import dev.wobsoriano.muse.encodeURIComponent
import dev.wobsoriano.muse.info
import dev.wobsoriano.muse.uuid4
import dev.wobsoriano.muse.warn
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val TICK_MS = 20L
private const val MAX_KINDS = 48
private const val TAIL_RECORDS = 10
/** About four mic frames: past this a frame is shed rather than queued behind a stalled socket. */
internal const val MAX_BACKLOG = 12 shl 10
/** The newest 320 ms of capture wait for the call; older frames are dropped, never sent late. */
private const val MIC_FRAMES = 8
private const val REALTIME_PATH = "/voice/realtime"
private const val END_CALL_PATH = "/voice/end-call"
/** A call that was never ready gives its end-call this long, so a fallback is not kept waiting. */
private val UNREADY_CLEANUP = 2.seconds
/** A leftover end-call gets this long before the next call goes on without it. */
private val LEFTOVER_TRY = 3.seconds
private const val LEFTOVER_TRIES = 3
private const val LEFTOVER_MS = 10 * 60_000L

/**
 * A call's end-call that Muse never confirmed, kept exactly as first sent. Muse keeps a call through a
 * dropped connection for about a minute, during which another connection could take it over, so the
 * next call sends this first. It names the old call's id, so it can never end a new one.
 */
internal class Leftover(val body: ByteArray, val since: Long, val tries: Int)

/** Monotonic milliseconds for the machine, and the wall clock the server reads. */
internal class Clock(val now: () -> Long = { System.nanoTime() / 1_000_000 }, val wall: () -> Long = System::currentTimeMillis)

/**
 * Runs one call on [session], a voice connection that is not running yet: dials it, admits the call,
 * feeds the machine from this one coroutine, and always cleans up. The machine decides; this only
 * waits, sends and plays.
 */
internal class CallRunner(
    private val session: Session,
    private val audio: CallAudio,
    private val opts: CallOptions,
    nodeId: String,
    private val control: ReceiveChannel<CallCommand>,
    private val on: (CallView) -> Unit,
    private val log: Logger,
    private val clock: Clock = Clock(),
    private val leftover: Leftover? = null,
) {
  /** After [run]: the end-call still owed, this call's or [leftover]'s, if any. */
  var owed: Leftover? = leftover
    private set

  private val started = clock.now()
  private val startedWall = clock.wall()
  private val m = CallMachine(opts, nodeId, uuid4(), started)
  private val pending = ArrayDeque<CallInput>()
  private val records = NdjsonSplitter()
  private var stream: DuplexStream? = null
  private var inboundOver = false
  private var mic: ReceiveChannel<ByteArray>? = null
  private var controlOpen = true
  private var shed = 0
  /** How many records of each kind came, in arrival order, for the end-of-call log. */
  private val kinds = LinkedHashMap<String, Int>()
  /** The last few records other than audio, with when they came, to show how the call ended. */
  private val tail = ArrayDeque<Pair<String, Long>>()

  /** Returns how the call ended, after its cleanup. A canceled call is cleaned up too, and then rethrows. */
  suspend fun run(): CallEnd {
    // The connection and the mic outlive a cancel of the caller until the cleanup is done with them.
    val link = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val running = link.async { session.run() }
    var end: CallEnd? = null
    try {
      end = linkUp(running) ?: run {
        if (leftover != null) settle(leftover)
        drive(link)
      }
    } finally {
      end = withContext(NonCancellable) { cleanup(end).also { withTimeoutOrNull(1.seconds) { link.coroutineContext.job.cancelAndJoin() } } }
    }
    return checkNotNull(end)
  }

  private suspend fun linkUp(running: Deferred<Ended>): CallEnd? {
    val deadline = started + opts.admit.inWholeMilliseconds
    while (!session.up.isCompleted) {
      val left = deadline - clock.now()
      if (left <= 0) return CallEnd.Declined(Decline.TimedOut)
      select {
        session.up.onAwait {}
        running.onAwait {}
        if (controlOpen) control.onReceiveCatching(::command)
        onTimeout(left) {}
      }
      if (CallInput.HangUp in pending) return CallEnd.Over(OverReason.HungUp)
      pending.clear()
      if (running.isCompleted && !session.up.isCompleted) {
        val status =
            when (running.await().outcome) {
              Outcome.AuthRejected -> 401
              Outcome.Forbidden -> 403
              else -> null
            }
        return CallEnd.Declined(Decline.Upgrade(status))
      }
    }
    return null
  }

  /** One more try at an earlier call's end-call. A 4xx is final; anything else unconfirmed stays owed within its bounds. */
  private suspend fun settle(l: Leftover) {
    owed = null
    val status =
        try {
          session.request("POST", END_CALL_PATH, listOf(Header("Content-Type", "application/json")), l.body, LEFTOVER_TRY).let { if (endCallAcknowledged(it)) null else it.status }
        } catch (e: MuseException) {
          0
        }
    log.info("muse: an earlier call's end retried", "acked" to (status == null), "status" to status)
    if (status != null && status !in 400..499 && l.tries + 1 < LEFTOVER_TRIES && clock.now() - l.since < LEFTOVER_MS) owed = Leftover(l.body, l.since, l.tries + 1)
  }

  private suspend fun drive(link: CoroutineScope): CallEnd {
    val s =
        try {
          session.openStream("POST", "$REALTIME_PATH?call_id=${encodeURIComponent(m.callId)}", listOf(Header("Content-Type", "application/x-ndjson; charset=utf-8")))
        } catch (e: MuseException) {
          return CallEnd.Declined(Decline.Dropped("could not open the call"))
        }
    stream = s
    var effects = m.begin()
    while (true) {
      for (e in effects) exec(e, s, link)?.let { return it }
      if (pending.isEmpty()) next(s)
      effects = m.step(pending.removeFirstOrNull() ?: CallInput.Tick, clock.now())
    }
  }

  /** Waits for one thing to happen. A hang-up comes first, so a flood of audio cannot hold it back. */
  private suspend fun next(s: DuplexStream) {
    val mic = mic
    select {
      if (controlOpen) control.onReceiveCatching(::command)
      if (!inboundOver) s.inbound.onReceiveCatching(::inbound)
      if (mic != null) {
        mic.onReceiveCatching { r ->
          val frame = r.getOrNull()
          if (frame != null) {
            pending += CallInput.Mic(frame)
          } else {
            this@CallRunner.mic = null
            pending += if (r.exceptionOrNull() == null) CallInput.MicEnded else CallInput.AudioFailed("mic failed")
          }
        }
      }
      onTimeout(TICK_MS) { pending += CallInput.Tick }
    }
  }

  private fun command(r: ChannelResult<CallCommand>) {
    when (r.getOrNull()) {
      CallCommand.HangUp -> pending += CallInput.HangUp
      CallCommand.Interrupt -> pending += CallInput.Interrupt
      null -> controlOpen = false
    }
  }

  private fun inbound(r: ChannelResult<Inbound>) {
    val i = r.getOrNull()
    if (i == null) {
      inboundOver = true
      pending += CallInput.WireFailed((r.exceptionOrNull() as? StreamFailed)?.why ?: "the answer closed")
      return
    }
    when (i) {
      is Inbound.Head -> pending += CallInput.Head(i.status)
      is Inbound.Chunk -> {
        val got =
            try {
              records.feed(i.data)
            } catch (e: MuseException) {
              inboundOver = true
              pending += CallInput.WireFailed("bad record")
              return
            } finally {
              i.release()
            }
        for (o in got) {
          val kind = recordKind(o)
          if (kinds.size < MAX_KINDS || kind in kinds) kinds.merge(kind, 1, Int::plus)
          if (!kind.endsWith(".delta")) {
            tail.addLast(kind to clock.now())
            if (tail.size > TAIL_RECORDS) tail.removeFirst()
          }
          pending += CallInput.Server(parseServerEvent(o))
        }
      }
      Inbound.End -> {
        inboundOver = true
        pending +=
            try {
              records.finish()
              CallInput.WireEnded
            } catch (e: MuseException) {
              CallInput.WireFailed("bad record")
            }
      }
    }
  }

  private suspend fun exec(e: Effect, s: DuplexStream, link: CoroutineScope): CallEnd? {
    when (e) {
      is Effect.Send -> {
        if (e.line is ClientLine.Append && s.backlog > MAX_BACKLOG) {
          shed++
          return null
        }
        try {
          s.send(e.line.encode(clock.wall()))
        } catch (x: MuseException) {
          pending.addFirst(CallInput.WireFailed("send failed"))
        }
      }
      is Effect.Play ->
          try {
            audio.play(e.pcm)
          } catch (x: Exception) {
            pending.addFirst(CallInput.AudioFailed("speaker failed"))
          }
      Effect.Flush -> runCatching { audio.flush() }
      Effect.OpenAudio -> openAudio(link)
      is Effect.Show -> runCatching { on(e.view) }
      is Effect.Finish -> return e.end
    }
    return null
  }

  /** Opens the devices here, before anything is played; then a thread of the link reads the mic. */
  private suspend fun openAudio(link: CoroutineScope) {
    try {
      withContext(Dispatchers.IO) { audio.open() }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      pending.addFirst(CallInput.AudioFailed("could not open the audio"))
      return
    }
    val frames = Channel<ByteArray>(MIC_FRAMES, BufferOverflow.DROP_OLDEST)
    mic = frames
    link.launch(Dispatchers.IO) {
      try {
        while (true) frames.trySend(audio.readMic() ?: break)
        frames.close()
      } catch (e: Exception) {
        frames.close(e)
      }
    }
  }

  /** Releases the devices, ends the call on the server whenever its start may have gone, and closes the stream. */
  private suspend fun cleanup(end: CallEnd?): CallEnd {
    runCatching { audio.close() }
    val s = stream
    var acked = false
    var refused = false
    var body: ByteArray? = null
    if (s != null && m.endCallDue) {
      val budget = if (m.wasLive) opts.cleanup else minOf(opts.cleanup, UNREADY_CLEANUP)
      val finished =
          try {
            withTimeoutOrNull(budget) {
              val b = endCallBody(m.callId, startedWall, clock.wall(), m.threadId).also { body = it }
              val r = session.request("POST", END_CALL_PATH, listOf(Header("Content-Type", "application/json")), b, budget)
              acked = endCallAcknowledged(r)
              refused = !acked && r.status in 400..499
              if (!acked) {
                log.warn("muse: the call's end was not acknowledged", "status" to r.status)
                return@withTimeoutOrNull true
              }
              s.halfClose()
              while (!inboundOver) {
                val i = s.inbound.receiveCatching()
                (i.getOrNull() as? Inbound.Chunk)?.release()
                if (i.isClosed || i.getOrNull() == Inbound.End) inboundOver = true
              }
              true
            }
          } catch (e: MuseException) {
            log.warn("muse: the call could not be ended", "err" to (if (acked) "drain" else "end-call"))
            true
          }
      if (finished == null) log.warn("muse: the call's cleanup ran out of time", "acked" to acked)
    }
    s?.close()
    // This call's own end-call comes first: the older one's call has most likely timed out on Muse by now.
    body?.let { if (!acked && !refused) owed = Leftover(it, clock.now(), 1) }
    val result = end ?: CallEnd.Over(OverReason.Failed("aborted"))
    // Our own words only: a Rejected code has passed shortCode, and every why is ours.
    when (result) {
      is CallEnd.Declined -> log.info("muse: call declined", "why" to result.why, "end_call_acked" to acked)
      is CallEnd.Over ->
          log.info("muse: call over", "reason" to result.reason, "end_call_acked" to acked, "advisory_errors" to m.advisoryErrors, "unlined_audio" to m.unlined, "shed_frames" to shed, "records" to kinds.entries.joinToString(",") { "${it.key}=${it.value}" }, "last" to tail.joinToString(",") { "${it.first}@-${clock.now() - it.second}ms" })
    }
    return if (result is CallEnd.Over) result.copy(endAcked = acked) else result
  }
}
