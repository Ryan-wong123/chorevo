package com.example.chorevo.firebase

/** Username rules, shared with firestore.rules (`/usernames/{handle}`). */
object Usernames {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20
    private val VALID = Regex("^[a-z0-9_]{$MIN_LENGTH,$MAX_LENGTH}$")

    /** Lower case, a leading "@" dropped, spaces and dashes turned into "_", other characters removed. */
    fun normalize(raw: String): String =
        raw.trim()
            .removePrefix("@")
            .lowercase()
            .map { c -> if (c == ' ' || c == '-' || c == '.') '_' else c }
            .filter { it in 'a'..'z' || it in '0'..'9' || it == '_' }
            .joinToString("")
            .take(MAX_LENGTH)

    fun isValid(handle: String): Boolean = VALID.matches(handle)

    /** Why [handle] can't be used, or null when it is well-formed. */
    fun problem(handle: String): String? = when {
        handle.length < MIN_LENGTH -> "At least $MIN_LENGTH characters"
        handle.length > MAX_LENGTH -> "At most $MAX_LENGTH characters"
        !isValid(handle) -> "Use a–z, 0–9 and _ only"
        else -> null
    }

    /** A starting suggestion from a display name, e.g. "Ryan Wong" -> "ryan_wong". */
    fun suggestionFor(displayName: String?): String {
        val base = normalize(displayName.orEmpty())
            .replace(Regex("_+"), "_")
            .trim('_')
            .take(MAX_LENGTH - 4)
        return if (base.length >= MIN_LENGTH) base else "dancer"
    }

    /** [base] with a number on the end, kept within [MAX_LENGTH], for when [base] is taken. */
    fun withNumber(base: String, number: Int): String {
        val suffix = number.toString()
        return base.take(MAX_LENGTH - suffix.length) + suffix
    }
}
