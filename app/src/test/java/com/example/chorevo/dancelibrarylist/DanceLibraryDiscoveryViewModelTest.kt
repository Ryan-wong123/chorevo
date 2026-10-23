package com.example.chorevo.dancelibrarylist

import com.example.chorevo.danceactivity.repository.FirebaseStreakRepository
import com.example.chorevo.dancelibrarylist.repository.DanceLibraryRepository
import com.example.chorevo.dancelibrarylist.viewmodel.DanceLibraryListViewModel
import com.example.chorevo.leaderboard.repository.LeaderboardRepository
import com.example.chorevo.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DanceLibraryDiscoveryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())

    private lateinit var repo: DanceLibraryRepository
    private lateinit var vm: DanceLibraryListViewModel

    private fun dance(id: String) = DanceLibraryModel(danceId = id, title = "Dance $id")

    @Before
    fun setUp() {
        repo = mockk()
        coEvery { repo.loadCategories() } returns Result.success(listOf(DanceCategory("hiphop", "Hip Hop", 2)))
        vm = DanceLibraryListViewModel(repo, mockk<FirebaseStreakRepository>(relaxed = true), mockk<LeaderboardRepository>(relaxed = true))
        vm.forYouSeed = 0.25
    }

    private fun ids() = vm.uiState.value.visibleItems.map { it.danceId }

    @Test
    fun refresh_loadsChipsAndFirstPage() = runTest {
        coEvery { repo.loadFeedPage(DiscoveryFilter(), 0.25, null, any()) } returns
            Result.success(FeedPage(listOf(dance("a"), dance("b")), next = null))

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), ids())
        assertEquals(listOf("hiphop"), vm.uiState.value.categories.map { it.key })
        assertTrue(vm.uiState.value.endReached)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun loadMore_appendsNextPage_withoutDuplicates_andStopsAtEnd() = runTest {
        val cursor = FeedCursor(wrapped = true)
        coEvery { repo.loadFeedPage(any(), any(), null, any()) } returns
            Result.success(FeedPage(listOf(dance("a"), dance("b")), next = cursor))
        coEvery { repo.loadFeedPage(any(), any(), cursor, any()) } returns
            Result.success(FeedPage(listOf(dance("b"), dance("c")), next = null))

        vm.refresh()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.endReached)

        vm.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), ids())
        assertTrue(vm.uiState.value.endReached)

        vm.loadMore() // at the end: no further request
        advanceUntilIdle()
        coVerify(exactly = 2) { repo.loadFeedPage(any(), any(), any(), any()) }
    }

    @Test
    fun emptyPagesAfterSearchFiltering_keepFetchingUntilResults() = runTest {
        val c1 = FeedCursor(wrapped = false)
        coEvery { repo.loadFeedPage(any(), any(), null, any()) } returns
            Result.success(FeedPage(emptyList(), next = c1))
        coEvery { repo.loadFeedPage(any(), any(), c1, any()) } returns
            Result.success(FeedPage(listOf(dance("z")), next = null))

        vm.refresh()
        advanceUntilIdle()

        assertEquals(listOf("z"), ids())
    }

    @Test
    fun selectingTabOrChip_reloadsWithThatFilter() = runTest {
        coEvery { repo.loadFeedPage(any(), any(), any(), any()) } returns
            Result.success(FeedPage(listOf(dance("a")), next = null))
        vm.refresh()
        advanceUntilIdle()

        vm.selectTab(FeedTab.TRENDING)
        advanceUntilIdle()
        coVerify { repo.loadFeedPage(DiscoveryFilter(tab = FeedTab.TRENDING), any(), null, any()) }

        vm.selectCategory("hiphop")
        advanceUntilIdle()
        coVerify {
            repo.loadFeedPage(DiscoveryFilter(tab = FeedTab.TRENDING, categoryKey = "hiphop"), any(), null, any())
        }

        vm.selectCategory("hiphop") // tapping the active chip clears it
        assertNull(vm.uiState.value.filter.categoryKey)
    }

    @Test
    fun search_isDebounced_soOnlyTheFinalTextIsQueried() = runTest {
        coEvery { repo.loadFeedPage(any(), any(), any(), any()) } returns
            Result.success(FeedPage(emptyList(), next = null))

        vm.setSearchQuery("s")
        vm.setSearchQuery("sa")
        vm.setSearchQuery("salsa")
        advanceTimeBy(100)
        coVerify(exactly = 0) { repo.loadFeedPage(any(), any(), any(), any()) }

        advanceUntilIdle()
        coVerify(exactly = 1) { repo.loadFeedPage(any(), any(), any(), any()) }
        coVerify { repo.loadFeedPage(DiscoveryFilter(query = "salsa"), any(), null, any()) }
    }
}
