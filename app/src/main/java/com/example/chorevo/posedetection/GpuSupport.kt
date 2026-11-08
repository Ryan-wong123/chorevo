package com.example.chorevo.posedetection

import android.os.Build

/**
 * Whether the ML GPU delegate should be attempted. Emulators expose a software or
 * partial GL stack where creating the delegate can crash natively (which a Kotlin
 * try/catch cannot recover from), so they always use the CPU.
 */
object GpuSupport {
    val isEmulator: Boolean by lazy {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator", ignoreCase = true) ||
            fingerprint.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true)
    }

    fun canUseGpu(requested: Boolean): Boolean = requested && !isEmulator
}
