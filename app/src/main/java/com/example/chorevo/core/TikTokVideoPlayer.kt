package com.example.chorevo.core

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView


/** Creates feed exo player. */
@androidx.annotation.OptIn(UnstableApi::class)
fun createFeedExoPlayer(
    context: Context,
    repeat: Boolean = true
): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            1500, // minBufferMs
            5000, // maxBufferMs
            750,  // bufferForPlaybackMs
            1500  // bufferForPlaybackAfterRebufferMs
        )
        .build()

    return ExoPlayer.Builder(context)
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
