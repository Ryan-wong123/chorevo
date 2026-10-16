package com.example.chorevo.dancelibrarylist

import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import io.mockk.coEvery

/**
 * Stubs the paged feed on a mocked repository with a fixed set of dances:
 * one page, filtered by the discovery filter's chip and search like the
 * server queries would, plus matching category chips.
 */
fun DanceLibraryRepository.stubFeed(items: List<DanceLibraryModel>) {
    val keys = items.mapNotNull { item -> danceTypeKey(item.danceType)?.let { it to item.danceType } }
    coEvery { loadCategories() } returns Result.success(
        categoriesFromCounts(
            counts = keys.groupingBy { it.first }.eachCount(),
            labels = keys.associate { it.first to it.second }
        )
    )
    coEvery { loadFeedPage(any(), any(), any(), any()) } answers {
        val filter = firstArg<DiscoveryFilter>()
        val page = items.filter { item ->
            (filter.categoryKey == null || danceTypeKey(item.danceType) == filter.categoryKey) &&
                matchesSearch(item, filter.query)
        }
        Result.success(FeedPage(page, next = null))
    }
}
