package com.example.chorevo.dancelibrarylist

import com.example.chorevo.danceactivity.Repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.Repository.DanceLibraryRepository
import com.example.chorevo.dancelibrarylist.ViewModel.DanceLibraryListViewModel
import com.example.chorevo.leaderboard.Repository.LeaderboardRepository
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DanceLibraryDiscoveryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: DanceLibraryRepository
    private lateinit var vm: DanceLibraryListViewModel

    private val day = 86_400_000L
    private val now = 100 * day

    private val dances = listOf(
        DanceLibraryModel(danceId = "a", title = "Salsa Basics", danceType = "Salsa", createdAt = now - 3 * day),
        DanceLibraryModel(danceId = "b", title = "Hip Hop Groove", danceType = "Hip Hop", createdAt = now - 1 * day),
        DanceLibraryModel(danceId = "c", title = "Street Bounce", danceType = "hip-hop", createdAt = now - 7 * day)
    )

    @Before
    fun setUp() {
        repo = mockk()
        coEvery { repo.listEnabled() } returns Result.success(dances)
        coEvery { repo.likeCounts(any()) } returns mapOf("a" to 1, "b" to 0, "c" to 40)
        vm = DanceLibraryListViewModel(repo, mockk<FirebaseStreakRepository>(relaxed = true), mockk<LeaderboardRepository>(relaxed = true))
        vm.clock = { now }
    }

    private fun visibleIds() = vm.uiState.value.visibleItems.map { it.danceId }

    @Test
    fun refresh_buildsCategories_andShowsAllInFeedOrder() = runTest {
        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), visibleIds())
        assertEquals(listOf("hiphop", "salsa"), vm.uiState.value.categories.map { it.key })
    }

    @Test
    fun newTab_sortsNewestFirst() = runTest {
        vm.refresh()
        advanceUntilIdle()

        vm.selectTab(FeedTab.NEW)

        assertEquals(listOf("b", "a", "c"), visibleIds())
    }

    @Test
    fun trendingTab_loadsLikeCountsOnce_andRanksByThem() = runTest {
        vm.refresh()
        advanceUntilIdle()

        vm.selectTab(FeedTab.TRENDING)
        advanceUntilIdle()
        assertEquals("c", visibleIds().first())
        assertFalse(vm.uiState.value.isLoadingTrending)

        vm.selectTab(FeedTab.FOR_YOU)
        vm.selectTab(FeedTab.TRENDING)
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.likeCounts(any()) }
    }

    @Test
    fun categoryChip_filters_andTogglesOff() = runTest {
        vm.refresh()
        advanceUntilIdle()

        vm.selectCategory("hiphop")
        assertEquals(listOf("b", "c"), visibleIds())

        vm.selectCategory("hiphop")
        assertNull(vm.uiState.value.filter.categoryKey)
        assertEquals(3, visibleIds().size)
    }

    @Test
    fun search_filters_andClearRestoresFeed() = runTest {
        vm.refresh()
        advanceUntilIdle()

        vm.setSearchQuery("groove")
        assertEquals(listOf("b"), visibleIds())

        vm.setSearchQuery("nothing like this")
        assertEquals(emptyList<String>(), visibleIds())

        vm.clearDiscoveryFilters()
        assertEquals(3, visibleIds().size)
    }
}
