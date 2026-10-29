// Ported from linux/src/musegadget/voice_gate.py in Meta's Muse Gadget SDK (PR 275), Copyright (c) Meta
// Platforms, Inc. and affiliates, licensed under the Apache License, Version 2.0 (LICENSE at the root
// of this module). Rewritten in Kotlin.

package dev.wobsoriano.muse.voice

import dev.wobsoriano.muse.MuseException

internal const val MAX_IDS = 128

/** The gate would have to forget lineage to go on, so the call ends instead. */
internal class GateOverflow(what: String) : MuseException("muse: too many $what")

/**
 * Fences provider audio after a local or server-side interruption. The provider's response ids on
 * continuous audio, not response.created's, name stale audio. A new caller's matching turn admits its
 * reply and what continues it. After an interruption, audio without lineage stays muted, with no timer
 * to unmute it, and audio without lineage after a confirmed turn cannot be told from that turn's.
 */
internal class ReplyGate {
  enum class Lineage {
    Stale,
    Waiting,
    Playing,
    Continuing,
  }

  enum class Verdict {
    Drop,
    Hold,
    Play,
  }

  /** What to throw away now: everything queued for the speaker ([playing]), and the reply held for confirmation ([waiting]). */
  data class Flush(val playing: Boolean, val waiting: Boolean)

  private val ids = LinkedHashMap<String, Lineage>()
  private val items = HashSet<String>()
  private var fenced = false
  private var item: String? = null
  private var confirmed = false
  private var blocked = false
  private var turnFresh = false
  private var flushPlaying = false
  private var flushWaiting = false

  /** The response the server last said it created and has not ended, which a barge-in cancels. */
  var openResponse: String? = null
    private set

  val waiting
    get() = Lineage.Waiting in ids.values

  val playing
    get() = ids.values.any { it == Lineage.Playing || it == Lineage.Continuing }

  /** Every known response goes stale until caller input [item] (none for a local barge-in) is confirmed by its turn. */
  fun interrupt(item: String? = null) {
    fenced = true
    ids.replaceAll { _, _ -> Lineage.Stale }
    this.item = item
    confirmed = false
    blocked = false
    turnFresh = false
    openResponse = null
    flushPlaying = true
    flushWaiting = true
  }

  fun takeFlush(): Flush = Flush(flushPlaying, flushWaiting).also {
    flushPlaying = false
    flushWaiting = false
  }

  private fun replace(from: Set<Lineage>, to: Lineage) = ids.replaceAll { _, l -> if (l in from) to else l }

  /** Speech starts, turns, suppressions and responses; any other event leaves the gate alone. */
  fun on(e: ServerEvent) {
    when (e) {
      is ServerEvent.SpeechStarted -> {
        val started = e.itemId
        if (started != null) {
          // A delayed or repeated speech start cannot select an older caller.
          if (started in items) return
          if (items.size == MAX_IDS) throw GateOverflow("caller input identities")
          items += started
        }
        interrupt(started)
      }
      is ServerEvent.TurnStarted -> {
        if (item == null) return
        if (e.itemId != item) {
          flushWaiting = flushWaiting || waiting
          flushPlaying = flushPlaying || Lineage.Continuing in ids.values
          replace(setOf(Lineage.Waiting, Lineage.Continuing), Lineage.Stale)
          blocked = true
          turnFresh = false
          return
        }
        val hadReply = waiting || playing
        replace(setOf(Lineage.Waiting, Lineage.Continuing), Lineage.Playing)
        confirmed = true
        blocked = false
        turnFresh = !hadReply
      }
      is ServerEvent.InputSuppressed -> if (item != null && e.itemId == item) interrupt()
      is ServerEvent.ResponseCreated -> if (e.id != null) openResponse = e.id
      is ServerEvent.ResponseEnded -> if (e.id != null && e.id == openResponse) openResponse = null
      else -> Unit
    }
  }

  /** The verdict on one audio event. Without a response id it is Drop once fenced, else Play. */
  fun audio(responseId: String?): Verdict {
    if (responseId == null) return if (fenced) Verdict.Drop else Verdict.Play
    ids[responseId]?.let { return verdict(it) }
    if (ids.size == MAX_IDS) throw GateOverflow("provider response identities")
    val state =
        when {
          !fenced -> Lineage.Playing
          item == null || blocked -> Lineage.Stale
          confirmed -> {
            flushPlaying = flushPlaying || playing
            replace(setOf(Lineage.Playing, Lineage.Continuing), Lineage.Stale)
            (if (turnFresh) Lineage.Playing else Lineage.Continuing).also { turnFresh = false }
          }
          else -> {
            flushWaiting = flushWaiting || waiting
            replace(setOf(Lineage.Waiting), Lineage.Stale)
            Lineage.Waiting
          }
        }
    ids[responseId] = state
    return verdict(state)
  }

  private fun verdict(l: Lineage) =
      when (l) {
        Lineage.Waiting -> Verdict.Hold
        Lineage.Stale -> Verdict.Drop
        Lineage.Playing,
        Lineage.Continuing -> Verdict.Play
      }
}
