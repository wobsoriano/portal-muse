package dev.wobsoriano.muse.core

import dev.wobsoriano.muse.AskInput
import dev.wobsoriano.muse.BusyException
import dev.wobsoriano.muse.Client
import dev.wobsoriano.muse.ConnState
import dev.wobsoriano.muse.Credentials
import dev.wobsoriano.muse.FileStore
import dev.wobsoriano.muse.Identity
import dev.wobsoriano.muse.Logger
import dev.wobsoriano.muse.MuseException
import dev.wobsoriano.muse.OfflineException
import dev.wobsoriano.muse.Phase
import dev.wobsoriano.muse.ReplyEvent
import dev.wobsoriano.muse.State
import dev.wobsoriano.muse.Store
import dev.wobsoriano.muse.error
import dev.wobsoriano.muse.info
import dev.wobsoriano.muse.pairing.AlreadyOnline
import dev.wobsoriano.muse.pairing.Advertisement
import dev.wobsoriano.muse.pairing.Controller
import dev.wobsoriano.muse.pairing.Device
import dev.wobsoriano.muse.pairing.Progress
import dev.wobsoriano.muse.pairing.Reason
import dev.wobsoriano.muse.pairing.Result
import dev.wobsoriano.muse.pairing.SHUTDOWN_GRACE
import dev.wobsoriano.muse.pairing.SetupConfig
import dev.wobsoriano.muse.pairing.SetupState
import dev.wobsoriano.muse.pairing.Transport
import dev.wobsoriano.muse.pairing.ASSUMED_MTU
import dev.wobsoriano.muse.pairing.DEFAULT_WINDOW
import dev.wobsoriano.muse.voice.CallAudio
import dev.wobsoriano.muse.voice.CallCommand
import dev.wobsoriano.muse.voice.CallEnd
import dev.wobsoriano.muse.voice.CallOptions
import dev.wobsoriano.muse.voice.CallView
import dev.wobsoriano.muse.voice.Decline
import dev.wobsoriano.muse.voice.OverReason
import dev.wobsoriano.muse.voice.ProbeReport
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import okhttp3.OkHttpClient

/** What the app hands the core: the name the Muse app shows, the developer's mgst_ token, and the Wi-Fi network the device is on. */
data class CoreConfig(val name: String, val sdkToken: String, val ssid: String) {
  override fun toString() = "CoreConfig(name=$name, sdkToken=${if (sdkToken.isEmpty()) "<empty>" else "<set>"}, ssid=$ssid)"
}

/** The Muse connection, for the screen. [error] is set only when offline. */
data class Status(val phase: Phase, val error: String = "")

enum class PairState {
  Open,
  Connected,
  Paired,
  Failed,
  Closed,
}

/** Pairing progress for the screen. [name] is the MuseGadgetXXXXXX the phone will list. */
data class PairStatus(val state: PairState, val name: String = "", val error: String = "")

enum class ReplyKind {
  Heard,
  Text,
  Settled,
  Done,
  Error,
}

/** Progress on the ask with this [id]. Done and Error end it. */
data class AskUpdate(val id: String, val kind: ReplyKind, val text: String = "", val error: String = "")

/** Progress on the call with this [id]. [Ended] comes once, last, after the call's audio is closed. */
sealed interface CallUpdate {
  val id: String

  data class Phase(override val id: String, val view: CallView) : CallUpdate

  data class Ended(override val id: String, val end: CallEnd) : CallUpdate
}

/** What the core tells the app. Calls come from the core's coroutines, one at a time, and must not block. */
interface CoreListener {
  fun status(status: Status)

  fun pairStatus(status: PairStatus)

  fun reply(update: AskUpdate)

  fun call(update: CallUpdate) = Unit
}

/** The app's GATT server, as the pairing needs it. Calls come one at a time and must not block. */
interface BlePeripheral {
  /** Starts the GATT server and a connectable advertisement. The answer comes back as [BleEvents.advertising]. */
  fun advertise(ad: Advertisement)

  /** Stops advertising and closes the GATT server. */
  fun unadvertise()

  /** Notifies each packet on TX, in order, each after the last was sent and about CHUNK_STAGGER later. */
  fun notify(packets: List<ByteArray>)

