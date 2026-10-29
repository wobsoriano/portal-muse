package dev.wobsoriano.museportal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.museportal.MuseState
import dev.wobsoriano.museportal.avatar.AvatarLoad
import dev.wobsoriano.museportal.avatar.AvatarSource

interface SettingsActions {
  fun back()

  fun setWakeEnabled(on: Boolean)

  fun setKeepListening(on: Boolean)

  fun setConversationMode(on: Boolean)

  fun requestOverlay()

  fun setVoice(name: String?, rate: Float)

  fun testVoice()

  fun rename(name: String)

  fun saveToken(token: String)

  fun unpair()

  fun copyDiagnostics()
}

@Composable
fun SettingsScreen(state: MuseState, canOverlay: Boolean, avatar: AvatarLoad, avatarDir: String, actions: SettingsActions) {
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
      IconButton(onClick = actions::back, modifier = Modifier.size(64.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.Text, modifier = Modifier.size(36.dp))
      }
      Spacer(Modifier.width(12.dp))
      Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
    Box(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
      Column(
          Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 64.dp),
          verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Section("Hey Muse", "Ask without touching the screen.") {
              HeyMuseSwitch(state, actions::setWakeEnabled)
              Divider()
              SwitchRow(
                  "Keep listening after every answer",
                  "Listens for about 6 seconds for your reply, without “Hey Muse”.",
                  state.keepListening,
                  actions::setKeepListening)
              Divider()
              SwitchRow(
                  "Conversation mode (experimental)",
                  "“Hey Muse” starts a call: Muse answers in its own voice, with captions, and a tap cuts it off. End call hangs up. Falls back to a voice note when unavailable.",
                  state.conversationMode,
                  actions::setConversationMode)
              if (!canOverlay) {
                Divider()
                OverlayOffer(canOverlay, actions::requestOverlay, boxed = false)
              }
            }
            Section("Voice", "How Muse sounds when it answers.") { VoiceSettings(state, actions) }
            Section("Device", "How this Portal shows up in Muse.") { DeviceSettings(state, actions) }
            Section("About", null) { About(state, avatar, avatarDir, actions) }
          }
    }
  }
}

@Composable
private fun Section(title: String, detail: String?, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    if (detail != null) Text(detail, style = MaterialTheme.typography.bodyLarge, color = Palette.Dim)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Card).padding(horizontal = 28.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
          content()
        }
  }
}

@Composable
private fun Divider() = HorizontalDivider(color = Palette.Line)

@Composable
private fun VoiceSettings(state: MuseState, actions: SettingsActions) {
  if (!state.canSpeak) {
    Text("This Portal has no text-to-speech engine, so answers are shown but not spoken.", style = MaterialTheme.typography.bodyLarge, color = Palette.Warn)
    return
  }
  var picking by remember { mutableStateOf(false) }
  var rate by remember(state.speechRate) { mutableFloatStateOf(state.speechRate) }
  if (state.voices.isNotEmpty()) {
    val current = state.voices.find { it.name == state.voice }?.label ?: "Portal's default"
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { picking = true }, verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Voice", style = MaterialTheme.typography.titleLarge)
        Text(current, style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
      }
      Text("Change", style = MaterialTheme.typography.labelLarge, color = Palette.Accent)
    }
    Divider()
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text("Speaking rate", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
      Text(rateLabel(rate), style = MaterialTheme.typography.bodyLarge, color = Palette.Dim)
    }
    Slider(
        value = rate,
        onValueChange = { rate = (Math.round(it * 10) / 10f) },
        onValueChangeFinished = { actions.setVoice(state.voice, rate) },
        valueRange = 0.6f..1.6f,
        steps = 9,
        colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.Raised),
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
  }
  Pill("Test voice", actions::testVoice, primary = false)
  if (picking) {
    AlertDialog(
        onDismissRequest = { picking = false },
        confirmButton = { Link("Close", { picking = false }) },
        title = { Text("Voice", style = MaterialTheme.typography.headlineMedium) },
        text = {
          LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(listOf<Pair<String?, String>>(null to "Portal's default") + state.voices.map { it.name to it.label }) { (name, label) ->
              Row(
                  Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable {
                    actions.setVoice(name, rate)
                    actions.testVoice()
                  },
                  verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.voice == name, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Palette.Accent))
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                  }
            }
          }
        },
        containerColor = Palette.Raised)
  }
}

