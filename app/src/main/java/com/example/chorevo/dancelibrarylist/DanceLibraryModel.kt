package com.example.chorevo.dancelibrarylist

import com.example.chorevo.posedetection.PoseTemplate

data class DanceLibraryModel(
    val danceId: String = "",
    val title: String = "",
    val description: String = "",
    val danceType: String = "",
    val educatorUid: String? = "",
    val educatorName: String = "",
    val videoUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val enabled: Boolean = true,
    val template: PoseTemplate? = null,
    val templateStoragePath: String = "",
    val templateGenerationStatus: String = "ready",
    val templateGenerationError: String = "",
    val dancerCount: Int = 0,
    val templateReviewStatus: String = ""
) {
    val isTemplateReady: Boolean
        get() = templateGenerationStatus.equals("ready", ignoreCase = true)
}
