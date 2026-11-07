package com.example.chorevo.posedetection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

class TfliteYoloPersonDetector(
    context: Context,
    private val config: PersonDetectorConfig
) : PersonDetector {
    private var gpuDelegate: GpuDelegate? = null
    private val interpreter = createInterpreter(context)

    /** Newer exports (YOLO11 via litert-torch) take [1,3,H,W]; older ones [1,H,W,3]. */
    private val channelsFirst: Boolean =
        interpreter.getInputTensor(0).shape().let { it.size == 4 && it[1] == CHANNEL_COUNT }

    override suspend fun detect(frame: Bitmap, timestampMs: Long): List<DetectionBox> {
        val letterbox = LetterboxTransform.compute(frame.width, frame.height, config.inputSize)
        val input = preprocess(frame, config.inputSize, letterbox)
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = createOutputBuffer(outputShape)
        interpreter.run(input, output)
        return YoloPersonDetectionParser.parse(output, config)
            .mapNotNull { letterbox.toFrameSpace(it) }
    }

    override fun close() {
        interpreter.close()
        gpuDelegate?.close()
        gpuDelegate = null
    }

    /** GPU when the device supports it (used by the live path), CPU otherwise. */
    private fun createInterpreter(context: Context): Interpreter {
        val model = loadModel(context, config.modelAssetPath)
        if (config.useGpu) {
            val gpu = runCatching {
                if (CompatibilityList().isDelegateSupportedOnThisDevice) GpuDelegate() else null
            }.getOrNull()
            if (gpu != null) {
                val withGpu = runCatching {
                    Interpreter(model, Interpreter.Options().addDelegate(gpu))
                }.getOrNull()
                if (withGpu != null) {
                    gpuDelegate = gpu
                    return withGpu
                }
                gpu.close()
            }
        }
        return Interpreter(model, Interpreter.Options().setNumThreads(CPU_THREADS))
    }

    /** Letterboxes [frame] to a square [inputSize] canvas filled with neutral grey. */
    private fun preprocess(frame: Bitmap, inputSize: Int, letterbox: LetterboxTransform): ByteBuffer {
        val resized = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        Canvas(resized).apply {
            drawColor(Color.rgb(PAD_GREY, PAD_GREY, PAD_GREY))
            drawBitmap(
                frame,
                null,
                RectF(
                    letterbox.padX,
                    letterbox.padY,
                    letterbox.padX + frame.width * letterbox.scale,
                    letterbox.padY + frame.height * letterbox.scale
                ),
                Paint(Paint.FILTER_BITMAP_FLAG)
            )
        }

        val input = ByteBuffer
            .allocateDirect(1 * inputSize * inputSize * CHANNEL_COUNT * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        if (channelsFirst) {
            for (shift in intArrayOf(16, 8, 0)) {
                for (pixel in pixels) {
                    input.putFloat(((pixel shr shift) and 0xff) / 255f)
                }
            }
        } else {
            for (pixel in pixels) {
                input.putFloat(((pixel shr 16) and 0xff) / 255f)
                input.putFloat(((pixel shr 8) and 0xff) / 255f)
                input.putFloat((pixel and 0xff) / 255f)
            }
        }
        input.rewind()

        resized.recycle()
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
        private const val PAD_GREY = 114
        private const val CPU_THREADS = 4
    }
}

/**
 * Maps between a frame and its letterboxed square model input. The frame is scaled
 * uniformly (no aspect distortion) and centred; boxes come back normalized to the
 * square input and are mapped to normalized frame coordinates.
 */
internal data class LetterboxTransform(
    val scale: Float,
    val padX: Float,
    val padY: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val inputSize: Int
) {
    fun toFrameSpace(box: DetectionBox): DetectionBox? {
        val size = inputSize.toFloat()
        val left = ((box.left * size - padX) / scale / frameWidth).coerceIn(0f, 1f)
        val right = ((box.right * size - padX) / scale / frameWidth).coerceIn(0f, 1f)
        val top = ((box.top * size - padY) / scale / frameHeight).coerceIn(0f, 1f)
        val bottom = ((box.bottom * size - padY) / scale / frameHeight).coerceIn(0f, 1f)
        if (right <= left || bottom <= top) return null
        return DetectionBox.normalized(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            confidence = box.confidence,
            classId = box.classId,
            source = box.source
        )
    }

    companion object {
        fun compute(frameWidth: Int, frameHeight: Int, inputSize: Int): LetterboxTransform {
            val scale = minOf(inputSize.toFloat() / frameWidth, inputSize.toFloat() / frameHeight)
            return LetterboxTransform(
                scale = scale,
                padX = (inputSize - frameWidth * scale) / 2f,
                padY = (inputSize - frameHeight * scale) / 2f,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                inputSize = inputSize
            )
        }
    }
}
