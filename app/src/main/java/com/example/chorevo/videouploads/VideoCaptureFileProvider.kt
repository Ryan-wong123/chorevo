package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VideoCaptureFileProvider {

    /** Creates a temporary uri for a camera-recorded video. */
    fun createVideoCaptureUri(context: Context): Uri? {
        return runCatching {
            val outputDirectory = File(context.cacheDir, "captured_videos").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val videoFile = File(outputDirectory, "dance_capture_$timestamp.mp4")
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                videoFile
            )
        }.getOrNull()
    }
}
