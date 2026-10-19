package com.example.chorevo.core

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner

/**
 * A live camera preview + a continuous stream of ImageProxy frames to analyzer.
 * Delegates camera binding and use case construction to CameraBinder and CameraUseCaseFactory.
 * The camera is released when this preview leaves the composition.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    context: Context,
    lifecycleOwner: LifecycleOwner,
    analyzer: ImageAnalysis.Analyzer
) {
    val previewView = remember { PreviewView(context).apply {
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }}

    val cameraBinder = remember { CameraBinder() }
    DisposableEffect(cameraBinder) {
        onDispose { cameraBinder.unbind() }
    }

    AndroidView(
        modifier = modifier,
        factory = { previewView }
    )

    LaunchedEffect(analyzer, context, lifecycleOwner, previewView) {
        cameraBinder.bind(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            analyzer = analyzer
        )
    }
}
