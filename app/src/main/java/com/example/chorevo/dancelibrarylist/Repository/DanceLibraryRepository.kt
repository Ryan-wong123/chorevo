package com.example.chorevo.dancelibrarylist.Repository

import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.dancelibrarylist.DanceComment
import com.example.chorevo.dancelibrarylist.DanceCategory
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.dancelibrarylist.DiscoveryFilter
import com.example.chorevo.dancelibrarylist.FeedCursor
import com.example.chorevo.dancelibrarylist.FeedPage
import com.example.chorevo.dancelibrarylist.FeedTab
import com.example.chorevo.dancelibrarylist.categoriesFromCounts
import com.example.chorevo.dancelibrarylist.matchesSearch
import com.example.chorevo.dancelibrarylist.searchToken
import com.example.chorevo.dancelibrarylist.MAX_SEARCH_TOKEN_LENGTH
import com.example.chorevo.dancelibrarylist.searchScore
import com.example.chorevo.dancelibrarylist.searchTrigramsOf
import com.example.chorevo.dancelibrarylist.searchWords
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTemplateJson
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        const val COMMENTS_PAGE_SIZE = 20L
        /** Dances fetched per feed page. */
        const val FEED_PAGE_SIZE = 12L
        /** Exact (prefix) search results fetched per search. */
        const val SEARCH_EXACT_LIMIT = 40L
        /** Typo-tolerant search candidates fetched per search. */
        const val SEARCH_FUZZY_LIMIT = 60L
        /** Matches the Firestore rule limit for comment text. */
        const val MAX_COMMENT_LENGTH = 1000
    }

    /**
     * One page of the feed for the discovery bar's [filter]. Uses fields the
     * Cloud Functions keep on each dance (functions/discovery.js):
     * - visibility == "public" and feedReady == true (also what the rules require),
     * - danceTypeKey for a category chip,
     * - searchTokens (word prefixes) for the first search word,
     * - ordering by shuffleKey (For You), createdAt (New) or trendingScore (Trending).
     *
     * For You starts at a random [seed] in shuffleKey space and wraps around
     * once, so each app session starts the feed somewhere different. Further
     * search words are matched on the device.
     */
    suspend fun loadFeedPage(
        filter: DiscoveryFilter,
        seed: Double,
        cursor: FeedCursor?,
        pageSize: Long = FEED_PAGE_SIZE
    ): Result<FeedPage> {
        if (searchToken(filter.query) != null) return searchFeed(filter)
        return try {
            var position = cursor ?: FeedCursor()
            val collected = mutableListOf<DanceLibraryModel>()

            while (true) {
                var q: Query = firestore.collection("danceLibrary")
                    .whereEqualTo("visibility", DanceLibraryModel.VISIBILITY_PUBLIC)
                    .whereEqualTo("feedReady", true)
                filter.categoryKey?.let { q = q.whereEqualTo("danceTypeKey", it) }
                searchToken(filter.query)?.let { q = q.whereArrayContains("searchTokens", it) }
                q = when (filter.tab) {
                    FeedTab.FOR_YOU ->
                        if (!position.wrapped) {
                            q.whereGreaterThanOrEqualTo("shuffleKey", seed).orderBy("shuffleKey")
                        } else {
                            q.whereLessThan("shuffleKey", seed).orderBy("shuffleKey")
                        }
                    FeedTab.NEW -> q.orderBy("createdAt", Query.Direction.DESCENDING)
                    FeedTab.TRENDING -> q.orderBy("trendingScore", Query.Direction.DESCENDING)
                }
                position.lastDoc?.let { q = q.startAfter(it) }

                val docs = q.limit(pageSize).get().await().documents
                collected += docs
                    .mapNotNull { it.toObject(DanceLibraryModel::class.java) }
                    .filter { it.isTemplateReady && !it.isPrivate && matchesSearch(it, filter.query) }

                val pageFull = docs.size.toLong() == pageSize
                position = when {
                    pageFull -> position.copy(lastDoc = docs.last())
                    // For You: first half done, continue below the seed.
                    filter.tab == FeedTab.FOR_YOU && !position.wrapped -> FeedCursor(wrapped = true)
                    else -> return Result.success(FeedPage(collected, next = null))
                }
                // Stop at a full page, or once something was found after wrapping.
                if (pageFull || collected.isNotEmpty()) {
                    return Result.success(FeedPage(collected, next = position))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.success(FeedPage(collected, next = null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Search results (one page, best matches first). Two queries run together:
     * - exact: searchTokens array-contains the first word (word prefixes), in tab order;
     * - typo-tolerant: searchTrigrams array-contains-any the first word's
     *   3-letter pieces, so "hiphp" or "slasa" still find candidates.
     * Every candidate is then scored on the device with [searchScore] (all
     * words must match, allowing a typo or two) and sorted by score.
     */
    private suspend fun searchFeed(filter: DiscoveryFilter): Result<FeedPage> {
        return try {
            val firstWord = searchWords(filter.query).first().take(MAX_SEARCH_TOKEN_LENGTH)
            fun base(): Query {
                var q: Query = firestore.collection("danceLibrary")
                    .whereEqualTo("visibility", DanceLibraryModel.VISIBILITY_PUBLIC)
                    .whereEqualTo("feedReady", true)
                filter.categoryKey?.let { q = q.whereEqualTo("danceTypeKey", it) }
                return q
            }
            val (exact, fuzzy) = coroutineScope {
                val exactDocs = async {
                    val q = base().whereArrayContains("searchTokens", firstWord)
                    when (filter.tab) {
                        FeedTab.FOR_YOU -> q.orderBy("shuffleKey")
                        FeedTab.NEW -> q.orderBy("createdAt", Query.Direction.DESCENDING)
                        FeedTab.TRENDING -> q.orderBy("trendingScore", Query.Direction.DESCENDING)
                    }.limit(SEARCH_EXACT_LIMIT).get().await().documents
                }
                val fuzzyDocs = async {
                    // Best effort: a missing index or other failure just means no typo matches.
                    runCatching {
                        base().whereArrayContainsAny("searchTrigrams", searchTrigramsOf(firstWord))
                            .limit(SEARCH_FUZZY_LIMIT).get().await().documents
                    }.getOrDefault(emptyList())
                }
                exactDocs.await() to fuzzyDocs.await()
            }
            val ranked = (exact + fuzzy)
                .mapNotNull { it.toObject(DanceLibraryModel::class.java) }
                .distinctBy { it.danceId }
                .filter { it.isTemplateReady && !it.isPrivate }
                .mapNotNull { dance -> searchScore(dance, filter.query)?.let { dance to it } }
                .sortedBy { it.second } // stable: ties keep the exact query's tab order first
                .map { it.first }
            Result.success(FeedPage(ranked, next = null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Category chips from the counts the Cloud Functions keep in meta/danceCategories. */
    suspend fun loadCategories(): Result<List<DanceCategory>> {
        return try {
            val snap = firestore.collection("meta").document("danceCategories").get().await()
            @Suppress("UNCHECKED_CAST")
            val counts = (snap.get("counts") as? Map<String, Any?>).orEmpty()
            @Suppress("UNCHECKED_CAST")
            val labels = (snap.get("labels") as? Map<String, Any?>).orEmpty()
            Result.success(categoriesFromCounts(counts, labels))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Deep-link support  ← NEW
    // -------------------------------------------------------------------------

    /**
     * Fetches a single dance by its Firestore document ID.
     * Used when the app is opened via a share link. [viewerUid] is the signed-in
     * user; a private dance is only returned to its uploader.
     *
     * Returns [Result.failure] when:
     * - the document does not exist (deleted or bad link)
     * - the dance is disabled
     * - the dance is private and [viewerUid] is not its uploader
     * - the template is not yet ready
     * - any network / Firestore error occurs
     */
    suspend fun getDanceById(danceId: String, viewerUid: String? = null): Result<DanceLibraryModel> {
        return try {
            val doc = firestore.collection("danceLibrary")
                .document(danceId)
                .get()
                .await()

            if (!doc.exists()) {
                return Result.failure(NoSuchElementException("This dance is no longer available."))
            }

            val dance = doc.toObject(DanceLibraryModel::class.java)
                ?: return Result.failure(IllegalStateException("Could not read dance data."))

            if (!dance.enabled) {
                return Result.failure(IllegalStateException("This dance is no longer available."))
            }

            if (!dance.isVisibleTo(viewerUid)) {
                return Result.failure(IllegalStateException("This video is private."))
            }

            if (!dance.isTemplateReady) {
                return Result.failure(IllegalStateException("This dance is still being processed. Try again soon."))
            }

            Result.success(dance)
        } catch (e: FirebaseFirestoreException) {
            // The rules refuse private dances to anyone but their uploader.
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Result.failure(IllegalStateException("This video is private."))
            } else {
                Result.failure(e)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // Existing functions — unchanged
    // -------------------------------------------------------------------------

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
            "sessionId"               to session.sessionId,
            "tsStart"                 to session.tsStart,
            "tsEnd"                   to session.tsEnd,
            "activityName"            to session.activityName,
            "activityType"            to session.activityType,
            "durationMs"              to session.durationMs,
            "smoothnessScore"         to session.smoothnessScore,
            "balanceScore"            to session.balanceScore,
            "timingConsistencyScore"  to session.timingConsistencyScore,
            "alignmentScore"          to session.alignmentScore,
            "qualityFlag"             to session.qualityFlag,
            // Lets the server put the session on the user's local day/week.
            "tzOffsetMinutes"         to java.util.TimeZone.getDefault().getOffset(session.tsEnd) / 60_000
        )

        if (extraAnalytics.isNotEmpty()) {
            payload["extraAnalytics"] = extraAnalytics
        }

        val analysisMap = session.analysisJson.toFirestoreMapOrEmpty()
        if (analysisMap.isNotEmpty()) {
            requireValidSessionAnalysis(analysisMap)
            payload["analysis"] = analysisMap
        }

        // Sessions are final once uploaded (they earn points server-side), so a
        // retry of a session that already made it is a no-op.
        if (runCatching { doc.get().await().exists() }.getOrDefault(false)) return
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
        val danceId    = analysis["danceId"]    as? String
        val educatorUid = analysis["educatorUid"] as? String

        require(!danceId.isNullOrBlank())    { "Session analytics are missing danceId" }
        require(!educatorUid.isNullOrBlank()) { "Session analytics are missing educatorUid" }
    }

    /** Handles json object to map. */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out  = mutableMapOf<String, Any?>()
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
            is JSONObject         -> jsonObjectToMap(v)
            is JSONArray          -> jsonArrayToList(v)
            is Boolean, is Int, is Long, is Double, is String -> v
            is Number             -> v.toDouble()
            else                  -> v.toString()
        }
    }

    /**
     * Like count and whether [currentUid] liked the dance: the count the
     * danceLikeCount function keeps on the dance (falling back to a count()
     * query before it has run) and a single read of the user's own like.
     */
    suspend fun loadSocialData(danceId: String, currentUid: String): Result<Pair<Int, Boolean>> {
        return try {
            val danceRef = firestore.collection("danceLibrary").document(danceId)
            val stored = danceRef.get().await().getLong("likeCount")
            val likeCount = stored?.toInt()
                ?: danceRef.collection("likes").count().get(AggregateSource.SERVER).await().count.toInt()
            val isLiked = danceRef.collection("likes").document(currentUid).get().await().exists()
            Result.success(likeCount to isLiked)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleLike(danceId: String, uid: String, isCurrentlyLiked: Boolean): Result<Unit> {
        return try {
            val ref = firestore.collection("danceLibrary")
                .document(danceId).collection("likes").document(uid)
            if (isCurrentlyLiked) {
                ref.delete().await()
            } else {
                // uid/createdAt let the liked-videos query find this like.
                ref.set(mapOf("liked" to true, "uid" to uid, "createdAt" to System.currentTimeMillis())).await()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns the list and the last document snapshot used as a cursor for [loadMoreComments].
     */
    suspend fun loadComments(danceId: String): Result<Pair<List<DanceComment>, DocumentSnapshot?>> {
        return try {
            val snap = firestore.collection("danceLibrary")
                .document(danceId).collection("comments")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(COMMENTS_PAGE_SIZE)
                .get().await()

            val comments = snap.documents.mapNotNull { it.toObject(DanceComment::class.java) }
            val cursor   = snap.documents.lastOrNull()
            Result.success(Pair(comments, cursor))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun loadMoreComments(
        danceId: String,
        after: DocumentSnapshot
    ): Result<Pair<List<DanceComment>, DocumentSnapshot?>> {
        return try {
            val snap = firestore.collection("danceLibrary")
                .document(danceId).collection("comments")
                // Same order as the first page so "Load more" continues to older comments.
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .startAfter(after)
                .limit(COMMENTS_PAGE_SIZE)
                .get().await()
            val comments   = snap.documents.mapNotNull { it.toObject(DanceComment::class.java) }
            val cursor     = snap.documents.lastOrNull()
            val nextCursor = if (comments.size < COMMENTS_PAGE_SIZE) null else cursor
            Result.success(Pair(comments, nextCursor))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Returns all dances that [uid] has liked, by querying each danceLibrary doc's
     * likes sub-collection for a document matching the uid.
     */
    suspend fun getLikedDances(uid: String): Result<List<DanceLibraryModel>> {
        return try {
            // The user's own likes across all dances, newest first.
            val likes = firestore.collectionGroup("likes")
                .whereEqualTo("uid", uid)
                .get()
                .await()
                .documents
                .sortedByDescending { it.getLong("createdAt") ?: 0L }

            val danceIds = likes.mapNotNull { it.reference.parent.parent?.id }.distinct()
            val dances = coroutineScope {
                danceIds.map { id ->
                    async {
                        // A dance that became private (or was deleted) can't be read: skip it.
                        runCatching {
                            firestore.collection("danceLibrary").document(id).get().await()
                                .toObject(DanceLibraryModel::class.java)
                        }.getOrNull()
                    }
                }.awaitAll()
            }
            Result.success(dances.filterNotNull().filter { it.enabled && it.isVisibleTo(uid) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun postComment(
        danceId: String,
        uid: String,
        username: String,
        text: String
    ): Result<Unit> {
        return try {
            val commentId = java.util.UUID.randomUUID().toString()
            // Limits match firestore.rules (name 1-60, text 1-1000 characters).
            val comment   = DanceComment(commentId, uid, username.trim().ifBlank { "User" }.take(60), text.take(1_000))
            firestore.collection("danceLibrary")
                .document(danceId).collection("comments")
                .document(commentId).set(comment).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
}