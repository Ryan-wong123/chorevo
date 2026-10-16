package com.example.chorevo.core

import android.content.Context
import java.io.File

/** Files the app leaves on the device for the signed-in user. */
object LocalUserFiles {
    /** Cache folders with the user's own recordings and shared result cards. */
    private val FOLDERS = listOf("captured_videos", "shared_results")

    /** Deletes them (on sign-out and account deletion). Never throws. */
    fun clear(context: Context) {
        runCatching {
            val cacheDir = context.cacheDir ?: return
            if (cacheDir.path.isBlank()) return
            FOLDERS.forEach { name -> File(cacheDir, name).deleteRecursively() }
        }
    }
}
