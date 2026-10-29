// Ported from linux/src/musegadget/service.py, and the command spec shape of executor.py, in Meta's
// Muse Gadget SDK, Copyright (c) Meta Platforms, Inc. and affiliates, licensed under the Apache
// License, Version 2.0 (LICENSE at the root of this module). Modified: rewritten in Kotlin, with the state
// kept by the caller, rotated tokens held until they are saved, and the connection state reported as
// it changes.

package dev.wobsoriano.muse

import dev.wobsoriano.muse.voice.CallAudio
import dev.wobsoriano.muse.voice.CallCommand
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallOptions
import dev.wobsoriano.muse.voice.CallPhase
import dev.wobsoriano.muse.voice.CallRunner
import dev.wobsoriano.muse.voice.CallView
import dev.wobsoriano.muse.voice.Leftover
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.ProbeReport
import dev.wobsoriano.muse.voice.SilentAudio
import dev.wobsoriano.muse.voice.reach
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient

private const val DEFAULT_NOISE_HOST = "hatch.metaaivm.com"

/** Run's end when the device has no credentials, or Muse took them away. */
class UnpairedException : MuseException("muse: not paired")

/** A refresh that was needed and did not happen, so the old token should not be tried again for a while. */
internal class RefreshFailedException(cause: Throwable) : MuseException("muse: token refresh failed: ${cause.message}", cause)

/** Spaces the reconnects: doubling from the base to the cap, and never under the floor. */
internal class Backoff(private val t: Timing) {
  private var failures = 0
  var floor = Duration.ZERO

  fun next(): Duration {
    var d = t.backoffMax
    if (failures < 16) d = minOf(t.backoffBase * (1 shl failures), t.backoffMax)
    failures++
    return maxOf(d, floor)
  }

  fun reset() {
    failures = 0
    floor = Duration.ZERO
  }
}

private fun commandSpecs(commands: List<Command>): Pair<JsonObject, Map<String, Command>> {
  val byName = LinkedHashMap<String, Command>()
  val specs = buildJsonObject {
    for (c in commands) {
      when {
        c.name.isEmpty() || c.handler == null -> throw IllegalArgumentException("muse: command \"${c.name}\" needs a name and a handler")
        // The server pushes ESP32 firmware to whatever advertises it.
        c.name == "device.ota" -> throw IllegalArgumentException("muse: device.ota must never be advertised")
        c.name in byName -> throw IllegalArgumentException("muse: command \"${c.name}\" is listed twice")
      }
      val names = HashSet<String>()
      fun JsonObjectBuilder.params(into: String, list: List<Param>) = putJsonObject(into) {
        for (p in list) {
          if (p.name.isEmpty() || !names.add(p.name)) throw IllegalArgumentException("muse: command \"${c.name}\" has a bad parameter \"${p.name}\"")
          putJsonObject(p.name) {
            put("type", p.type.json)
            put("description", p.description)
          }
        }
      }
      putJsonObject(c.name) {
        put("description", c.description)
        params("required", c.required)
        params("optional", c.optional)
        if (c.timeout.inWholeMilliseconds != 0L) put("timeout_ms", c.timeout.inWholeMilliseconds)
      }
      byName[c.name] = c
    }
  }
  return specs to byName
}

/** Keeps a paired device connected to its Muse. */
class Client(private val cfg: Config) {
  private val log = cfg.logger
  private val version = cfg.version.ifEmpty { "0" }
  private val userAgent =
      "musegadget/$version (${System.getProperty("os.name").orEmpty().lowercase()} ${System.getProperty("os.arch").orEmpty()}) kotlin/${KotlinVersion.CURRENT}"
  private val http: OkHttpClient = cfg.http ?: OkHttpClient()
  private val api = ApiClient(http.newBuilder().callTimeout(API_TIMEOUT.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS).build(), userAgent)
  private val register: JsonObject
  private val commands: Map<String, Command>
  internal var t = Timing()
  /** Lets the tests point at a server without TLS. Nothing outside them sets it. */
  internal var allowPlaintext = false

