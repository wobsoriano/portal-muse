package dev.wobsoriano.museportal.ui

import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.core.Status
import dev.wobsoriano.museportal.CoreState
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.heymuse.WakeState
import dev.wobsoriano.muse.voice.CallPhase
import org.junit.Assert.assertEquals
import org.junit.Test

class MoodTest {
  private val online = MuseState(core = CoreState.Running, connection = Status(Phase.Online), wakeEnabled = true, wake = WakeState.Listening)

  @Test
  fun aTurnMovesTheMoodFromReadyToSpeakingAndBack() {
    val answered = AskState.Answered("a1", "hi", "Hello.", complete = true)
    val turn =
        listOf(
            online,
            online.copy(ask = AskState.Recording(0.6f)),
            online.copy(ask = AskState.Sending("a1")),
            online.copy(ask = AskState.Heard("a1", "hi")),
            online.copy(ask = answered.copy(complete = false)),
            online.copy(ask = answered, speaking = true),
            online.copy(ask = AskState.Recording(followUp = true)),
            online.copy(ask = answered),
        )
    assertEquals(
        listOf(Mood.Ready, Mood.Hearing, Mood.Thinking, Mood.Thinking, Mood.Thinking, Mood.Speaking, Mood.Reply, Mood.Ready),
        turn.map { it.mood })
  }

  @Test
  fun theOrbFollowsTheVoiceOnlyWhileRecording() {
    assertEquals(0.6f, online.copy(ask = AskState.Recording(0.6f)).level, 0f)
    assertEquals(0f, online.copy(ask = AskState.Sending("a1")).level, 0f)
  }

  @Test
  fun aPortalThatIsNotOnlineRestsOffline() {
    val offline = online.copy(connection = Status(Phase.Offline, "timeout"))
    assertEquals(Mood.Offline, offline.mood)
    assertEquals("Reconnecting…", offline.connectionLabel)
    assertEquals(Mood.Hearing, offline.copy(ask = AskState.Recording()).mood)
  }

  @Test
  fun theStatusLineSaysHowToAsk() {
    assertEquals("Say “Hey Muse”, or tap Muse", online.statusLine)
    assertEquals("Tap Muse to ask", online.copy(wakeEnabled = false, wake = WakeState.Off).statusLine)
    assertEquals("Getting “Hey Muse” ready · 40%", online.copy(wake = WakeState.Downloading(0.4f)).statusLine)
    assertEquals("Go ahead, I'm listening", online.copy(ask = AskState.Recording(followUp = true)).statusLine)
  }

  @Test
  fun theTopBarNamesTheConnectionPlainly() {
    assertEquals("Online", online.connectionLabel)
    assertEquals("Connecting…", online.copy(connection = Status(Phase.Connecting)).connectionLabel)
    assertEquals("Restarting…", online.copy(core = CoreState.Restarting("boom", 1000)).connectionLabel)
  }

  @Test
  fun aCallShowsWhoIsTalking() {
    val phases = CallPhase.entries.map { online.copy(ask = AskState.Calling("c1", it, 0.5f)) }
    assertEquals(
        listOf(Mood.Thinking, Mood.Hearing, Mood.Thinking, Mood.Speaking, Mood.Hearing, Mood.Thinking),
        phases.map { it.mood })
    assertEquals(
        listOf("Connecting…", "Listening…", "Thinking…", "", "Go ahead, I'm listening", "Ending call…"),
        phases.map { it.statusLine })
  }

  @Test
  fun aCallCaptionsMuseWhileItTalksAndWhatItHeardWhileItThinks() {
    val call = AskState.Calling("c1", CallPhase.Talking, heard = "what time is it", reply = "It's noon.")
    assertEquals(TurnText("what time is it", "It's noon.", false, live = true), call.turnText)
    assertEquals(TurnText("what time is it", "", false), call.copy(phase = CallPhase.Thinking).turnText)
    assertEquals("no words yet, no caption", null, call.copy(reply = "").turnText)
    for (p in listOf(CallPhase.Listening, CallPhase.Interrupted, CallPhase.Connecting, CallPhase.Ending)) assertEquals(null, call.copy(phase = p).turnText)
  }

  @Test
  fun theOrbFollowsTheVoiceInACall() {
    assertEquals(0.5f, online.copy(ask = AskState.Calling("c1", CallPhase.Listening, 0.5f)).level, 0f)
  }
}
