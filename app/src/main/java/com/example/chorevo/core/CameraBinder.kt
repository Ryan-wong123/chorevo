package com.example.chorevo.core

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Handles binding CameraX use cases to the lifecycle.
 * Keeps camera binding / unbinding logic separate from UI and use case construction.
 * One instance per camera preview, which must call [unbind] when it goes away.
 */
class CameraBinder {

    private var cameraProvider: ProcessCameraProvider? = null
    private var released = false

    // Frames are analysed here, one at a time, never on the UI thread.
    private var analysisExecutor: ExecutorService? = null

    private fun analysisExecutor(): ExecutorService =
        analysisExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "camera-analysis")
        }.also { analysisExecutor = it }

    /** Binds the current data source. */
    fun bind(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        analyzer: ImageAnalysis.Analyzer
    ) {
        released = false
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            // The preview may have gone away while the camera was starting.
            if (released) return@addListener
            cameraProvider = cameraProviderFuture.get()

            val preview = CameraUseCaseFactory.createPreview(previewView)
            val analysis = CameraUseCaseFactory.createImageAnalysis(analysisExecutor(), analyzer)

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
        released = true
        cameraProvider?.unbindAll()
        cameraProvider = null
        // A frame already being analysed finishes; no new ones start.
        analysisExecutor?.shutdown()
        analysisExecutor = null
    }
}
