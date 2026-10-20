package com.privacydelete.app

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import java.util.Locale

/**
 * Shared, thread-safe-enough state between the wipe thread, the service
 * (notification) and the screen. Only the wipe thread writes the numbers;
 * the UI just reads them every half second.
 */
object WipeState {
    enum class Phase { IDLE, RUNNING, HOT_PAUSE, CLEANING, DONE, CANCELLED, ERROR }

    @Volatile var phase = Phase.IDLE
    @Volatile var writtenBytes = 0L
    @Volatile var targetBytes = 0L
    @Volatile var bytesPerSec = 0L
    @Volatile var message = ""
    @Volatile var cancelRequested = false

    val isBusy get() = phase == Phase.RUNNING || phase == Phase.HOT_PAUSE || phase == Phase.CLEANING

    /** 0..1000 for the progress bar */
    fun progressPermille(): Int =
        if (targetBytes <= 0) 0 else ((writtenBytes * 1000) / targetBytes).toInt().coerceIn(0, 1000)

    fun etaSeconds(): Long =
        if (bytesPerSec <= 0) -1 else (targetBytes - writtenBytes).coerceAtLeast(0) / bytesPerSec
}

object Util {
    fun freeBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).availableBytes
    fun totalBytes(ctx: Context): Long = StatFs(ctx.filesDir.path).totalBytes

    fun batteryLevel(ctx: Context): Int =
        ctx.getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun isCharging(ctx: Context): Boolean =
        ctx.getSystemService(BatteryManager::class.java).isCharging

    fun fmt(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024 * 1024)
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb)
        else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024))
    }

    fun fmtTime(sec: Long): String =
        if (sec < 0) "--:--" else String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
}
