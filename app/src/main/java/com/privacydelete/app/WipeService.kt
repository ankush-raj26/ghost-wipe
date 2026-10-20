package com.privacydelete.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log

/**
 * Foreground service so the wipe keeps running when the user leaves the app
 * or turns the screen off. Shows a progress notification with a Cancel button.
 */
class WipeService : Service() {

    companion object {
        const val ACTION_START = "com.privacydelete.app.START"
        const val ACTION_CANCEL = "com.privacydelete.app.CANCEL"
        private const val CHANNEL_ID = "wipe"
        private const val NOTIF_ID = 1
        private const val DONE_NOTIF_ID = 2

        fun start(ctx: Context) {
            val i = Intent(ctx, WipeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            WipeState.cancelRequested = true
            ctx.startService(Intent(ctx, WipeService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager

    private val notifUpdater = object : Runnable {
        override fun run() {
            if (worker?.isAlive == true) {
                nm.notify(NOTIF_ID, buildProgressNotification())
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Wipe progress", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            WipeState.cancelRequested = true
            if (worker?.isAlive != true) stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground quickly after startForegroundService
        val n = buildProgressNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }

        if (worker?.isAlive == true) return START_NOT_STICKY   // already running

        WipeState.cancelRequested = false
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivacyDelete:wipe")
            .apply { acquire(5 * 60 * 60 * 1000L) }   // max 5 h safety timeout

        worker = Thread({
            // Low priority = UI and other apps always come first -> no lag
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            try {
                WipeEngine(applicationContext).run()
            } catch (t: Throwable) {
                Log.e(WipeEngine.TAG, "Wipe crashed", t)
                WipeEngine.cleanupLeftovers(applicationContext)
                WipeState.phase = WipeState.Phase.ERROR
                WipeState.message = "Error: ${t.message}"
            }
            handler.post { finishUp() }
        }, "wipe-worker").also { it.start() }

        handler.post(notifUpdater)
        return START_NOT_STICKY
    }

    /** Android 15+: dataSync services get a daily time limit. Stop cleanly if hit. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(WipeEngine.TAG, "Foreground service time limit reached, stopping")
        WipeState.cancelRequested = true
    }

    private fun finishUp() {
        handler.removeCallbacks(notifUpdater)
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(DONE_NOTIF_ID, buildDoneNotification())
        stopSelf()
    }

    override fun onDestroy() {
        // If the system kills us, make sure the junk files are not left behind.
        WipeState.cancelRequested = true
        wakeLock?.let { if (it.isHeld) it.release() }
        handler.removeCallbacks(notifUpdater)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun builder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else Notification.Builder(this)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildProgressNotification(): Notification {
        val s = WipeState
        val cancelPi = PendingIntent.getService(
            this, 1, Intent(this, WipeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = "${Util.fmt(s.writtenBytes)} / ${Util.fmt(s.targetBytes)} · " +
                "${Util.fmt(s.bytesPerSec)}/s · ETA ${Util.fmtTime(s.etaSeconds())}"
        return builder()
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (s.message.isBlank()) "Preparing wipe…" else s.message)
            .setContentText(text)
            .setProgress(1000, s.progressPermille(), s.targetBytes == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, "Cancel", cancelPi).build())
            .build()
    }

    private fun buildDoneNotification(): Notification =
        builder()
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Privacy Delete")
            .setContentText(WipeState.message)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
}
