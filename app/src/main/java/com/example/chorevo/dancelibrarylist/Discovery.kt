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

/**
 * Edit distance (insertions, deletions, substitutions, adjacent swaps)
 * between two strings. Used for typo-tolerant search and style suggestions.
 */
fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}

/** Typos allowed for a word of [length] letters: none up to 3, one up to 6, then two. */
fun allowedTypos(length: Int): Int = when {
    length <= 3 -> 0
    length <= 6 -> 1
    else -> 2
}

/** Dance styles offered when uploading; keeps category chips tidy. */
val STANDARD_DANCE_STYLES = listOf(
    "Hip Hop", "K-Pop", "Ballet", "Contemporary", "Jazz", "Street Jazz",
    "Salsa", "Bachata", "Latin", "Ballroom", "Breaking", "Popping",
    "Locking", "House", "Waacking", "Krump", "Dancehall", "Afrobeats",
    "Tap", "Bollywood", "Folk", "Line Dance", "Zumba", "Freestyle"
)

/**
 * The standard style [raw] most likely means: an exact match ignoring case
 * and punctuation ("hip-hop" → "Hip Hop"), or a close spelling ("Hiphp" →
 * "Hip Hop"). Null when nothing is close.
 */
fun closestStandardStyle(raw: String, styles: List<String> = STANDARD_DANCE_STYLES): String? {
    val key = danceTypeKey(raw) ?: return null
    styles.firstOrNull { danceTypeKey(it) == key }?.let { return it }
    return styles
        .map { it to editDistance(key, danceTypeKey(it)!!) }
        .filter { (style, distance) -> distance <= allowedTypos(danceTypeKey(style)!!.length) }
        .minByOrNull { it.second }
        ?.first
}

/** Lower-cased words (letters and digits) in [text]; mirrors words() in functions/discovery.js. */
fun searchWords(text: String): List<String> =
    text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

/**
 * Three-letter pieces of [word] (whole word when shorter), matching the
 * server's searchTrigrams; at most 30, Firestore's array-contains-any limit.
 */
fun searchTrigramsOf(word: String): List<String> =
    if (word.length < 3) listOf(word)
    else (0..word.length - 3).map { word.substring(it, it + 3) }.distinct().take(30)

/**
 * How well [item] matches a search, or null when it doesn't: every word of
 * [query] must start a word of the title, dance type, creator or description
 * (cost 0), appear inside one (cost 1), or be within a few typos of one
 * (cost = typos + 1, see [allowedTypos]). Lower is better.
 */
fun searchScore(item: DanceLibraryModel, query: String): Int? {
    val queryWords = searchWords(query)
    if (queryWords.isEmpty()) return 0
    val docWords = searchWords(listOf(item.title, item.danceType, item.educatorName, item.description).joinToString(" ")) +
        listOfNotNull(danceTypeKey(item.danceType))
    var total = 0
    for (q in queryWords) {
        val best = docWords.minOfOrNull { w ->
            when {
                w.startsWith(q) -> 0
                q in w -> 1
                else -> {
                    // Compare against the start of the word so "salsaa" ~ "salsa", "hiphp" ~ "hiphop".
                    val typos = minOf(editDistance(q, w), editDistance(q, w.take(q.length)))
                    if (typos <= allowedTypos(q.length)) typos + 1 else Int.MAX_VALUE
                }
            }
        } ?: Int.MAX_VALUE
        if (best == Int.MAX_VALUE) return null
        total += best
    }
    return total
}
