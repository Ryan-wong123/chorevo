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
    val templateGenerationStatus: String = "ready",
    val templateGenerationError: String = ""
) {
    val isTemplateReady: Boolean
        get() = templateGenerationStatus.equals("ready", ignoreCase = true)
}
