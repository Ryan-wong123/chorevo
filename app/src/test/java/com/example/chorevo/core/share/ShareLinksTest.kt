package com.example.chorevo.core.share

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.net.URLEncoder

class ShareLinksTest {

    @Before
    fun setup() {
        // android.net.Uri is a stub on the JVM; mimic Uri.encode (percent-encoding, spaces as %20).
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers {
            URLEncoder.encode(firstArg<String>(), "UTF-8").replace("+", "%20")
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun fakeUri(scheme: String?, host: String?, vararg segments: String, text: String = ""): Uri {
        val uri = mockk<Uri>()
        every { uri.scheme } returns scheme
        every { uri.host } returns host
        every { uri.pathSegments } returns segments.toList()
        every { uri.toString() } returns text
        return uri
    }

    // ── constants ─────────────────────────────────────────────────────────────

    @Test
    fun constants_matchHostingSiteAndScheme() {
        assertEquals("chorevo-dea05.web.app", ShareLinks.WEB_HOST)
        assertEquals("chorevo", ShareLinks.APP_SCHEME)
    }

    // ── building links ────────────────────────────────────────────────────────

    @Test
    fun danceLink_buildsHttpsLinkOnHostingDomain() {
        assertEquals("https://chorevo-dea05.web.app/dance/abc123", ShareLinks.danceLink("abc123"))
    }

    @Test
    fun danceLink_encodesReservedCharactersInId() {
        assertEquals("https://chorevo-dea05.web.app/dance/a%20b%2Fc%3Fd", ShareLinks.danceLink("a b/c?d"))
    }

    @Test
    fun danceLink_keepsSafeIdCharactersUnchanged() {
        assertEquals("https://chorevo-dea05.web.app/dance/Id-9_x", ShareLinks.danceLink("Id-9_x"))
    }

    @Test
    fun groupInviteLink_buildsJoinLinkWithCode() {
        assertEquals("https://chorevo-dea05.web.app/join/ABCD2345", ShareLinks.groupInviteLink("ABCD2345"))
    }

    // ── parseInviteCode ───────────────────────────────────────────────────────

    @Test
    fun parseInviteCode_httpsInviteLink_returnsNormalizedCode() {
        val uri = fakeUri("https", "chorevo-dea05.web.app", "join", "abcd2345",
            text = "https://chorevo-dea05.web.app/join/abcd2345")

        assertEquals("ABCD2345", ShareLinks.parseInviteCode(uri))
    }

    @Test
    fun parseInviteCode_customSchemeLink_returnsCode() {
        val uri = fakeUri("chorevo", "join", "abcd2345", text = "chorevo://join/abcd2345")

        assertEquals("ABCD2345", ShareLinks.parseInviteCode(uri))
    }

    @Test
    fun parseInviteCode_linkBuiltByGroupInviteLink_roundTrips() {
        val link = ShareLinks.groupInviteLink("ABCD2345")
        val uri = fakeUri("https", ShareLinks.WEB_HOST, "join", "ABCD2345", text = link)

        assertEquals("ABCD2345", ShareLinks.parseInviteCode(uri))
    }

    @Test
    fun parseInviteCode_danceLink_isNotAnInvite() {
        val uri = fakeUri("https", "chorevo-dea05.web.app", "dance", "abc",
            text = "https://chorevo-dea05.web.app/dance/abc")

        assertNull(ShareLinks.parseInviteCode(uri))
    }

    @Test
    fun parseInviteCode_wrongLengthCode_isRejected() {
        val uri = fakeUri("https", "chorevo-dea05.web.app", "join", "ABC",
            text = "https://chorevo-dea05.web.app/join/ABC")

        assertNull(ShareLinks.parseInviteCode(uri))
    }

    // ── parseDanceId ──────────────────────────────────────────────────────────

    @Test
    fun parseDanceId_hostingLink_returnsId() {
        assertEquals("abc123", ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_firebaseappDomain_returnsId() {
        assertEquals("abc123", ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.firebaseapp.com", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_legacyChorevoAppDomain_returnsId() {
        assertEquals("abc123", ShareLinks.parseDanceId(fakeUri("https", "chorevo.app", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_plainHttp_isAccepted() {
        assertEquals("abc123", ShareLinks.parseDanceId(fakeUri("http", "chorevo-dea05.web.app", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_customScheme_returnsId() {
        // chorevo://dance/<id>: "dance" is the host, the id is the only path segment.
        assertEquals("abc123", ShareLinks.parseDanceId(fakeUri("chorevo", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_customSchemeWithOtherHost_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("chorevo", "join", "abc123")))
    }

    @Test
    fun parseDanceId_unknownHost_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "evil.example.com", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_unsupportedScheme_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("ftp", "chorevo-dea05.web.app", "dance", "abc123")))
    }

    @Test
    fun parseDanceId_nullSchemeAndHost_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri(null, null, "dance", "abc123")))
    }

    @Test
    fun parseDanceId_wrongFirstSegment_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app", "join", "abc123")))
    }

    @Test
    fun parseDanceId_missingId_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app", "dance")))
    }

    @Test
    fun parseDanceId_extraPathSegments_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app", "dance", "abc123", "extra")))
    }

    @Test
    fun parseDanceId_blankId_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app", "dance", "   ")))
    }

    @Test
    fun parseDanceId_rootPath_isRejected() {
        assertNull(ShareLinks.parseDanceId(fakeUri("https", "chorevo-dea05.web.app")))
    }
}
