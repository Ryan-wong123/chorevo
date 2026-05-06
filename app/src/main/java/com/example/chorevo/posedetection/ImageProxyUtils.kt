package com.example.chorevo.posedetection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream



object ImageProxyUtils {

    /** Convert ImageProxy to Bitmap
     * Convert YUV_420_888 -> NV21 byte[] -> JPEG -> Bitmap
     * Current pipeline:
        Camera gives YUV_420_888 (3 planes)
        Convert to NV21 bytes
        Compress into JPEG
        Decode JPEG into Bitmap
     */
    fun toBitmap(image: ImageProxy): Bitmap {

        // create YuvImage wrapper over the NV21 buffer
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        //compress the JPEG quality at 90
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)

        //convert the byte array to bitmap
        val jpegBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }

    // Conversion of YUV_420_888 to NV21
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        // gets the 3 planes
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        // allocates the output buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        // copy luma(brightness) plane
        yBuffer.get(nv21, 0, ySize)

        // Interleave VU (NV21 format)
        val uvPixelStride = image.planes[1].pixelStride
        val uvRowStride = image.planes[1].rowStride
        val width = image.width
        val height = image.height

        // copy U and V into arrays
        var outputOffset = ySize
        val uBytes = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
        val vBytes = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

        // UV planes are subsampled in YUV420
        val uvHeight = height / 2
        val uvWidth = width / 2

        // NV21 expects interleaved VU bytes
        for (row in 0 until uvHeight) {
            val rowStart = row * uvRowStride
            for (col in 0 until uvWidth) {
                val uvIndex = rowStart + col * uvPixelStride
                nv21[outputOffset++] = vBytes[uvIndex] // V
                nv21[outputOffset++] = uBytes[uvIndex] // U
            }
        }

        return nv21
    }
}