  private val lock = Any()
  private var state = ConnState(Phase.Offline)
  private var session: Session? = null
  /** What a call borrows from the session: set and cleared with it. */
  private var voice: VoiceTarget? = null
  private var running = false
  private var calling = false
  /** An earlier call's end-call that Muse never confirmed, for the next call to send first. */
  private var leftover: Leftover? = null

  // The rest belongs to the coroutine in run.

  /**
   * A State with rotated tokens that save has not accepted yet. The tokens in the store are dead and
   * these are the only live ones, so nothing else happens until they are saved.
   */
  private var unsaved: State? = null
  private var lastRefresh: Long? = null
  private var reportAttempted = false
  /** Whether the last round ended with the API rejecting the access token. */
  private var rejected = false
  /** When a refusing door was last asked again, which is not done again for a while. */
  private var knockedAt: Long? = null

  init {
    val (specs, byName) = commandSpecs(cfg.commands)
    commands = byName
    // The VM knows these values from the SDK's Linux client. The family must never be "link": the
    // server pushes ESP32 firmware updates to every link device.
    register = buildJsonObject {
      put("display_name", cfg.displayName.ifEmpty { System.getenv("HOSTNAME").orEmpty() })
      put("platform", "linux")
      put("version", version)
      put("device_family", "homehub")
      put("model_id", "linux")
      put("is_wakeup_supported", false)
      put("commands_v2", specs)
    }
  }

  /** The connection state right now. */
  val connState: ConnState
    get() = synchronized(lock) { state }

  private fun setState(next: ConnState) {
    val changed =
        synchronized(lock) {
          val changed = state != next
          state = next
          changed
        }
    if (changed) cfg.onState?.invoke(next)
  }

