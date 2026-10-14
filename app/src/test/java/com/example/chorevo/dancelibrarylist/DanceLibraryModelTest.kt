package com.example.chorevo.dancelibrarylist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanceLibraryModelTest {

    @Test
    fun defaultsToPublic_soOlderUploadsStayVisible() {
        val dance = DanceLibraryModel(educatorUid = "owner")

        assertFalse(dance.isPrivate)
        assertTrue(dance.isVisibleTo("anyone"))
        assertTrue(dance.isVisibleTo(null))
    }

    @Test
    fun privateVideo_isOnlyVisibleToItsUploader() {
        val dance = DanceLibraryModel(
            educatorUid = "owner",
            visibility = DanceLibraryModel.VISIBILITY_PRIVATE
        )

        assertTrue(dance.isPrivate)
        assertTrue(dance.isVisibleTo("owner"))
        assertFalse(dance.isVisibleTo("someoneElse"))
        assertFalse(dance.isVisibleTo(null))
    }
}
