package com.example.chorevo.community

import android.graphics.Bitmap
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [InviteQr.bitmap] with ZXing running for real; the Android Bitmap factory is
 * mocked so the pixel array handed to it can be inspected. [InviteQr.scan] needs the
 * Google code scanner and is not covered.
 */
class InviteQrTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private val pixelsSlot = slot<IntArray>()
    private val widthSlot = slot<Int>()
    private val heightSlot = slot<Int>()

    @Before
    fun setup() {
        mockkStatic(Bitmap::class)
        every {
            Bitmap.createBitmap(capture(pixelsSlot), capture(widthSlot), capture(heightSlot), any<Bitmap.Config>())
        } returns mockk<Bitmap>(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun bitmap_returnsAnImage() {
        assertNotNull(InviteQr.bitmap("https://chorevo-dea05.web.app/join/ABCD2345", sizePx = 200))
    }

    @Test
    fun bitmap_isSquareAtRequestedSize() {
        InviteQr.bitmap("ABCD2345", sizePx = 200)

        assertEquals(200, widthSlot.captured)
        assertEquals(200, heightSlot.captured)
        assertEquals(200 * 200, pixelsSlot.captured.size)
    }

    @Test
    fun bitmap_defaultSizeIs720() {
        InviteQr.bitmap("ABCD2345")

        assertEquals(720, widthSlot.captured)
        assertEquals(720, heightSlot.captured)
        assertEquals(720 * 720, pixelsSlot.captured.size)
    }

    @Test
    fun bitmap_usesOnlyBlackAndWhitePixels() {
        InviteQr.bitmap("https://chorevo-dea05.web.app/join/ABCD2345", sizePx = 300)

        assertTrue(pixelsSlot.captured.all { it == black || it == white })
    }

    @Test
    fun bitmap_containsBothDarkModulesAndLightBackground() {
        InviteQr.bitmap("https://chorevo-dea05.web.app/join/ABCD2345", sizePx = 300)

        assertTrue(pixelsSlot.captured.any { it == black })
        assertTrue(pixelsSlot.captured.any { it == white })
    }

    @Test
    fun bitmap_hasWhiteQuietZoneAroundTheCode() {
        InviteQr.bitmap("ABCD2345", sizePx = 300)

        val pixels = pixelsSlot.captured
        val size = 300
        val borderIsWhite = (0 until size).all { i ->
            pixels[i] == white &&                       // top row
                pixels[(size - 1) * size + i] == white &&  // bottom row
                pixels[i * size] == white &&               // left column
                pixels[i * size + size - 1] == white       // right column
        }
        assertTrue(borderIsWhite)
    }

    @Test
    fun bitmap_sameTextProducesSamePixels() {
        InviteQr.bitmap("ABCD2345", sizePx = 200)
        val first = pixelsSlot.captured.copyOf()

        InviteQr.bitmap("ABCD2345", sizePx = 200)

        assertTrue(first.contentEquals(pixelsSlot.captured))
    }

    @Test
    fun bitmap_differentTextProducesDifferentPixels() {
        InviteQr.bitmap("ABCD2345", sizePx = 200)
        val first = pixelsSlot.captured.copyOf()

        InviteQr.bitmap("WXYZ6789", sizePx = 200)

        assertTrue(!first.contentEquals(pixelsSlot.captured))
    }
}
