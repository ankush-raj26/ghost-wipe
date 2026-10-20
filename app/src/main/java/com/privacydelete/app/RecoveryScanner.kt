package com.privacydelete.app

import android.Manifest
import android.annotation.TargetApi
import android.app.admin.DevicePolicyManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.Date

/**
 * "Recovery proof": looks at the phone the same way a normal (non-root)
 * recovery app like DiskDigger does, and reports anything it could still
 * restore:
 *   - photos/videos sitting in the system trash
 *   - gallery recycle-bin files (.trashed-*, .Trash, .recycle, …)
 *   - thumbnail caches (.thumbnails, .thumbdata*)
 *   - other hidden photos/videos
 * plus the proof-test photo created by createTestPhoto().
 *
 * It only READS. Nothing is deleted unless deleteLeftovers() is called.
 */
class RecoveryScanner(private val ctx: Context) {

    enum class Category(val title: String, val autoDelete: Boolean) {
        TRASH_FILES("Recycle-bin files", true),
        THUMBNAILS("Thumbnail caches", true),
        HIDDEN_MEDIA("Other hidden photos/videos", false)
    }

    enum class ProofStatus { NOT_CREATED, FOUND_IN_TRASH, FOUND_VISIBLE, GONE }

    data class Finding(val category: Category, val relPath: String, val size: Long)
    data class TrashedItem(val uri: Uri, val name: String, val size: Long)

    data class Result(
        val encryption: String,
        val encrypted: Boolean,
        val fullAccess: Boolean,
        val galleryAccess: Boolean,
        val filesChecked: Int,
        val systemTrash: List<TrashedItem>,
        val findings: List<Finding>,
        val proof: ProofStatus,
        val millis: Long
    ) {
        val leftoverCount get() = systemTrash.size + findings.size
    }

