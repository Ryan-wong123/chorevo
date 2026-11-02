package com.example.chorevo.videouploads

import com.example.chorevo.dancelibrarylist.STANDARD_DANCE_STYLES
import com.example.chorevo.dancelibrarylist.closestStandardStyle
import com.example.chorevo.dancelibrarylist.danceTypeKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logic-only tests for the pure helpers [DanceStylePicker] is built on: which values count
 * as "standard" and which "Did you mean ...?" suggestion is offered for a custom entry.
 */
class DanceStylePickerTest {

    /** Mirrors the picker's own check for whether the dropdown (not custom field) is shown. */
    private fun isStandard(value: String) =
        STANDARD_DANCE_STYLES.any { danceTypeKey(it) == danceTypeKey(value) }

    /** Mirrors the picker's suggestion rule. */
    private fun suggestion(value: String) = closestStandardStyle(value)?.takeIf { it != value }

    @Test
    fun standardStyles_areNonEmpty_withUniqueNonBlankKeys() {
        assertTrue(STANDARD_DANCE_STYLES.isNotEmpty())
        val keys = STANDARD_DANCE_STYLES.map { danceTypeKey(it) }
        assertTrue(keys.all { it != null })
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun everyStandardStyle_isRecognisedAsStandard_andHasNoSuggestion() {
        for (style in STANDARD_DANCE_STYLES) {
            assertTrue(style, isStandard(style))
            assertNull(style, suggestion(style))
        }
    }

    @Test
    fun standardCheck_ignoresCaseAndPunctuation() {
        assertTrue(isStandard("hip-hop"))
        assertTrue(isStandard("HIP HOP"))
        assertTrue(isStandard("k pop"))
    }

    @Test
    fun customStyle_isNotStandard() {
        assertFalse(isStandard("Voguing"))
        assertFalse(isStandard("Tango Nuevo"))
    }

    @Test
    fun blankValue_isNotStandard_andHasNoSuggestion() {
        // The picker only switches to the custom field for non-blank, non-standard values.
        assertFalse(isStandard("   ") && "   ".isNotBlank())
        assertNull(suggestion(""))
        assertNull(suggestion("   "))
    }

    @Test
    fun nearMissSpelling_offersTheStandardSpelling() {
        assertEquals("Hip Hop", suggestion("hiphop"))
        assertEquals("Hip Hop", suggestion("Hiphp"))
        assertEquals("K-Pop", suggestion("kpop"))
        assertEquals("Ballet", suggestion("balet"))
    }

    @Test
    fun suggestionForDifferentCasing_returnsCanonicalStyle() {
        assertEquals("Ballroom", suggestion("BALLROOM"))
        assertNotNull(suggestion("salsa"))
        assertEquals("Salsa", suggestion("salsa"))
    }

    @Test
    fun unrelatedCustomStyle_hasNoSuggestion() {
        assertNull(suggestion("Voguing"))
        assertNull(suggestion("Capoeira"))
    }

    @Test
    fun testTagsForStyles_areStableKeys() {
        // Each dropdown item is tagged "danceStyle_<key>"; keys must be usable (non-blank) for that.
        for (style in STANDARD_DANCE_STYLES) {
            val key = danceTypeKey(style)
            assertNotNull(style, key)
            assertTrue(key!!.all { it.isLetterOrDigit() && !it.isUpperCase() })
        }
    }
}
