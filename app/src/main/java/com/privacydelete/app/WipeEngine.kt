package com.privacydelete.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom

/**
 * The free-space wiper.
 *
 * It ONLY creates new junk files inside this app's private folder until the
 * storage is nearly full, then deletes them. It never opens, reads or changes
 * any existing file, so the user's current photos/apps are safe. Space that
 * belonged to already-deleted files gets overwritten by the junk.
 *
 * Kept light on the phone by:
 *  - one 8 MB buffer filled with random bytes ONCE and reused (almost no CPU)
 *  - running on a background-priority thread (set by WipeService)
 *  - syncing to flash every 256 MB so RAM doesn't fill with unwritten data
 *    (that's what normally makes phones stutter during big writes)
 *  - slowing down / pausing when the phone reports it is getting hot
 *  - stopping SAFETY_MARGIN before full so Android keeps working
 */
class WipeEngine(private val context: Context) {

    companion object {
        const val TAG = "WipeEngine"
        const val CHUNK = 8 * 1024 * 1024                 // 8 MB per write
        const val FILE_SIZE = 1024L * 1024 * 1024         // 1 GB per junk file
        const val SYNC_EVERY = 256L * 1024 * 1024         // flush to flash every 256 MB
        const val SAFETY_MARGIN = 400L * 1024 * 1024      // leave 400 MB free
        const val MIN_BATTERY = 15                        // stop below this if not charging

        fun junkDir(ctx: Context) = File(ctx.filesDir, "wipe_junk")

        /** Removes junk left behind if the app was killed mid-wipe. */
        fun cleanupLeftovers(ctx: Context) {
            val dir = junkDir(ctx)
            if (dir.exists()) {
                Log.i(TAG, "Removing leftover junk from a previous run")
                dir.deleteRecursively()
            }
        }
    }

    private val state = WipeState
    private val pm = context.getSystemService(PowerManager::class.java)

    fun run() {
        val dir = junkDir(context)
        dir.deleteRecursively()
        dir.mkdirs()

        val buf = ByteArray(CHUNK).also { SecureRandom().nextBytes(it) }

        val startFree = Util.freeBytes(context)
        state.targetBytes = (startFree - SAFETY_MARGIN).coerceAtLeast(0)
        state.writtenBytes = 0
        state.bytesPerSec = 0
        state.phase = WipeState.Phase.RUNNING
        state.message = "Wiping free space…"
        Log.i(TAG, "Start: free=${Util.fmt(startFree)} target=${Util.fmt(state.targetBytes)}")

        val startTime = SystemClock.elapsedRealtime()
        var fileIndex = 0
        var chunkCount = 0L
        var stoppedForBattery = false
        var error: String? = null

        try {
            outer@ while (!state.cancelRequested) {
                if (Util.freeBytes(context) - SAFETY_MARGIN < CHUNK) break   // done: disk is full

                val file = File(dir, "junk_${fileIndex++}.bin")
                FileOutputStream(file).use { out ->
                    var inFile = 0L
                    var sinceSync = 0L
                    while (inFile < FILE_SIZE && !state.cancelRequested) {
                        // Every 64 MB: check heat and battery
                        if (chunkCount % 8 == 0L) {
                            coolDownIfHot()
                            if (!Util.isCharging(context) && Util.batteryLevel(context) < MIN_BATTERY) {
                                stoppedForBattery = true
                                state.cancelRequested = true
                                break
                            }
                        }

                        val room = Util.freeBytes(context) - SAFETY_MARGIN
                        if (room <= 0) break
                        val n = minOf(CHUNK.toLong(), room, FILE_SIZE - inFile).toInt()

                        out.write(buf, 0, n)
                        inFile += n
                        sinceSync += n
                        chunkCount++
                        state.writtenBytes += n

                        if (sinceSync >= SYNC_EVERY) {
                            out.fd.sync()
                            sinceSync = 0
                        }

                        val elapsedMs = SystemClock.elapsedRealtime() - startTime
                        if (elapsedMs > 0) state.bytesPerSec = state.writtenBytes * 1000 / elapsedMs
                    }
                    out.fd.sync()
                }
                if (Util.freeBytes(context) - SAFETY_MARGIN <= 0) break@outer
            }
        } catch (e: IOException) {
            // "No space left on device" is a normal way to finish.
            if (e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true) {
                Log.i(TAG, "Storage full (ENOSPC) — finishing")
            } else {
                Log.e(TAG, "Write failed", e)
                error = e.message
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            error = e.message
        } finally {
            // ALWAYS give the space back, whatever happened.
            state.phase = WipeState.Phase.CLEANING
            state.message = "Deleting junk files…"
            dir.deleteRecursively()
        }

        val secs = (SystemClock.elapsedRealtime() - startTime) / 1000
        Log.i(TAG, "End: wrote=${Util.fmt(state.writtenBytes)} in ${secs}s, " +
                "avg=${Util.fmt(state.bytesPerSec)}/s, freeNow=${Util.fmt(Util.freeBytes(context))}")

        when {
            error != null -> {
                state.phase = WipeState.Phase.ERROR
                state.message = "Error: $error (junk was cleaned up)"
            }
            stoppedForBattery -> {
                state.phase = WipeState.Phase.CANCELLED
                state.message = "Stopped: battery below $MIN_BATTERY%. Plug in and run again."
            }
            state.cancelRequested -> {
                state.phase = WipeState.Phase.CANCELLED
                state.message = "Cancelled. Wiped ${Util.fmt(state.writtenBytes)} before stopping."
            }
            else -> {
                state.phase = WipeState.Phase.DONE
                state.message = "Done! Overwrote ${Util.fmt(state.writtenBytes)} of free space " +
                        "in ${Util.fmtTime(secs)}."
            }
        }
    }

    /** Slows down when warm, pauses completely when hot (Android 10+). */
    private fun coolDownIfHot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var status = pm.currentThermalStatus
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            state.phase = WipeState.Phase.HOT_PAUSE
            state.message = "Phone is hot — pausing to cool down…"
            Log.w(TAG, "Thermal status $status, pausing")
            while (status >= PowerManager.THERMAL_STATUS_MODERATE && !state.cancelRequested) {
                SystemClock.sleep(3000)
                status = pm.currentThermalStatus
            }
            state.phase = WipeState.Phase.RUNNING
            state.message = "Wiping free space…"
        } else if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
            SystemClock.sleep(150)   // gentle slow-down
        }
    }
}
