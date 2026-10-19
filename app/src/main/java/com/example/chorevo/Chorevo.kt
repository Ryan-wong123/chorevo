package com.example.chorevo

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.chorevo.notification.NotificationChannels
import com.google.firebase.FirebaseApp
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class Chorevo : Application(), Configuration.Provider {

    // Lets WorkManager create @HiltWorker workers with their injected dependencies.
    @Inject lateinit var workerFactory: HiltWorkerFactory

    private val workManagerExecutor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setExecutor(workManagerExecutor)
            .setWorkerFactory(workerFactory)
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
