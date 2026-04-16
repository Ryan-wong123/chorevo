package com.example.chorevo.dancelibrarylist.Repository

import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTemplateJson
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DanceLibraryRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val danceSessionDao: DanceSessionDao
) {

    companion object {
        private const val MAX_TEMPLATE_DOWNLOAD_BYTES = 20L * 1024L * 1024L
    }

    /** Handles list enabled. */
    suspend fun listEnabled(): Result<List<DanceLibraryModel>> {
        return try {
            val snap = firestore.collection("danceLibrary")
                .whereEqualTo("enabled", true)
                .get()
                .await()

            val list = snap.documents
                .mapNotNull { it.toObject(DanceLibraryModel::class.java) }
                .filter { it.isTemplateReady }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Loads the full pose template for a dance, using inline data as a legacy fallback. */
    suspend fun loadTemplate(item: DanceLibraryModel): Result<PoseTemplate> {
        return try {
            item.template?.let { return Result.success(it) }

            val templateStoragePath = item.templateStoragePath.trim()
            if (templateStoragePath.isBlank()) {
                return Result.failure(
                    IllegalStateException("Reference template is unavailable for this dance")
                )
            }

            val bytes = storage.reference
                .child(templateStoragePath)
                .getBytes(MAX_TEMPLATE_DOWNLOAD_BYTES)
                .await()

            Result.success(PoseTemplateJson.fromJson(bytes.toString(Charsets.UTF_8)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Saves session and upload. */
    suspend fun saveSessionAndUpload(
        session: DanceSessionEntity,
        extraAnalytics: Map<String, Any?> = emptyMap(),
        uid: String
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                danceSessionDao.insert(session)

                uploadDanceSession(
                    uid = uid,
                    session = session,
                    extraAnalytics = extraAnalytics
                )

                danceSessionDao.markSynced(session.sessionId)

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Uploads dance session. */
    suspend fun uploadDanceSession(
        uid: String?,
        session: DanceSessionEntity,
        extraAnalytics: Map<String, Any?> = emptyMap()
    ) {
        val safeUid = uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Cannot upload: uid is null/blank")

        val doc = firestore.collection("users")
            .document(safeUid)
            .collection("danceLearningSessions")
            .document(session.sessionId)

        val payload = mutableMapOf<String, Any?>(
            "sessionId" to session.sessionId,
            "tsStart" to session.tsStart,
            "tsEnd" to session.tsEnd,
            "activityName" to session.activityName,
            "activityType" to session.activityType,
            "durationMs" to session.durationMs,
            "smoothnessScore" to session.smoothnessScore,
            "balanceScore" to session.balanceScore,
            "timingConsistencyScore" to session.timingConsistencyScore,
            "alignmentScore" to session.alignmentScore,
            "qualityFlag" to session.qualityFlag
        )

        if (extraAnalytics.isNotEmpty()) {
            payload["extraAnalytics"] = extraAnalytics
        }

        val analysisMap = session.analysisJson.toFirestoreMapOrEmpty()
        if (analysisMap.isNotEmpty()) {
            requireValidSessionAnalysis(analysisMap)
            payload["analysis"] = analysisMap
        }

        doc.set(payload).await()
    }


    // ---------------------------
    // JSON mapping helpers
    // ---------------------------
    private fun String.toFirestoreMapOrEmpty(): Map<String, Any?> {
        val trimmed = trim()
        if (trimmed.isEmpty() || trimmed == "{}") return emptyMap()

        return try {
            val obj = JSONObject(trimmed)
            jsonObjectToMap(obj)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Ensures uploaded session analytics satisfy Firestore rule requirements. */
    private fun requireValidSessionAnalysis(analysis: Map<String, Any?>) {
        val danceId = analysis["danceId"] as? String
        val educatorUid = analysis["educatorUid"] as? String

        require(!danceId.isNullOrBlank()) {
            "Session analytics are missing danceId"
        }
        require(!educatorUid.isNullOrBlank()) {
            "Session analytics are missing educatorUid"
        }
    }

    /** Handles json object to map. */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.opt(k)
            out[k] = jsonValueToFirestoreSafe(v)
        }
        return out
    }

    /** Handles json array to list. */
    private fun jsonArrayToList(arr: JSONArray): List<Any?> {
        val list = ArrayList<Any?>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(jsonValueToFirestoreSafe(arr.opt(i)))
        }
        return list
    }

    /** Handles json value to firestore safe. */
    private fun jsonValueToFirestoreSafe(v: Any?): Any? {
        return when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(v)
            is JSONArray -> jsonArrayToList(v)
            is Boolean, is Int, is Long, is Double, is String -> v
            is Number -> v.toDouble()
            else -> v.toString()
        }
    }
}


