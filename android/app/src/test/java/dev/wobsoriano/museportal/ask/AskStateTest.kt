package dev.wobsoriano.museportal.ask

import dev.wobsoriano.muse.core.AskUpdate
import dev.wobsoriano.muse.core.CallUpdate
import dev.wobsoriano.muse.core.ReplyKind
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.muse.voice.CallView
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.OverReason
import org.junit.Assert.assertEquals
import org.junit.Test

class AskStateTest {
  private fun reply(kind: ReplyKind, text: String = "", error: String = "", id: String = "a1") =
      AskInput.Reply(AskUpdate(id, kind, text, error))

  private fun run(vararg inputs: AskInput): List<AskStep> {
    var state: AskState = AskState.Idle
    return inputs.map { input -> state.next(input).also { state = it.state } }
  }

  @Test
  fun aVoiceQuestionGoesFromTapToSpokenAnswer() {
    val steps =
        run(
            AskInput.Tap,
            AskInput.Level(0.4f),
            AskInput.Sent("a1"),
            reply(ReplyKind.Heard, "what time is it"),
            reply(ReplyKind.Text, "It is"),
            reply(ReplyKind.Text, "It is noon."),
            reply(ReplyKind.Settled, "It is noon."),
            reply(ReplyKind.Done, "It is noon."),
        )
    assertEquals(
        listOf(
            AskStep(AskState.Recording(tapped = true), AskEffect.StartRecording),
            AskStep(AskState.Recording(0.4f, tapped = true)),
            AskStep(AskState.Sending("a1")),
            AskStep(AskState.Heard("a1", "what time is it")),
            AskStep(AskState.Answered("a1", "what time is it", "It is", complete = false)),
            AskStep(AskState.Answered("a1", "what time is it", "It is noon.", complete = false)),
            AskStep(
                AskState.Answered("a1", "what time is it", "It is noon.", complete = true),
                AskEffect.Speak("It is noon.")),
            AskStep(AskState.Answered("a1", "what time is it", "It is noon.", complete = true)),
        ),
        steps)
  }

