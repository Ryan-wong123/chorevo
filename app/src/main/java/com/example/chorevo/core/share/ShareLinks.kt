package com.example.chorevo.core.share

import android.net.Uri

/**
 * Single source of truth for Chorevo share links.
 *
 * Links are served by the project's Firebase Hosting site, which rewrites
 * /dance/<id> to the danceShare Cloud Function (functions/index.js). It puts
 * the dance's title into the link preview, then the page opens the app if it
 * is installed (via the chorevo:// scheme) or shows a landing page.
 */
object ShareLinks {

    /** Firebase Hosting domain for the chorevo-dea05 project. */
    const val WEB_HOST = "chorevo-dea05.web.app"

    /** Custom scheme used by the landing page to hand off into the app. */
    const val APP_SCHEME = "chorevo"

    private val HTTPS_HOSTS = setOf(
        WEB_HOST,
        "chorevo-dea05.firebaseapp.com",
        "chorevo.app" // legacy links shared before the hosting move
    )

    /** Public https link to a dance, safe to paste anywhere. */
    fun danceLink(danceId: String): String =
        "https://$WEB_HOST/dance/${Uri.encode(danceId)}"

    /**
     * Extracts the dance ID from any supported link:
     *  - https://chorevo-dea05.web.app/dance/<id>
     *  - https://chorevo-dea05.firebaseapp.com/dance/<id>
     *  - https://chorevo.app/dance/<id>
     *  - chorevo://dance/<id>
     */
    fun parseDanceId(uri: Uri): String? {
        val segments = when {
            uri.scheme == APP_SCHEME && uri.host == "dance" -> listOf("dance") + uri.pathSegments
            (uri.scheme == "https" || uri.scheme == "http") && uri.host in HTTPS_HOSTS -> uri.pathSegments
            else -> return null
        }
        if (segments.size != 2 || segments[0] != "dance") return null
        return segments[1].takeIf { it.isNotBlank() }
    }
}