  /** Drops the connected central. */
  fun disconnect()
}

/** What the GATT server saw, in the order it happened on the link. Any thread may call these. */
interface BleEvents {
  /** Whether the advertisement asked for by [BlePeripheral.advertise] is on the air. */
  fun advertising(ok: Boolean, error: String = "")

  /** A central (the phone) connected. */
  fun connected(device: String)

  /** The central wrote to RX. [mtu] is the negotiated ATT MTU, or 0 when unknown. */
  fun wrote(data: ByteArray, mtu: Int)

  /** The central went away. */
  fun disconnected()
}

/** A config that changes any of these restarts the client. */
private data class Plan(val name: String, val sdkToken: String)

private fun planOf(cfg: CoreConfig) = Plan(cfg.name, cfg.sdkToken)

/**
 * One Client from start until run returns. Once stopping, nothing it says is reported and its saves are
 * refused, so a token rotation racing an unpair cannot bring the credentials back. No new client starts
 * until it has returned: one identity holds one session.
 */
private class ClientRun(val plan: Plan) {
  lateinit var client: Client
  lateinit var job: Job
  var stopping = false
  var state = ConnState(Phase.Connecting)
}

/** Where the pairing link stands. A closed pairing is a null pair. */
private enum class PairPhase {
  /** Advertising, no phone on the link. */
  Open,
  /** A phone is on the link and its writes go to the Controller. */
  Connected,
  /** The tokens are saved and the phone told; the service stays up for SHUTDOWN_GRACE so the last notification reaches it. */
  Finished,
}

private class PairRun(val name: String, val link: BleLink) {
  lateinit var ctrl: Controller
  lateinit var job: Job
  var phase = PairPhase.Open
}

/** The pairing Transport whose GATT server is the app's. The Controller calls it from its own coroutine, so the MTU the loop learns is kept atomically. */
private class BleLink(private val out: Out) : Transport {
  val mtu = AtomicInteger(0)

  override fun send(packets: List<ByteArray>) = out { ble.notify(packets) }

  override fun disconnect() = out { ble.disconnect() }

  override fun mtu() = mtu.get().takeIf { it > 0 } ?: ASSUMED_MTU
}

/** The one way out, so what the loop, the Controller and an ask say never interleaves. */
private class Out(val listener: CoreListener, val ble: BlePeripheral) {
  @Synchronized operator fun invoke(block: Out.() -> Unit) = block()
}

private sealed interface Ev {
  class Config(val cfg: CoreConfig) : Ev

  data object PairStart : Ev

  data object PairStop : Ev

  class Advertising(val ok: Boolean, val error: String) : Ev

  class Connected(val device: String) : Ev

  class Wrote(val data: ByteArray, val mtu: Int) : Ev

  data object Disconnected : Ev

  class Ask(val id: String, val text: String?, val wav: ByteArray?) : Ev

  data object Unpair : Ev

  data object InputEnded : Ev

  class ClientSaid(val run: ClientRun, val state: ConnState) : Ev

  class ClientSave(val run: ClientRun, val state: State, val reply: CompletableDeferred<Throwable?>) : Ev

  class ClientDone(val run: ClientRun, val unpaired: Boolean) : Ev

  class PairProgress(val run: PairRun, val p: Progress) : Ev

  class PairSave(val run: PairRun, val result: Result, val reply: CompletableDeferred<Throwable?>) : Ev

  class PairDone(val run: PairRun, val error: Throwable?) : Ev

  class Call(val id: String, val audio: CallAudio, val options: CallOptions) : Ev

  class Command(val command: CallCommand) : Ev

  class Probe(val realtime: Boolean, val report: (String) -> Unit) : Ev

  /** The ask or the call that ran is over. */
  data object Done : Ev
}

/** What runs: one ask or one call at a time. Muse does not say which question an answer belongs to, and the Portal has one microphone. */
private enum class Busy {
  Idle,
  Asking,
  Calling,
}

private class StoppingException(message: String) : MuseException(message)

/**
 * The Muse half of Muse for Portal: the client, the pairing and the state file, behind one event loop.
 * [run]'s coroutine owns every field below; the app, the client and the pairing Controller reach it only
 * by posting events. It is musecore's core.go, with direct calls where the JSON lines were.
 */
