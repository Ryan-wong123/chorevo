package com.example.chorevo.posedetection

/** Shared Firebase Storage paths for generated pose templates. */
object PoseTemplateStorage {
    fun jsonPath(danceId: String): String = "danceTemplates/$danceId.json"
}
