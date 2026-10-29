package dev.wobsoriano.museportal.device

import android.content.Context
import dev.wobsoriano.muse.voice.Duplex
import dev.wobsoriano.museportal.setup.SetupProgress

/** The user's settings, in app-private preferences. The token never leaves this file except into the core's config. */
class Settings(context: Context) {
  private val prefs = context.getSharedPreferences("muse", Context.MODE_PRIVATE)

  var name: String
    get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME
    set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

  /** Whether the user has chosen a name, rather than living with the default. */
  val named: Boolean
    get() = !prefs.getString(KEY_NAME, null).isNullOrBlank()

  var sdkToken: String
    get() = prefs.getString(KEY_TOKEN, null).orEmpty()
    set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

  /** The "Hey Muse" switch. */
  var wakeEnabled: Boolean
    get() = prefs.getBoolean(KEY_WAKE, false)
    set(value) = prefs.edit().putBoolean(KEY_WAKE, value).apply()

  /** Listen for a reply after every answer, not only after a question. */
  var keepListening: Boolean
    get() = prefs.getBoolean(KEY_KEEP_LISTENING, false)
    set(value) = prefs.edit().putBoolean(KEY_KEEP_LISTENING, value).apply()

  /** "Hey Muse" starts a realtime call with Muse instead of a voice note. Experimental. */
  var conversationMode: Boolean
    get() = prefs.getBoolean(KEY_CONVERSATION, false)
    set(value) = prefs.edit().putBoolean(KEY_CONVERSATION, value).apply()

  /** Half until a talk-over test shows the user's voice survives Muse's on the Portal. */
  var duplex: Duplex
    get() = prefs.getString(KEY_DUPLEX, null)?.let { s -> Duplex.entries.find { it.name == s } } ?: Duplex.Half
    set(value) = prefs.edit().putString(KEY_DUPLEX, value.name).apply()

  var setup: SetupProgress
    get() = prefs.getString(KEY_SETUP, null)?.let { s -> SetupProgress.entries.find { it.name == s } } ?: SetupProgress.NotStarted
    set(value) = prefs.edit().putString(KEY_SETUP, value.name).apply()

  /** The chosen TTS voice, or null for the engine's default. */
  var voice: String?
    get() = prefs.getString(KEY_VOICE, null)
    set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

  var speechRate: Float
    get() = prefs.getFloat(KEY_RATE, 1f)
    set(value) = prefs.edit().putFloat(KEY_RATE, value).apply()

  private companion object {
    const val DEFAULT_NAME = "Portal"
    const val KEY_NAME = "name"
    const val KEY_TOKEN = "sdkToken"
    const val KEY_WAKE = "wakeEnabled"
    const val KEY_KEEP_LISTENING = "keepListening"
    const val KEY_CONVERSATION = "conversationMode"
    const val KEY_DUPLEX = "duplex"
    const val KEY_SETUP = "setup"
    const val KEY_VOICE = "voice"
    const val KEY_RATE = "speechRate"
  }
}
