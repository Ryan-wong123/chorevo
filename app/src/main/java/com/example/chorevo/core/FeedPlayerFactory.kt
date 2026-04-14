package com.example.chorevo.core

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/** Creates cached feed exo player. */
@androidx.annotation.OptIn(UnstableApi::class)
fun createCachedFeedExoPlayer(
    context: Context,
    repeat: Boolean = true
): ExoPlayer {
    val simpleCache = VideoPlayerCache.getInstance(context)

    val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)

    val upstreamFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)

    val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(simpleCache)
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
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
}
