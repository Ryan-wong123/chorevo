package com.example.chorevo

import android.app.Application
import androidx.work.Configuration
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.ktx.appCheck
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@HiltAndroidApp
class Chorevo : Application(), Configuration.Provider {

    private val workManagerExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(workManagerExecutor)
            .setMaxSchedulerLimit(20)
            .build()

    /** Initializes app-level services when the application starts. */
    override fun onCreate() {
        super.onCreate()

        FirebaseApp.initializeApp(this)

        if (BuildConfig.DEBUG) {
            Firebase.appCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        workManagerExecutor.shutdown()
    }
}
