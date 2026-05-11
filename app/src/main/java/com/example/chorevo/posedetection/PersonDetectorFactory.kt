package com.example.chorevo.posedetection

import android.content.Context
import android.util.Log

object PersonDetectorFactory {
    private const val TAG = "PersonDetectorFactory"
    const val WARNING_PERSON_DETECTOR_UNAVAILABLE = "personDetectorUnavailable"

    fun create(
        context: Context,
        config: PersonDetectorConfig = PersonDetectorConfig.disabled(),
        detectorProvider: (() -> PersonDetector)? = null
    ): PersonDetectorInitialization {
        if (!config.enabled) {
            return PersonDetectorInitialization(detector = NoOpPersonDetector())
        }

        if (!assetExists(context, config.modelAssetPath)) {
            return unavailable("Person detector model asset is unavailable")
        }

        return try {
            val detector = detectorProvider?.invoke()
                ?: TfliteYoloPersonDetector(context = context, config = config)
            PersonDetectorInitialization(detector = detector)
        } catch (exception: Exception) {
            safeLogWarning("Person detector initialization failed; using MediaPipe-only fallback.", exception)
            unavailable("Person detector could not be initialized")
        }
    }

    private fun unavailable(message: String): PersonDetectorInitialization {
        safeLogWarning("$message; using MediaPipe-only fallback.")
        return PersonDetectorInitialization(
            detector = NoOpPersonDetector(),
            warning = PoseAnalysisWarning(
                code = WARNING_PERSON_DETECTOR_UNAVAILABLE,
                message = message
            )
        )
    }

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeLogWarning(message: String, throwable: Throwable? = null) {
        runCatching {
            if (throwable == null) {
                Log.w(TAG, message)
            } else {
                Log.w(TAG, message, throwable)
            }
        }
    }
}
