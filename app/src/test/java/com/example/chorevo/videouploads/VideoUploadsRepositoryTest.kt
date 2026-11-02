package com.example.chorevo.videouploads

import android.content.Context
import android.net.Uri
import androidx.work.WorkManager
import com.example.chorevo.core.VideoFrames
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.videouploads.repository.VideoUploadsRepository
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class VideoUploadsRepositoryTest {

    private lateinit var db: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var workManager: WorkManager
    private lateinit var compressor: VideoCompressor
    private lateinit var videoFrames: VideoFrames
    private lateinit var repo: VideoUploadsRepository

    private lateinit var danceCol: CollectionReference
    private lateinit var danceRef: DocumentReference

    @Before
    fun setUp() {
        db = mockk()
        storage = mockk()
        workManager = mockk(relaxed = true)
        compressor = mockk()
        videoFrames = mockk()

        danceCol = mockk()
        danceRef = mockk()
        every { db.collection("danceLibrary") } returns danceCol
        every { danceCol.document(any()) } returns danceRef

        repo = VideoUploadsRepository(
            db = db,
            storage = storage,
            workManager = workManager,
            videoCompressor = compressor,
            videoFrames = videoFrames,
            defaultDispatcher = UnconfinedTestDispatcher()
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ------------------------------------------------------------
    // updateDance
    // ------------------------------------------------------------

    @Test
    fun updateDance_trimsAndTruncatesFields_andMergesWithoutVisibility() = runTest {
        val dataSlot = slot<Any>()
        val optionsSlot = slot<SetOptions>()
        every { danceRef.set(capture(dataSlot), capture(optionsSlot)) } returns Tasks.forResult(null)

        val res = repo.updateDance(
            danceId = "d1",
            title = "  " + "a".repeat(130) + "  ",
            danceType = "  " + "b".repeat(50) + "  ",
            description = "c".repeat(2_500)
        )

        assertTrue(res.isSuccess)
        val data = dataSlot.captured as Map<*, *>
        assertEquals("a".repeat(120), data["title"])
        assertEquals("b".repeat(40), data["danceType"])
        assertEquals("c".repeat(2_000), data["description"])
        assertFalse(data.containsKey("visibility"))
        assertEquals(SetOptions.merge(), optionsSlot.captured)
        verify { danceCol.document("d1") }
    }

    @Test
    fun updateDance_withVisibility_includesVisibilityField() = runTest {
        val dataSlot = slot<Any>()
        every { danceRef.set(capture(dataSlot), any<SetOptions>()) } returns Tasks.forResult(null)

        val res = repo.updateDance(
            danceId = "d1",
            title = "Title",
            danceType = "Jazz",
            description = "Desc",
            visibility = DanceLibraryModel.VISIBILITY_PRIVATE
        )

        assertTrue(res.isSuccess)
        val data = dataSlot.captured as Map<*, *>
        assertEquals(DanceLibraryModel.VISIBILITY_PRIVATE, data["visibility"])
        assertEquals("Title", data["title"])
    }

    @Test
    fun updateDance_firestoreFailure_returnsFailure() = runTest {
        every { danceRef.set(any(), any<SetOptions>()) } returns
            Tasks.forException<Void>(RuntimeException("boom"))

        val res = repo.updateDance("d1", "t", "type", "desc")

        assertTrue(res.isFailure)
        assertEquals("boom", res.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------
    // setVisibility
    // ------------------------------------------------------------

    @Test
    fun setVisibility_writesOnlyVisibilityWithMerge() = runTest {
        val dataSlot = slot<Any>()
        val optionsSlot = slot<SetOptions>()
        every { danceRef.set(capture(dataSlot), capture(optionsSlot)) } returns Tasks.forResult(null)

        val res = repo.setVisibility("d9", DanceLibraryModel.VISIBILITY_PRIVATE)

        assertTrue(res.isSuccess)
        assertEquals(
            mapOf("visibility" to DanceLibraryModel.VISIBILITY_PRIVATE),
            dataSlot.captured as Map<*, *>
        )
        assertEquals(SetOptions.merge(), optionsSlot.captured)
        verify { danceCol.document("d9") }
    }

    @Test
    fun setVisibility_firestoreFailure_returnsFailure() = runTest {
        every { danceRef.set(any(), any<SetOptions>()) } returns
            Tasks.forException<Void>(IllegalStateException("denied"))

        val res = repo.setVisibility("d9", DanceLibraryModel.VISIBILITY_PUBLIC)

        assertTrue(res.isFailure)
        assertEquals("denied", res.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------
    // loadVideos
    // ------------------------------------------------------------

    @Test
    fun loadVideos_queriesByEducatorNewestFirst_andReturnsModels() = runTest {
        val filtered = mockk<Query>()
        val ordered = mockk<Query>()
        val snap = mockk<QuerySnapshot>()
        val items = listOf(
            DanceLibraryModel(danceId = "a"),
            DanceLibraryModel(danceId = "b")
        )
        every { danceCol.whereEqualTo("educatorUid", "u1") } returns filtered
        every { filtered.orderBy("createdAt", Query.Direction.DESCENDING) } returns ordered
        every { ordered.get() } returns Tasks.forResult(snap)
        every { snap.toObjects(DanceLibraryModel::class.java) } returns items

        val res = repo.loadVideos("u1")

        assertTrue(res.isSuccess)
        assertEquals(listOf("a", "b"), res.getOrThrow().map { it.danceId })
    }

    @Test
    fun loadVideos_firestoreFailure_returnsFailure() = runTest {
        val filtered = mockk<Query>()
        val ordered = mockk<Query>()
        every { danceCol.whereEqualTo("educatorUid", "u1") } returns filtered
        every { filtered.orderBy("createdAt", Query.Direction.DESCENDING) } returns ordered
        every { ordered.get() } returns Tasks.forException<QuerySnapshot>(IOException("offline"))

        val res = repo.loadVideos("u1")

        assertTrue(res.isFailure)
        assertEquals("offline", res.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------
    // deleteDance
    // ------------------------------------------------------------

    private class DeleteFixture(
        val videoRef: StorageReference,
        val thumbRef: StorageReference,
        val templateRef: StorageReference,
        val likesCol: CollectionReference,
        val commentsCol: CollectionReference
    )

    private fun stubDeleteTargets(uid: String, danceId: String): DeleteFixture {
        val root = mockk<StorageReference>()
        val videoRef = mockk<StorageReference>()
        val thumbRef = mockk<StorageReference>()
        val templateRef = mockk<StorageReference>()
        every { storage.reference } returns root
        every { root.child("danceVideos/$uid/$danceId.mp4") } returns videoRef
        every { root.child("danceThumbnails/$uid/$danceId.jpg") } returns thumbRef
        every { root.child("danceTemplates/$uid/$danceId.json") } returns templateRef
        every { videoRef.delete() } returns Tasks.forResult(null)
        every { thumbRef.delete() } returns Tasks.forResult(null)
        every { templateRef.delete() } returns Tasks.forResult(null)

        val likesCol = mockk<CollectionReference>()
        val commentsCol = mockk<CollectionReference>()
        every { danceRef.collection("likes") } returns likesCol
        every { danceRef.collection("comments") } returns commentsCol
        every { danceRef.delete() } returns Tasks.forResult(null)

        return DeleteFixture(videoRef, thumbRef, templateRef, likesCol, commentsCol)
    }

    private fun emptySnapshot(): QuerySnapshot {
        val snap = mockk<QuerySnapshot>()
        every { snap.documents } returns emptyList<DocumentSnapshot>()
        return snap
    }

    @Test
    fun deleteDance_blankUid_failsWithoutTouchingFirebase() = runTest {
        val res = repo.deleteDance(danceId = "d1", educatorUid = "  ")

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
        assertEquals("Missing educatorUid for delete", res.exceptionOrNull()?.message)
        verify(exactly = 0) { db.collection(any()) }
    }

    @Test
    fun deleteDance_nullUid_fails() = runTest {
        val res = repo.deleteDance(danceId = "d1", educatorUid = null)

        assertTrue(res.isFailure)
        assertEquals("Missing educatorUid for delete", res.exceptionOrNull()?.message)
    }

    @Test
    fun deleteDance_removesStorageFiles_subcollections_thenDocument() = runTest {
        val fx = stubDeleteTargets("u1", "d1")

        val likeDocRef = mockk<DocumentReference>()
        val likeDoc = mockk<DocumentSnapshot>()
        every { likeDoc.reference } returns likeDocRef
        every { likeDocRef.delete() } returns Tasks.forResult(null)
        val likesSnap = mockk<QuerySnapshot>()
        every { likesSnap.documents } returns listOf(likeDoc)
        every { fx.likesCol.get() } returns Tasks.forResult(likesSnap)
        every { fx.commentsCol.get() } returns Tasks.forResult(emptySnapshot())

        val res = repo.deleteDance("d1", "u1")

        assertTrue(res.isSuccess)
        verify(exactly = 1) { fx.videoRef.delete() }
        verify(exactly = 1) { fx.thumbRef.delete() }
        verify(exactly = 1) { fx.templateRef.delete() }
        verify(exactly = 1) { likeDocRef.delete() }
        verify(exactly = 1) { danceRef.delete() }
        verifyOrder {
            fx.videoRef.delete()
            likeDocRef.delete()
            danceRef.delete()
        }
    }

    @Test
    fun deleteDance_storageAndSubcollectionErrorsAreIgnored_documentStillDeleted() = runTest {
        val fx = stubDeleteTargets("u1", "d1")
        every { fx.videoRef.delete() } returns Tasks.forException<Void>(IOException("no video"))
        every { fx.thumbRef.delete() } returns Tasks.forException<Void>(IOException("no thumb"))
        every { fx.templateRef.delete() } returns Tasks.forException<Void>(IOException("no template"))
        every { fx.likesCol.get() } returns Tasks.forException<QuerySnapshot>(IOException("likes failed"))
        every { fx.commentsCol.get() } returns Tasks.forResult(emptySnapshot())

        val res = repo.deleteDance("d1", "u1")

        assertTrue(res.isSuccess)
        verify(exactly = 1) { danceRef.delete() }
    }

    @Test
    fun deleteDance_documentDeleteFails_returnsFailure() = runTest {
        val fx = stubDeleteTargets("u1", "d1")
        every { fx.likesCol.get() } returns Tasks.forResult(emptySnapshot())
        every { fx.commentsCol.get() } returns Tasks.forResult(emptySnapshot())
        every { danceRef.delete() } returns Tasks.forException<Void>(RuntimeException("firestore down"))

        val res = repo.deleteDance("d1", "u1")

        assertTrue(res.isFailure)
        assertEquals("firestore down", res.exceptionOrNull()?.message)
    }

    // ------------------------------------------------------------
    // uploadDance (paths that do not need the real Android runtime)
    // ------------------------------------------------------------

    @Test
    fun uploadDance_withoutContext_failsBeforeCompressing() = runTest {
        val res = repo.uploadDance(
            context = null,
            educatorUid = "u1",
            educatorName = "Coach",
            title = "t",
            danceType = "Jazz",
            description = "d",
            videoUri = mockk<Uri>()
        )

        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull() is IllegalStateException)
        assertEquals(
            "Context is required for upload/compression",
            res.exceptionOrNull()?.message
        )
        coVerify(exactly = 0) { compressor.compressVideo(any(), any(), any()) }
    }

    @Test
    fun uploadDance_blankOrNullEducatorUid_failsBeforeCompressing() = runTest {
        val ctx = mockk<Context>()
        val uri = mockk<Uri>()

        val blank = repo.uploadDance(
            context = ctx, educatorUid = "   ", educatorName = "Coach",
            title = "t", danceType = "Jazz", description = "d", videoUri = uri
        )
        val missing = repo.uploadDance(
            context = ctx, educatorUid = null, educatorName = "Coach",
            title = "t", danceType = "Jazz", description = "d", videoUri = uri
        )

        assertEquals("Missing educatorUid", blank.exceptionOrNull()?.message)
        assertEquals("Missing educatorUid", missing.exceptionOrNull()?.message)
        coVerify(exactly = 0) { compressor.compressVideo(any(), any(), any()) }
    }

    @Test
    fun uploadDance_compressionFails_reportsStageAndProgress_andReturnsFailure() = runTest {
        val ctx = mockk<Context>()
        val uri = mockk<Uri>()
        val error = IOException("encoder crashed")
        coEvery { compressor.compressVideo(any(), any(), any()) } throws error

        val stages = mutableListOf<String>()
        val progress = mutableListOf<Float>()
        val templateFailures = mutableListOf<Throwable>()

        val res = repo.uploadDance(
            context = ctx,
            educatorUid = "u1",
            educatorName = "Coach",
            title = "t",
            danceType = "Jazz",
            description = "d",
            videoUri = uri,
            onProgress = { progress += it },
            onStageChanged = { stages += it },
            onTemplateFailure = { templateFailures += it }
        )

        assertTrue(res.isFailure)
        assertEquals(error, res.exceptionOrNull())
        assertEquals(listOf("Compressing video"), stages)
        assertEquals(listOf(0.1f), progress)
        // Default continueWithoutTemplateOnFailure = true: no template-failure callback.
        assertTrue(templateFailures.isEmpty())
        coVerify(exactly = 1) { compressor.compressVideo(uri, 720, 24) }
        verify(exactly = 0) { db.collection(any()) }
    }

    @Test
    fun uploadDance_compressionFails_notifiesTemplateFailureWhenNotContinuing() = runTest {
        val error = IOException("encoder crashed")
        coEvery { compressor.compressVideo(any(), any(), any()) } throws error
        val templateFailures = mutableListOf<Throwable>()

        val res = repo.uploadDance(
            context = mockk<Context>(),
            educatorUid = "u1",
            educatorName = "Coach",
            title = "t",
            danceType = "Jazz",
            description = "d",
            videoUri = mockk<Uri>(),
            onTemplateFailure = { templateFailures += it },
            continueWithoutTemplateOnFailure = false
        )

        assertTrue(res.isFailure)
        assertEquals(listOf<Throwable>(error), templateFailures)
    }

    @Test
    fun uploadDance_failureAfterCompression_deletesCompressedTempFile() = runTest {
        val compressed = File.createTempFile("compressed_test", ".mp4")
        compressed.writeBytes(ByteArray(16) { it.toByte() })
        assertTrue(compressed.exists())

        coEvery { compressor.compressVideo(any(), any(), any()) } returns compressed
        // The compressed file is used (original size unknown -> -1), which builds a file Uri.
        // Make that step fail deterministically so the finally block must clean up.
        mockkStatic(Uri::class)
        every { Uri.fromFile(any()) } throws IllegalStateException("no uri")

        val res = repo.uploadDance(
            context = mockk<Context>(),
            educatorUid = "u1",
            educatorName = "Coach",
            title = "t",
            danceType = "Jazz",
            description = "d",
            videoUri = mockk<Uri>()
        )

        assertTrue(res.isFailure)
        assertEquals("no uri", res.exceptionOrNull()?.message)
        assertFalse(compressed.exists())
        compressed.delete()
    }
}
