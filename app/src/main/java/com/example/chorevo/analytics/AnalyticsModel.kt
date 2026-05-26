// AnalyticsModel.kt
package com.example.chorevo.analytics

import org.json.JSONArray
import org.json.JSONObject

enum class DanceDashboardMode {
    MY_DANCE,
    OTHER_ANALYTICS
}

data class DanceDashboardArgs(
    val mode: DanceDashboardMode,
    val myUid: String,
    val uploadedDanceId: String? = null
)

data class AnalysisData(
    val danceId: String?,
    val educatorUid: String?,
    val videoUrl: String?,
    val videoPositionMs: Long?,
    val referenceTrackId: String? = null,
    val referenceTrackLabel: String? = null,
    val alignmentScore: Double?,
    val timingConsistencyScore: Double?,
    val smoothnessScore: Double?,
    val avgPoseError: Double?,
    val frames: Int?,
    val feedbackMessages: List<FeedbackMessage> = emptyList(),
    val timelinePoints: List<TimelinePoint>,
    val performanceSegments: List<PerformanceSegment>
)


data class DanceSessionDetail(
    val summary: DanceSessionSummary,
    val analysis: AnalysisData?
)

data class DanceSessionSummary(
    val sessionId: String,
    val activityName: String,
    val activityType: String,
    val tsStart: Long,
    val tsEnd: Long,
    val durationMs: Long,
    val alignmentScore: Double,
    val smoothnessScore: Double,
    val timingConsistencyScore: Double,
    val avgPoseError: Double?,
    val frames: Int?
)

data class PerformanceSegment(
    val startMs: Long,
    val endMs: Long,
    val label: SegmentLabel,
    val avgScore: Double,
    val avgErr: Double
)

enum class SegmentLabel { GOOD, OK, BAD }

data class TimelinePoint(
    val tMs: Long,
    val score: Double,
    val err: Double
)

data class FeedbackMessage(
    val tMs: Long,
    val message: String
)

data class SessionAnalysisPayload(
    val danceId: String,
    val alignmentScore: Double,
    val timingConsistencyScore: Double,
    val smoothnessScore: Double,
    val avgPoseError: Double,
    val frames: Int,
    val educatorUid: String,
    val videoUrl: String,
    val videoPositionMs: Long,
    val referenceTrackId: String? = null,
    val referenceTrackLabel: String? = null,
    val feedbackMessages: List<FeedbackMessage> = emptyList(),
    val timelinePoints: List<TimelinePoint> = emptyList(),
    val performanceSegments: List<PerformanceSegment> = emptyList()
) {

    /** Converts to json. */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("danceId", danceId)
        obj.put("alignmentScore", alignmentScore)
        obj.put("timingConsistencyScore", timingConsistencyScore)
        obj.put("smoothnessScore", smoothnessScore)
        obj.put("avgPoseError", avgPoseError)
        obj.put("frames", frames)
        obj.put("educatorUid", educatorUid)
        obj.put("videoUrl", videoUrl)
        obj.put("videoPositionMs", videoPositionMs)
        obj.put("referenceTrackId", referenceTrackId)
        obj.put("referenceTrackLabel", referenceTrackLabel)

        val feedbackArr = JSONArray()
        for (feedback in feedbackMessages) {
            val o = JSONObject()
            o.put("tMs", feedback.tMs)
            o.put("message", feedback.message)
            feedbackArr.put(o)
        }
        obj.put("feedbackMessages", feedbackArr)

        val tArr = JSONArray()
        for (p in timelinePoints) {
            val o = JSONObject()
            o.put("tMs", p.tMs)
            o.put("score", p.score)
            o.put("err", p.err)
            tArr.put(o)
        }
        obj.put("timelinePoints", tArr)

        val sArr = JSONArray()
        for (s in performanceSegments) {
            val o = JSONObject()
            o.put("startMs", s.startMs)
            o.put("endMs", s.endMs)
            o.put("label", s.label.name)
            o.put("avgScore", s.avgScore)
            o.put("avgErr", s.avgErr)
            sArr.put(o)
        }
        obj.put("performanceSegments", sArr)

        return obj.toString()
    }
}
