package com.example.chorevo.analytics

import android.util.Log
import com.example.chorevo.analytics.repository.AnalyticsRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var repo: AnalyticsRepository

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        db = mockk(relaxed = true)
        repo = AnalyticsRepository(db)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun observeSessions_myDance_emitsMappedSummaries() = runTest {
        val users = mockk<CollectionReference>()
        val userDoc = mockk<DocumentReference>()
        val sessions = mockk<CollectionReference>()
        val ordered = mockk<Query>()
        val limited = mockk<Query>()
        val registration = mockk<ListenerRegistration>(relaxed = true)
        val snapshot = mockk<QuerySnapshot>()

        val d1 = mockSummaryDoc(
            id = "s1",
            path = "users/u1/danceLearningSessions/s1",
            sessionId = "s1",
            activityName = "Warmup",
            tsStart = 200L
        )
        val d2 = mockSummaryDoc(
            id = "s2",
            path = "users/u1/danceLearningSessions/s2",
            sessionId = "s2",
            activityName = "Groove",
            tsStart = 100L
        )

        every { db.collection("users") } returns users
        every { users.document("u1") } returns userDoc
        every { userDoc.collection("danceLearningSessions") } returns sessions
        every { sessions.orderBy("tsStart", Query.Direction.DESCENDING) } returns ordered
        every { ordered.limit(30) } returns limited

        val listenerSlot = slot<EventListener<QuerySnapshot>>()
        every { limited.addSnapshotListener(capture(listenerSlot)) } returns registration
        every { snapshot.documents } returns listOf(d1, d2)

        val deferred = async {
            repo.observeSessions(
                args = DanceDashboardArgs(
                    mode = DanceDashboardMode.MY_DANCE,
                    myUid = "u1",
                    uploadedDanceId = null
                )
            ).first()
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(snapshot, null)
        advanceUntilIdle()

        val result = deferred.await()

        assertEquals(2, result.size)
        assertEquals("s1", result[0].sessionId)
        assertEquals("Warmup", result[0].activityName)
        assertEquals(200L, result[0].tsStart)
        assertEquals("s2", result[1].sessionId)

        verify(exactly = 1) { registration.remove() }
    }

    @Test
    fun observeDetail_otherAnalytics_blankPath_emitsNull() = runTest {
        val detail = repo.observeDetail(
            args = DanceDashboardArgs(
                mode = DanceDashboardMode.OTHER_ANALYTICS,
                myUid = "educator1",
                uploadedDanceId = "danceA"
            ),
            sessionKey = "session123||"
        ).first()

        assertNull(detail)
    }

    @Test
    fun observeSessions_otherAnalytics_usesDocDisplayNameWithoutExtraFetch() = runTest {
        val groupQuery = mockk<Query>()
        val registration = mockk<ListenerRegistration>(relaxed = true)
        val snapshot = mockk<QuerySnapshot>()

        val doc = mockSummaryDoc(
            id = "s9",
            path = "users/learner1/danceLearningSessions/s9",
            sessionId = "s9",
            activityName = "Shuffle",
            tsStart = 300L,
            learnerDisplayName = "Ava"
        )

        every { db.collectionGroup("danceLearningSessions") } returns groupQuery
        every { groupQuery.whereEqualTo("analysis.educatorUid", "educator1") } returns groupQuery

        val listenerSlot = slot<EventListener<QuerySnapshot>>()
        every { groupQuery.addSnapshotListener(capture(listenerSlot)) } returns registration
        every { snapshot.documents } returns listOf(doc)

        val deferred = async {
            repo.observeSessions(
                args = DanceDashboardArgs(
                    mode = DanceDashboardMode.OTHER_ANALYTICS,
                    myUid = "educator1",
                    uploadedDanceId = null
                )
            ).first()
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(snapshot, null)
        advanceUntilIdle()

        val result = deferred.await()

        assertEquals(1, result.size)
        assertEquals("s9||users/learner1/danceLearningSessions/s9", result[0].sessionId)
        assertEquals("Shuffle • Ava", result[0].activityName)

        verify(exactly = 0) { db.collection("users") }
        verify(exactly = 1) { registration.remove() }
    }

    @Test
    fun observeDetail_otherAnalytics_fetchesFallbackName_andParsesAnalysis() = runTest {
        val detailRef = mockk<DocumentReference>()
        val users = mockk<CollectionReference>()
        val userRef = mockk<DocumentReference>()
        val registration = mockk<ListenerRegistration>(relaxed = true)

        val detailDoc = mockDetailDoc(
            id = "s7",
            path = "users/learner42/danceLearningSessions/s7",
            sessionId = "s7",
            activityName = "Footwork",
            tsStart = 500L,
            learnerDisplayName = null
        )
        val userProfileDoc = mockk<DocumentSnapshot>()

        every { db.document("users/learner42/danceLearningSessions/s7") } returns detailRef

        val listenerSlot = slot<EventListener<DocumentSnapshot>>()
        every { detailRef.addSnapshotListener(capture(listenerSlot)) } returns registration

        every { db.collection("users") } returns users
        every { users.document("learner42") } returns userRef
        every { userRef.get() } returns Tasks.forResult(userProfileDoc)
        every { userProfileDoc.getString("displayName") } returns "Jamie"

        val deferred = async {
            repo.observeDetail(
                args = DanceDashboardArgs(
                    mode = DanceDashboardMode.OTHER_ANALYTICS,
                    myUid = "educator1",
                    uploadedDanceId = "danceA"
                ),
                sessionKey = "s7||users/learner42/danceLearningSessions/s7"
            ).first()
        }

        advanceUntilIdle()
        listenerSlot.captured.onEvent(detailDoc, null)
        advanceUntilIdle()

        val detail = deferred.await()

        assertNotNull(detail)
        detail!!

        assertEquals("s7||users/learner42/danceLearningSessions/s7", detail.summary.sessionId)
        assertEquals("Footwork • Jamie", detail.summary.activityName)
        assertEquals("danceA", detail.analysis?.danceId)
        assertEquals("educator1", detail.analysis?.educatorUid)
        assertEquals(listOf(100L, 200L), detail.analysis?.timelinePoints?.map { it.tMs })
        assertEquals(listOf(0L, 100L), detail.analysis?.performanceSegments?.map { it.startMs })
        assertEquals(SegmentLabel.OK, detail.analysis?.performanceSegments?.first()?.label)
        assertEquals(SegmentLabel.BAD, detail.analysis?.performanceSegments?.get(1)?.label)

        verify(exactly = 1) { db.collection("users") }
        verify(exactly = 1) { users.document("learner42") }
        verify(exactly = 1) { userRef.get() }
        verify(exactly = 1) { registration.remove() }
    }

    private fun mockSummaryDoc(
        id: String,
        path: String,
        sessionId: String,
        activityName: String,
        tsStart: Long,
        learnerDisplayName: String? = null
    ): DocumentSnapshot {
        val ref = mockk<DocumentReference>()
        val doc = mockk<DocumentSnapshot>()

        every { doc.id } returns id
        every { doc.reference } returns ref
        every { ref.path } returns path
        every { doc.getString("sessionId") } returns sessionId
        every { doc.getString("activityName") } returns activityName
        every { doc.getString("activityType") } returns "DANCE"
        every { doc.getString("learnerDisplayName") } returns learnerDisplayName
        every { doc.getLong("tsStart") } returns tsStart
        every { doc.getLong("tsEnd") } returns tsStart + 50
        every { doc.getLong("durationMs") } returns 50L
        every { doc.getDouble("alignmentScore") } returns 91.0
        every { doc.getDouble("smoothnessScore") } returns 82.0
        every { doc.getDouble("timingConsistencyScore") } returns 73.0
        every { doc.get("analysis") } returns mapOf(
            "avgPoseError" to 0.12,
            "frames" to 240
        )
        return doc
    }

    private fun mockDetailDoc(
        id: String,
        path: String,
        sessionId: String,
        activityName: String,
        tsStart: Long,
        learnerDisplayName: String?
    ): DocumentSnapshot {
        val ref = mockk<DocumentReference>()
        val doc = mockk<DocumentSnapshot>()

        every { doc.id } returns id
        every { doc.exists() } returns true
        every { doc.reference } returns ref
        every { ref.path } returns path
        every { doc.getString("sessionId") } returns sessionId
        every { doc.getString("activityName") } returns activityName
        every { doc.getString("activityType") } returns "DANCE"
        every { doc.getString("learnerDisplayName") } returns learnerDisplayName
        every { doc.getLong("tsStart") } returns tsStart
        every { doc.getLong("tsEnd") } returns tsStart + 400
        every { doc.getLong("durationMs") } returns 400L
        every { doc.getDouble("alignmentScore") } returns 95.0
        every { doc.getDouble("smoothnessScore") } returns 88.0
        every { doc.getDouble("timingConsistencyScore") } returns 92.0
        every { doc.get("analysis") } returns mapOf(
            "danceId" to "danceA",
            "educatorUid" to "educator1",
            "videoUrl" to "https://example/video.mp4",
            "videoPositionMs" to 1234L,
            "alignmentScore" to 95.0,
            "timingConsistencyScore" to 92.0,
            "smoothnessScore" to 88.0,
            "avgPoseError" to 0.11,
            "frames" to 300,
            "timelinePoints" to listOf(
                mapOf("tMs" to 200L, "score" to 80.0, "err" to 0.2),
                mapOf("tMs" to 100L, "score" to 90.0, "err" to 0.1)
            ),
            "performanceSegments" to listOf(
                mapOf("startMs" to 100L, "endMs" to 200L, "label" to "BAD", "avgScore" to 70.0, "avgErr" to 0.4),
                mapOf("startMs" to 0L, "endMs" to 100L, "label" to "OK", "avgScore" to 95.0, "avgErr" to 0.1)
            )
        )
        return doc
    }
}
