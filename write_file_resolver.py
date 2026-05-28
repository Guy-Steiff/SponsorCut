content = """\
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
        val file = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")
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

    fun createOutputTargetInTree(context: Context, treeUri: Uri, fileName: String): OutputTarget {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("Cannot open tree URI: $treeUri")
        val existing = tree.findFile(fileName)
        existing?.delete()
        val mimeType = if (fileName.endsWith(".mkv")) "video/x-matroska" else "video/mp4"
        val doc = tree.createFile(mimeType, fileName)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SponsorCut")
            }
            val insertUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: error("MediaStore insert failed")
            Log.i(TAG, "Output target (MediaStore): $insertUri")
            return OutputTarget.UriTarget(insertUri, "Movies/SponsorCut/$fileName")
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
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
"""

with open('/Users/gsm/projects/sponsorcut/app/src/main/java/com/sponsorcut/FileResolver.kt', 'w') as f:
    f.write(content)
print('Written', len(content), 'bytes')

