package com.example.chorevo.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Files the app leaves on the device for the signed-in user. */
object LocalUserFiles {
    /** Cache folders with the user's own recordings and shared result cards. */
    private val FOLDERS = listOf("captured_videos", "shared_results")

    /** Deletes them (on sign-out and account deletion), off the main thread. Never throws. */
    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            runCatching {
                val cacheDir = context.cacheDir
                if (cacheDir != null && cacheDir.path.isNotBlank()) {
                    FOLDERS.forEach { name -> File(cacheDir, name).deleteRecursively() }
                }
            }
        }
    }
}
