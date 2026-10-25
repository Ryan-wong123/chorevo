package com.example.chorevo.core.database.di


import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU-heavy work such as pose analysis (Dispatchers.Default). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher


/** The main-thread dispatcher (Dispatchers.Main.immediate); tests can replace it. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher
