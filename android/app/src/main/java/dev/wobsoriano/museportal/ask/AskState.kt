package dev.wobsoriano.museportal.ask

import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.CallUpdate
import dev.wobsoriano.muse.core.ReplyKind
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.muse.voice.CallView
import dev.wobsoriano.muse.voice.OverReason

/** One question, from the mic to the spoken answer, started by a tap or by "Hey Muse". */
sealed interface AskState {
  data object Idle : AskState

  /** [followUp]: listening for the answer to a question Muse asked, with no wake word. */
  /** [tapped]: started by a tap on the screen, so leaving the app drops it. */
  data class Recording(val level: Float = 0f, val followUp: Boolean = false, val tapped: Boolean = false) : AskState

  data class Sending(val id: String) : AskState

  data class Heard(val id: String, val heard: String) : AskState

  /** [complete] once the answer settled or finished; it is spoken then, once. */
  data class Answered(val id: String, val heard: String, val answer: String, val complete: Boolean) :
      AskState

  data class Failed(val reason: String) : AskState

  /** A realtime call from "Hey Muse". [phase], [level] and the captions are the call's own, copied from its last update. */
  data class Calling(val id: String, val phase: CallPhase = CallPhase.Connecting, val level: Float = 0f, val heard: String = "", val reply: String = "") : AskState
}

sealed interface AskInput {
  data object Tap : AskInput

  /** "Hey Muse" was heard. [listenerRecords]: the wake listener records the question on its own mic. */
  data class Wake(val listenerRecords: Boolean) : AskInput

  /** "Hey Muse" was heard and a call [id] is to be tried instead of a voice note. */
  data class WakeCall(val id: String) : AskInput

  data class Call(val update: CallUpdate) : AskInput

  data class Level(val level: Float) : AskInput

  /** A recording or a typed question went to the core as ask [id]. */
  data class Sent(val id: String) : AskInput

  data class NotSent(val reason: String) : AskInput

  data class Reply(val reply: AskUpdate) : AskInput

  /** The answer has finished being spoken. [keepListening]: listen for a reply even to a plain answer. */
  data class Spoken(val keepListening: Boolean = false) : AskInput

  /** The answer or failure has been on screen long enough. */
  data object Expired : AskInput

  /** The app's screen went to the background. */
  data object LeftApp : AskInput

  /** The user closed the turn: whatever was being recorded or answered is dropped. */
  data object Dismissed : AskInput
}

sealed interface AskEffect {
  data object StartRecording : AskEffect

  data object StopRecording : AskEffect

  data class Speak(val text: String) : AskEffect

  /** Muse asked a question: chime and record the reply, giving up quietly if nobody starts talking. */
  data object ListenForReply : AskEffect

  data class StartCall(val id: String) : AskEffect

  data object HangUp : AskEffect

  data object Interrupt : AskEffect

  /** Muse picked up and the mic is open: the chime says "go ahead". */
  data object CallReady : AskEffect

  /** Muse would not take the call: chime, and record the question as a voice note on a mic of its own. */
  data object FallBack : AskEffect
}

/** Whether the question is still being recorded or answered; speaking the answer is tracked apart. */
val AskState.inFlight: Boolean
  get() =
      this is AskState.Recording || this is AskState.Sending || this is AskState.Heard ||
          (this is AskState.Answered && !complete) || this is AskState.Calling

/** Whether the mic is taken by a recording or a call, so a wake has nothing to start. */
val AskState.holdsMic: Boolean
  get() = this is AskState.Recording || this is AskState.Calling

data class AskStep(val state: AskState, val effect: AskEffect? = null)

