package com.example.chorevo.core.telemetry

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTelemetry @Inject constructor(
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics
) : Telemetry {

    override fun setUserId(uid: String?) {
        runCatching {
            analytics.setUserId(uid)
            crashlytics.setUserId(uid.orEmpty())
        }
    }

    override fun logEvent(name: String, params: Map<String, Any?>) {
        runCatching { analytics.logEvent(name, params.toBundle()) }
    }

    override fun recordError(error: Throwable, where: String) {
        runCatching {
            crashlytics.log("Handled error in $where")
            crashlytics.setCustomKey("last_error_where", where)
            crashlytics.recordException(error)
        }
    }

    private fun Map<String, Any?>.toBundle(): Bundle = Bundle().apply {
        forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> putString(key, value.take(100)) // Analytics' limit for values
                is Int -> putLong(key, value.toLong())
                is Long -> putLong(key, value)
                is Float -> putDouble(key, value.toDouble())
                is Double -> putDouble(key, value)
                is Boolean -> putLong(key, if (value) 1L else 0L)
                else -> putString(key, value.toString().take(100))
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TelemetryModule {
    @Binds
    abstract fun bindTelemetry(impl: FirebaseTelemetry): Telemetry
}
