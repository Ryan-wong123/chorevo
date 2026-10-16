package com.example.chorevo.dancelibrarylist

import com.example.chorevo.core.Database.AppDb
import com.example.chorevo.danceactivity.DanceSessionEntity
import com.example.chorevo.danceactivity.Repository.DanceSessionDao
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.posedetection.PoseFrame
import com.example.chorevo.posedetection.PoseTemplate
import com.example.chorevo.posedetection.PoseTemplateJson
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class DanceLibraryRepositoryTest {

    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var appDb: AppDb
    private lateinit var dao: DanceSessionDao
    private lateinit var repo: DanceLibraryRepository

    @Before
    fun setUp() {
        firestore = mockk()
        storage = mockk()
        appDb = mockk()
        dao = mockk(relaxed = true)

        every { appDb.danceSessionDao() } returns dao

        repo = DanceLibraryRepository(
            firestore = firestore,
            storage = storage,
            danceSessionDao = dao
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun uploadDanceSession_blankUid_throws() = runTest {
        val session = mockk<DanceSessionEntity>(relaxed = true)
        every { session.sessionId } returns "s1"
        every { session.analysisJson } returns "{}"

        try {
            repo.uploadDanceSession(uid = " ", session = session)
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("uid is null/blank"))
        }
    }

    @Test
    fun loadTemplate_downloadsTemplateFromStorage() = runTest {
        val rootRef = mockk<StorageReference>()
        val templateRef = mockk<StorageReference>()
        val template = PoseTemplate(
            fps = 2,
            frames = listOf(PoseFrame(tMs = 0L, landmarks = listOf(0.1f, 0.2f, 0.3f, 0.9f)))
        )
        val jsonBytes = PoseTemplateJson.toJson(template).toByteArray(Charsets.UTF_8)

        every { storage.reference } returns rootRef
        every { rootRef.child("danceTemplates/d1.json") } returns templateRef
        every { templateRef.getBytes(any()) } returns Tasks.forResult(jsonBytes)

        val res = repo.loadTemplate(
            DanceLibraryModel(
                danceId = "d1",
                templateStoragePath = "danceTemplates/d1.json"
            )
        )

        assertTrue(res.isSuccess)
        assertEquals(template.fps, res.getOrThrow().fps)
        assertEquals(template.frames, res.getOrThrow().frames)
    }

    @Test
    fun uploadDanceSession_includesParsedAnalysis_andExtraAnalytics() = runTest {
        val usersCol = mockk<CollectionReference>()
        val sessCol = mockk<CollectionReference>()
        val sessDoc = mockk<DocumentReference>()

        every { firestore.collection("users") } returns usersCol
        every { usersCol.document("uid123") } returns mockk {
            every { collection("danceLearningSessions") } returns sessCol
        }
        every { sessCol.document("sess999") } returns sessDoc
        every { sessDoc.set(any<Map<String, Any?>>()) } returns Tasks.forResult(null)

        val session = mockk<DanceSessionEntity>(relaxed = true)
        every { session.sessionId } returns "sess999"
        every { session.analysisJson } returns """{"a":1,"b":[true,2,"x"],"c":{"d":null}}"""

        repo.uploadDanceSession(
            uid = "uid123",
            session = session,
            extraAnalytics = mapOf("foo" to "bar")
        )

        verify(exactly = 1) {
            sessDoc.set(
                match<Map<String, Any?>> { payload ->
                    payload["sessionId"] == "sess999" &&
                            payload["extraAnalytics"] == mapOf("foo" to "bar") &&
                            (payload["analysis"] == null || payload["analysis"] is Map<*, *>)
                }
            )
        }
    }

    @Test
    fun saveSessionAndUpload_success_inserts_uploads_marksSynced() = runTest {
        val usersCol = mockk<CollectionReference>()
        val sessCol = mockk<CollectionReference>()
        val sessDoc = mockk<DocumentReference>()

        every { firestore.collection("users") } returns usersCol
        every { usersCol.document("uid123") } returns mockk {
            every { collection("danceLearningSessions") } returns sessCol
        }
        every { sessCol.document("sess1") } returns sessDoc
        every { sessDoc.set(any<Map<String, Any?>>()) } returns Tasks.forResult(null)

        val session = mockk<DanceSessionEntity>(relaxed = true)
        every { session.sessionId } returns "sess1"
        every { session.analysisJson } returns "{}"

        val res = repo.saveSessionAndUpload(session = session, uid = "uid123")

        assertTrue(res.isSuccess)
        coVerify(exactly = 1) { dao.insert(session) }
        coVerify(exactly = 1) { dao.markSynced("sess1") }
        verify(exactly = 1) { sessDoc.set(any<Map<String, Any?>>()) }
    }

    @Test
    fun uploadDanceSession_skipsSessionsAlreadyUploaded_andSendsTheTimeZone() = runTest {
        val usersCol = mockk<CollectionReference>()
        val sessCol = mockk<CollectionReference>()
        val sessDoc = mockk<DocumentReference>()
        val existing = mockk<DocumentSnapshot>()

        every { firestore.collection("users") } returns usersCol
        every { usersCol.document("uid123") } returns mockk {
            every { collection("danceLearningSessions") } returns sessCol
        }
        every { sessCol.document("sess1") } returns sessDoc
        every { sessDoc.get() } returns Tasks.forResult(existing)
        every { sessDoc.set(any<Map<String, Any?>>()) } returns Tasks.forResult(null)
        val session = mockk<DanceSessionEntity>(relaxed = true)
        every { session.sessionId } returns "sess1"
        every { session.analysisJson } returns "{}"

        // Already uploaded (sessions are final): nothing is written again.
        every { existing.exists() } returns true
        repo.uploadDanceSession(uid = "uid123", session = session)
        verify(exactly = 0) { sessDoc.set(any<Map<String, Any?>>()) }

        every { existing.exists() } returns false
        repo.uploadDanceSession(uid = "uid123", session = session)
        verify(exactly = 1) {
            sessDoc.set(match<Map<String, Any?>> { it["tzOffsetMinutes"] is Int })
        }
    }

    // ── Video privacy ─────────────────────────────────────────────────────────

    private fun mockDanceDoc(model: DanceLibraryModel) {
        val col = mockk<CollectionReference>()
        val ref = mockk<DocumentReference>()
        val doc = mockk<DocumentSnapshot>()
        every { firestore.collection("danceLibrary") } returns col
        every { col.document(model.danceId) } returns ref
        every { ref.get() } returns Tasks.forResult(doc)
        every { doc.exists() } returns true
        every { doc.toObject(DanceLibraryModel::class.java) } returns model
    }

    @Test
    fun getDanceById_privateVideo_isHiddenFromOtherUsers() = runTest {
        mockDanceDoc(
            DanceLibraryModel(danceId = "priv", educatorUid = "owner", visibility = DanceLibraryModel.VISIBILITY_PRIVATE)
        )

        val res = repo.getDanceById("priv", viewerUid = "someoneElse")

        assertTrue(res.isFailure)
        assertEquals("This video is private.", res.exceptionOrNull()?.message)
    }

    @Test
    fun getDanceById_privateVideo_isVisibleToOwner() = runTest {
        mockDanceDoc(
            DanceLibraryModel(danceId = "priv", educatorUid = "owner", visibility = DanceLibraryModel.VISIBILITY_PRIVATE)
        )

        val res = repo.getDanceById("priv", viewerUid = "owner")

        assertEquals("priv", res.getOrThrow().danceId)
    }

    // ── Paged feed ───────────────────────────────────────────────────────────

    private lateinit var feedCol: CollectionReference
    private lateinit var feedQuery: Query

    private fun docFor(model: DanceLibraryModel): DocumentSnapshot {
        val doc = mockk<DocumentSnapshot>()
        every { doc.toObject(DanceLibraryModel::class.java) } returns model
        return doc
    }

    /** A query chain where every call returns the same query; get() returns [pages] in turn. */
    private fun mockFeedQuery(vararg pages: List<DocumentSnapshot>) {
        feedCol = mockk()
        feedQuery = mockk()
        every { firestore.collection("danceLibrary") } returns feedCol
        every { feedCol.whereEqualTo(any<String>(), any()) } returns feedQuery
        every { feedQuery.whereEqualTo(any<String>(), any()) } returns feedQuery
        every { feedQuery.whereArrayContains(any<String>(), any()) } returns feedQuery
        every { feedQuery.whereArrayContainsAny(any<String>(), any()) } returns feedQuery
        every { feedQuery.whereGreaterThanOrEqualTo(any<String>(), any()) } returns feedQuery
        every { feedQuery.whereLessThan(any<String>(), any()) } returns feedQuery
        every { feedQuery.orderBy(any<String>()) } returns feedQuery
        every { feedQuery.orderBy(any<String>(), any()) } returns feedQuery
        every { feedQuery.startAfter(any<DocumentSnapshot>()) } returns feedQuery
        every { feedQuery.limit(any()) } returns feedQuery
        val snaps = pages.map { docs ->
            mockk<QuerySnapshot>().also { every { it.documents } returns docs }
        }
        every { feedQuery.get() } returnsMany snaps.map { Tasks.forResult(it) }
    }

    @Test
    fun loadFeedPage_newTab_queriesPublicFeedReadyByDate_andEndsOnShortPage() = runTest {
        mockFeedQuery(
            listOf(
                docFor(DanceLibraryModel(danceId = "a", educatorUid = "u")),
                docFor(DanceLibraryModel(danceId = "b", educatorUid = "u", templateGenerationStatus = "queued"))
            )
        )

        val page = repo.loadFeedPage(DiscoveryFilter(tab = FeedTab.NEW), seed = 0.5, cursor = null).getOrThrow()

        assertEquals(listOf("a"), page.items.map { it.danceId }) // not-ready template dropped
        assertNull(page.next)
        verify { feedCol.whereEqualTo("visibility", "public") }
        verify { feedQuery.whereEqualTo("feedReady", true) }
        verify { feedQuery.orderBy("createdAt", Query.Direction.DESCENDING) }
    }

    @Test
    fun loadFeedPage_categoryAndSearch_queryKeysAndMatchOtherWordsOnDevice() = runTest {
        mockFeedQuery(
            listOf(
                docFor(DanceLibraryModel(danceId = "one", title = "Groove One", educatorUid = "u")),
                docFor(DanceLibraryModel(danceId = "two", title = "Groove Two", educatorUid = "u"))
            )
        )
        val filter = DiscoveryFilter(tab = FeedTab.TRENDING, categoryKey = "hiphop", query = "Groo two")

        val page = repo.loadFeedPage(filter, seed = 0.5, cursor = null).getOrThrow()

        assertEquals(listOf("two"), page.items.map { it.danceId })
        verify { feedQuery.whereEqualTo("danceTypeKey", "hiphop") }
        verify { feedQuery.whereArrayContains("searchTokens", "groo") }
        verify { feedQuery.orderBy("trendingScore", Query.Direction.DESCENDING) }
    }

    @Test
    fun loadFeedPage_forYou_startsAtSeed_thenWrapsAroundOnce() = runTest {
        mockFeedQuery(
            listOf(docFor(DanceLibraryModel(danceId = "late", educatorUid = "u"))),  // shuffleKey >= seed
            emptyList()                                                                // shuffleKey < seed
        )

        val first = repo.loadFeedPage(DiscoveryFilter(), seed = 0.5, cursor = null, pageSize = 5).getOrThrow()
        assertEquals(listOf("late"), first.items.map { it.danceId })
        assertEquals(FeedCursor(wrapped = true), first.next)
        verify { feedQuery.whereGreaterThanOrEqualTo("shuffleKey", 0.5) }

        val second = repo.loadFeedPage(DiscoveryFilter(), seed = 0.5, cursor = first.next, pageSize = 5).getOrThrow()
        assertTrue(second.items.isEmpty())
        assertNull(second.next)
        verify { feedQuery.whereLessThan("shuffleKey", 0.5) }
    }

    @Test
    fun loadFeedPage_fullPage_returnsCursorAfterLastDoc() = runTest {
        val docs = listOf(
            docFor(DanceLibraryModel(danceId = "a", educatorUid = "u")),
            docFor(DanceLibraryModel(danceId = "b", educatorUid = "u"))
        )
        mockFeedQuery(docs)

        val page = repo.loadFeedPage(DiscoveryFilter(tab = FeedTab.NEW), seed = 0.5, cursor = null, pageSize = 2).getOrThrow()

        assertEquals(docs.last(), page.next?.lastDoc)
    }

    @Test
    fun loadFeedPage_failure_returnsFailure() = runTest {
        mockFeedQuery()
        every { feedQuery.get() } returns Tasks.forException(RuntimeException("boom"))

        assertTrue(repo.loadFeedPage(DiscoveryFilter(), seed = 0.5, cursor = null).isFailure)
    }

    @Test
    fun search_typoTolerant_findsMisspelledWords_andRanksExactMatchesFirst() = runTest {
        mockFeedQuery(
            listOf(
                docFor(DanceLibraryModel(danceId = "exact", title = "Hip Hop Grove", danceType = "Hip Hop", educatorUid = "u")),
                docFor(DanceLibraryModel(danceId = "typo", title = "Hip Hop Groove", danceType = "Hip Hop", educatorUid = "u")),
                docFor(DanceLibraryModel(danceId = "other", title = "Swan Lake", danceType = "Ballet", educatorUid = "u"))
            )
        )

        val page = repo.loadFeedPage(DiscoveryFilter(query = "hiphp grove"), seed = 0.5, cursor = null).getOrThrow()

        assertEquals(listOf("exact", "typo"), page.items.map { it.danceId })
        assertNull(page.next)
        verify { feedQuery.whereArrayContains("searchTokens", "hiphp") }
        verify { feedQuery.whereArrayContainsAny("searchTrigrams", listOf("hip", "iph", "php")) }
    }
}
