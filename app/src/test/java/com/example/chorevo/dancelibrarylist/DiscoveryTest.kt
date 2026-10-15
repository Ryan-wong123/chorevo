package com.example.chorevo.dancelibrarylist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {

    private val day = 86_400_000L
    private val now = 100 * day

    private fun dance(
        id: String,
        title: String = "Dance $id",
        type: String = "Hip Hop",
        creator: String = "Coach",
        createdAt: Long = now
    ) = DanceLibraryModel(
        danceId = id,
        title = title,
        danceType = type,
        educatorName = creator,
        createdAt = createdAt
    )

    @Test
    fun danceTypeKey_groupsSpellings() {
        assertEquals("hiphop", danceTypeKey("Hip Hop"))
        assertEquals("hiphop", danceTypeKey("hip-hop"))
        assertEquals("hiphop", danceTypeKey(" HIPHOP "))
        assertEquals("kpop", danceTypeKey("K-Pop"))
        assertNull(danceTypeKey("  "))
    }

    @Test
    fun categories_areGroupedCountedAndUseMostCommonSpelling() {
        val cats = categoriesOf(
            listOf(
                dance("1", type = "hip hop"),
                dance("2", type = "hip hop"),
                dance("3", type = "Hip-Hop"),
                dance("4", type = "k-pop"),
                dance("5", type = "")
            )
        )
        assertEquals(listOf("hiphop", "kpop"), cats.map { it.key })
        assertEquals("Hip Hop", cats[0].label)
        assertEquals(3, cats[0].count)
        assertEquals("K-Pop", cats[1].label)
    }

    @Test
    fun search_matchesAllWordsAcrossFields_caseInsensitive() {
        val d = dance("1", title = "Salsa Basics", type = "Latin", creator = "Coach Maria")
        assertTrue(matchesSearch(d, ""))
        assertTrue(matchesSearch(d, "salsa"))
        assertTrue(matchesSearch(d, "BASICS maria"))
        assertTrue(matchesSearch(d, "latin"))
        assertFalse(matchesSearch(d, "salsa ballet"))
    }

    @Test
    fun trendingScore_favoursLikes_andDecaysWithAge() {
        assertTrue(trendingScore(10, now, now) > trendingScore(2, now, now))
        assertTrue(trendingScore(5, now, now) > trendingScore(5, now - 30 * day, now))
        // A fresh dance with a few likes beats an old one with a few more.
        assertTrue(trendingScore(3, now - day, now) > trendingScore(6, now - 60 * day, now))
    }

    @Test
    fun applyDiscovery_forYouKeepsOrder_newSortsByDate() {
        val items = listOf(
            dance("a", createdAt = now - 5 * day),
            dance("b", createdAt = now - 1 * day),
            dance("c", createdAt = now - 9 * day)
        )
        assertEquals(
            listOf("a", "b", "c"),
            applyDiscovery(items, DiscoveryFilter(FeedTab.FOR_YOU), emptyMap(), now).map { it.danceId }
        )
        assertEquals(
            listOf("b", "a", "c"),
            applyDiscovery(items, DiscoveryFilter(FeedTab.NEW), emptyMap(), now).map { it.danceId }
        )
    }

    @Test
    fun applyDiscovery_trendingRanksByLikes() {
        val items = listOf(dance("a"), dance("b"), dance("c"))
        val ranked = applyDiscovery(
            items,
            DiscoveryFilter(FeedTab.TRENDING),
            mapOf("a" to 1, "b" to 20, "c" to 5),
            now
        )
        assertEquals(listOf("b", "c", "a"), ranked.map { it.danceId })
    }

    @Test
    fun applyDiscovery_categoryAndSearchNarrowAnyTab() {
        val items = listOf(
            dance("a", title = "Groove One", type = "Hip Hop"),
            dance("b", title = "Groove Two", type = "hip-hop"),
            dance("c", title = "Swan Steps", type = "Ballet")
        )
        val byCategory = applyDiscovery(items, DiscoveryFilter(categoryKey = "hiphop"), emptyMap(), now)
        assertEquals(listOf("a", "b"), byCategory.map { it.danceId })

        val bySearch = applyDiscovery(items, DiscoveryFilter(categoryKey = "hiphop", query = "two"), emptyMap(), now)
        assertEquals(listOf("b"), bySearch.map { it.danceId })

        assertTrue(DiscoveryFilter(query = "x").isFiltered)
        assertFalse(DiscoveryFilter(tab = FeedTab.NEW).isFiltered)
    }
}
