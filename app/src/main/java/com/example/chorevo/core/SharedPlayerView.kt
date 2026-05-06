package com.example.chorevo.core

import android.view.LayoutInflater
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.chorevo.R

/** Handles shared player view. */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SharedPlayerView(
    exoPlayer: ExoPlayer,
    modifier: Modifier = Modifier,
    useController: Boolean = false,
    isMirrored: Boolean = false
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = LayoutInflater.from(ctx)
                .inflate(R.layout.shared_player_view, null, false) as PlayerView

            view.player = exoPlayer
            view.useController = useController
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            view.keepScreenOn = true
            view.videoSurfaceView?.scaleX = if (isMirrored) -1f else 1f
            view
        },
        update = { view ->
            view.player = exoPlayer
            view.useController = useController
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            view.keepScreenOn = true
            view.videoSurfaceView?.scaleX = if (isMirrored) -1f else 1f
        }
    )
}