private fun rateLabel(rate: Float) =
    when {
      rate < 0.95f -> "Slower · ${"%.1f".format(rate)}×"
      rate > 1.05f -> "Faster · ${"%.1f".format(rate)}×"
      else -> "Normal"
    }

@Composable
private fun DeviceSettings(state: MuseState, actions: SettingsActions) {
  var name by remember(state.name) { mutableStateOf(state.name) }
  var replacing by remember { mutableStateOf(false) }
  var confirmUnpair by remember { mutableStateOf(false) }
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(40) },
        label = { Text("Name") },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = MaterialTheme.shapes.large,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line),
        modifier = Modifier.weight(1f))
    Pill("Rename", { actions.rename(name) }, enabled = name.isNotBlank() && name.trim() != state.name, primary = false)
  }
  Divider()
  Row(Modifier.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) {
      Text("Muse token", style = MaterialTheme.typography.titleLarge)
      MaskedToken(state.tokenHint)
    }
    Link(if (replacing) "Cancel" else "Replace", { replacing = !replacing })
  }
  if (replacing) {
    Text("Create one at gadgets.muse.ai → Settings → SDK tokens.", style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
    TokenField(state, actions::saveToken)
  }
  if (state.paired == true) {
    Divider()
    Row(Modifier.heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text("Unpair", style = MaterialTheme.typography.titleLarge)
        Text("Removes this Portal from your Muse account.", style = MaterialTheme.typography.bodyMedium, color = Palette.Dim)
      }
      Pill("Unpair…", { confirmUnpair = true }, primary = false, danger = true)
    }
  }
  if (confirmUnpair) {
    AlertDialog(
        onDismissRequest = { confirmUnpair = false },
        title = { Text("Unpair this Portal?", style = MaterialTheme.typography.headlineMedium) },
        text = { Text("Muse stops answering here until you pair it again from the Muse app on your phone.", style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
          Pill("Unpair", {
            confirmUnpair = false
            actions.unpair()
          }, danger = true, primary = false)
        },
        dismissButton = { Link("Cancel", { confirmUnpair = false }) },
        containerColor = Palette.Raised)
  }
}

@Composable
private fun About(state: MuseState, avatar: AvatarLoad, avatarDir: String, actions: SettingsActions) {
  Fact("Version", state.version)
  Fact("Connection", state.connectionLabel + (state.connection?.error?.takeIf { it.isNotEmpty() && state.connection.phase == Phase.Offline }?.let { " · $it" } ?: ""))
  state.pairing?.name?.takeIf { it.isNotEmpty() }?.let { Fact("Bluetooth name", it) }
  Fact(
      "Character",
      when (avatar) {
        is AvatarLoad.Loaded -> if (avatar.set.source == AvatarSource.Yours) "Your own, from the muse-avatar folder" else "Built in"
        AvatarLoad.Loading -> "Loading…"
        is AvatarLoad.Unavailable -> "Not loaded: ${avatar.reason}"
      })
  Text(
      "To use your own character, copy a folder made with hey-muse's tools/muse-avatar to $avatarDir and restart the app.",
      style = MaterialTheme.typography.bodyMedium,
      color = Palette.Faint,
      fontFamily = FontFamily.Default)
  Pill("Copy diagnostics", actions::copyDiagnostics, primary = false)
}

@Composable
private fun Fact(label: String, value: String) {
  Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(label, style = MaterialTheme.typography.bodyLarge, color = Palette.Dim, modifier = Modifier.width(220.dp))
    Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
  }
}
