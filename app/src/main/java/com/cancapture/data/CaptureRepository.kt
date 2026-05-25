package com.cancapture.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.time.Instant

class CaptureRepository(private val context: Context) {

    private val capturesDir: File = File(context.filesDir, "captures").apply { mkdirs() }

    fun directory(): File = capturesDir

    fun newTempFile(): File {
        val name = "tmp_${System.currentTimeMillis()}.asc"
        return File(capturesDir, name)
    }

    fun list(): List<Capture> {
        val files = capturesDir.listFiles { f -> f.isFile && f.extension.equals("asc", ignoreCase = true) }
            ?: return emptyList()
        return files.mapNotNull { ascFile ->
            if (ascFile.name.startsWith("tmp_")) return@mapNotNull null
            val meta = readMeta(ascFile)
            Capture(
                file = ascFile,
                displayName = meta?.optString("name").takeUnless { it.isNullOrEmpty() }
                    ?: ascFile.nameWithoutExtension,
                createdAt = meta?.optString("createdAt")
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                    ?: Instant.ofEpochMilli(ascFile.lastModified()),
                durationMs = meta?.optLong("durationMs") ?: 0L,
                frameCount = meta?.optInt("frameCount") ?: 0,
                sizeBytes = ascFile.length()
            )
        }.sortedByDescending { it.createdAt }
    }

    /**
     * Renames [tempFile] to a sanitized version of [name].asc and writes a sidecar
     * .meta.json with createdAt / durationMs / frameCount.
     */
    fun finalizeCapture(
        tempFile: File,
        name: String,
        durationMs: Long,
        frameCount: Int,
        createdAt: Instant
    ): File {
        val safeName = sanitize(name)
        val target = uniqueFile(capturesDir, safeName, "asc")
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        val meta = JSONObject().apply {
            put("name", name)
            put("createdAt", createdAt.toString())
            put("durationMs", durationMs)
            put("frameCount", frameCount)
        }
        File(capturesDir, "${target.nameWithoutExtension}.meta.json").writeText(meta.toString())
        return target
    }

    fun discardTemp(tempFile: File) {
        if (tempFile.exists()) tempFile.delete()
    }

    fun delete(capture: Capture) {
        capture.file.delete()
        File(capturesDir, "${capture.file.nameWithoutExtension}.meta.json").delete()
    }

    fun buildShareIntent(capture: Capture): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, capture.file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, capture.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun readMeta(ascFile: File): JSONObject? {
        val meta = File(capturesDir, "${ascFile.nameWithoutExtension}.meta.json")
        if (!meta.exists()) return null
        return runCatching { JSONObject(meta.readText()) }.getOrNull()
    }

    private fun sanitize(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "capture" }
        return cleaned.take(80)
    }

    private fun uniqueFile(dir: File, baseName: String, ext: String): File {
        var candidate = File(dir, "$baseName.$ext")
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($i).$ext")
            i++
        }
        return candidate
    }
}
