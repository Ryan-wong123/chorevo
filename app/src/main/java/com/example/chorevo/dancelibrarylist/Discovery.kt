package com.example.chorevo.dancelibrarylist

import kotlin.math.pow

/** Feed tabs in the discovery bar. */
enum class FeedTab(val label: String) {
    FOR_YOU("For You"),
    TRENDING("Trending"),
    NEW("New")
}

/** A dance-type chip: [key] groups spellings like "Hip Hop" / "hip-hop"; [label] is shown. */
data class DanceCategory(val key: String, val label: String, val count: Int)

/** What the discovery bar is currently filtering on. */
data class DiscoveryFilter(
    val tab: FeedTab = FeedTab.FOR_YOU,
    /** [DanceCategory.key], or null for all. */
    val categoryKey: String? = null,
    val query: String = ""
) {
    val isFiltered: Boolean
        get() = categoryKey != null || query.isNotBlank()
}

/**
 * Groups free-text dance types: lower-case, letters and digits only, so
 * "Hip Hop", "hip-hop" and "HIPHOP" all become "hiphop". Blank → null.
 */
fun danceTypeKey(raw: String): String? =
    raw.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { null }

/** Title-cases a dance type for display ("hip hop" → "Hip Hop", "k-pop" → "K-Pop"). */
private fun displayLabel(raw: String): String =
    raw.trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                part.lowercase().replaceFirstChar { it.titlecase() }
            }
        }

/**
 * Category chips from the feed's dance types, most common first. Each chip
 * uses the most common spelling of its group as its label.
 */
fun categoriesOf(items: List<DanceLibraryModel>): List<DanceCategory> =
    items
        .mapNotNull { item -> danceTypeKey(item.danceType)?.let { it to item.danceType.trim() } }
        .groupBy({ it.first }, { it.second })
        .map { (key, spellings) ->
            val label = spellings.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            DanceCategory(key = key, label = displayLabel(label), count = spellings.size)
        }
        .sortedWith(compareByDescending<DanceCategory> { it.count }.thenBy { it.label })

/**
 * True when every word of [query] appears in the title, dance type, creator
 * or description (case-insensitive). A blank query matches everything.
 */
fun matchesSearch(item: DanceLibraryModel, query: String): Boolean {
    val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val haystack = listOf(item.title, item.danceType, item.educatorName, item.description)
        .joinToString(" ")
        .lowercase()
    return words.all { it in haystack }
}

/**
 * Likes, decayed by age so a fresh dance with a few likes can beat an old one
 * with more: (likes + 1) / (ageDays + 2)^1.5.
 */
fun trendingScore(likes: Int, createdAtMs: Long, nowMs: Long): Double {
    val ageDays = ((nowMs - createdAtMs).coerceAtLeast(0L)) / 86_400_000.0
    return (likes + 1) / (ageDays + 2).pow(1.5)
}

/**
 * Applies the discovery filter to the full feed:
 * - For You keeps the feed's own order.
 * - Trending sorts by [trendingScore] (newest first on ties).
 * - New sorts by upload time, newest first.
 * The category chip and search box narrow any tab.
 */
fun applyDiscovery(
    items: List<DanceLibraryModel>,
    filter: DiscoveryFilter,
    likeCounts: Map<String, Int>,
    nowMs: Long
): List<DanceLibraryModel> {
    val filtered = items.filter { item ->
        (filter.categoryKey == null || danceTypeKey(item.danceType) == filter.categoryKey) &&
            matchesSearch(item, filter.query)
    }
    return when (filter.tab) {
        FeedTab.FOR_YOU -> filtered
        FeedTab.NEW -> filtered.sortedByDescending { it.createdAt }
        FeedTab.TRENDING -> filtered.sortedWith(
            compareByDescending<DanceLibraryModel> {
                trendingScore(likeCounts[it.danceId] ?: 0, it.createdAt, nowMs)
            }.thenByDescending { it.createdAt }
        )
    }
}
