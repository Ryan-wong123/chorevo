package com.example.chorevo.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedMediaUrlTest {

    @Test
    fun acceptsThisAppsStorageDownloadUrls() {
        assertTrue(
            isTrustedMediaUrl(
                "https://firebasestorage.googleapis.com/v0/b/chorevo-dea05.firebasestorage.app/o/groupVideos%2Fu1%2Fp1.mp4?alt=media&token=t"
            )
        )
    }

    @Test
    fun rejectsOtherHostsAndLocalSchemes() {
        listOf(
            null,
            "",
            "http://firebasestorage.googleapis.com/x",
            "https://evil.example/v.mp4",
            "https://firebasestorage.googleapis.com.evil.example/v.mp4",
            "file:///data/data/com.example.chorevo/shared_prefs/x.xml",
            "content://com.example.chorevo.fileprovider/captured_videos/a.mp4",
            "not a url"
        ).forEach { assertFalse(it.toString(), isTrustedMediaUrl(it)) }
    }
}
