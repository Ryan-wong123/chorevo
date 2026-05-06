package com.example.chorevo.analytics.repository

import android.util.Log
import com.example.chorevo.analytics.AnalysisData
import com.example.chorevo.analytics.DanceDashboardArgs
import com.example.chorevo.analytics.DanceDashboardMode
import com.example.chorevo.analytics.DanceSessionDetail
import com.example.chorevo.analytics.DanceSessionSummary
import com.example.chorevo.analytics.PerformanceSegment
import com.example.chorevo.analytics.SegmentLabel
import com.example.chorevo.analytics.TimelinePoint
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.get

@Singleton
class AnalyticsRepository @Inject constructor(
    private val db: FirebaseFirestore
){
    /**
     * Returns session summaries based on the selected dashboard mode.
     * Uses either the current user's sessions or educator-view analytics sessions.
     */
    fun observeSessions(
        args: DanceDashboardArgs,
        limit: Long = 30
    ): Flow<List<DanceSessionSummary>> {
        return when (args.mode) {
            DanceDashboardMode.MY_DANCE ->
                observeMySessions(uid = args.myUid, limit = limit)

            DanceDashboardMode.OTHER_ANALYTICS ->
                observeSessionsForUploader(
                    uploaderUid = args.myUid,
                    uploadedDanceId = args.uploadedDanceId
                )
        }
    }
    /**
     * Returns detailed session data for the selected mode and session key.
     * Resolves either a user session ID or a Firestore document path.
     */
    fun observeDetail(
        args: DanceDashboardArgs,
        sessionKey: String
    ): Flow<DanceSessionDetail?> {
        return when (args.mode) {

            DanceDashboardMode.MY_DANCE -> {
                val sessionId = sessionKey.substringBefore("||")
                observeMySessionDetail(uid = args.myUid, sessionId = sessionId)
            }

            DanceDashboardMode.OTHER_ANALYTICS -> {
                val docPath = sessionKey.split("||").getOrNull(1)
                if (docPath.isNullOrBlank()) flowOf(null)
                else observeSessionDetailByPath(docPath)
            }
        }
    }
    /**
     * Observes the current user's session list from Firestore.
     * Results are ordered by latest session start time.
     */
    private fun observeMySessions(uid: String, limit: Long): Flow<List<DanceSessionSummary>> =
        callbackFlow {
            val reg = db.collection("users")
                .document(uid)
                .collection("danceLearningSessions")
                .orderBy("tsStart", Query.Direction.DESCENDING)
                .limit(limit)
                .addSnapshotListener { snap, err ->
                    if (err != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents.orEmpty().mapNotNull { it.toSummaryOrNull() }
                    trySend(list)
                }

            awaitClose { reg.remove() }
        }
    /**
     * Observes one session detail from the current user's collection.
     * Combines summary and analysis data into a single detail object.
     */
    private fun observeMySessionDetail(uid: String, sessionId: String): Flow<DanceSessionDetail?> =
        callbackFlow {
            val reg = db.collection("users")
                .document(uid)
                .collection("danceLearningSessions")
                .document(sessionId)
                .addSnapshotListener { doc, err ->
                    if (err != null || doc == null || !doc.exists()) {
                        trySend(null)
                        return@addSnapshotListener
                    }

                    val summary = doc.toSummaryOrNull() ?: run {
                        trySend(null)
                        return@addSnapshotListener
                    }

                    val analysis = doc.toAnalysisOrNull()
                    trySend(DanceSessionDetail(summary = summary, analysis = analysis))
                }

            awaitClose { reg.remove() }
        }
    /**
     * Observes learner sessions linked to an educator's uploaded dance.
     * Optionally filters by a specific uploaded dance ID.
     */
    private fun observeSessionsForUploader(
        uploaderUid: String,
        uploadedDanceId: String? = null
    ): Flow<List<DanceSessionSummary>> = callbackFlow {

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        var q = db.collectionGroup("danceLearningSessions")
            .whereEqualTo("analysis.educatorUid", uploaderUid)

        if (!uploadedDanceId.isNullOrBlank()) {
            q = q.whereEqualTo("analysis.danceId", uploadedDanceId)
        }

        val reg = q.addSnapshotListener { snap, err ->
            if (err != null) {
                val code = (err as? FirebaseFirestoreException)?.code
                Log.e(
                    "AnalyticsRepository",
                    "observeSessionsForUploader failed. code=$code msg=${err.message}",
                    err
                )
                return@addSnapshotListener
            }

            val docs = snap?.documents.orEmpty()

            val learnerUids = docs
                .mapNotNull { extractLearnerUidFromPath(it.reference.path) }
                .toSet()

            scope.launch {
                val displayNamesFromDocs = docs.associate { doc ->
                    val uid =
                        extractLearnerUidFromPath(doc.reference.path) ?: return@associate "" to ""
                    val nameFromDoc = doc.getString("learnerDisplayName")
                        ?.takeIf { it.isNotBlank() }
                    uid to (nameFromDoc ?: "")
                }.filterKeys { it.isNotBlank() }

                // Only fetch from /users/{uid} for UIDs where the session doc had no name
                val uidsStillMissing = learnerUids.filter {
                    displayNamesFromDocs[it].isNullOrBlank()
                }.toSet()

                val displayNamesFromFirestore = fetchDisplayNames(uidsStillMissing)

                // Merge: session-doc names take priority, firestore fetch fills the rest
                val displayNames =
                    displayNamesFromFirestore + displayNamesFromDocs.filterValues { it.isNotBlank() }

                learnerUids.forEach { uid ->
                    Log.d("AnalyticsRepository", "UID=$uid resolvedName=${displayNames[uid]}")
                }

                val list = docs
                    .mapNotNull { it.toSummaryOrNullWithLearnerTag(displayNames) }
                    .sortedByDescending { it.tsStart }

                trySend(list)
            }
        }

        awaitClose {
            reg.remove()
            scope.cancel()
        }
    }
    /**
     * Observes a session directly from its Firestore document path.
     * Used mainly for educator-side analytics detail view.
     */
    private fun observeSessionDetailByPath(docPath: String): Flow<DanceSessionDetail?> =
        callbackFlow {

            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

            val reg = db.document(docPath).addSnapshotListener { doc, err ->
                if (err != null) {
                    val code = (err as? FirebaseFirestoreException)?.code
                    Log.e(
                        "AnalyticsRepository",
                        "observeSessionDetailByPath failed. code=$code msg=${err.message}",
                        err
                    )
                    trySend(null)
                    return@addSnapshotListener
                }

                if (doc == null || !doc.exists()) {
                    trySend(null)
                    return@addSnapshotListener
                }

                val learnerUid = extractLearnerUidFromPath(doc.reference.path)

                scope.launch {
                    val nameFromDoc = doc.getString("learnerDisplayName")
                        ?.takeIf { it.isNotBlank() }

                    val displayNames: Map<String, String> = if (!learnerUid.isNullOrBlank()) {
                        if (!nameFromDoc.isNullOrBlank()) {
                            // Use name from the doc directly — no extra Firestore fetch needed
                            mapOf(learnerUid to nameFromDoc)
                        } else {
                            // Fallback: try fetching from /users/{uid}
                            fetchDisplayNames(setOf(learnerUid))
                        }
                    } else {
                        emptyMap()
                    }

                    Log.d(
                        "AnalyticsRepository",
                        "detail learnerUid=$learnerUid resolvedName=${displayNames[learnerUid]}"
                    )

                    val summary = doc.toSummaryOrNullWithLearnerTag(displayNames) ?: run {
                        trySend(null)
                        return@launch
                    }

                    val analysis = doc.toAnalysisOrNull()
                    trySend(DanceSessionDetail(summary = summary, analysis = analysis))
                }
            }

            awaitClose {
                reg.remove()
                scope.cancel()
            }
        }

    /**
     * Fetches display names for a set of UIDs from the top-level "users" collection.
     * Returns a map of UID to resolved name.
     */
    private suspend fun fetchDisplayNames(uids: Set<String>): Map<String, String> {
        if (uids.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (uid in uids) {
            try {
                val doc = db.collection("users").document(uid).get().await()
                val name = doc.getString("displayName")?.takeIf { it.isNotBlank() } ?: uid
                result[uid] = name
            } catch (e: FirebaseFirestoreException) {
                Log.e(
                    "AnalyticsRepository",
                    "PERMISSION_DENIED or Firestore error fetching displayName for uid=$uid code=${e.code}",
                    e
                )
                result[uid] = uid // still fallback to UID so UI doesn't crash
            } catch (e: Exception) {
                Log.w("AnalyticsRepository", "Failed to fetch displayName for $uid", e)
                result[uid] = uid
            }
        }
        return result
    }
    /**
     * Converts a Firestore document into a basic session summary.
     * Used for the current user's own session list.
     */
    private fun DocumentSnapshot.toSummaryOrNull(): DanceSessionSummary? {
        val sessionId = getString("sessionId") ?: id
        val activityName = getString("activityName") ?: return null
        val activityType = getString("activityType") ?: "UNKNOWN"
        val tsStart = getLong("tsStart") ?: 0L
        val tsEnd = getLong("tsEnd") ?: 0L
        val durationMs = getLong("durationMs") ?: (tsEnd - tsStart)

        val alignmentScore = getDouble("alignmentScore") ?: 0.0
        val smoothnessScore = getDouble("smoothnessScore") ?: 0.0
        val timingConsistencyScore = getDouble("timingConsistencyScore") ?: 0.0

        val analysisMap = get("analysis") as? Map<*, *>
        val avgPoseError = (analysisMap?.get("avgPoseError") as? Number)?.toDouble()
        val frames = (analysisMap?.get("frames") as? Number)?.toInt()

        return DanceSessionSummary(
            sessionId = sessionId,
            activityName = activityName,
            activityType = activityType,
            tsStart = tsStart,
            tsEnd = tsEnd,
            durationMs = durationMs,
            alignmentScore = alignmentScore,
            smoothnessScore = smoothnessScore,
            timingConsistencyScore = timingConsistencyScore,
            avgPoseError = avgPoseError,
            frames = frames
        )
    }
    /**
     * Extracts the learner UID from a Firestore document path.
     * Returns null if the path format is invalid.
     */
    private fun extractLearnerUidFromPath(path: String): String? {
        // "users/{learnerUid}/danceLearningSessions/{sessionId}"
        val parts = path.split("/")
        val idx = parts.indexOf("users")
        return if (idx >= 0 && idx + 1 < parts.size) parts[idx + 1] else null
    }
    /**
     * Converts a Firestore document into a session summary with learner info.
     * Adds the learner name to the activity label when available.
     */
    private fun DocumentSnapshot.toSummaryOrNullWithLearnerTag(displayNames: Map<String, String> = emptyMap()): DanceSessionSummary? {
        val rawSessionId = getString("sessionId") ?: id
        val activityNameRaw = getString("activityName") ?: return null

        val learnerUid = extractLearnerUidFromPath(reference.path)
        Log.d(
            "AnalyticsRepository",
            "toSummaryOrNullWithLearnerTag: path=${reference.path} learnerUid=$learnerUid displayNames=$displayNames"
        )

        val learnerLabel =
            learnerUid?.let { displayNames[it]?.takeIf { n -> n.isNotBlank() } ?: it }
        val activityName = if (!learnerLabel.isNullOrBlank()) {
            "$activityNameRaw • $learnerLabel"
        } else activityNameRaw

        val activityType = getString("activityType") ?: "UNKNOWN"
        val tsStart = getLong("tsStart") ?: 0L
        val tsEnd = getLong("tsEnd") ?: 0L
        val durationMs = getLong("durationMs") ?: (tsEnd - tsStart)

        val alignmentScore = getDouble("alignmentScore") ?: 0.0
        val smoothnessScore = getDouble("smoothnessScore") ?: 0.0
        val timingConsistencyScore = getDouble("timingConsistencyScore") ?: 0.0

        val analysisMap = get("analysis") as? Map<*, *>
        val avgPoseError = (analysisMap?.get("avgPoseError") as? Number)?.toDouble()
        val frames = (analysisMap?.get("frames") as? Number)?.toInt()

        val docPath = reference.path
        val sessionKey = "$rawSessionId||$docPath"

        return DanceSessionSummary(
            sessionId = sessionKey,
            activityName = activityName,
            activityType = activityType,
            tsStart = tsStart,
            tsEnd = tsEnd,
            durationMs = durationMs,
            alignmentScore = alignmentScore,
            smoothnessScore = smoothnessScore,
            timingConsistencyScore = timingConsistencyScore,
            avgPoseError = avgPoseError,
            frames = frames
        )
    }
    /**
     * Converts the nested analysis map into an AnalysisData object.
     * Parses scores, timeline points, and performance segments.
     */
    private fun DocumentSnapshot.toAnalysisOrNull(): AnalysisData? {
        val analysis = get("analysis") as? Map<*, *> ?: return null

        /** Returns the value as a number. */
        fun num(key: String): Double? = (analysis[key] as? Number)?.toDouble()
        /** Returns the value as a long. */
        fun long(key: String): Long? = (analysis[key] as? Number)?.toLong()
        /** Returns the value as an int. */
        fun int(key: String): Int? = (analysis[key] as? Number)?.toInt()
        /** Returns the value as a string. */
        fun str(key: String): String? = analysis[key] as? String

        val timeline = (analysis["timelinePoints"] as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val tMs = (m["tMs"] as? Number)?.toLong() ?: return@mapNotNull null
            val score = (m["score"] as? Number)?.toDouble() ?: return@mapNotNull null
            val err = (m["err"] as? Number)?.toDouble() ?: 0.0
            TimelinePoint(tMs = tMs, score = score, err = err)
        }.orEmpty()

        val segments = (analysis["performanceSegments"] as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val startMs = (m["startMs"] as? Number)?.toLong() ?: return@mapNotNull null
            val endMs = (m["endMs"] as? Number)?.toLong() ?: return@mapNotNull null
            val labelStr = (m["label"] as? String) ?: "OK"
            val label = runCatching { SegmentLabel.valueOf(labelStr) }.getOrElse { SegmentLabel.OK }
            val avgScore = (m["avgScore"] as? Number)?.toDouble() ?: 0.0
            val avgErr = (m["avgErr"] as? Number)?.toDouble() ?: 0.0

            PerformanceSegment(
                startMs = startMs,
                endMs = endMs,
                label = label,
                avgScore = avgScore,
                avgErr = avgErr
            )
        }.orEmpty()

        return AnalysisData(
            danceId = str("danceId"),
            educatorUid = str("educatorUid"),
            videoUrl = str("videoUrl"),
            videoPositionMs = long("videoPositionMs"),
            referenceTrackId = str("referenceTrackId"),
            referenceTrackLabel = str("referenceTrackLabel"),
            alignmentScore = num("alignmentScore"),
            timingConsistencyScore = num("timingConsistencyScore"),
            smoothnessScore = num("smoothnessScore"),
            avgPoseError = num("avgPoseError"),
            frames = int("frames"),
            timelinePoints = timeline.sortedBy { it.tMs },
            performanceSegments = segments.sortedBy { it.startMs }
        )
    }
}