  /**
   * Says one thing to Muse and waits for the whole answer. [on] hears the answer arrive: Heard for a
   * voice note, Text as the text grows, Settled when it looks complete. It runs on the calling
   * coroutine, so once ask has returned, for a cancellation or anything else, it is never called again.
   *
   * One ask can be open at a time. Canceling stops the waiting, but a message Muse has already taken
   * stays said.
   */
  suspend fun ask(input: AskInput, on: suspend (ReplyEvent) -> Unit = {}): Reply {
    when (input) {
      is AskInput.Text -> {
        if (input.message.isBlank()) throw MuseException("muse: nothing to ask")
        if (input.message.toByteArray(Charsets.UTF_8).size > MAX_ASK_TEXT) throw MuseException("muse: the message is too long")
      }
      is AskInput.VoiceNote -> {
        val w = input.wav
        if (w.size < 12 || String(w, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(w, 8, 4, Charsets.ISO_8859_1) != "WAVE") {
          throw MuseException("muse: the voice note is not a WAV file")
        }
        if (w.size > MAX_VOICE_NOTE) throw MuseException("muse: the voice note is too long")
      }
    }
    val s = synchronized(lock) { session } ?: throw OfflineException()
    return s.ask(input, on)
  }

  /**
   * Calls Muse over [audio] and talks until one side hangs up, on a voice connection of its own. It
   * borrows the VM, bearer, Noise host and node id of the registered session, so it needs the client
   * online, never touches the tokens, and leaves that session registered. It returns once the call is
   * over and cleaned up; [CallEnd.Declined] means it never went live and the caller may fall back. Once
   * the call's start may have reached Muse the call is ended there, also when the coroutine is
   * canceled, which then rethrows. [control] hangs up or interrupts, and [on] hears what the call shows,
   * on the call's coroutine, and must not block. One call at a time.
   */
  suspend fun call(
      audio: CallAudio,
      options: CallOptions = CallOptions(),
      control: ReceiveChannel<CallCommand> = Channel(),
      on: (CallView) -> Unit = {},
  ): CallEnd {
    val (s, target) = synchronized(lock) { session to voice }
    if (s == null || target == null || s.registeredAt == null) return CallEnd.Declined(Decline.Offline)
    synchronized(lock) {
      if (calling) return CallEnd.Declined(Decline.Dropped("busy"))
      calling = true
    }
    try {
      val url =
          try {
            noiseUrl(target.host, target.vmId, allowPlaintext, options.appId)
          } catch (e: MuseException) {
            return CallEnd.Declined(Decline.Dropped("bad app id"))
          }
      val link =
          Session(
              SessionParams(
                  url = url,
                  bearer = target.bearer,
                  userAgent = userAgent,
                  http = sessionHttp(),
                  nodeId = target.nodeId,
                  register = null,
                  commands = emptyMap(),
                  log = log,
                  t = t,
                  registered = {},
                  knockEvery = Duration.ZERO,
                  knocks = 0))
      val runner = CallRunner(link, audio, options, target.nodeId, control, on, log, leftover = synchronized(lock) { leftover.also { leftover = null } })
      try {
        return runner.run()
      } finally {
        synchronized(lock) { leftover = runner.owed }
      }
    } finally {
      synchronized(lock) { calling = false }
    }
  }

  /**
   * Asks Muse a few things on the registered session, and with [realtime] admits a call and hangs it up
   * again: GET /chat/history, POST /fs/read of a file that is not there, and POST /voice/realtime. Only
   * statuses and short codes come back, never a body. An admitted call may replace one already on the VM.
   */
  suspend fun probe(realtime: Boolean = false): ProbeReport {
    val s = synchronized(lock) { session }?.takeIf { it.registeredAt != null }
    fun headers(vararg extra: Header) = extra.toList() + listOf(Header("x-request-id", uuid4()), Header("x-app-id", APP_ID))
    val history = reach(s, "GET", "/chat/history?limit=1", headers())
    val missing = buildJsonObject { put("path", "gadget-data/probe-${uuid4()}.txt") }.toString().toByteArray()
    val fsRead = reach(s, "POST", "/fs/read", headers(Header("Content-Type", "application/json")), missing)
    val call =
        if (!realtime) {
          null
        } else {
          val control = Channel<CallCommand>(Channel.UNLIMITED)
          call(SilentAudio(), CallOptions(admit = 10.seconds), control) { if (it.phase != CallPhase.Connecting) control.trySend(CallCommand.HangUp) }
        }
    return ProbeReport(history, fsRead, call)
  }

  // Noise ciphertext does not compress, so nothing is deflated even if the VM agrees to it.
  private fun sessionHttp() =
      http
          .newBuilder()
          .pingInterval(t.ping.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
          .minWebSocketMessageToCompress(Long.MAX_VALUE)
          .build()

  /**
   * Keeps the device connected until the device turns out to be unpaired, and then returns. Each
   * round fetches the VMs with the device token, which also yields a fresh bearer, connects to the
   * default one and serves until the connection ends. Failures back off, and a session that stayed up
   * a while clears the backoff. The device token is rotated before it expires, and at once if the API
   * rejects it. Canceling the coroutine stops it.
   */
  suspend fun run() {
    synchronized(lock) {
      check(!running) { "muse: already running" }
      running = true
    }
    try {
      val pace = Backoff(t)
      while (true) {
        val (wait, err) =
            try {
              round(pace)
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              pace.next() to e
            }
        if (err is UnpairedException) return
        if (err != null) {
          log.warn("muse: offline", "err" to err.message, "retry_in" to wait)
          setState(ConnState(Phase.Offline, err))
        }
        delay(wait)
      }
    } catch (e: CancellationException) {
      setState(ConnState(Phase.Offline))
      throw e
    } finally {
      synchronized(lock) { running = false }
    }
  }

  /**
   * One try at being connected, from the stored State to the end of a session. It returns how long to
   * wait before the next and what went wrong, which is an [UnpairedException] when there is to be no
   * next.
   */
  private suspend fun round(pace: Backoff): Pair<Duration, Throwable?> {
    val st =
        try {
          load()
        } catch (e: MuseException) {
          return pace.next() to e
        }
    val creds = st.credentials
    if (creds == null) {
      setState(ConnState(Phase.Unpaired))
      return Duration.ZERO to UnpairedException()
    }
    setState(ConnState(Phase.Connecting))

    val root =
        try {
          apiRoot(creds, allowPlaintext)
        } catch (e: MuseException) {
          return t.backoffMax to e
        }
    val cur =
        try {
          refresh(root, st, force = false)
        } catch (e: MuseException) {
          return refreshFailed(st, e, pace)
        }
    val vms =
        try {
          api.fetchVms(root, checkNotNull(cur.credentials).accessToken)
        } catch (e: ApiException) {
          if (e.status == 401) {
            log.warn("muse: device token rejected by the API; refreshing")
            try {
              refresh(root, cur, force = true)
            } catch (e2: MuseException) {
              return refreshFailed(cur, e2, pace)
            }
            // The new token is tried on the next round, at once the first time. A server that rejects
            // every token it hands out must not have the device rotate them in a tight loop.
            if (rejected) return pace.next() to null
            rejected = true
            return Duration.ZERO to null
          }
          rejected = false
          return pace.next() to e
        }
    rejected = false
    if (vms.isEmpty()) return pace.next() to MuseException("muse: no VM is leased to this device")
    val chosen = vms.firstOrNull { it.isDefault } ?: vms[0]

    val (ended, lasted) = serve(chosen, cur)
    if (lasted >= t.healthy) pace.reset()
    when (ended.outcome) {
      Outcome.Unpaired -> {
        log.warn("muse: Muse removed this device")
        return Duration.ZERO to unpair(cur)
      }
      // Either way the next round fetches the VMs again, and with them a new bearer.
      Outcome.AuthRejected,
      Outcome.Forbidden -> pace.floor = t.authFloor
      Outcome.Closed -> Unit
    }
    return pace.next() to ended.error
  }

  /**
   * How many times this round may ask a refusing door again: as many as the API said, once, and then
   * not at all until knockRest has passed. A door that stays shut through a whole spell of asking is
   * shut for a reason asking does not change, and after that the rounds' own backoff is the pace, one
   * try each.
   */
  private fun knocks(v: Vm): Int {
    val at = knockedAt
    if (at != null && (System.nanoTime() - at).nanoseconds < t.knockRest) return 0
    return v.knocks
  }

  private suspend fun refreshFailed(st: State, err: MuseException, pace: Backoff): Pair<Duration, Throwable?> =
      when (err) {
        is UnpairedException -> Duration.ZERO to unpair(st)
        is RefreshFailedException -> t.tokenRetry to err
        else -> pace.next() to err
      }

  /** Reads the State, once any rotated tokens still waiting to be saved have been. */
  private suspend fun load(): State {
    unsaved?.let { pending ->
      try {
        cfg.store.save(pending)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        throw MuseException("muse: could not save the rotated tokens: ${e.message}", e)
      }
      unsaved = null
      log.info("muse: rotated tokens saved")
    }
    val st =
        try {
          cfg.store.load()
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          throw MuseException("muse: could not load the state: ${e.message}", e)
        }
    if (st.credentials != null && !st.identity.valid) throw MuseException("muse: the state has credentials but no identity")
    return st
  }

  /** Forgets the credentials, keeping the identity so the device pairs again as itself. */
  private suspend fun unpair(st: State): UnpairedException {
    unsaved = null
    try {
      cfg.store.save(State(identity = st.identity))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Nothing is lost: the dead credentials are found dead again on the next start.
      log.error("muse: could not save the unpaired state", "err" to e.message)
    }
    setState(ConnState(Phase.Unpaired))
    return UnpairedException()
  }

  private suspend fun serve(v: Vm, st: State): Pair<Ended, Duration> {
    val creds = checkNotNull(st.credentials)
    val host = creds.noiseHost.ifEmpty { DEFAULT_NOISE_HOST }
    val vmId = v.id.ifEmpty { v.name }
    val url =
        try {
          noiseUrl(host, vmId, allowPlaintext)
        } catch (e: MuseException) {
          return Ended(Outcome.Closed, e) to Duration.ZERO
        }
    val register = JsonObject(mapOf("node_id" to kotlinx.serialization.json.JsonPrimitive(st.identity.nodeId)) + this.register)
    val s =
        Session(
            SessionParams(
                url = url,
                bearer = v.authToken,
                userAgent = userAgent,
                http = sessionHttp(),
                nodeId = st.identity.nodeId,
                register = register,
                commands = commands,
                log = log,
                t = t,
                registered = { setState(ConnState(Phase.Online)) },
                knockEvery = v.knockEvery,
                knocks = knocks(v),
            ))
    log.info("muse: connecting", "vm" to vmId)
    synchronized(lock) {
      session = s
      voice = VoiceTarget(host, vmId, v.authToken, st.identity.nodeId)
    }
    val ended: Ended
    try {
      ended = s.run()
    } finally {
      if (s.knocked > 0) knockedAt = System.nanoTime()
      synchronized(lock) {
        session = null
        voice = null
      }
    }
    val lasted = s.registeredAt?.let { (System.nanoTime() - it).nanoseconds } ?: Duration.ZERO
    log.info("muse: session ended", "registered_for" to lasted.inWholeSeconds.seconds, "err" to ended.error?.message)
    return ended to lasted
  }

  /**
   * Returns the State to connect with, rotating the tokens first if they are due. [force] is for when
   * the API has just rejected the access token.
   *
   * A rotation kills the pair it used, so the new pair goes to the Store before anything else: only a
   * State that save has accepted is returned. If save fails the new pair waits in [unsaved], and load
   * keeps trying it before every round.
   */
  private suspend fun refresh(root: String, st: State, force: Boolean): State {
    val creds = checkNotNull(st.credentials)
    val age = (System.currentTimeMillis() / 1000 - creds.accessTokenSavedAt).seconds
    // The SDK token reaches Muse only in a refresh body, so a device with one that has not been
    // reported refreshes early, once, to say it.
    val reportDue = cfg.sdkToken.isNotEmpty() && !st.sdkTokenReported && !reportAttempted
    // A negative age is a clock that has not been set, which says nothing about the token.
    val due = force || age >= t.refreshAge || age < Duration.ZERO
    if (!due && !reportDue) return st
    val last = lastRefresh
    if (!force && last != null && (System.nanoTime() - last).nanoseconds < t.tokenRetry) return st
    lastRefresh = System.nanoTime()
    if (reportDue) {
      reportAttempted = true
      log.info("muse: refreshing the device token to report the SDK token")
    }
    val pair =
        try {
          api.refresh(root, creds.refreshToken, st.identity.nodeId, cfg.sdkToken)
        } catch (e: ApiException) {
          when {
            !due -> {
              // Only reporting the SDK token: nothing has rejected the current token, so a refusal
              // here must never unpair the device.
              log.warn("muse: the SDK token report failed; keeping the pairing", "err" to e.message)
              return st
            }
            e.status == 401 -> {
              log.error("muse: pairing revoked; the device has to be paired again")
              throw UnpairedException()
            }
            force -> throw RefreshFailedException(e)
          }
          // Not rejected yet, so the current token is used while it still works.
          log.warn("muse: token refresh failed; keeping the current token", "err" to e.message)
          return st
        }
    val next =
        st.copy(
            credentials =
                creds.copy(accessToken = pair.accessToken, refreshToken = pair.refreshToken, accessTokenSavedAt = System.currentTimeMillis() / 1000),
            sdkTokenReported = st.sdkTokenReported || cfg.sdkToken.isNotEmpty())
    try {
      cfg.store.save(next)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      unsaved = next
      throw MuseException("muse: could not save the rotated tokens: ${e.message}", e)
    }
    log.info("muse: device token rotated")
    return next
  }
}

/** The VM a registered session reached, which a call connects to again with the same bearer. */
private class VoiceTarget(val host: String, val vmId: String, val bearer: String, val nodeId: String)
