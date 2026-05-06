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
    fun listEnabled_success_returnsModels() = runTest {
        val col = mockk<CollectionReference>()
        val query = mockk<Query>()
        val snap = mockk<QuerySnapshot>()
        val d1 = mockk<DocumentSnapshot>()
        val d2 = mockk<DocumentSnapshot>()

        every { firestore.collection("danceLibrary") } returns col
        every { col.whereEqualTo("enabled", true) } returns query
        every { query.get() } returns Tasks.forResult(snap)
        every { snap.documents } returns listOf(d1, d2)

        val m1 = DanceLibraryModel(danceId = "d1", title = "A", educatorName = "E1", enabled = true)
        val m2 = DanceLibraryModel(danceId = "d2", title = "B", educatorName = "E2", enabled = true)

        every { d1.toObject(DanceLibraryModel::class.java) } returns m1
        every { d2.toObject(DanceLibraryModel::class.java) } returns m2

        val res = repo.listEnabled()

        assertTrue(res.isSuccess)
        val list = res.getOrThrow()
        assertEquals(2, list.size)
        assertEquals("d1", list[0].danceId)
        assertEquals("d2", list[1].danceId)

        verify(exactly = 1) { firestore.collection("danceLibrary") }
        verify(exactly = 1) { col.whereEqualTo("enabled", true) }
        verify(exactly = 1) { query.get() }
    }

    @Test
    fun listEnabled_failure_returnsFailure() = runTest {
        val col = mockk<CollectionReference>()
        val query = mockk<Query>()

        every { firestore.collection("danceLibrary") } returns col
        every { col.whereEqualTo("enabled", true) } returns query
        every { query.get() } returns Tasks.forException(RuntimeException("boom"))

        val res = repo.listEnabled()

        assertTrue(res.isFailure)
        assertNotNull(res.exceptionOrNull())

        verify(exactly = 1) { firestore.collection("danceLibrary") }
        verify(exactly = 1) { col.whereEqualTo("enabled", true) }
        verify(exactly = 1) { query.get() }
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
}
