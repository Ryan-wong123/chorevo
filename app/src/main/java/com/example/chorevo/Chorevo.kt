package com.example.chorevo

import android.app.Application
import androidx.work.Configuration
import com.example.chorevo.notification.NotificationChannels
import com.google.firebase.FirebaseApp
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
        // Push notifications arrive on these channels even before any reminder is scheduled.
        NotificationChannels.create(this)

        // Play Integrity in release, the debug provider in debug builds.
        AppCheckInstaller.install()
    }

    override fun onTerminate() {
        super.onTerminate()
        workManagerExecutor.shutdown()
    }
}
