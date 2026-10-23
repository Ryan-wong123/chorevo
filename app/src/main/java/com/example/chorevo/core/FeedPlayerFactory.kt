package com.example.chorevo.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Creates ExoPlayers for feed videos that share one on-disk video cache. */
@androidx.annotation.OptIn(UnstableApi::class)
@Singleton
class FeedPlayerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoCache: SimpleCache
) {
    fun create(repeat: Boolean = true): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)

        val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(videoCache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mediaSourceFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                1500, // minBufferMs
                5000, // maxBufferMs
                750,  // bufferForPlaybackMs
                1500  // bufferForPlaybackAfterRebufferMs
            )
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = if (repeat) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }
                playWhenReady = false
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }
    }
}

/** The 200 MB video cache. Media3 allows only one SimpleCache per folder, so it is a singleton. */
@Module
@InstallIn(SingletonComponent::class)
object VideoCacheModule {
    @androidx.annotation.OptIn(UnstableApi::class)
    @Provides
    @Singleton
    fun provideVideoCache(@ApplicationContext context: Context): SimpleCache = SimpleCache(
        File(context.cacheDir, "media_cache"),
        LeastRecentlyUsedCacheEvictor(200L * 1024L * 1024L),
        StandaloneDatabaseProvider(context)
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FeedPlayerEntryPoint {
    fun feedPlayerFactory(): FeedPlayerFactory
}

/** The app's [FeedPlayerFactory], for composables (which can't take constructor injection). */
@Composable
fun rememberFeedPlayerFactory(): FeedPlayerFactory {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, FeedPlayerEntryPoint::class.java).feedPlayerFactory()
    }
}
