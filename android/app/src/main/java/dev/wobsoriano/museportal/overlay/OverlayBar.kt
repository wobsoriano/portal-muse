package dev.wobsoriano.museportal.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.height
import dev.wobsoriano.museportal.ui.Mood
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.wobsoriano.museportal.MainActivity
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.avatar.AvatarLoad
import dev.wobsoriano.museportal.ui.Character
import dev.wobsoriano.museportal.ui.MuseTheme
import dev.wobsoriano.museportal.ui.level
import dev.wobsoriano.museportal.ui.mood
import dev.wobsoriano.museportal.ui.statusLine
import dev.wobsoriano.museportal.ui.RollingText
import dev.wobsoriano.museportal.ui.turnText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The card a turn shows over whatever is in front, usually Immortal's home screen or photo frame,
 * when our own screen isn't. It needs "display over other apps"; without it nothing shows.
 *
 * It is drawn as light glass to match the glass launcher. Android 9 gives an overlay no way to blur
 * the app beneath it, so the glass is a white tint, a bright rim and a shadow, without blur.
 */
class OverlayBar(
    private val context: Context,
    private val state: StateFlow<MuseState>,
    private val avatar: StateFlow<AvatarLoad>,
    private val onClose: () -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val windows = context.getSystemService(WindowManager::class.java)
  private val shown = MutableTransitionState(false)
  private var view: ComposeView? = null
  private var owner: Owner? = null

  fun start() {
    scope.launch {
      state.map { overlayWanted(it.ask, it.speaking, it.inFront) to it.inFront }.distinctUntilChanged().collect { (wanted, inFront) ->
        if (wanted) show()
        else if (inFront) remove()
        else shown.targetState = false
      }
    }
  }

  fun stop() {
    scope.cancel()
    remove()
  }

  private fun show() {
    if (!Settings.canDrawOverlays(context)) return
    if (view == null) attach()
    shown.targetState = true
  }

  private fun attach() {
    val o = Owner().also { owner = it }
    val v =
        ComposeView(context).apply {
          setViewTreeLifecycleOwner(o)
          setViewTreeSavedStateRegistryOwner(o)
          setContent { MuseTheme { Bar() } }
        }
    val params =
        WindowManager.LayoutParams(
                ((BAR_WIDTH_DP + 32) * context.resources.displayMetrics.density).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
    try {
      windows.addView(v, params)
      view = v
    } catch (e: RuntimeException) {
      Log.w(TAG, "could not show the overlay", e)
      o.destroy()
      owner = null
    }
  }

  /** Takes the window away; called once the bar has slid out, or at once when our screen comes to the front. */
  private fun remove() {
    shown.targetState = false
    view?.let { runCatching { windows.removeView(it) } }
    view = null
    owner?.destroy()
    owner = null
  }

  @Composable
  private fun Bar() {
    val s by state.collectAsStateWithLifecycle()
    val load by avatar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
      // Lingers so the end of an answer, or a failure, can be read before it slides away.
      snapshotFlow { shown.targetState }.collect { target ->
        if (!target) {
          delay(LINGER_MS)
          if (!shown.targetState) {
            snapshotFlow { shown.isIdle }.first { it }
            if (!shown.targetState) remove()
          }
        }
      }
    }
    AnimatedVisibility(shown, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
      val turn = s.ask.turnText
      Box(Modifier.padding(top = 24.dp, start = 16.dp, end = 16.dp, bottom = 40.dp)) {
        Row(
            Modifier.width(BAR_WIDTH_DP.dp)
                .heightIn(min = 136.dp)
                .shadow(28.dp, GlassShape)
                .clip(GlassShape)
                .background(GlassFill)
                .border(GlassRim, GlassShape)
                .clickable { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)) {
              Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                Character(s.mood, s.level, (load as? AvatarLoad.Loaded)?.set, 128.dp)
              }
              Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val heard = turn?.heard.orEmpty()
                Text(
                    if (heard.isNotEmpty()) "“$heard”" else s.statusLine.ifEmpty { "Muse" },
                    style = if (heard.isNotEmpty()) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleLarge,
                    color = if (heard.isNotEmpty()) InkDim else Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                val answer = turn?.answer.orEmpty()
                if (turn?.live == true) {
                  RollingText(answer, 2, MaterialTheme.typography.titleLarge, Ink)
                } else if (answer.isNotEmpty()) {
                  Text(
                      answer,
                      style = MaterialTheme.typography.titleLarge,
                      color = if (turn?.failed == true) InkBad else Ink,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis)
                } else if (heard.isNotEmpty()) {
                  Text(s.statusLine, style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                if (s.mood == Mood.Hearing || s.mood == Mood.Reply) VoiceBars(s.level)
              }
              IconButton(
                  onClick = onClose,
                  modifier = Modifier.size(56.dp).clip(CircleShape).background(Ink.copy(alpha = 0.08f))) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Ink, modifier = Modifier.size(28.dp))
                  }
            }
      }
    }
  }

  /** Four bars that move while Muse listens, taller as the voice gets louder. */
  @Composable
  private fun VoiceBars(level: Float) {
    val wave = rememberInfiniteTransition(label = "bars")
    Row(Modifier.height(BAR_MAX_DP.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
      for (i in 0 until 4) {
        val phase by
            wave.animateFloat(
                0.3f, 1f, infiniteRepeatable(tween(520 + i * 90, easing = LinearEasing), RepeatMode.Reverse), label = "bar$i")
        val fraction = (0.25f + 0.75f * level.coerceIn(0f, 1f)) * phase
        Box(Modifier.width(7.dp).height((BAR_MAX_DP * fraction.coerceAtLeast(0.2f)).dp).clip(CircleShape).background(Listening))
      }
    }
  }

  /** What Compose needs from a window that has no activity behind it. */
  private class Owner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle
      get() = registry

    override val savedStateRegistry
      get() = saved.savedStateRegistry

    init {
      saved.performRestore(null)
      registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
      registry.currentState = Lifecycle.State.DESTROYED
    }
  }

  private companion object {
    const val TAG = "MuseOverlay"
    const val BAR_WIDTH_DP = 880
    val GlassShape = RoundedCornerShape(40.dp)
    val GlassFill = Brush.verticalGradient(listOf(Color(0xF5FFFFFF), Color(0xE0EEF2FA)))
    val GlassRim = BorderStroke(1.5.dp, Brush.verticalGradient(listOf(Color.White, Color(0x99FFFFFF))))
    val Ink = Color(0xFF12141C)
    val InkDim = Color(0xFF5D6378)
    val InkBad = Color(0xFFB3261E)
    val Listening = Color(0xFF4B6BFF)
    const val BAR_MAX_DP = 36
  }
}