class MuseCore(
    private val store: FileStore,
    /** Reported to Muse and to the phone while pairing. */
    private val version: String,
    listener: CoreListener,
    ble: BlePeripheral,
    private val log: Logger = Logger.None,
    private val http: OkHttpClient? = null,
) : BleEvents {
  private val out = Out(listener, ble)
  private val events = Channel<Ev>(Channel.UNLIMITED)
  private val finished = CompletableDeferred<Unit>()

  private var st = State()
  /** Why the state file could not be read, until something is saved over it. */
  private var loadErr: Throwable? = null
  /** Null until the app's first config. */
  private var cfg: CoreConfig? = null
  private var client: ClientRun? = null
  /** Null while pairing is closed. */
  private var pair: PairRun? = null
  private var busy = Busy.Idle
  /** The running call's commands, while busy calling. */
  private var callControl: Channel<CallCommand>? = null
  private var status: Status? = null
  private var closing = false
  /** Ends at shutdown, and with it the client, the pairing and any ask or call, which is still ended on the server first. */
  private lateinit var root: CoroutineScope

  fun config(cfg: CoreConfig) = post(Ev.Config(cfg))

  /** Opens pairing for [DEFAULT_WINDOW]. Refused with a failed PairStatus when there is no SDK token. */
  fun pairStart() = post(Ev.PairStart)

  fun pairStop() = post(Ev.PairStop)

  /** A question: exactly one of [text] and [wav], a whole RIFF/WAVE file of 16 kHz mono PCM16. */
  fun ask(id: String, text: String? = null, wav: ByteArray? = null) = post(Ev.Ask(id, text, wav))

  /**
   * A realtime voice call over [audio]. It is refused, as [CallEnd.Declined], while an ask or another
   * call runs or when offline; a declined call never opened [audio].
   */
  fun call(id: String, audio: CallAudio, options: CallOptions = CallOptions()) = post(Ev.Call(id, audio, options))

  /** Hangs up or interrupts the running call; ignored when none runs. */
  fun callCommand(command: CallCommand) = post(Ev.Command(command))

  /** Asks the server a few questions and reports each as one line of statuses and codes; [realtime] also admits and ends a call. */
  fun probe(realtime: Boolean = false, report: (String) -> Unit) = post(Ev.Probe(realtime, report))

  /** Forgets the pairing. */
  fun unpair() = post(Ev.Unpair)

  /** Shuts down: run returns once nothing it started is still running. */
  fun close() = post(Ev.InputEnded)

  override fun advertising(ok: Boolean, error: String) = post(Ev.Advertising(ok, error))

  override fun connected(device: String) = post(Ev.Connected(device))

  override fun wrote(data: ByteArray, mtu: Int) = post(Ev.Wrote(data.copyOf(), mtu))

  override fun disconnected() = post(Ev.Disconnected)

  private fun post(ev: Ev) {
    events.trySend(ev)
  }

  /** Posts the event [build] makes around a reply, and waits for the loop's answer. */
  private suspend fun request(build: (CompletableDeferred<Throwable?>) -> Ev) {
    val reply = CompletableDeferred<Throwable?>()
    post(build(reply))
    val err =
        select<Throwable?> {
          reply.onAwait { it }
          finished.onJoin { StoppingException("the Muse core is stopping") }
        }
    if (err != null) throw err
  }

  /**
   * Serves until [close], and returns once nothing it started is still running. Canceling the
   * coroutine is a close.
   */
  suspend fun run() {
    coroutineScope {
      root = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
      load()
      try {
        while (!closing || client != null || pair != null || busy != Busy.Idle) {
          val ev =
              try {
                events.receive()
              } catch (e: CancellationException) {
                if (closing) throw e
                Ev.InputEnded
              }
          handle(ev)
          if (closing) root.coroutineContext[Job]?.cancel()
        }
      } finally {
        events.close()
        finished.complete(Unit)
      }
    }
  }

  private suspend fun load() {
    try {
      st = store.load()
      loadErr = null
    } catch (e: NoSuchFileException) {
      st = State()
      loadErr = null
    } catch (e: FileNotFoundException) {
      st = State()
      loadErr = null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.error("could not read the pairing", "err" to e.message)
      loadErr = e
    }
  }

  private suspend fun save(next: State) {
    store.save(next)
    st = next
    loadErr = null
  }

  private suspend fun handle(ev: Ev) {
    when (ev) {
      Ev.InputEnded -> {
        closing = true
        pair?.job?.cancel()
        reconcile()
      }
      is Ev.ClientSaid -> if (client === ev.run && !ev.run.stopping) ev.run.state = ev.state
      is Ev.ClientSave ->
          if (client !== ev.run || ev.run.stopping) {
            ev.reply.complete(MuseException("muse: the client is being stopped"))
          } else {
            ev.reply.complete(attempt { save(ev.state) })
          }
      is Ev.ClientDone -> {
        if (client === ev.run) client = null
        if (ev.unpaired) load()
        reconcile()
      }
      is Ev.PairProgress -> progressed(ev.run, ev.p)
      is Ev.PairSave ->
          if (pair !== ev.run) {
            ev.reply.complete(MuseException("pairing: this pairing was closed"))
          } else {
            ev.reply.complete(attempt { savePairing(ev.result) })
          }
      is Ev.PairDone -> {
        if (pair === ev.run) pair = null
        out { ble.unadvertise() }
        if (ev.error != null) {
          log.info("pairing closed", "err" to ev.error.message)
          out { listener.pairStatus(PairStatus(PairState.Closed, ev.run.name)) }
        }
        reconcile()
      }
      Ev.Done -> {
        busy = Busy.Idle
        callControl = null
      }
      else -> if (!closing) handleInput(ev)
    }
    publishStatus(false)
  }

  private suspend fun attempt(block: suspend () -> Unit): Throwable? =
      try {
        block()
        null
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        e
      }

  private suspend fun handleInput(ev: Ev) {
    val p = pair
    when (ev) {
      is Ev.Config -> {
        cfg = ev.cfg
        reconcile()
        publishStatus(true)
      }
      Ev.PairStart -> startPairing()
      Ev.PairStop -> if (p != null && p.phase != PairPhase.Finished) p.job.cancel()
      is Ev.Advertising -> {
        if (p == null || ev.ok) return
        out { listener.pairStatus(PairStatus(PairState.Failed, p.name, "could not advertise: ${ev.error}")) }
        p.job.cancel()
      }
      is Ev.Connected -> if (p != null && p.phase == PairPhase.Open) p.phase = PairPhase.Connected
      is Ev.Wrote -> {
        if (p == null || p.phase == PairPhase.Finished) {
          log.info("ignoring a write with no pairing open")
          return
        }
        p.phase = PairPhase.Connected
        p.link.mtu.set(ev.mtu)
        p.ctrl.onWrite(ev.data)
      }
      Ev.Disconnected ->
          if (p != null && p.phase == PairPhase.Connected) {
            p.phase = PairPhase.Open
            p.link.mtu.set(0)
            p.ctrl.onDisconnect()
          }
      is Ev.Ask -> ask(ev)
      is Ev.Call -> call(ev)
      is Ev.Command -> callControl?.trySend(ev.command)
      is Ev.Probe -> probe(ev)
      Ev.Unpair -> forget()
      else -> Unit
    }
  }

  /**
   * Makes what runs match what should: a client exactly when the app has configured one, the device is
   * paired, no pairing is open, and the core is not stopping, built from the current config.
   */
  private fun reconcile() {
    val cfg = cfg
    val want = cfg != null && st.credentials != null && pair == null && !closing
    val r = client
    if (r != null && !r.stopping && (!want || r.plan != planOf(cfg!!))) {
      r.stopping = true
      r.job.cancel()
    }
    if (want && client == null) startClient(cfg!!)
  }

  private fun startClient(cfg: CoreConfig) {
    val r = ClientRun(planOf(cfg))
    r.client =
        Client(
            dev.wobsoriano.muse.Config(
                store = ClientStore(r),
                displayName = cfg.name,
                version = version,
                sdkToken = cfg.sdkToken,
                logger = log,
                onState = { s -> post(Ev.ClientSaid(r, s)) },
                http = http))
    client = r
    r.job =
        root.launch {
          var unpaired = false
          try {
            r.client.run()
            unpaired = true
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            log.error("the Muse client failed", "err" to e.message)
          } finally {
            post(Ev.ClientDone(r, unpaired))
          }
        }
  }

  /** Tells the app where the connection stands when that has changed, or always when [force] is set. Nothing is said before the first config. */
  private fun publishStatus(force: Boolean) {
    if (cfg == null) return
    val c = client
    val s =
        when {
          c != null && !c.stopping -> Status(c.state.phase, if (c.state.phase == Phase.Offline) c.state.error?.message.orEmpty() else "")
          loadErr != null -> Status(Phase.Offline, "could not read the pairing: ${loadErr?.message}")
          st.credentials == null -> Status(Phase.Unpaired)
          else -> Status(Phase.Offline)
        }
    if (force || s != status) {
      status = s
      out { listener.status(s) }
    }
  }

  private suspend fun startPairing() {
    if (pair != null) {
      log.info("pairing is already open")
      return
    }
    fun failed(err: String) {
      val name = if (st.identity.valid) st.identity.bleName else ""
      out { listener.pairStatus(PairStatus(PairState.Failed, name, err)) }
    }
    val cfg = cfg
    if (cfg == null || cfg.sdkToken.isEmpty()) return failed("no Muse SDK token: the Muse app will not pair without one")
    // The identity is on disk before it is advertised: the app remembers the device by it.
    if (!st.identity.valid) {
      attempt { save(st.copy(identity = Identity.new())) }?.let {
        return failed("could not keep the device's identity: ${it.message}")
      }
    }
    val id = st.identity
    val run = PairRun(id.bleName, BleLink(out))
    val ctrl =
        try {
          Controller(
              SetupConfig(
                  device = Device(id.nodeId, id.bleName, id.mac, version),
                  transport = run.link,
                  sdkToken = cfg.sdkToken,
                  network = AlreadyOnline(ssid = { cfg.ssid }),
                  save = { r -> request { reply -> Ev.PairSave(run, r, reply) } },
                  progress = { p -> post(Ev.PairProgress(run, p)) },
                  logger = log))
        } catch (e: IllegalArgumentException) {
          return failed(e.message.orEmpty())
        }
    run.ctrl = ctrl
    pair = run
    reconcile()
    out { ble.advertise(Advertisement(run.name)) }
    log.info("pairing open", "name" to run.name, "for" to DEFAULT_WINDOW)
    run.job =
        root.launch {
          var err: Throwable? = null
          try {
            ctrl.run()
            try {
              delay(SHUTDOWN_GRACE)
            } catch (_: CancellationException) {}
          } catch (e: Throwable) {
            err = e
          } finally {
            post(Ev.PairDone(run, err))
          }
        }
  }

  private fun progressed(run: PairRun, p: Progress) {
    if (pair !== run) return
    val s =
        when (p.state) {
          SetupState.Waiting -> PairStatus(PairState.Open, run.name)
          SetupState.Connected,
          SetupState.Confirmed,
          SetupState.Provisioning -> PairStatus(PairState.Connected, run.name)
          SetupState.Done -> {
            run.phase = PairPhase.Finished
            PairStatus(PairState.Paired, run.name)
          }
          SetupState.Failed -> {
            // A stop is reported as closed once the Controller has returned.
            if (p.reason == Reason.Canceled) return
            PairStatus(PairState.Failed, run.name, p.reason.toString() + (p.error?.let { ": ${it.message}" } ?: ""))
          }
        }
    out { listener.pairStatus(s) }
  }

  /** The Controller's save: the tokens the app sent, stamped the way the SDK stamps them. */
  private suspend fun savePairing(r: Result) {
    save(
        st.copy(
            credentials =
                Credentials(
                    accessToken = r.accessToken,
                    refreshToken = r.refreshToken,
                    tokenType = r.tokenType,
                    username = r.username,
                    apiUrl = r.apiUrl,
                    apiUrlV2 = r.apiUrlV2,
                    noiseHost = r.noiseHost,
                    accessTokenSavedAt = System.currentTimeMillis() / 1000),
            // A fresh pairing has told Muse nothing yet.
            sdkTokenReported = false))
  }

  /** Forgets the tokens and keeps the identity, so the device pairs again as itself. */
  private suspend fun forget() {
    val p = pair
    if (p != null && p.phase != PairPhase.Finished) p.job.cancel()
    attempt { save(State(identity = st.identity)) }?.let {
      log.error("could not forget the pairing", "err" to it.message)
      return
    }
    log.info("unpaired")
    reconcile()
  }

  private fun ask(a: Ev.Ask) {
    fun fail(err: String) = out { listener.reply(AskUpdate(a.id, ReplyKind.Error, error = err)) }
    val input =
        when {
          a.text != null && a.wav == null -> AskInput.Text(a.text)
          a.wav != null && a.text == null -> AskInput.VoiceNote(a.wav)
          else -> return fail("an ask carries exactly one of text and wav")
        }
    if (busy != Busy.Idle) return fail(BusyException().message.orEmpty())
    val c = client
    if (c == null || c.stopping) return fail(OfflineException().message.orEmpty())
    val id = a.id
    busy = Busy.Asking
    root.launch {
      try {
        val reply =
            c.client.ask(input) { e ->
              val update =
                  when (e) {
                    is ReplyEvent.Heard -> AskUpdate(id, ReplyKind.Heard, e.text)
                    is ReplyEvent.Text -> AskUpdate(id, ReplyKind.Text, e.text)
                    is ReplyEvent.Settled -> AskUpdate(id, ReplyKind.Settled, e.text)
                  }
              out { listener.reply(update) }
            }
        out { listener.reply(AskUpdate(id, ReplyKind.Done, reply.text)) }
      } catch (e: CancellationException) {
        out { listener.reply(AskUpdate(id, ReplyKind.Error, error = "context canceled")) }
      } catch (e: Exception) {
        out { listener.reply(AskUpdate(id, ReplyKind.Error, error = e.message.orEmpty())) }
      } finally {
        post(Ev.Done)
      }
    }
  }

  private fun call(c: Ev.Call) {
    fun ended(end: CallEnd) = out { listener.call(CallUpdate.Ended(c.id, end)) }
    if (busy != Busy.Idle) return ended(CallEnd.Declined(Decline.Dropped("busy")))
    val r = client
    if (r == null || r.stopping) return ended(CallEnd.Declined(Decline.Offline))
    val control = Channel<CallCommand>(Channel.UNLIMITED)
    busy = Busy.Calling
    callControl = control
    root.launch {
      var end: CallEnd = CallEnd.Over(OverReason.Failed("canceled"))
      try {
        end = r.client.call(c.audio, c.options, control) { v -> out { listener.call(CallUpdate.Phase(c.id, v)) } }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.error("the call failed", "err" to e.javaClass.simpleName)
        end = CallEnd.Over(OverReason.Failed("the call failed"))
      } finally {
        // Done goes first, so a voice note the app sends on hearing the end is never refused as busy.
        post(Ev.Done)
        ended(end)
      }
    }
  }

  private fun probe(p: Ev.Probe) {
    fun say(line: String) = out { p.report(line) }
    fun reached(name: String, r: ProbeReport.Reached) = say("$name=${r.status ?: "unreachable"}" + (r.code?.let { " $it" } ?: ""))
    val r = client
    if (r == null || r.stopping) return say("probe=offline")
    // The realtime part is a call, so it waits its turn like one.
    val realtime = p.realtime && busy == Busy.Idle
    if (p.realtime && !realtime) say("realtime=skipped busy")
    if (realtime) busy = Busy.Calling
    root.launch {
      try {
        val report = r.client.probe(realtime)
        reached("history", report.history)
        reached("fs_read", report.fsRead)
        report.realtime?.let { say("realtime=$it") }
      } finally {
        if (realtime) post(Ev.Done)
      }
    }
  }

  /** A client's view of the state file. Reads go to the file; writes go through the loop, which refuses them once the client is being stopped. */
  private inner class ClientStore(private val run: ClientRun) : Store {
    override suspend fun load(): State = store.load()

    override suspend fun save(state: State) = request { reply -> Ev.ClientSave(run, state, reply) }
  }
}
