package com.example.chorevo.videouploads

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import com.example.chorevo.dancelibrarylist.DanceLibraryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Logic-only tests for [VideoVisibilitySelector]; the composable itself needs a UI test. */
class VideoVisibilitySelectorTest {

    @Test
    fun visibilityIcon_private_isLock() {
        assertSame(
            Icons.Outlined.Lock,
            visibilityIcon(DanceLibraryModel.VISIBILITY_PRIVATE)
        )
    }

    @Test
    fun visibilityIcon_public_isGlobe() {
        assertSame(
            Icons.Outlined.Public,
            visibilityIcon(DanceLibraryModel.VISIBILITY_PUBLIC)
        )
    }

    @Test
    fun visibilityIcon_unknownOrLegacyValue_fallsBackToPublicGlobe() {
        assertSame(Icons.Outlined.Public, visibilityIcon(""))
        assertSame(Icons.Outlined.Public, visibilityIcon("friends"))
        // The selector compares exactly (case-sensitive), unlike DanceLibraryModel.isPrivate.
        assertSame(Icons.Outlined.Public, visibilityIcon("PRIVATE"))
    }

    @Test
    fun visibilityIcon_publicAndPrivate_areDistinct() {
        assertNotEquals(
            visibilityIcon(DanceLibraryModel.VISIBILITY_PUBLIC),
            visibilityIcon(DanceLibraryModel.VISIBILITY_PRIVATE)
        )
    }

    @Test
    fun visibilityConstants_matchTheValuesStoredInFirestoreAndUsedAsTestTags() {
        // The selector tags its chips "visibility_<value>"; UI tests and Firestore rely on these strings.
        assertEquals("public", DanceLibraryModel.VISIBILITY_PUBLIC)
        assertEquals("private", DanceLibraryModel.VISIBILITY_PRIVATE)
    }

    @Test
    fun selectorPrivacyRule_matchesModelBehaviour() {
        val privateDance = DanceLibraryModel(
            danceId = "d",
            educatorUid = "owner",
            visibility = DanceLibraryModel.VISIBILITY_PRIVATE
        )
        val publicDance = privateDance.copy(visibility = DanceLibraryModel.VISIBILITY_PUBLIC)

        assertEquals(true, privateDance.isPrivate)
        assertEquals(false, privateDance.isVisibleTo("stranger"))
        assertEquals(true, privateDance.isVisibleTo("owner"))
        assertEquals(true, publicDance.isVisibleTo(null))
    }
}
