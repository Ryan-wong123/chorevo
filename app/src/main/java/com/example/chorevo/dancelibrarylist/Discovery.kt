package com.example.chorevo.dancelibrarylist

import com.google.firebase.firestore.DocumentSnapshot

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
internal fun displayLabel(raw: String): String =
    raw.trim()
        .split(Regex("\\s+"))
        .joinToString(" ") { word ->
            word.split("-").joinToString("-") { part ->
                part.lowercase().replaceFirstChar { it.titlecase() }
            }
        }

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

/** Longest search prefix the server stores per word (functions/discovery.js). */
const val MAX_SEARCH_TOKEN_LENGTH = 15

/**
 * The token the feed query searches with: the first word of [query],
 * lower-cased, letters and digits only, cut to [MAX_SEARCH_TOKEN_LENGTH]
 * (the server stores every prefix up to that length). Null for a blank query.
 */
fun searchToken(query: String): String? =
    query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .firstOrNull { it.isNotEmpty() }
        ?.take(MAX_SEARCH_TOKEN_LENGTH)

/**
 * Chips from the meta/danceCategories document: counts per key and labels
 * (the most common spelling), most common first. Empty categories are dropped.
 */
fun categoriesFromCounts(counts: Map<String, Any?>, labels: Map<String, Any?>): List<DanceCategory> =
    counts
        .mapNotNull { (key, value) ->
            val count = (value as? Number)?.toInt() ?: 0
            if (count <= 0) null
            else DanceCategory(key = key, label = displayLabel((labels[key] as? String) ?: key), count = count)
        }
        .sortedWith(compareByDescending<DanceCategory> { it.count }.thenBy { it.label })

/** Where the next feed page starts. For You runs from its seed to the end, then wraps. */
data class FeedCursor(
    val lastDoc: DocumentSnapshot? = null,
    val wrapped: Boolean = false
)

/** One page of feed results; [next] is null when the feed has ended. */
data class FeedPage(
    val items: List<DanceLibraryModel>,
    val next: FeedCursor?
)
