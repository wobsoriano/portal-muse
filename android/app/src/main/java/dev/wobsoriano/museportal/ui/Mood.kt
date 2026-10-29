package dev.wobsoriano.museportal.ui

import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.museportal.CoreState
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.heymuse.WakeState

/** What the orb shows, from across the room. */
enum class Mood {
  /** Waiting to be asked. */
  Ready,
  /** Recording a question; the orb follows the voice. */
  Hearing,
  Thinking,
  Speaking,
  /** Recording the reply to an answer, with no wake word needed. */
  Reply,
  Offline,
}

val MuseState.online: Boolean
  get() = core == CoreState.Running && connection?.phase == Phase.Online

val MuseState.mood: Mood
  get() {
    if (speaking) return Mood.Speaking
    return when (val a = ask) {
      is AskState.Recording -> if (a.followUp) Mood.Reply else Mood.Hearing
      is AskState.Sending,
      is AskState.Heard -> Mood.Thinking
      is AskState.Answered -> if (a.complete) restingMood else Mood.Thinking
      AskState.Idle,
      is AskState.Failed -> restingMood
      is AskState.Calling ->
          when (a.phase) {
            CallPhase.Listening,
            CallPhase.Interrupted -> Mood.Hearing
            CallPhase.Talking -> Mood.Speaking
            CallPhase.Connecting,
            CallPhase.Thinking,
            CallPhase.Ending -> Mood.Thinking
          }
    }
  }

private val MuseState.restingMood
  get() = if (online) Mood.Ready else Mood.Offline

/** The voice level while recording or in a call, 0 otherwise. */
val MuseState.level: Float
  get() =
      when (val a = ask) {
        is AskState.Recording -> a.level
        is AskState.Calling -> a.level
        else -> 0f
      }

/** The connection in plain words, for the top bar. */
val MuseState.connectionLabel: String
  get() =
      when (core) {
        is CoreState.Restarting -> "Restarting…"
        CoreState.Stopped -> "Starting…"
        CoreState.Running ->
            when (connection?.phase) {
              null -> "Starting…"
              Phase.Online -> "Online"
              Phase.Connecting -> "Connecting…"
              Phase.Offline -> "Reconnecting…"
              Phase.Unpaired -> "Not paired"
            }
      }

/** The one line under the orb. */
val MuseState.statusLine: String
  get() =
      (ask as? AskState.Calling)?.let { callLine(it.phase) } ?: when (mood) {
        Mood.Hearing -> "Listening…"
        Mood.Reply -> "Go ahead, I'm listening"
        Mood.Thinking -> "Thinking…"
        Mood.Speaking -> ""
        Mood.Offline -> "Muse is offline right now"
        Mood.Ready -> if (wakeEnabled) wakeLine(wake) else TAP
      }

private const val TAP = "Tap Muse to ask"

private fun callLine(phase: CallPhase): String =
    when (phase) {
      CallPhase.Connecting -> "Connecting…"
      CallPhase.Listening -> "Listening…"
      CallPhase.Thinking -> "Thinking…"
      CallPhase.Talking -> ""
      CallPhase.Interrupted -> "Go ahead, I'm listening"
      CallPhase.Ending -> "Ending call…"
    }

private fun wakeLine(wake: WakeState): String =
    when (wake) {
      WakeState.Listening,
      WakeState.Delegated,
      WakeState.Asking -> "Say “Hey Muse”, or tap Muse"
      is WakeState.Downloading -> "Getting “Hey Muse” ready" + (wake.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "…")
      WakeState.Loading -> "Getting “Hey Muse” ready…"
      WakeState.Yielded -> "Another app has the mic. $TAP"
      is WakeState.Failed -> "“Hey Muse” stopped: ${wake.reason}"
      WakeState.Off -> TAP
    }
