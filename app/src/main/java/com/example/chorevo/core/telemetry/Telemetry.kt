package com.example.chorevo.core.telemetry

/**
 * Product analytics (Firebase Analytics) and crash reporting (Crashlytics)
 * behind one interface, so ViewModels can be tested with [NoOpTelemetry].
 *
 * Never put personal data (names, emails, free text) into events or keys:
 * ids, counts, scores and enum-like strings only.
 */
interface Telemetry {
    /** Ties events and crash reports to the signed-in user's id (null on sign-out). */
    fun setUserId(uid: String?)

    /** Logs an analytics event; see [AnalyticsEvents] for the names. */
    fun logEvent(name: String, params: Map<String, Any?> = emptyMap())

    /** Reports a handled error to Crashlytics as a non-fatal, with where it happened. */
    fun recordError(error: Throwable, where: String)
}

object NoOpTelemetry : Telemetry {
    override fun setUserId(uid: String?) = Unit
    override fun logEvent(name: String, params: Map<String, Any?>) = Unit
    override fun recordError(error: Throwable, where: String) = Unit
}

/** Event and parameter names. Firebase's recommended names where one exists. */
object AnalyticsEvents {
    // Accounts
    const val SIGN_UP = "sign_up"                       // method
    const val LOGIN = "login"                           // method

    // Uploading a dance
    const val DANCE_ANALYSIS_COMPLETED = "dance_analysis_completed" // dancer_count, duration_s
    const val DANCE_ANALYSIS_FAILED = "dance_analysis_failed"
    const val DANCE_UPLOAD_STARTED = "dance_upload_started"     // analysis (confirmed/background), visibility
    const val DANCE_UPLOAD_COMPLETED = "dance_upload_completed" // analysis, visibility
    const val DANCE_UPLOAD_FAILED = "dance_upload_failed"

    // Practising
    const val PRACTICE_STARTED = "practice_started"     // dance_id, mode
    const val PRACTICE_COMPLETED = "practice_completed" // dance_id, score, duration_s, personal_best

    // Groups
    const val GROUP_CREATED = "group_created"           // visibility
    const val GROUP_JOINED = "group_joined"             // method (code/invitation/public)

    // Parameters
    const val PARAM_METHOD = "method"
    const val PARAM_DANCE_ID = "dance_id"
    const val PARAM_MODE = "mode"
    const val PARAM_SCORE = "score"
    const val PARAM_DURATION_S = "duration_s"
    const val PARAM_PERSONAL_BEST = "personal_best"
    const val PARAM_DANCER_COUNT = "dancer_count"
    const val PARAM_ANALYSIS = "analysis"
    const val PARAM_VISIBILITY = "visibility"
}
