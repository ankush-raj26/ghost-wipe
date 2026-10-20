package com.privacydelete.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_NOTIF = 10
        private const val REQ_MEDIA_TRASH = 11
        private const val REQ_DELETE_TRASH = 12
        private const val REQ_MEDIA_SCAN = 13
        private const val REQ_TRASH_TEST = 14
        private const val REQ_LEGACY_STORAGE = 15
    }

    private lateinit var storageInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnWipe: Button
    private lateinit var btnCancel: Button
    private lateinit var btnTrash: Button
    private lateinit var btnAllFiles: Button
    private lateinit var btnProof: Button
    private lateinit var btnScan: Button
    private lateinit var btnDeleteLeftovers: Button
    private lateinit var scanResult: TextView

    private lateinit var scanner: RecoveryScanner
    private val ui = Handler(Looper.getMainLooper())
    private var pendingTrashCount = 0
    @Volatile private var scanning = false
    private var lastScan: RecoveryScanner.Result? = null

    private val refresher = object : Runnable {
        override fun run() {
            refreshUi()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        scanner = RecoveryScanner(applicationContext)

        storageInfo = findViewById(R.id.storageInfo)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnWipe = findViewById(R.id.btnWipe)
        btnCancel = findViewById(R.id.btnCancel)
        btnTrash = findViewById(R.id.btnTrash)
        btnAllFiles = findViewById(R.id.btnAllFiles)
        btnProof = findViewById(R.id.btnProof)
        btnScan = findViewById(R.id.btnScan)
        btnDeleteLeftovers = findViewById(R.id.btnDeleteLeftovers)
        scanResult = findViewById(R.id.scanResult)

        // If the app was killed mid-wipe last time, give that space back now.
        if (!WipeState.isBusy) WipeEngine.cleanupLeftovers(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        btnTrash.setOnClickListener { withGalleryAccess(REQ_MEDIA_TRASH) { emptyTrash() } }
        btnWipe.setOnClickListener { onWipeClicked() }
        btnCancel.setOnClickListener { WipeService.cancel(this) }
        btnAllFiles.setOnClickListener { requestAllFilesAccess() }
        btnProof.setOnClickListener { onProofClicked() }
        btnScan.setOnClickListener { withGalleryAccess(REQ_MEDIA_SCAN) { runScan() } }
        btnDeleteLeftovers.setOnClickListener { onDeleteLeftoversClicked() }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    // ---------------------------------------------------------------- UI

    private fun refreshUi() {
        val s = WipeState
        storageInfo.text = "Storage: ${Util.fmt(Util.freeBytes(this))} free of " +
                "${Util.fmt(Util.totalBytes(this))}   ·   Battery ${Util.batteryLevel(this)}%" +
                if (Util.isCharging(this)) " (charging)" else ""

        btnWipe.isEnabled = !s.isBusy && !scanning
        btnTrash.isEnabled = !s.isBusy
        btnCancel.isEnabled = s.isBusy && !s.cancelRequested
        btnScan.isEnabled = !s.isBusy && !scanning
        btnProof.isEnabled = !s.isBusy && !scanning
        btnDeleteLeftovers.isEnabled = !s.isBusy && !scanning &&
                (lastScan?.findings?.any { it.category.autoDelete } == true)

        val full = scanner.hasFullAccess()
        btnAllFiles.isEnabled = !full
        btnAllFiles.text = if (full) "Full scan allowed ✓" else "Allow full scan (All files access)"

        progress.progress = s.progressPermille()
        status.text = when (s.phase) {
            WipeState.Phase.IDLE -> s.message
            WipeState.Phase.RUNNING, WipeState.Phase.HOT_PAUSE ->
                "${s.message}\n" +
                        "Written : ${Util.fmt(s.writtenBytes)} / ${Util.fmt(s.targetBytes)}\n" +
                        "Speed   : ${Util.fmt(s.bytesPerSec)}/s\n" +
                        "Time left: ${Util.fmtTime(s.etaSeconds())}"
            else -> s.message
        }
    }

    // ---------------------------------------------------------- permissions

    private var afterGalleryPermission: (() -> Unit)? = null

    private fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Runs [action] once photo/video read permission is granted (or already was). */
    private fun withGalleryAccess(requestCode: Int, action: () -> Unit) {
        if (scanner.hasGalleryAccess()) { action(); return }
        afterGalleryPermission = action
        val missing = mediaPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        requestPermissions(missing.toTypedArray(), requestCode)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        val granted = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (code) {
            REQ_MEDIA_TRASH, REQ_MEDIA_SCAN -> {
                val action = afterGalleryPermission
                afterGalleryPermission = null
                if (granted) action?.invoke()
                else if (code == REQ_MEDIA_SCAN) {
                    toast("Without photo access the scan only sees part of the trash.")
                    runScan()
                } else {
                    toast("Photo access is needed to find trashed items. Choose \"Allow all\".")
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            toast("Turn on \"Allow access to manage all files\", then come back.")
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_LEGACY_STORAGE)
        }
    }

    // ---------------------------------------------------------- Step 2: wipe

    private fun onWipeClicked() {
        val level = Util.batteryLevel(this)
        if (level < 30 && !Util.isCharging(this)) {
            AlertDialog.Builder(this)
                .setTitle("Battery too low")
                .setMessage("Battery is $level%. Charge to 30% or plug in the charger first.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val free = Util.freeBytes(this)
        AlertDialog.Builder(this)
            .setTitle("Wipe ${Util.fmt(free)} of free space?")
            .setMessage(
                "Your current files will NOT be touched.\n\n" +
                        "The phone will be almost full until it finishes, so don't take photos " +
                        "or download things meanwhile. You can leave the app; progress shows in " +
                        "the notification."
            )
            .setPositiveButton("Start") { _, _ ->
                WipeState.phase = WipeState.Phase.RUNNING
                WipeState.message = "Starting…"
                WipeState.writtenBytes = 0
                WipeState.targetBytes = 0
                WipeService.start(this)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------- Step 1: empty trash

    private fun emptyTrash() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("System trash needs Android 11+. Empty your Gallery's bin by hand.")
            return
        }
        val items = scanner.queryTrashed()
        Log.i(WipeEngine.TAG, "Found ${items.size} trashed media items")
        if (items.isEmpty()) {
            WipeState.phase = WipeState.Phase.IDLE
            WipeState.message = "System trash is already empty. (Also check your Gallery / Google Photos bin by hand.)"
            return
        }
        pendingTrashCount = items.size
        val req = MediaStore.createDeleteRequest(contentResolver, items.map { it.uri })
        startIntentSenderForResult(req.intentSender, REQ_DELETE_TRASH, null, 0, 0, 0)
    }

    // ---------------------------------------------------- Recovery check

    private fun onProofClicked() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("The proof test needs Android 11 or newer.")
            return
        }
        Thread {
            val uri = scanner.createTestPhoto()
            ui.post {
                if (uri == null) {
                    toast("Could not create the test photo.")
                } else {
                    // Delete it exactly like a gallery app does: move to trash.
                    val req = MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
                    startIntentSenderForResult(req.intentSender, REQ_TRASH_TEST, null, 0, 0, 0)
                }
            }
        }.start()
    }

    private fun runScan() {
        if (scanning) return
        scanning = true
        scanResult.text = "Scanning… (this can take a minute)"
        Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val r = try {
                scanner.scan()
            } catch (e: Exception) {
                Log.e(RecoveryScanner.TAG, "Scan failed", e)
                null
            }
            ui.post {
                scanning = false
                lastScan = r
                scanResult.text = if (r == null) "Scan failed. Check Logcat (tag RecoveryScan)." else formatResult(r)
            }
        }.start()
    }

    private fun formatResult(r: RecoveryScanner.Result): String {
        val sb = StringBuilder()
        sb.append("Encryption : ${r.encryption}\n")
        sb.append("Scan mode  : ")
            .append(if (r.fullAccess) "FULL (all folders)" else "LIMITED (tap \"Allow full scan\")")
            .append("\n")
        if (!r.galleryAccess) sb.append("Photo access: NO, trash count may be incomplete\n")
        sb.append("Files seen : ${r.filesChecked}  (${r.millis / 1000.0}s)\n\n")

        sb.append("PROOF TEST PHOTO: ")
        sb.append(
            when (r.proof) {
                RecoveryScanner.ProofStatus.NOT_CREATED -> "not created yet"
                RecoveryScanner.ProofStatus.FOUND_IN_TRASH -> "FOUND in trash ✗ (recoverable)"
                RecoveryScanner.ProofStatus.FOUND_VISIBLE -> "still in gallery (not deleted)"
                RecoveryScanner.ProofStatus.GONE -> "GONE ✓ (not recoverable)"
            }
        ).append("\n\n")

        val trashSize = r.systemTrash.sumOf { it.size }
        sb.append("System trash: ${r.systemTrash.size} items (${Util.fmt(trashSize)})")
        if (r.systemTrash.isNotEmpty()) sb.append("  → use Step 1")
        sb.append("\n")
        r.systemTrash.take(5).forEach { sb.append("   • ${it.name}\n") }

        for (cat in RecoveryScanner.Category.values()) {
            val list = r.findings.filter { it.category == cat }
            sb.append("${cat.title}: ${list.size} (${Util.fmt(list.sumOf { it.size })})")
            if (list.isNotEmpty() && !cat.autoDelete) sb.append("  → check by hand")
            sb.append("\n")
            list.take(5).forEach { sb.append("   • ${it.relPath}\n") }
            if (list.size > 5) sb.append("   … and ${list.size - 5} more\n")
        }

        sb.append("\nVERDICT: ")
        sb.append(
            if (r.leftoverCount == 0)
                "Nothing a recovery app could find ✓"
            else
                "${r.leftoverCount} items a recovery app could still find ✗"
        )
        if (!r.fullAccess) sb.append("\n(Limited scan: allow full scan for a complete check)")
        sb.append("\n\nNote: this checks what recovery apps can reach. Raw-chip leftovers are " +
                "covered by encryption + Step 2 wipe.")
        return sb.toString()
    }

    private fun onDeleteLeftoversClicked() {
        val r = lastScan ?: return
        val toDelete = r.findings.filter { it.category.autoDelete }
        if (toDelete.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Delete ${toDelete.size} leftover files?")
            .setMessage(
                "Only recycle-bin files and thumbnail caches are deleted " +
                        "(${Util.fmt(toDelete.sumOf { it.size })}). Your real photos are not touched; " +
                        "thumbnails are rebuilt automatically.\n\nRun Step 2 afterwards to overwrite them."
            )
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val (ok, fail) = scanner.deleteLeftovers(toDelete)
                    ui.post {
                        toast("Deleted $ok files" + if (fail > 0) ", $fail failed" else "")
                        runScan()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------- results

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_DELETE_TRASH -> {
                WipeState.phase = WipeState.Phase.IDLE
                WipeState.message = if (resultCode == RESULT_OK)
                    "Permanently deleted $pendingTrashCount items from the trash. Now run Step 2."
                else "Trash was not emptied."
            }
            REQ_TRASH_TEST -> {
                if (resultCode == RESULT_OK) {
                    toast("Test photo moved to trash. Now tap Scan, it should be FOUND.")
                    withGalleryAccess(REQ_MEDIA_SCAN) { runScan() }
                } else {
                    toast("Test photo was created but not deleted.")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
