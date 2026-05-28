package com.sponsorcut

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

sealed class OutputTarget {
    abstract val label: String
    data class FileTarget(val file: File) : OutputTarget() {
        override val label get() = file.absolutePath
    }
    data class UriTarget(val uri: Uri, override val label: String) : OutputTarget()
}

object FileResolver {

    private const val TAG = "FileResolver"

    fun uriToFile(context: Context, uri: Uri): File {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open URI")
        // Preserve the original extension so FfmpegEngine can use the right container
        val sourceName = getDisplayName(context, uri)
        val ext = sourceName.substringAfterLast('.', "mp4").lowercase().ifBlank { "mp4" }
        val file = File(context.cacheDir, "input_${System.currentTimeMillis()}.$ext")
        file.outputStream().use { output -> input.copyTo(output) }
        return file
    }

    fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return cursor.getString(idx)
                }
            }
        return uri.lastPathSegment ?: "video.mp4"
    }

    fun outputFileNameFromSource(sourceName: String): String {
        val dot = sourceName.lastIndexOf('.')
        val base = if (dot > 0) sourceName.substring(0, dot) else sourceName
        val ext = if (dot > 0) sourceName.substring(dot) else ".mp4"
        val ts = System.currentTimeMillis() / 1000
        return "${base}_clean_${ts}${ext}"
    }

    private fun mimeTypeFor(fileName: String): String = when {
        fileName.endsWith(".mkv", ignoreCase = true) -> "video/x-matroska"
        fileName.endsWith(".m4a", ignoreCase = true) -> "audio/mp4"
        fileName.endsWith(".mp3", ignoreCase = true) -> "audio/mpeg"
        fileName.endsWith(".aac", ignoreCase = true) -> "audio/aac"
        fileName.endsWith(".opus", ignoreCase = true) -> "audio/ogg"
        else -> "video/mp4"
    }

    fun createOutputTargetInTree(context: Context, treeUri: Uri, fileName: String): OutputTarget {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Cannot open tree URI: $treeUri")
        val existing = tree.findFile(fileName)
        existing?.delete()
        val doc = tree.createFile(mimeTypeFor(fileName), fileName)
            ?: error("Cannot create file in tree: $fileName")
        val path = treeUri.lastPathSegment?.let { "$it/$fileName" } ?: fileName
        return OutputTarget.UriTarget(doc.uri, path)
    }

    fun createOutputTarget(context: Context, sourceUri: Uri, fileName: String, inputFile: File): OutputTarget {
        val realPath = tryGetRealPath(context, sourceUri)
        if (realPath != null) {
            val parent = File(realPath).parentFile
            if (parent != null && parent.canWrite()) {
                val out = File(parent, fileName)
                Log.i(TAG, "Output target (same folder): ${out.absolutePath}")
                return OutputTarget.FileTarget(out)
            }
        }
        return createFallbackTarget(context, fileName)
    }

    private fun tryGetRealPath(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                            if (idx >= 0) {
                                val path = cursor.getString(idx)
                                if (!path.isNullOrBlank()) return path
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.w(TAG, "DATA column failed: ${e.message}")
            }
            val uriPath = uri.path ?: return null
            val marker = "/external/"
            val idx = uriPath.indexOf(marker)
            if (idx >= 0) {
                val candidate = "/storage/emulated/0/" + uriPath.substring(idx + marker.length)
                if (File(candidate).exists()) return candidate
            }
        }
        return null
    }

    private fun createFallbackTarget(context: Context, fileName: String): OutputTarget {
        val mime = mimeTypeFor(fileName)
        val isAudio = mime.startsWith("audio/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isAudio) "Music/SponsorCut" else "Movies/SponsorCut")
            }
            val collection = if (isAudio)
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val insertUri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore insert failed")
            Log.i(TAG, "Output target (MediaStore): $insertUri")
            val folder = if (isAudio) "Music/SponsorCut" else "Movies/SponsorCut"
            return OutputTarget.UriTarget(insertUri, "$folder/$fileName")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES),
                "SponsorCut"
            )
            dir.mkdirs()
            val out = File(dir, fileName)
            Log.i(TAG, "Output target (fallback file): ${out.absolutePath}")
            return OutputTarget.FileTarget(out)
        }
    }

    fun writeToOutputTarget(context: Context, tempFile: File, target: OutputTarget) {
        when (target) {
            is OutputTarget.FileTarget -> tempFile.copyTo(target.file, overwrite = true)
            is OutputTarget.UriTarget -> {
                context.contentResolver.openOutputStream(target.uri)?.use { out ->
                    tempFile.inputStream().use { it.copyTo(out) }
                } ?: error("Cannot open output URI for writing: ${target.uri}")
            }
        }
    }
}
