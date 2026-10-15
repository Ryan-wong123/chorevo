package com.example.chorevo.posedetection

/** Shared Firebase Storage paths for generated pose templates. */
object PoseTemplateStorage {
    /**
     * Template path for a dance, scoped to the uploader so Storage rules can
     * restrict writes to its owner.
     */
    fun jsonPath(ownerUid: String, danceId: String): String = "danceTemplates/$ownerUid/$danceId.json"

    /** Pre-ownership path; existing templates here stay readable but read-only. */
    fun legacyJsonPath(danceId: String): String = "danceTemplates/$danceId.json"

    /** Extracts the uploader uid from a `danceVideos/{uid}/{danceId}.mp4` path. */
    fun ownerUidFromVideoPath(videoStoragePath: String): String? =
        videoStoragePath.split('/').takeIf { it.size >= 3 && it[0] == "danceVideos" }?.get(1)?.takeIf { it.isNotBlank() }
}
