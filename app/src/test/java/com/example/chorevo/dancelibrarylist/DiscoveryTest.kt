package com.example.chorevo.dancelibrarylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    @Test
    fun danceTypeKey_groupsSpellings() {
        assertEquals("hiphop", danceTypeKey("Hip Hop"))
        assertEquals("hiphop", danceTypeKey("hip-hop"))
        assertEquals("hiphop", danceTypeKey(" HIPHOP "))
        assertEquals("kpop", danceTypeKey("K-Pop"))
        assertNull(danceTypeKey("  "))
    }

    @Test
    fun searchToken_isFirstWord_lowercased_andCapped() {
        assertEquals("salsa", searchToken("  Salsa basics"))
        assertEquals("hip", searchToken("hip-hop"))
        assertEquals("supercalifragil", searchToken("Supercalifragilisticexpialidocious"))
        assertNull(searchToken("   "))
        assertNull(searchToken("!!"))
    }

    @Test
    fun search_matchesAllWordsAcrossFields_caseInsensitive() {
        val d = DanceLibraryModel(danceId = "1", title = "Salsa Basics", danceType = "Latin", educatorName = "Coach Maria")
        assertTrue(matchesSearch(d, ""))
        assertTrue(matchesSearch(d, "salsa"))
        assertTrue(matchesSearch(d, "BASICS maria"))
        assertTrue(matchesSearch(d, "latin"))
        assertFalse(matchesSearch(d, "salsa ballet"))
    }

    @Test
    fun categoriesFromCounts_sortsByCount_titleCasesLabels_andDropsEmpty() {
        val cats = categoriesFromCounts(
            counts = mapOf("hiphop" to 3L, "kpop" to 5L, "ballet" to 0L),
            labels = mapOf("hiphop" to "hip hop", "kpop" to "k-pop")
        )
        assertEquals(listOf("kpop", "hiphop"), cats.map { it.key })
        assertEquals("K-Pop", cats[0].label)
        assertEquals("Hip Hop", cats[1].label)
        assertEquals(3, cats[1].count)
    }

    @Test
    fun filter_isFilteredOnlyForChipOrSearch() {
        assertTrue(DiscoveryFilter(query = "x").isFiltered)
        assertTrue(DiscoveryFilter(categoryKey = "salsa").isFiltered)
        assertFalse(DiscoveryFilter(tab = FeedTab.NEW).isFiltered)
    }

    @Test
    fun editDistance_countsEditsAndSwaps() {
        assertEquals(0, editDistance("salsa", "salsa"))
        assertEquals(1, editDistance("hiphp", "hiphop"))
        assertEquals(1, editDistance("slasa", "salsa")) // adjacent swap
        assertEquals(3, editDistance("", "abc"))
    }

    @Test
    fun closestStandardStyle_fixesSpellingAndTypos() {
        assertEquals("Hip Hop", closestStandardStyle("hip-hop"))
        assertEquals("Hip Hop", closestStandardStyle("Hiphp"))
        assertEquals("K-Pop", closestStandardStyle("kpop"))
        assertEquals("Ballet", closestStandardStyle("balet"))
        assertNull(closestStandardStyle("Voguing"))
        assertNull(closestStandardStyle(""))
    }

    @Test
    fun searchScore_prefersExact_toleratesTypos_requiresEveryWord() {
        val d = DanceLibraryModel(danceId = "1", title = "Salsa Basics", danceType = "Latin", educatorName = "Coach Maria")
        assertEquals(0, searchScore(d, "sal mar"))
        assertEquals(2, searchScore(d, "slasa"))       // one typo: cost 1 + 1
        assertNull(searchScore(d, "salsa ballet"))     // "ballet" matches nothing
        assertNull(searchScore(d, "xyz"))
        assertEquals(0, searchScore(d, ""))
    }

    @Test
    fun searchTrigramsOf_matchesServerPieces() {
        assertEquals(listOf("hip", "iph", "php"), searchTrigramsOf("hiphp"))
        assertEquals(listOf("go"), searchTrigramsOf("go"))
    }
}
