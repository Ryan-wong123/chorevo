package com.example.chorevo.core

import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

/**
 * Factory for creating CameraX use cases.
 * Keeps use case construction separate from binding and UI logic.
 */
object CameraUseCaseFactory {

    // connects preview frames to the preview view
    fun createPreview(previewView: PreviewView): Preview {
        return Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    // analyzes image frames using the provided analyzer
    fun createImageAnalysis(
        context: Context,
        analyzer: ImageAnalysis.Analyzer
    ): ImageAnalysis {
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(ContextCompat.getMainExecutor(context), analyzer) }
    }
}