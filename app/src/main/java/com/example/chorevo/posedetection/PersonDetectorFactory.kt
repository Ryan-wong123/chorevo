package com.example.chorevo.posedetection

import android.content.Context

object PersonDetectorFactory {
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
            if (detector == null) {
                unavailable("Person detector runtime is not configured")
            } else {
                PersonDetectorInitialization(detector = detector)
            }
        } catch (_: Exception) {
            unavailable("Person detector could not be initialized")
        }
    }

    private fun unavailable(message: String): PersonDetectorInitialization {
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
}
