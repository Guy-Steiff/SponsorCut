package com.sponsorcut

import android.content.Context
import android.net.Uri
import java.io.File

object FileResolver {

    fun uriToFile(context: Context, uri: Uri): File {

        val input = context.contentResolver.openInputStream(uri)
            ?: error("Cannot open URI")

        val file = File(context.cacheDir, "input_${System.currentTimeMillis()}.mp4")

        file.outputStream().use { output ->
            input.copyTo(output)
        }

        return file
    }
}
