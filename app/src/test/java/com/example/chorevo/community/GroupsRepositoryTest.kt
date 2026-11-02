package com.example.chorevo.community

import com.example.chorevo.community.repository.GroupsRepository
import com.example.chorevo.core.VideoFrames
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import com.example.chorevo.firebase.repository.UserSearchResult
import com.example.chorevo.testing.MainDispatcherRule
import com.example.chorevo.videouploads.VideoCompressor
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * JVM tests for [GroupsRepository]. Firestore chains are mocked with MockK and
 * Play-services Tasks are completed with [Tasks.forResult]/[Tasks.forException].
 * Video upload ([GroupsRepository.createVideoPost]) is not covered here because it
 * needs an Android Context/Uri and the Storage upload extension.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupsRepositoryTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage
    private lateinit var functions: FirebaseFunctions
    private lateinit var groupsCol: CollectionReference
    private lateinit var repo: GroupsRepository

    private val me = GroupsRepository.Author(uid = "me", displayName = "Me", photoUrl = null)

    @Before
    fun setUp() {
        firestore = mockk()
        storage = mockk()
        functions = mockk()
        groupsCol = mockk()
        every { firestore.collection("groups") } returns groupsCol
        repo = GroupsRepository(
            firestore,
            storage,
            mockk<VideoCompressor>(relaxed = true),
            mockk<VideoFrames>(relaxed = true),
            functions
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun ok(): Task<Void> = Tasks.forResult(null)

    private fun <T> failed(e: Exception): Task<T> = Tasks.forException(e)

    private fun groupDoc(id: String = "g1"): DocumentReference {
        val doc = mockk<DocumentReference>()
        every { groupsCol.document(id) } returns doc
        return doc
    }

    private fun subCollection(parent: DocumentReference, name: String): CollectionReference {
        val col = mockk<CollectionReference>()
        every { parent.collection(name) } returns col
        return col
    }

    private fun subDoc(col: CollectionReference, id: String): DocumentReference {
        val doc = mockk<DocumentReference>()
        every { col.document(id) } returns doc
        return doc
    }

    /** Records what is passed to `doc.set(data)` and completes successfully. */
    private fun recordSet(doc: DocumentReference): MutableList<Any> {
        val written = mutableListOf<Any>()
        every { doc.set(any()) } answers {
            written += firstArg<Any>()
            ok()
        }
        return written
    }

    private class SetCall(val ref: DocumentReference, val data: Any)

    private fun stubBatch(commit: Task<Void> = Tasks.forResult(null)): Pair<WriteBatch, MutableList<SetCall>> {
        val batch = mockk<WriteBatch>()
        val sets = mutableListOf<SetCall>()
        every { firestore.batch() } returns batch
        every { batch.set(any<DocumentReference>(), any()) } answers {
            sets += SetCall(firstArg<DocumentReference>(), secondArg<Any>())
            batch
        }
        every { batch.delete(any()) } returns batch
        every { batch.commit() } returns commit
        return batch to sets
    }

    private fun snap(
        exists: Boolean = true,
        id: String = "x",
        strings: Map<String, String?> = emptyMap(),
        longs: Map<String, Long?> = emptyMap()
    ): DocumentSnapshot {
        val s = mockk<DocumentSnapshot>()
        every { s.exists() } returns exists
        every { s.id } returns id
        every { s.getString(any<String>()) } answers { strings[firstArg<String>()] }
        every { s.getLong(any<String>()) } answers { longs[firstArg<String>()] }
        return s
    }

    private fun querySnap(docs: List<DocumentSnapshot>): QuerySnapshot {
        val qs = mockk<QuerySnapshot>()
        every { qs.documents } returns docs
        return qs
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any.asMap(): Map<String, Any?> = this as Map<String, Any?>

    // ── createGroup ─────────────────────────────────────────────────────

    @Test
    fun createGroup_private_writesGroupOwnerAndInviteInOneBatch() = runTest {
        val newDoc = mockk<DocumentReference>()
        every { groupsCol.document() } returns newDoc
        every { newDoc.id } returns "new1"
        val members = subCollection(newDoc, "members")
        val ownerDoc = subDoc(members, "owner1")
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = mockk<DocumentReference>()
        every { invitesCol.document(any<String>()) } returns inviteDoc
        val (batch, sets) = stubBatch()

        val result = repo.createGroup(
            uid = "owner1",
            displayName = "Olivia",
            photoUrl = "http://p",
            name = "  Salsa Crew  ",
            description = "  Fun  ",
            private = true
        )

        assertTrue(result.isSuccess)
        val group = result.getOrThrow()
        assertEquals("new1", group.id)
        assertEquals("Salsa Crew", group.name)
        assertEquals("Fun", group.description)
        assertEquals(DanceGroup.VISIBILITY_PRIVATE, group.visibility)
        assertEquals("owner1", group.ownerUid)
        assertEquals("Olivia", group.ownerName)
        assertEquals(1, group.memberCount)
        val code = group.inviteCode
        assertNotNull(code)
        assertEquals(INVITE_CODE_LENGTH, code!!.length)
        assertTrue(code.all { it in INVITE_CODE_ALPHABET })

        assertEquals(3, sets.size)
        assertEquals(newDoc, sets[0].ref)
        val groupData = sets[0].data.asMap()
        assertEquals("Salsa Crew", groupData["name"])
        assertEquals("salsa crew", groupData["nameLower"])
        assertEquals("private", groupData["visibility"])
        assertEquals(code, groupData["inviteCode"])

        assertEquals(ownerDoc, sets[1].ref)
        val ownerData = sets[1].data.asMap()
        assertEquals("owner1", ownerData["uid"])
        assertEquals(GroupMember.ROLE_OWNER, ownerData["role"])
        assertEquals("http://p", ownerData["photoUrl"])

        assertEquals(inviteDoc, sets[2].ref)
        assertEquals("new1", sets[2].data.asMap()["groupId"])
        verify(exactly = 1) { invitesCol.document(code) }
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun createGroup_public_hasNoInviteCodeAndSkipsInvitesCollection() = runTest {
        val newDoc = mockk<DocumentReference>()
        every { groupsCol.document() } returns newDoc
        every { newDoc.id } returns "new2"
        val members = subCollection(newDoc, "members")
        subDoc(members, "u1")
        val (_, sets) = stubBatch()

        val result = repo.createGroup("u1", "   ", null, "Hip Hop", "", private = false)

        assertTrue(result.isSuccess)
        val group = result.getOrThrow()
        assertEquals(DanceGroup.VISIBILITY_PUBLIC, group.visibility)
        assertNull(group.inviteCode)
        // A blank display name falls back to "User".
        assertEquals("User", group.ownerName)
        assertEquals(2, sets.size)
        assertFalse(sets[0].data.asMap().containsKey("inviteCode"))
        assertEquals("User", sets[1].data.asMap()["displayName"])
        verify(exactly = 0) { firestore.collection("groupInvites") }
    }

    @Test
    fun createGroup_truncatesDescriptionTo500Characters() = runTest {
        val newDoc = mockk<DocumentReference>()
        every { groupsCol.document() } returns newDoc
        every { newDoc.id } returns "new3"
        subDoc(subCollection(newDoc, "members"), "u1")
        stubBatch()

        val result = repo.createGroup("u1", "U", null, "Valid", "x".repeat(800), private = false)

        assertEquals(500, result.getOrThrow().description.length)
    }

    @Test
    fun createGroup_tooShortName_failsWithoutWriting() = runTest {
        val result = repo.createGroup("u1", "U", null, "  ab  ", "d", private = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { firestore.batch() }
    }

    @Test
    fun createGroup_tooLongName_fails() = runTest {
        val result = repo.createGroup("u1", "U", null, "n".repeat(61), "d", private = true)

        assertTrue(result.isFailure)
        verify(exactly = 0) { firestore.batch() }
    }

    @Test
    fun createGroup_commitFailure_isReturnedAsFailure() = runTest {
        val newDoc = mockk<DocumentReference>()
        every { groupsCol.document() } returns newDoc
        every { newDoc.id } returns "new4"
        subDoc(subCollection(newDoc, "members"), "u1")
        stubBatch(commit = failed(RuntimeException("denied")))

        val result = repo.createGroup("u1", "U", null, "Valid", "", private = false)

        assertTrue(result.isFailure)
        assertEquals("denied", result.exceptionOrNull()?.message)
    }

    // ── join ────────────────────────────────────────────────────────────

    @Test
    fun joinWithCode_unknownCode_failsWithMessage() = runTest {
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = subDoc(invitesCol, "ABCD2345")
        every { inviteDoc.get() } returns Tasks.forResult(snap(exists = false))

        val result = repo.joinWithCode("ABCD2345", "me", "Me", null)

        assertTrue(result.isFailure)
        assertEquals("No group has that invite code.", result.exceptionOrNull()?.message)
    }

    @Test
    fun joinWithCode_inviteWithoutGroupId_fails() = runTest {
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = subDoc(invitesCol, "ABCD2345")
        every { inviteDoc.get() } returns Tasks.forResult(snap(exists = true, strings = mapOf("groupId" to "")))

        val result = repo.joinWithCode("ABCD2345", "me", "Me", null)

        assertTrue(result.isFailure)
        assertEquals("No group has that invite code.", result.exceptionOrNull()?.message)
    }

    @Test
    fun joinWithCode_newMember_writesMemberDocWithInviteCode() = runTest {
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = subDoc(invitesCol, "ABCD2345")
        every { inviteDoc.get() } returns Tasks.forResult(snap(strings = mapOf("groupId" to "g1")))
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        // Non-members can't read member docs: a failed read means "not yet a member".
        every { memberDoc.get() } returns failed(RuntimeException("permission denied"))
        val written = recordSet(memberDoc)

        val result = repo.joinWithCode("ABCD2345", "me", "Me", "http://p")

        assertEquals("g1", result.getOrNull())
        assertEquals(1, written.size)
        val data = written[0].asMap()
        assertEquals("me", data["uid"])
        assertEquals("Me", data["displayName"])
        assertEquals(GroupMember.ROLE_MEMBER, data["role"])
        assertEquals("ABCD2345", data["inviteCode"])
    }

    @Test
    fun joinWithCode_alreadyMember_returnsGroupIdWithoutWriting() = runTest {
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = subDoc(invitesCol, "ABCD2345")
        every { inviteDoc.get() } returns Tasks.forResult(snap(strings = mapOf("groupId" to "g1")))
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.get() } returns Tasks.forResult(snap(exists = true))

        val result = repo.joinWithCode("ABCD2345", "me", "Me", null)

        assertEquals("g1", result.getOrNull())
        verify(exactly = 0) { memberDoc.set(any()) }
    }

    @Test
    fun joinWithCode_writeRejected_returnsFailure() = runTest {
        val invitesCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvites") } returns invitesCol
        val inviteDoc = subDoc(invitesCol, "ABCD2345")
        every { inviteDoc.get() } returns Tasks.forResult(snap(strings = mapOf("groupId" to "g1")))
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.get() } returns Tasks.forResult(snap(exists = false))
        every { memberDoc.set(any()) } returns failed(RuntimeException("You are banned"))

        val result = repo.joinWithCode("ABCD2345", "me", "Me", null)

        assertTrue(result.isFailure)
        assertEquals("You are banned", result.exceptionOrNull()?.message)
    }

    @Test
    fun joinPublicGroup_writesMemberDoc() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        val written = recordSet(memberDoc)

        val result = repo.joinPublicGroup("g1", "me", "", "http://p")

        assertTrue(result.isSuccess)
        val data = written.single().asMap()
        assertEquals("me", data["uid"])
        // Blank display name becomes "User".
        assertEquals("User", data["displayName"])
        assertEquals(GroupMember.ROLE_MEMBER, data["role"])
        assertFalse(data.containsKey("inviteCode"))
    }

    @Test
    fun leaveGroup_deletesOwnMemberDoc() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.delete() } returns ok()

        val result = repo.leaveGroup("g1", "me")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { memberDoc.delete() }
    }

    @Test
    fun deleteGroup_deletesGroupDoc() = runTest {
        val group = groupDoc("g1")
        every { group.delete() } returns ok()

        assertTrue(repo.deleteGroup("g1").isSuccess)
        verify(exactly = 1) { group.delete() }
    }

    @Test
    fun deleteGroup_failure_isReturned() = runTest {
        val group = groupDoc("g1")
        every { group.delete() } returns failed(RuntimeException("not owner"))

        val result = repo.deleteGroup("g1")

        assertTrue(result.isFailure)
        assertEquals("not owner", result.exceptionOrNull()?.message)
    }

    // ── invitations ─────────────────────────────────────────────────────

    @Test
    fun sendInvitation_writesInvitationDocKeyedByGroupAndInvitee() = runTest {
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_u2")
        val written = recordSet(invDoc)

        val result = repo.sendInvitation(
            group = DanceGroup(id = "g1", name = "Salsa"),
            inviter = me,
            invitee = UserSearchResult(uid = "u2", username = "bob", displayName = "Bob", photoUrl = null)
        )

        assertTrue(result.isSuccess)
        val data = written.single().asMap()
        assertEquals("g1", data["groupId"])
        assertEquals("Salsa", data["groupName"])
        assertEquals("u2", data["inviteeUid"])
        assertEquals("bob", data["inviteeUsername"])
        assertEquals("me", data["inviterUid"])
        assertEquals("Me", data["inviterName"])
    }

    @Test
    fun acceptInvitation_success_joinsAndDeletesInvitationInOneBatch() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        val (batch, sets) = stubBatch()

        val result = repo.acceptInvitation(GroupInvitation(id = "g1_me", groupId = "g1"), me)

        assertEquals("g1", result.getOrNull())
        assertEquals(1, sets.size)
        assertEquals(memberDoc, sets[0].ref)
        assertEquals(GroupMember.ROLE_MEMBER, sets[0].data.asMap()["role"])
        verify(exactly = 1) { batch.delete(invDoc) }
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun acceptInvitation_alreadyMember_justClearsInvitation() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.get() } returns Tasks.forResult(snap(exists = true))
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        every { invDoc.delete() } returns ok()
        stubBatch(commit = failed(RuntimeException("exists")))

        val result = repo.acceptInvitation(GroupInvitation(id = "g1_me", groupId = "g1"), me)

        assertEquals("g1", result.getOrNull())
        verify(exactly = 1) { invDoc.delete() }
    }

    @Test
    fun acceptInvitation_groupDeleted_clearsInvitationAndReportsGone() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.get() } returns Tasks.forResult(snap(exists = false))
        every { group.get() } returns Tasks.forResult(snap(exists = false))
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        every { invDoc.delete() } returns ok()
        stubBatch(commit = failed(RuntimeException("not found")))

        val result = repo.acceptInvitation(GroupInvitation(id = "g1_me", groupId = "g1"), me)

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("This group no longer exists.", result.exceptionOrNull()?.message)
        verify(exactly = 1) { invDoc.delete() }
    }

    @Test
    fun acceptInvitation_otherFailure_rethrowsOriginalError() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        every { memberDoc.get() } returns Tasks.forResult(snap(exists = false))
        every { group.get() } returns Tasks.forResult(snap(exists = true))
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        stubBatch(commit = failed(RuntimeException("banned from group")))

        val result = repo.acceptInvitation(GroupInvitation(id = "g1_me", groupId = "g1"), me)

        assertTrue(result.isFailure)
        assertEquals("banned from group", result.exceptionOrNull()?.message)
        verify(exactly = 0) { invDoc.delete() }
    }

    @Test
    fun declineInvitation_deletesInvitationDoc() = runTest {
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        every { invDoc.delete() } returns ok()

        val result = repo.declineInvitation(GroupInvitation(id = "g1_me", groupId = "g1"))

        assertTrue(result.isSuccess)
        verify(exactly = 1) { invDoc.delete() }
    }

    @Test
    fun declineInvitation_failure_isReturned() = runTest {
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val invDoc = subDoc(invitationsCol, "g1_me")
        every { invDoc.delete() } returns failed(RuntimeException("offline"))

        val result = repo.declineInvitation(GroupInvitation(id = "g1_me", groupId = "g1"))

        assertTrue(result.isFailure)
    }

    // ── ban / unban ─────────────────────────────────────────────────────

    @Test
    fun removeMember_deletesMembershipAndWritesBanInOneBatch() = runTest {
        val group = groupDoc("g1")
        val members = subCollection(group, "members")
        val bans = subCollection(group, "bans")
        val memberDoc = subDoc(members, "bad")
        val banDoc = subDoc(bans, "bad")
        val (batch, sets) = stubBatch()

        val result = repo.removeMember("g1", "bad")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { batch.delete(memberDoc) }
        assertEquals(1, sets.size)
        assertEquals(banDoc, sets[0].ref)
        val ban = sets[0].data.asMap()
        assertEquals("bad", ban["uid"])
        assertTrue((ban["bannedAt"] as Long) > 0L)
        verify(exactly = 1) { batch.commit() }
    }

    @Test
    fun unbanMember_deletesBanDoc() = runTest {
        val group = groupDoc("g1")
        val banDoc = subDoc(subCollection(group, "bans"), "bad")
        every { banDoc.delete() } returns ok()

        val result = repo.unbanMember("g1", "bad")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { banDoc.delete() }
    }

    @Test
    fun bannedMembers_joinsProfilesAndSortsNewestFirst() = runTest {
        val group = groupDoc("g1")
        val bans = subCollection(group, "bans")
        every { bans.get() } returns Tasks.forResult(
            querySnap(
                listOf(
                    snap(id = "old", longs = mapOf("bannedAt" to 100L)),
                    snap(id = "new", longs = mapOf("bannedAt" to 300L)),
                    snap(id = "mid", longs = mapOf("bannedAt" to 200L)),
                    snap(id = "blank", longs = mapOf("bannedAt" to 50L))
                )
            )
        )
        val usersCol = mockk<CollectionReference>()
        every { firestore.collection("users") } returns usersCol
        val newUser = subDoc(usersCol, "new")
        every { newUser.get() } returns Tasks.forResult(
            snap(strings = mapOf("displayName" to "  Newbie  ", "photoUrl" to "http://n"))
        )
        val oldUser = subDoc(usersCol, "old")
        every { oldUser.get() } returns failed(RuntimeException("no access"))
        val midUser = subDoc(usersCol, "mid")
        every { midUser.get() } returns Tasks.forResult(snap(strings = mapOf("displayName" to "Middle")))
        val blankUser = subDoc(usersCol, "blank")
        every { blankUser.get() } returns Tasks.forResult(snap(strings = mapOf("displayName" to "   ")))

        val list = repo.bannedMembers("g1").getOrThrow()

        assertEquals(listOf("new", "mid", "old", "blank"), list.map { it.uid })
        assertEquals(BannedMember("new", "Newbie", "http://n", 300L), list[0])
        assertEquals("Middle", list[1].displayName)
        assertNull(list[1].photoUrl)
        // Unreadable or blank profiles fall back to a generic label.
        assertEquals("Removed member", list[2].displayName)
        assertEquals("Removed member", list[3].displayName)
        assertEquals(50L, list[3].bannedAt)
    }

    @Test
    fun bannedMembers_listFailure_isReturnedAsFailure() = runTest {
        val group = groupDoc("g1")
        val bans = subCollection(group, "bans")
        every { bans.get() } returns failed(RuntimeException("not owner"))

        assertTrue(repo.bannedMembers("g1").isFailure)
    }

    // ── posts and replies ───────────────────────────────────────────────

    @Test
    fun createTextPost_writesTrimmedBodyAsTextKind() = runTest {
        val group = groupDoc("g1")
        val posts = subCollection(group, "posts")
        val postDoc = mockk<DocumentReference>()
        every { posts.document() } returns postDoc
        every { postDoc.id } returns "p1"
        val written = recordSet(postDoc)

        val result = repo.createTextPost("g1", me, "  hello  ")

        assertTrue(result.isSuccess)
        val data = written.single().asMap()
        assertEquals("p1", data["id"])
        assertEquals("me", data["uid"])
        assertEquals("Me", data["displayName"])
        assertEquals("hello", data["body"])
        assertEquals("text", data["kind"])
    }

    @Test
    fun createTextPost_truncatesBodyTo1500AndDefaultsBlankName() = runTest {
        val group = groupDoc("g1")
        val posts = subCollection(group, "posts")
        val postDoc = mockk<DocumentReference>()
        every { posts.document() } returns postDoc
        every { postDoc.id } returns "p1"
        val written = recordSet(postDoc)

        repo.createTextPost("g1", GroupsRepository.Author("me", "  ", null), "y".repeat(2_000))

        val data = written.single().asMap()
        assertEquals(1_500, (data["body"] as String).length)
        assertEquals("User", data["displayName"])
    }

    @Test
    fun createTextPost_failure_isReturned() = runTest {
        val group = groupDoc("g1")
        val posts = subCollection(group, "posts")
        val postDoc = mockk<DocumentReference>()
        every { posts.document() } returns postDoc
        every { postDoc.id } returns "p1"
        every { postDoc.set(any()) } returns failed(RuntimeException("not a member"))

        val result = repo.createTextPost("g1", me, "hi")

        assertTrue(result.isFailure)
        assertEquals("not a member", result.exceptionOrNull()?.message)
    }

    @Test
    fun createDancePost_includesDanceFieldsAndNullsBlankThumbnail() = runTest {
        val group = groupDoc("g1")
        val posts = subCollection(group, "posts")
        val postDoc = mockk<DocumentReference>()
        every { posts.document() } returns postDoc
        every { postDoc.id } returns "p2"
        val written = recordSet(postDoc)
        val dance = DanceLibraryModel(
            danceId = "d1",
            title = "Cha Cha",
            videoUrl = "http://v",
            thumbnailUrl = ""
        )

        val result = repo.createDancePost("g1", me, "check this", dance)

        assertTrue(result.isSuccess)
        val data = written.single().asMap()
        assertEquals("dance", data["kind"])
        assertEquals("d1", data["danceId"])
        assertEquals("Cha Cha", data["danceTitle"])
        assertEquals("http://v", data["videoUrl"])
        assertTrue(data.containsKey("thumbnailUrl"))
        assertNull(data["thumbnailUrl"])
    }

    @Test
    fun createReply_writesTrimmedBody() = runTest {
        val group = groupDoc("g1")
        val posts = subCollection(group, "posts")
        val postDoc = subDoc(posts, "p1")
        val replies = subCollection(postDoc, "replies")
        val replyDoc = mockk<DocumentReference>()
        every { replies.document() } returns replyDoc
        every { replyDoc.id } returns "r1"
        val written = recordSet(replyDoc)

        val result = repo.createReply("g1", "p1", me, "  nice!  ")

        assertTrue(result.isSuccess)
        val data = written.single().asMap()
        assertEquals("r1", data["id"])
        assertEquals("p1", data["postId"])
        assertEquals("nice!", data["body"])
        assertEquals("me", data["uid"])
    }

    @Test
    fun createReply_truncatesTo500Characters() = runTest {
        val group = groupDoc("g1")
        val postDoc = subDoc(subCollection(group, "posts"), "p1")
        val replies = subCollection(postDoc, "replies")
        val replyDoc = mockk<DocumentReference>()
        every { replies.document() } returns replyDoc
        every { replyDoc.id } returns "r1"
        val written = recordSet(replyDoc)

        repo.createReply("g1", "p1", me, "z".repeat(900))

        assertEquals(500, (written.single().asMap()["body"] as String).length)
    }

    @Test
    fun createReply_blankBody_failsWithoutWriting() = runTest {
        val result = repo.createReply("g1", "p1", me, "   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        verify(exactly = 0) { groupsCol.document(any<String>()) }
    }

    @Test
    fun deletePost_byAuthor_deletesPostRepliesAndOwnFiles() = runTest {
        val group = groupDoc("g1")
        val postDoc = subDoc(subCollection(group, "posts"), "p1")
        val replies = subCollection(postDoc, "replies")
        val replyRef = mockk<DocumentReference>()
        every { replyRef.delete() } returns ok()
        val replySnap = mockk<DocumentSnapshot>()
        every { replySnap.reference } returns replyRef
        every { replies.get() } returns Tasks.forResult(querySnap(listOf(replySnap)))
        every { postDoc.delete() } returns ok()
        val root = mockk<StorageReference>()
        every { storage.reference } returns root
        val videoRef = mockk<StorageReference>()
        val thumbRef = mockk<StorageReference>()
        every { root.child("groupVideos/me/p1.mp4") } returns videoRef
        every { root.child("groupThumbnails/me/p1.jpg") } returns thumbRef
        every { videoRef.delete() } returns ok()
        every { thumbRef.delete() } returns ok()
        val post = GroupPost(
            id = "p1",
            groupId = "g1",
            uid = "me",
            kind = GroupPostKind.VIDEO,
            storagePath = "groupVideos/me/p1.mp4",
            thumbnailPath = "groupThumbnails/me/p1.jpg"
        )

        val result = repo.deletePost(post, requesterUid = "me")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { replyRef.delete() }
        verify(exactly = 1) { postDoc.delete() }
        verify(exactly = 1) { videoRef.delete() }
        verify(exactly = 1) { thumbRef.delete() }
    }

    @Test
    fun deletePost_byGroupOwner_keepsAuthorsStorageFiles() = runTest {
        val group = groupDoc("g1")
        val postDoc = subDoc(subCollection(group, "posts"), "p1")
        val replies = subCollection(postDoc, "replies")
        every { replies.get() } returns Tasks.forResult(querySnap(emptyList()))
        every { postDoc.delete() } returns ok()
        val post = GroupPost(
            id = "p1",
            groupId = "g1",
            uid = "author",
            kind = GroupPostKind.VIDEO,
            storagePath = "groupVideos/author/p1.mp4"
        )

        val result = repo.deletePost(post, requesterUid = "owner")

        assertTrue(result.isSuccess)
        verify(exactly = 1) { postDoc.delete() }
        // storage is a strict mock: touching it would have failed the call.
        verify(exactly = 0) { storage.reference }
    }

    @Test
    fun deletePost_postDeleteFails_returnsFailure() = runTest {
        val group = groupDoc("g1")
        val postDoc = subDoc(subCollection(group, "posts"), "p1")
        val replies = subCollection(postDoc, "replies")
        every { replies.get() } returns failed(RuntimeException("x"))
        every { postDoc.delete() } returns failed(RuntimeException("forbidden"))

        val result = repo.deletePost(GroupPost(id = "p1", groupId = "g1", uid = "me"), "me")

        assertTrue(result.isFailure)
        assertEquals("forbidden", result.exceptionOrNull()?.message)
    }

    // ── groupMediaUrls ──────────────────────────────────────────────────

    @Test
    fun groupMediaUrls_emptyIds_returnsEmptyWithoutCallingFunction() = runTest {
        val result = repo.groupMediaUrls("g1", emptyList())

        assertEquals(emptyMap<String, PostMedia>(), result.getOrNull())
        verify(exactly = 0) { functions.getHttpsCallable(any<String>()) }
    }

    @Test
    fun groupMediaUrls_mapsResponseAndSkipsEntriesWithoutVideo() = runTest {
        val callable = mockk<HttpsCallableReference>()
        val callResult = mockk<HttpsCallableResult>()
        every { functions.getHttpsCallable("groupMediaUrls") } returns callable
        val sent = slot<Any>()
        every { callable.call(capture(sent)) } returns Tasks.forResult(callResult)
        every { callResult.getData() } returns mapOf(
            "urls" to mapOf(
                "p1" to mapOf("videoUrl" to "http://v1", "thumbnailUrl" to "http://t1", "expiresAt" to 5_000L),
                "p2" to mapOf("videoUrl" to "http://v2", "expiresAt" to 7),
                "p3" to mapOf("thumbnailUrl" to "http://t3"),
                "p4" to "garbage"
            )
        )

        val result = repo.groupMediaUrls("g1", listOf("p1", "p2", "p3", "p4"))

        val map = result.getOrThrow()
        assertEquals(setOf("p1", "p2"), map.keys)
        assertEquals(PostMedia("http://v1", "http://t1", 5_000L), map["p1"])
        assertEquals(PostMedia("http://v2", null, 7L), map["p2"])
        val payload = sent.captured.asMap()
        assertEquals("g1", payload["groupId"])
        assertEquals(listOf("p1", "p2", "p3", "p4"), payload["postIds"])
    }

    @Test
    fun groupMediaUrls_unexpectedPayload_returnsEmptyMap() = runTest {
        val callable = mockk<HttpsCallableReference>()
        val callResult = mockk<HttpsCallableResult>()
        every { functions.getHttpsCallable("groupMediaUrls") } returns callable
        every { callable.call(any()) } returns Tasks.forResult(callResult)
        every { callResult.getData() } returns "nope"

        val result = repo.groupMediaUrls("g1", listOf("p1"))

        assertEquals(emptyMap<String, PostMedia>(), result.getOrNull())
    }

    @Test
    fun groupMediaUrls_functionFailure_isReturnedAsFailure() = runTest {
        val callable = mockk<HttpsCallableReference>()
        every { functions.getHttpsCallable("groupMediaUrls") } returns callable
        every { callable.call(any()) } returns failed(RuntimeException("unauthenticated"))

        val result = repo.groupMediaUrls("g1", listOf("p1"))

        assertTrue(result.isFailure)
    }

    // ── loadPublicGroups ────────────────────────────────────────────────

    private fun groupSnap(id: String, name: String?, visibility: String? = "public"): DocumentSnapshot =
        snap(
            id = id,
            strings = mapOf(
                "name" to name,
                "description" to "desc",
                "visibility" to visibility,
                "ownerUid" to "o",
                "ownerName" to "Owner",
                "inviteCode" to null
            ),
            longs = mapOf("memberCount" to 4L, "createdAt" to 9L)
        )

    @Test
    fun loadPublicGroups_blankSearch_ordersByMemberCountAndMapsGroups() = runTest {
        val q1 = mockk<Query>()
        val q2 = mockk<Query>()
        val q3 = mockk<Query>()
        every { groupsCol.whereEqualTo("visibility", DanceGroup.VISIBILITY_PUBLIC) } returns q1
        every { q1.orderBy("memberCount", Query.Direction.DESCENDING) } returns q2
        every { q2.limit(50L) } returns q3
        every { q3.get() } returns Tasks.forResult(
            querySnap(
                listOf(
                    groupSnap("a", "Salsa"),
                    groupSnap("b", "   "),
                    snap(exists = false, id = "c"),
                    groupSnap("d", "Tango")
                )
            )
        )

        val result = repo.loadPublicGroups("   ")

        val groups = result.getOrThrow()
        // Blank-named and missing docs are dropped.
        assertEquals(listOf("a", "d"), groups.map { it.id })
        assertEquals("Salsa", groups[0].name)
        assertEquals(4, groups[0].memberCount)
        assertEquals("Owner", groups[0].ownerName)
        assertEquals(9L, groups[0].createdAt)
    }

    @Test
    fun loadPublicGroups_search_usesLowercasedNamePrefixRange() = runTest {
        val q1 = mockk<Query>()
        val q2 = mockk<Query>()
        val q3 = mockk<Query>()
        val q4 = mockk<Query>()
        val q5 = mockk<Query>()
        every { groupsCol.whereEqualTo("visibility", DanceGroup.VISIBILITY_PUBLIC) } returns q1
        every { q1.orderBy("nameLower") } returns q2
        every { q2.whereGreaterThanOrEqualTo("nameLower", "sal") } returns q3
        every { q3.whereLessThan("nameLower", "sal") } returns q4
        every { q4.limit(50L) } returns q5
        every { q5.get() } returns Tasks.forResult(querySnap(listOf(groupSnap("a", "Salsa"))))

        val result = repo.loadPublicGroups("  SAL ")

        assertEquals(listOf("a"), result.getOrThrow().map { it.id })
        verify(exactly = 1) { q2.whereGreaterThanOrEqualTo("nameLower", "sal") }
    }

    @Test
    fun loadPublicGroups_queryFailure_isReturnedAsFailure() = runTest {
        val q1 = mockk<Query>()
        val q2 = mockk<Query>()
        val q3 = mockk<Query>()
        every { groupsCol.whereEqualTo("visibility", DanceGroup.VISIBILITY_PUBLIC) } returns q1
        every { q1.orderBy("memberCount", Query.Direction.DESCENDING) } returns q2
        every { q2.limit(50L) } returns q3
        every { q3.get() } returns failed(RuntimeException("offline"))

        assertTrue(repo.loadPublicGroups("").isFailure)
    }

    // ── observers (listener callbacks captured with slots) ──────────────

    @Test
    fun observeMembership_emitsMemberForExistingDoc_andRemovesListenerOnCancel() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        val listener = slot<EventListener<DocumentSnapshot>>()
        val registration = mockk<ListenerRegistration>(relaxed = true)
        every { memberDoc.addSnapshotListener(capture(listener)) } returns registration

        val deferred = async { repo.observeMembership("g1", "me").first() }
        advanceUntilIdle()
        listener.captured.onEvent(
            snap(
                strings = mapOf("uid" to "me", "displayName" to "Me", "photoUrl" to null, "role" to "owner"),
                longs = mapOf("joinedAt" to 12L)
            ),
            null
        )
        val member = deferred.await()

        assertEquals(GroupMember(uid = "me", displayName = "Me", photoUrl = null, role = "owner", joinedAt = 12L), member)
        assertTrue(member!!.isOwner)
        verify(exactly = 1) { registration.remove() }
    }

    @Test
    fun observeMembership_emitsNullWhenDocMissingOrError() = runTest {
        val group = groupDoc("g1")
        val memberDoc = subDoc(subCollection(group, "members"), "me")
        val listener = slot<EventListener<DocumentSnapshot>>()
        every { memberDoc.addSnapshotListener(capture(listener)) } returns mockk<ListenerRegistration>(relaxed = true)

        val missing = async { repo.observeMembership("g1", "me").first() }
        advanceUntilIdle()
        listener.captured.onEvent(snap(exists = false), null)
        assertNull(missing.await())

        val errored = async { repo.observeMembership("g1", "me").first() }
        advanceUntilIdle()
        listener.captured.onEvent(null, mockk<FirebaseFirestoreException>(relaxed = true))
        assertNull(errored.await())
    }

    @Test
    fun observeGroup_emitsMappedGroup_andNullOnError() = runTest {
        val group = groupDoc("g1")
        val listener = slot<EventListener<DocumentSnapshot>>()
        every { group.addSnapshotListener(capture(listener)) } returns mockk<ListenerRegistration>(relaxed = true)

        val found = async { repo.observeGroup("g1").first() }
        advanceUntilIdle()
        listener.captured.onEvent(groupSnap("g1", "Salsa", visibility = "private"), null)
        val g = found.await()
        assertEquals("Salsa", g?.name)
        assertTrue(g!!.isPrivate)

        val errored = async { repo.observeGroup("g1").first() }
        advanceUntilIdle()
        listener.captured.onEvent(null, mockk<FirebaseFirestoreException>(relaxed = true))
        assertNull(errored.await())
    }

    @Test
    fun observeInvitations_emitsNewestFirst() = runTest {
        val invitationsCol = mockk<CollectionReference>()
        every { firestore.collection("groupInvitations") } returns invitationsCol
        val query = mockk<Query>()
        every { invitationsCol.whereEqualTo("inviteeUid", "me") } returns query
        val listener = slot<EventListener<QuerySnapshot>>()
        every { query.addSnapshotListener(capture(listener)) } returns mockk<ListenerRegistration>(relaxed = true)

        fun invDoc(id: String, createdAt: Long): DocumentSnapshot {
            val d = mockk<DocumentSnapshot>()
            every { d.id } returns id
            every { d.toObject(GroupInvitation::class.java) } returns GroupInvitation(groupId = "g", createdAt = createdAt)
            return d
        }

        val deferred = async { repo.observeInvitations("me").first() }
        advanceUntilIdle()
        listener.captured.onEvent(querySnap(listOf(invDoc("i1", 10L), invDoc("i2", 30L), invDoc("i3", 20L))), null)
        val list = deferred.await()

        assertEquals(listOf("i2", "i3", "i1"), list.map { it.id })
    }
}