    companion object {
        const val TAG = "RecoveryScan"
        private const val PREFS = "proof"
        private const val KEY_ID = "proof_id"
        const val TEST_FOLDER = "PrivacyDeleteTest"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root: File get() = Environment.getExternalStorageDirectory()

    // ------------------------------------------------------------ permissions

    fun hasFullAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED

    fun hasGalleryAccess(): Boolean {
        if (hasFullAccess()) return true
        val perms = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    // ------------------------------------------------------------ encryption

    fun encryptionStatus(): Pair<String, Boolean> {
        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        return when (dpm.storageEncryptionStatus) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER ->
                "File-based encryption ON (chip-level recovery blocked)" to true
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY ->
                "Full-disk encryption ON" to true
            else -> "NOT encrypted: free-space wipe is essential" to false
        }
    }

    // ------------------------------------------------------------ system trash

    fun queryTrashed(): List<TrashedItem> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val out = ArrayList<TrashedItem>()
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE
        )
        for (collection in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        )) {
            try {
                ctx.contentResolver.query(collection, projection, args, null)?.use { c ->
                    val idC = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeC = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    while (c.moveToNext()) {
                        out.add(
                            TrashedItem(
                                ContentUris.withAppendedId(collection, c.getLong(idC)),
                                c.getString(nameC) ?: "?",
                                c.getLong(sizeC)
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Trash query failed for $collection", e)
            }
        }
        return out
    }

    // ------------------------------------------------------------ proof photo

    /** Creates a test photo in Pictures/PrivacyDeleteTest. Android 11+. */
    @TargetApi(30)
    fun createTestPhoto(): Uri? {
        val bmp = Bitmap.createBitmap(1080, 1080, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF1565C0.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 72f; textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        canvas.drawText("PRIVACY DELETE", 540f, 470f, p)
        canvas.drawText("TEST PHOTO", 540f, 570f, p)
        p.textSize = 34f; p.isFakeBoldText = false
        canvas.drawText(Date().toString(), 540f, 660f, p)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "privacydelete_test_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + TEST_FOLDER)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Could not write test photo", e)
            resolver.delete(uri, null, null)
            return null
        } finally {
            bmp.recycle()
        }
        prefs.edit().putLong(KEY_ID, ContentUris.parseId(uri)).apply()
        Log.i(TAG, "Created test photo $uri")
        return uri
    }

    fun proofStatus(): ProofStatus {
        val id = prefs.getLong(KEY_ID, -1L)
        if (id < 0 || Build.VERSION.SDK_INT < 30) return ProofStatus.NOT_CREATED
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns._ID}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(id.toString()))
        }
        return try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED),
                args, null
            )?.use { c ->
                if (!c.moveToFirst()) ProofStatus.GONE
                else if (c.getInt(1) == 1) ProofStatus.FOUND_IN_TRASH
                else ProofStatus.FOUND_VISIBLE
            } ?: ProofStatus.GONE
        } catch (e: Exception) {
            Log.e(TAG, "Proof query failed", e)
            ProofStatus.GONE
        }
    }

    // ------------------------------------------------------------ full scan

    fun scan(): Result {
        val start = System.currentTimeMillis()
        val (enc, encOk) = encryptionStatus()
        val full = hasFullAccess()
        val findings = ArrayList<Finding>()
        var checked = 0

        if (full) {
            walk { f, rel ->
                checked++
                val cat = classify(rel) ?: return@walk
                val thumbData = cat == Category.THUMBNAILS && f.name.startsWith(".thumbdata")
                if (thumbData || looksLikeMedia(f)) findings.add(Finding(cat, rel, f.length()))
            }
        }

        val result = Result(
            encryption = enc,
            encrypted = encOk,
            fullAccess = full,
            galleryAccess = hasGalleryAccess(),
            filesChecked = checked,
            systemTrash = queryTrashed(),
            findings = findings,
            proof = proofStatus(),
            millis = System.currentTimeMillis() - start
        )
        Log.i(TAG, "Scan: checked=$checked trash=${result.systemTrash.size} " +
                "findings=${findings.size} proof=${result.proof} in ${result.millis}ms")
        return result
    }

    /** Deletes only recycle-bin files and thumbnail caches. Returns (deleted, failed). */
    fun deleteLeftovers(findings: List<Finding>): Pair<Int, Int> {
        var ok = 0
        var fail = 0
        for (f in findings.filter { it.category.autoDelete }) {
            if (File(root, f.relPath).delete()) ok++ else fail++
        }
        Log.i(TAG, "Deleted leftovers ok=$ok fail=$fail")
        return ok to fail
    }

    // ------------------------------------------------------------ helpers

    private fun walk(onFile: (File, String) -> Unit) {
        val base = root.absolutePath
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                val rel = f.absolutePath.removePrefix(base).trimStart('/')
                if (f.isDirectory) {
                    if (depth < 15 && rel != "Android/data" && rel != "Android/obb") {
                        stack.addLast(f to depth + 1)
                    }
                } else {
                    onFile(f, rel)
                }
            }
        }
    }

    /** Decides if a path is a place where "deleted" stuff hides. null = normal file, skip. */
    private fun classify(rel: String): Category? {
        val parts = rel.split('/')
        val name = parts.last().lowercase()
        val dirs = parts.dropLast(1).map { it.lowercase() }
        return when {
            name.startsWith(".trashed-") -> Category.TRASH_FILES
            dirs.any {
                it.contains("trash") || it.contains("recycle") || it.contains("deleted") ||
                        it == "bin" || it == ".bin"
            } -> Category.TRASH_FILES
            dirs.any { it.contains("thumb") } || name.startsWith(".thumbdata") -> Category.THUMBNAILS
            dirs.any { it.startsWith(".") } || name.startsWith(".") -> Category.HIDDEN_MEDIA
            else -> null
        }
    }

    /** Checks the first bytes of a file for JPEG/PNG/GIF/WEBP/MP4/HEIC signatures. */
    private fun looksLikeMedia(f: File): Boolean {
        if (f.length() < 12) return false
        val h = ByteArray(12)
        try {
            FileInputStream(f).use { if (it.read(h) < 12) return false }
        } catch (e: Exception) {
            return false
        }
        val b = IntArray(12) { h[it].toInt() and 0xFF }
        return (b[0] == 0xFF && b[1] == 0xD8 && b[2] == 0xFF) ||                       // JPEG
                (b[0] == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) ||       // PNG
                (b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46) ||                       // GIF
                (b[0] == 0x52 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x46 &&
                        b[8] == 0x57 && b[9] == 0x45 && b[10] == 0x42 && b[11] == 0x50) || // WEBP
                (b[4] == 0x66 && b[5] == 0x74 && b[6] == 0x79 && b[7] == 0x70)          // MP4/MOV/HEIC
    }
}