fun AskState.next(input: AskInput): AskStep =
    if (this is AskState.Calling) onCalling(input)
    else when (input) {
      AskInput.Tap ->
          if (this is AskState.Recording) AskStep(this, AskEffect.StopRecording)
          else AskStep(AskState.Recording(tapped = true), AskEffect.StartRecording)
      // A tapped question nobody asked out loud shouldn't follow the user to the home screen.
      AskInput.LeftApp ->
          if (this is AskState.Recording && tapped) AskStep(AskState.Idle, AskEffect.StopRecording) else AskStep(this)
      is AskInput.Wake ->
          if (this is AskState.Recording) AskStep(this)
          else AskStep(AskState.Recording(), if (input.listenerRecords) null else AskEffect.StartRecording)
      is AskInput.WakeCall ->
          if (this is AskState.Recording) AskStep(this) else AskStep(AskState.Calling(input.id), AskEffect.StartCall(input.id))
      is AskInput.Call -> AskStep(this)
      is AskInput.Level -> AskStep(if (this is AskState.Recording) copy(level = input.level) else this)
      is AskInput.Sent -> AskStep(AskState.Sending(input.id))
      is AskInput.NotSent ->
          AskStep(
              when {
                this !is AskState.Recording -> this
                followUp -> AskState.Idle
                else -> AskState.Failed(input.reason)
              })
      is AskInput.Reply -> onReply(input.reply)
      // As hey-muse does on the Echo: an answer that ends in a question is listened to without a wake word.
      is AskInput.Spoken ->
          if (this is AskState.Answered && complete && (input.keepListening || answer.trimEnd().endsWith("?"))) {
            AskStep(AskState.Recording(followUp = true), AskEffect.ListenForReply)
          } else {
            AskStep(this)
          }
      AskInput.Dismissed -> AskStep(AskState.Idle, if (this is AskState.Recording) AskEffect.StopRecording else null)
      AskInput.Expired ->
          AskStep(if (this is AskState.Failed || (this is AskState.Answered && complete)) AskState.Idle else this)
    }

private fun AskState.Calling.onCalling(input: AskInput): AskStep =
    when (input) {
      is AskInput.Call -> if (input.update.id == id) onCall(input.update) else AskStep(this)
      // A tap on Muse cuts it off mid-answer; it never hangs up.
      AskInput.Tap -> AskStep(this, if (phase == CallPhase.Talking) AskEffect.Interrupt else null)
      AskInput.Dismissed -> if (phase == CallPhase.Ending) AskStep(this) else AskStep(copy(phase = CallPhase.Ending), AskEffect.HangUp)
      else -> AskStep(this)
    }

private fun AskState.Calling.onCall(update: CallUpdate): AskStep =
    when (update) {
      is CallUpdate.Phase ->
          when {
            phase == CallPhase.Ending -> AskStep(this)
            // Ready: the chime waits for this, so nobody starts talking before the mic is open.
            phase == CallPhase.Connecting && update.view.phase == CallPhase.Listening ->
                AskStep(update.view.into(this), AskEffect.CallReady)
            else -> AskStep(update.view.into(this))
          }
      is CallUpdate.Ended ->
          when (val end = update.end) {
            is CallEnd.Declined -> if (phase == CallPhase.Connecting) AskStep(AskState.Recording(), AskEffect.FallBack) else AskStep(AskState.Idle)
            is CallEnd.Over ->
                if (end.reason is OverReason.Failed && phase != CallPhase.Ending) AskStep(AskState.Failed("The call dropped"))
                else AskStep(AskState.Idle)
          }
    }

private fun CallView.into(c: AskState.Calling) = c.copy(phase = phase, level = level, heard = heard, reply = reply)

private fun AskState.onReply(reply: AskUpdate): AskStep {
  val asked =
      when (this) {
        is AskState.Sending -> AskState.Answered(id, "", "", complete = false)
        is AskState.Heard -> AskState.Answered(id, heard, "", complete = false)
        is AskState.Answered -> this
        else -> return AskStep(this)
      }
  if (reply.id != asked.id) return AskStep(this)
  return when (reply.kind) {
    ReplyKind.Heard ->
        AskStep(if (this is AskState.Answered) copy(heard = reply.text) else AskState.Heard(asked.id, reply.text))
    ReplyKind.Text -> AskStep(asked.copy(answer = reply.text))
    ReplyKind.Settled,
    ReplyKind.Done -> {
      val text = reply.text.ifEmpty { asked.answer }
      val speak = if (asked.complete || text.isEmpty()) null else AskEffect.Speak(text)
      AskStep(asked.copy(answer = text, complete = true), speak)
    }
    ReplyKind.Error -> AskStep(AskState.Failed(reply.error.ifEmpty { "Muse could not answer" }))
  }
}
