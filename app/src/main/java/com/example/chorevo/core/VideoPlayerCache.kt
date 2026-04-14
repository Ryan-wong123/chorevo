package com.example.chorevo.core

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object VideoPlayerCache {

    @Volatile
    private var simpleCache: SimpleCache? = null

    /** Returns instance. */
    fun getInstance(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: buildCache(context.applicationContext).also {
                simpleCache = it
            }
        }
    }

    /** Builds cache. */
    private fun buildCache(context: Context): SimpleCache {
        val cacheDir = File(context.cacheDir, "media_cache")
        val evictor = LeastRecentlyUsedCacheEvictor(200L * 1024L * 1024L) // 200 MB
        val databaseProvider = StandaloneDatabaseProvider(context)

        return SimpleCache(cacheDir, evictor, databaseProvider)
    }
}
