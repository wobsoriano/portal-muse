package dev.wobsoriano.museportal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.isSpecified
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.ask.AskState
import dev.wobsoriano.museportal.avatar.AvatarSet
import dev.wobsoriano.muse.voice.CallPhase

/** What the turn has to say: the question as heard and the answer so far, or why it failed. [live]: captions of a call, newest words last. */
data class TurnText(val heard: String, val answer: String, val failed: Boolean, val live: Boolean = false)

val AskState.turnText: TurnText?
  get() =
      when (this) {
        is AskState.Heard -> TurnText(heard, "", false)
        is AskState.Answered -> TurnText(heard, answer, false).takeIf { heard.isNotEmpty() || answer.isNotEmpty() }
        is AskState.Failed -> TurnText("", reason, true)
        // Captions only while they are current: Muse's words as it says them, and what it heard while it thinks.
        is AskState.Calling ->
            when (phase) {
              CallPhase.Talking -> TurnText(heard, reply, false, live = true).takeIf { reply.isNotEmpty() }
              CallPhase.Thinking -> TurnText(heard, "", false).takeIf { heard.isNotEmpty() }
              else -> null
            }
        else -> null
      }

/**
 * The screen a room sees: the character (or the orb) in the middle, one line beneath it, and the turn's
 * words under that. Tapping the character asks; the rest of the screen ignores taps, so waking the
 * screen or brushing it doesn't start a recording. A call has its own button to hang up.
 */
@Composable
fun HomeScreen(state: MuseState, avatar: AvatarSet?, onTap: () -> Unit, onEndCall: () -> Unit, onSettings: () -> Unit) {
  val mood = state.mood
  Box(Modifier.fillMaxSize()) {
        TopBar(state, onSettings)
        val turn = state.ask.turnText
        val side = if (avatar == null && turn != null) 260.dp else 440.dp
        Column(
            Modifier.fillMaxSize().padding(top = 72.dp, bottom = 32.dp, start = 64.dp, end = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
              val dim by animateFloatAsState(if (mood == Mood.Offline) 0.45f else 1f, tween(600), label = "offline")
              Box(
                  Modifier.size(side)
                      .alpha(dim)
                      .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
                  contentAlignment = Alignment.Center) {
                    Character(mood, state.level, avatar, side)
                  }
              Spacer(Modifier.height(16.dp))
              AnimatedContent(Line(state.statusLine, turn), transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(400)) }, contentKey = { it.key }, label = "turn") { line ->
                TurnWords(line, captionOnly = avatar != null)
              }
              val call = state.ask as? AskState.Calling
              if (call != null && call.phase != CallPhase.Ending) {
                Spacer(Modifier.height(24.dp))
                Pill("End call", onEndCall, primary = false, danger = true)
              }
            }
      }
}

/**
 * Live captions: at most [lines] lines, the newest at the bottom, each new line pushing the oldest out
 * the top. The text keeps its start, so the lines already shown never rewrap as words arrive.
 */
@Composable
fun RollingText(text: String, lines: Int, style: TextStyle, color: Color, textAlign: TextAlign? = null) {
  // Every line exactly this tall, so the box holds whole lines only.
  val line = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.3f
  val exact = style.copy(lineHeight = line, lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None), platformStyle = PlatformTextStyle(includeFontPadding = false))
  val height = with(LocalDensity.current) { line.toDp() } * lines
  Box(Modifier.fillMaxWidth().heightIn(max = height).clipToBounds(), contentAlignment = Alignment.BottomCenter) {
    Text(text, style = exact, color = color, textAlign = textAlign, modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true))
  }
}

private data class Line(val status: String, val turn: TurnText?) {
  /** Words arriving in the same turn update in place; a new turn or status fades in. */
  val key
    get() = Triple(status, turn?.heard, turn?.let { it.live || it.answer.isNotEmpty() })
}

@Composable
private fun TurnWords(line: Line, captionOnly: Boolean) {
  Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val turn = line.turn
    if (turn != null && turn.heard.isNotEmpty()) {
      Text("“${turn.heard}”", style = MaterialTheme.typography.bodyLarge, color = Palette.Faint, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (line.status.isNotEmpty() && (turn == null || turn.answer.isEmpty())) {
      Text(line.status, style = MaterialTheme.typography.titleLarge, color = Palette.Dim, textAlign = TextAlign.Center)
    }
    if (turn != null && turn.live) {
      val size = if (captionOnly) 26.sp else 34.sp
      RollingText(turn.answer, if (captionOnly) 3 else 5, TextStyle(fontSize = size, lineHeight = size * 1.3f), if (captionOnly) Palette.Dim else Palette.Text, TextAlign.Center)
    } else if (turn != null && turn.answer.isNotEmpty()) {
      val size = if (captionOnly) 26.sp else if (turn.answer.length > 160) 30.sp else 38.sp
      Text(
          turn.answer,
          fontSize = size,
          lineHeight = size * 1.3f,
          color = if (turn.failed) Palette.Bad else if (captionOnly) Palette.Dim else Palette.Text,
          textAlign = TextAlign.Center,
          maxLines = if (captionOnly) 3 else 6,
          overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
private fun TopBar(state: MuseState, onSettings: () -> Unit) {
  Row(Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
    val online = state.online
    val dot by animateColorAsState(if (online) Palette.Good else Palette.Warn, label = "dot")
    Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
    Spacer(Modifier.size(14.dp))
    Text(state.connectionLabel, style = MaterialTheme.typography.bodyLarge, color = if (online) Palette.Faint else Palette.Warn)
    Spacer(Modifier.weight(1f))
    IconButton(onClick = onSettings, modifier = Modifier.size(64.dp)) {
      Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Palette.Dim, modifier = Modifier.size(36.dp))
    }
  }
}
