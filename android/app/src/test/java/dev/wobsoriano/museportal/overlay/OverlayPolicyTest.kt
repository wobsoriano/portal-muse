package dev.wobsoriano.museportal.overlay

import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.museportal.ask.AskState
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPolicyTest {
  private val answered = AskState.Answered("a1", "what time is it", "It is noon.", complete = true)

  @Test
  fun aWakeOverAnotherAppShowsTheBarUntilTheAnswerIsSpoken() {
    val steps =
        listOf(
            AskState.Idle to false,
            AskState.Recording(0.5f) to false,
            AskState.Sending("a1") to false,
            AskState.Heard("a1", "what time is it") to false,
            answered.copy(complete = false) to false,
            answered to true,
            answered to false,
        )
    assertEquals(
        listOf(false, true, true, true, true, true, false),
        steps.map { (ask, speaking) -> overlayWanted(ask, speaking, inFront = false) })
  }

  @Test
  fun aFollowUpKeepsTheBarUp() {
    assertEquals(true, overlayWanted(AskState.Recording(followUp = true), speaking = false, inFront = false))
  }

  @Test
  fun aCallKeepsTheBarUpUntilItEnds() {
    assertEquals(true, overlayWanted(AskState.Calling("c1", CallPhase.Ending), speaking = false, inFront = false))
  }

  @Test
  fun ourOwnScreenInFrontNeverGetsTheBar() {
    assertEquals(false, overlayWanted(AskState.Recording(), speaking = true, inFront = true))
  }

  @Test
  fun aFailureDoesNotHoldTheBar() {
    assertEquals(false, overlayWanted(AskState.Failed("no"), speaking = false, inFront = false))
  }
}
