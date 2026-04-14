package com.example.chorevo.core

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Handles binding CameraX use cases to the lifecycle.
 * Keeps camera binding / unbinding logic separate from UI and use case construction.
 */
object CameraBinder {

    private var cameraProvider: ProcessCameraProvider? = null

    /** Binds the current data source. */
    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()

            val preview = CameraUseCaseFactory.createPreview(previewView)
            val analysis = CameraUseCaseFactory.createImageAnalysis(context, analyzer)

            cameraProvider?.unbindAll()

            cameraProvider?.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
        }, ContextCompat.getMainExecutor(context))
    }

    /** Releases the current binding. */
    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }
}
