package com.example.chorevo.dancelibrarylist

import com.example.chorevo.posedetection.PoseTemplate
import com.google.firebase.firestore.Exclude

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
    val templateReviewStatus: String = "",
    /** [VISIBILITY_PUBLIC] or [VISIBILITY_PRIVATE]; older uploads have no value and stay public. */
    val visibility: String = VISIBILITY_PUBLIC,
    /** Position (ms) of the frame used as the video's cover thumbnail. */
    val coverFrameMs: Long = DEFAULT_COVER_FRAME_MS
) {
    /** Private videos are only visible to their uploader. */
    @get:Exclude
    val isPrivate: Boolean
        get() = visibility.equals(VISIBILITY_PRIVATE, ignoreCase = true)

    /** Whether [viewerUid] may see this video in feeds, liked lists and share links. */
    fun isVisibleTo(viewerUid: String?): Boolean =
        !isPrivate || (!viewerUid.isNullOrBlank() && viewerUid == educatorUid)

    companion object {
        const val VISIBILITY_PUBLIC = "public"
        const val VISIBILITY_PRIVATE = "private"
        const val DEFAULT_COVER_FRAME_MS = 1_000L
    }

    @get:Exclude
    val isTemplateReady: Boolean
        get() = templateGenerationStatus.equals("ready", ignoreCase = true)
}
