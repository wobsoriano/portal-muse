package dev.wobsoriano.museportal

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import dev.wobsoriano.museportal.overlay.OverlayBar

/** Keeps the process, and with it the Muse core, alive in the background. */
class MuseService : Service() {
  private var overlay: OverlayBar? = null

  /** Debug builds: `adb shell am broadcast -a dev.wobsoriano.museportal.PROBE [--ez realtime true]`, read with `adb logcat -s MuseProbe`. */
  private val probe =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = muse.probe(intent.getBooleanExtra("realtime", false))
      }

  override fun onCreate() {
    super.onCreate()
    val nm = getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel(CHANNEL, "Muse", NotificationManager.IMPORTANCE_MIN))
    val open =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Muse is running")
            .setContentIntent(open)
            .build()
    startForeground(NOTIFICATION_ID, notification)
    muse.start()
    overlay = OverlayBar(this, muse.state, avatars.load, onClose = muse::dismiss).also { it.start() }
    // DUMP is held by adb's shell, so other apps on the Portal cannot send it.
    if (BuildConfig.DEBUG) registerReceiver(probe, IntentFilter(PROBE), Manifest.permission.DUMP, null)
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

  override fun onDestroy() {
    if (BuildConfig.DEBUG) unregisterReceiver(probe)
    overlay?.stop()
    muse.stop()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  companion object {
    private const val CHANNEL = "muse"
    private const val NOTIFICATION_ID = 1
    private const val PROBE = "dev.wobsoriano.museportal.PROBE"

    fun start(context: Context) = context.startForegroundService(Intent(context, MuseService::class.java))
  }
}

class BootReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action == Intent.ACTION_BOOT_COMPLETED) MuseService.start(context)
  }
}