  @Test
  fun doneWithoutSettledStillSpeaksOnce() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi.")).last()
    assertEquals(AskStep(AskState.Answered("a1", "", "Hi.", complete = true), AskEffect.Speak("Hi.")), last)
  }

  @Test
  fun tappingWhileRecordingStopsIt() {
    assertEquals(
        AskStep(AskState.Recording(tapped = true), AskEffect.StopRecording), run(AskInput.Tap, AskInput.Tap).last())
  }

  @Test
  fun anErrorReplyFailsTheAsk() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Error, error = "muse: busy")).last()
    assertEquals(AskStep(AskState.Failed("muse: busy")), last)
  }

  @Test
  fun aRecordingThatWasNotSentFails() {
    assertEquals(
        AskStep(AskState.Failed("I didn't hear anything")),
        run(AskInput.Tap, AskInput.NotSent("I didn't hear anything")).last())
  }

  @Test
  fun repliesToAnEarlierAskAreIgnored() {
    val last = run(AskInput.Tap, AskInput.Sent("a2"), reply(ReplyKind.Done, "old answer", id = "a1")).last()
    assertEquals(AskStep(AskState.Sending("a2")), last)
  }

  @Test
  fun aNewTapAfterAnAnswerStartsOver() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi."), AskInput.Tap).last()
    assertEquals(AskStep(AskState.Recording(tapped = true), AskEffect.StartRecording), last)
  }

  @Test
  fun aTypedQuestionReplacesTheLastAnswer() {
    val last = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Done, "Hi."), AskInput.Sent("a2")).last()
    assertEquals(AskStep(AskState.Sending("a2")), last)
  }

  @Test
  fun aWakeRecordsOnTheListenersMicOrItsOwn() {
    assertEquals(AskStep(AskState.Recording()), run(AskInput.Wake(listenerRecords = true)).last())
    assertEquals(AskStep(AskState.Recording(), AskEffect.StartRecording), run(AskInput.Wake(listenerRecords = false)).last())
  }

  @Test
  fun aWakeWhileRecordingIsIgnored() {
    assertEquals(AskStep(AskState.Recording(tapped = true)), run(AskInput.Tap, AskInput.Wake(listenerRecords = false)).last())
  }

  @Test
  fun anAskIsInFlightUntilItsAnswerIsComplete() {
    val states = run(AskInput.Tap, AskInput.Sent("a1"), reply(ReplyKind.Text, "Hi"), reply(ReplyKind.Done, "Hi.")).map { it.state.inFlight }
    assertEquals(listOf(true, true, true, false), states)
    assertEquals(false, AskState.Failed("no").inFlight)
  }

  private fun answered(answer: String) = AskState.Answered("a1", "is it raining", answer, complete = true)

  @Test
  fun aQuestionFromMuseIsListenedToAfterItIsSpoken() {
    assertEquals(
        AskStep(AskState.Recording(followUp = true), AskEffect.ListenForReply),
        answered("Do you want the forecast for tomorrow? ").next(AskInput.Spoken()))
  }

  @Test
  fun aPlainAnswerEndsTheAskWhenSpoken() {
    val done = answered("It is not raining.")
    assertEquals(AskStep(done), done.next(AskInput.Spoken()))
  }

  @Test
  fun aFollowUpNobodyAnswersEndsQuietly() {
    assertEquals(
        AskStep(AskState.Idle),
        AskState.Recording(followUp = true).next(AskInput.NotSent("I didn't hear anything")))
  }

  @Test
  fun aFollowUpReplyIsSentLikeAnyQuestion() {
    assertEquals(AskStep(AskState.Sending("a2")), AskState.Recording(0.3f, followUp = true).next(AskInput.Sent("a2")))
  }

  @Test
  fun keepListeningHearsAReplyToAPlainAnswerToo() {
    assertEquals(
        AskStep(AskState.Recording(followUp = true), AskEffect.ListenForReply),
        answered("It is not raining.").next(AskInput.Spoken(keepListening = true)))
  }

  @Test
  fun aSettledAnswerOrFailureFadesToIdle() {
    assertEquals(AskStep(AskState.Idle), answered("It is not raining.").next(AskInput.Expired))
    assertEquals(AskStep(AskState.Idle), AskState.Failed("no").next(AskInput.Expired))
  }

  @Test
  fun anAskStillUnderWayDoesNotExpire() {
    val streaming = AskState.Answered("a1", "", "It is", complete = false)
    assertEquals(AskStep(streaming), streaming.next(AskInput.Expired))
    assertEquals(AskStep(AskState.Recording()), AskState.Recording().next(AskInput.Expired))
  }

  @Test
  fun leavingTheAppDropsATappedRecording() {
    assertEquals(
        AskStep(AskState.Idle, AskEffect.StopRecording), AskState.Recording(0.2f, tapped = true).next(AskInput.LeftApp))
  }

  @Test
  fun leavingTheAppKeepsAHeyMuseRecording() {
    val heard = AskState.Recording(0.2f)
    assertEquals(AskStep(heard), heard.next(AskInput.LeftApp))
  }

  @Test
  fun closingTheCardDropsARecording() {
    assertEquals(AskStep(AskState.Idle, AskEffect.StopRecording), AskState.Recording(0.5f).next(AskInput.Dismissed))
  }

  @Test
  fun closingTheCardDropsAnAnswerSoNoFollowUpStarts() {
    val asked = AskState.Answered("a1", "is it raining", "Want the forecast?", complete = true)
    val closed = asked.next(AskInput.Dismissed)
    assertEquals(AskStep(AskState.Idle), closed)
    assertEquals(AskStep(AskState.Idle), closed.state.next(AskInput.Spoken()))
  }

  private fun phase(p: CallPhase, level: Float = 0f, id: String = "c1") = AskInput.Call(CallUpdate.Phase(id, CallView(p, level)))

  private fun ended(end: CallEnd, id: String = "c1") = AskInput.Call(CallUpdate.Ended(id, end))

  private fun calling(p: CallPhase, level: Float = 0f) = AskState.Calling("c1", p, level)

  private val declined = CallEnd.Declined(Decline.Refused(403))

  @Test
  fun aCallWakeFromAnyRestingOrAnsweringStateStartsACall() {
    val from =
        listOf(
            AskState.Idle,
            AskState.Failed("no"),
            AskState.Sending("a1"),
            AskState.Heard("a1", "hi"),
            AskState.Answered("a1", "hi", "Hello.", complete = true),
            AskState.Answered("a1", "hi", "Hel", complete = false))
    for (s in from) assertEquals(AskStep(AskState.Calling("c1"), AskEffect.StartCall("c1")), s.next(AskInput.WakeCall("c1")))
  }

  @Test
  fun aWakeWhileTheMicIsTakenIsIgnored() {
    for (s in listOf(AskState.Recording(0.2f), calling(CallPhase.Listening))) {
      assertEquals(AskStep(s), s.next(AskInput.WakeCall("c2")))
      assertEquals(AskStep(s), s.next(AskInput.Wake(listenerRecords = true)))
    }
  }

  @Test
  fun theMicIsHeldByARecordingOrACallOnly() {
    assertEquals(
        listOf(true, true, false, false, false),
        listOf(AskState.Recording(), calling(CallPhase.Connecting), AskState.Idle, AskState.Sending("a1"), AskState.Failed("no")).map { it.holdsMic })
  }

  @Test
  fun aCallIsInFlightFromConnectingToEnding() {
    assertEquals(true, CallPhase.entries.all { calling(it).inFlight })
  }

  @Test
  fun aCallKeepsItsCaptions() {
    val view = CallView(CallPhase.Talking, 0.2f, heard = "hi", reply = "Hello.")
    assertEquals(
        AskStep(AskState.Calling("c1", CallPhase.Talking, 0.2f, heard = "hi", reply = "Hello.")),
        calling(CallPhase.Listening).next(AskInput.Call(CallUpdate.Phase("c1", view))))
  }

  @Test
  fun aCallChimesOnceWhenMuseFirstListens() {
    val steps =
        run(
            AskInput.WakeCall("c1"),
            phase(CallPhase.Listening, 0.1f),
            phase(CallPhase.Talking),
            phase(CallPhase.Listening, 0.3f),
            phase(CallPhase.Thinking),
        )
    assertEquals(
        listOf(
            AskStep(calling(CallPhase.Connecting), AskEffect.StartCall("c1")),
            AskStep(calling(CallPhase.Listening, 0.1f), AskEffect.CallReady),
            AskStep(calling(CallPhase.Talking)),
            AskStep(calling(CallPhase.Listening, 0.3f)),
            AskStep(calling(CallPhase.Thinking)),
        ),
        steps)
  }

  @Test
  fun aCallThatOpensWithMuseTalkingDoesNotChimeOverIt() {
    assertEquals(AskStep(calling(CallPhase.Talking)), calling(CallPhase.Connecting).next(phase(CallPhase.Talking)))
  }

  @Test
  fun anEndingCallStaysEndingWhateverItSaysNext() {
    val ending = calling(CallPhase.Ending)
    assertEquals(AskStep(ending), ending.next(phase(CallPhase.Talking)))
    assertEquals(AskStep(ending), ending.next(AskInput.Dismissed))
  }

  @Test
  fun aCallMuseWouldNotTakeFallsBackToAVoiceNote() {
    assertEquals(AskStep(AskState.Recording(), AskEffect.FallBack), calling(CallPhase.Connecting).next(ended(declined)))
  }

  @Test
  fun aDeclineAfterConnectingStartsNoRecording() {
    assertEquals(AskStep(AskState.Idle), calling(CallPhase.Listening).next(ended(declined)))
  }

  @Test
  fun aCallHungUpByTheUserEndsQuietlyHoweverItEnds() {
    val ending = calling(CallPhase.Ending)
    for (end in listOf(declined, CallEnd.Over(OverReason.HungUp), CallEnd.Over(OverReason.Failed("send failed")))) {
      assertEquals(AskStep(AskState.Idle), ending.next(ended(end)))
    }
  }

  @Test
  fun aCallThatFailsShowsThatItDropped() {
    assertEquals(AskStep(AskState.Failed("The call dropped")), calling(CallPhase.Talking).next(ended(CallEnd.Over(OverReason.Failed("x")))))
  }

  @Test
  fun aCallThatEndsAnyOtherWayGoesIdle() {
    for (reason in listOf(OverReason.RemoteStop, OverReason.RemoteEnd, OverReason.Idle, OverReason.TooLong, OverReason.HungUp)) {
      assertEquals(AskStep(AskState.Idle), calling(CallPhase.Listening).next(ended(CallEnd.Over(reason, endAcked = true))))
    }
  }

  @Test
  fun aTapInterruptsMuseOnlyWhileItTalks() {
    assertEquals(AskStep(calling(CallPhase.Talking), AskEffect.Interrupt), calling(CallPhase.Talking).next(AskInput.Tap))
    for (p in CallPhase.entries - CallPhase.Talking) assertEquals(AskStep(calling(p)), calling(p).next(AskInput.Tap))
  }

  @Test
  fun closingTheCardHangsUp() {
    for (p in CallPhase.entries - CallPhase.Ending) {
      assertEquals(AskStep(calling(CallPhase.Ending, 0.4f), AskEffect.HangUp), calling(p, 0.4f).next(AskInput.Dismissed))
    }
  }

  @Test
  fun aCallIgnoresTheAskFlowsInputs() {
    val call = calling(CallPhase.Listening, 0.2f)
    val inputs =
        listOf(
            AskInput.Level(0.9f),
            AskInput.Sent("a1"),
            AskInput.NotSent("no"),
            reply(ReplyKind.Done, "Hi."),
            AskInput.Spoken(keepListening = true),
            AskInput.Expired,
            AskInput.LeftApp)
    for (input in inputs) assertEquals(AskStep(call), call.next(input))
  }

  @Test
  fun anotherCallsUpdatesAreIgnored() {
    val call = calling(CallPhase.Listening)
    assertEquals(AskStep(call), call.next(phase(CallPhase.Talking, id = "c0")))
    assertEquals(AskStep(call), call.next(ended(declined, id = "c0")))
  }

  @Test
  fun updatesForACallThatIsOverChangeNothing() {
    assertEquals(AskStep(AskState.Idle), AskState.Idle.next(phase(CallPhase.Talking)))
    val recording = AskState.Recording()
    assertEquals(AskStep(recording), recording.next(ended(declined)))
  }
}
