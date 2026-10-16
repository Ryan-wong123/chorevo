package com.example.chorevo.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupModelsTest {

    @Test
    fun normalizeInviteCode_uppercasesAndDropsLookalikesAndSeparators() {
        assertEquals("ABCD2345", normalizeInviteCode("abcd-2345"))
        assertEquals("ABCD2345", normalizeInviteCode(" ab cd 23 45 extra"))
        // 0, O, 1, I and L are not in the alphabet.
        assertEquals("ABC", normalizeInviteCode("A0B1CIOL"))
    }

    @Test
    fun inviteAlphabet_hasNoLookalikes() {
        listOf('0', 'O', '1', 'I', 'L').forEach { assertFalse(it in INVITE_CODE_ALPHABET) }
    }

    @Test
    fun isValidGroupName_matchesRulesLimits() {
        assertFalse(isValidGroupName("ab"))
        assertFalse(isValidGroupName("   ab   "))
        assertTrue(isValidGroupName("abc"))
        assertTrue(isValidGroupName("x".repeat(60)))
        assertFalse(isValidGroupName("x".repeat(61)))
    }

    @Test
    fun composer_canPostNeedsContentAndNotPosting() {
        assertFalse(PostComposer().canPost)
        assertFalse(PostComposer(text = "   ").canPost)
        assertTrue(PostComposer(text = "hi").canPost)
        assertFalse(PostComposer(text = "hi", isPosting = true).canPost)
    }

    @Test
    fun uiState_derivesMembershipAndOpenPost() {
        val state = GroupsUiState(
            membership = GroupMember(uid = "u", role = GroupMember.ROLE_OWNER),
            posts = listOf(GroupPost(id = "p1"), GroupPost(id = "p2")),
            openPostId = "p2"
        )
        assertTrue(state.isMember)
        assertTrue(state.isOwner)
        assertEquals("p2", state.openPost?.id)
        assertFalse(GroupsUiState().isMember)
    }

    @Test
    fun postKind_fromKey_defaultsToText() {
        assertEquals(GroupPostKind.VIDEO, GroupPostKind.fromKey("video"))
        assertEquals(GroupPostKind.TEXT, GroupPostKind.fromKey(null))
        assertEquals(GroupPostKind.TEXT, GroupPostKind.fromKey("weird"))
    }
}
