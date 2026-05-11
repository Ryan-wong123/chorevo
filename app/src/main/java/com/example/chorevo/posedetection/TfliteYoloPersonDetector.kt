package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

class TfliteYoloPersonDetector(
    context: Context,
    private val config: PersonDetectorConfig
) : PersonDetector {
    private val interpreter = Interpreter(loadModel(context, config.modelAssetPath))

    override suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox> {
        val input = preprocess(frame, config.inputSize)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = createOutputBuffer(outputShape)
        interpreter.run(input, output)
        return YoloPersonDetectionParser.parse(output, config)
    }

    override fun close() {
        interpreter.close()
    }

    private fun preprocess(frame: Bitmap, inputSize: Int): ByteBuffer {
        val resized = if (frame.width == inputSize && frame.height == inputSize) {
            frame
        } else {
            Bitmap.createScaledBitmap(frame, inputSize, inputSize, true)
        }

        val input = ByteBuffer
            .allocateDirect(1 * inputSize * inputSize * CHANNEL_COUNT * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            input.putFloat(((pixel shr 16) and 0xff) / 255f)
            input.putFloat(((pixel shr 8) and 0xff) / 255f)
            input.putFloat((pixel and 0xff) / 255f)
        }
        input.rewind()

        if (resized !== frame) {
            resized.recycle()
        }
        return input
    }

    private fun createOutputBuffer(shape: IntArray): Array<Array<FloatArray>> {
        require(shape.size == 3) { "Unsupported YOLO output tensor rank" }
        require(shape[0] == 1) { "Only single-batch YOLO output is supported" }
        return Array(shape[0]) {
            Array(shape[1]) {
                FloatArray(shape[2])
            }
        }
    }

    private fun loadModel(context: Context, assetPath: String): ByteBuffer {
        val bytes = ByteArrayOutputStream().use { output ->
            context.assets.open(assetPath).use { input ->
                input.copyTo(output)
            }
            output.toByteArray()
        }
        return ByteBuffer
            .allocateDirect(bytes.size)
            .order(ByteOrder.nativeOrder())
            .put(bytes)
            .apply { rewind() }
    }

    companion object {
        private const val CHANNEL_COUNT = 3
        private const val FLOAT_BYTES = 4
    }
}
