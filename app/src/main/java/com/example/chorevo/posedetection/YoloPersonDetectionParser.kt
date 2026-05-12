package com.example.chorevo.posedetection

import kotlin.math.max
import kotlin.math.min

object YoloPersonDetectionParser {
    private const val BOX_ATTRIBUTE_COUNT = 4
    private const val OBJECTNESS_ATTRIBUTE_COUNT = 5
    private const val MIN_BOX_SIZE = 0.001f

    fun parse(
        rawOutput: Array<Array<FloatArray>>,
        config: PersonDetectorConfig
    ): List<DetectionBox> {
        if (rawOutput.isEmpty()) return emptyList()
        val firstBatch = rawOutput.firstOrNull() ?: return emptyList()
        if (firstBatch.isEmpty()) return emptyList()

        val rows = when (config.outputFormat) {
            YoloOutputFormat.BOXES_FIRST -> firstBatch
            YoloOutputFormat.ATTRIBUTES_FIRST -> transpose(firstBatch)
            YoloOutputFormat.AUTO -> if (looksAttributesFirst(firstBatch)) {
                transpose(firstBatch)
            } else {
                firstBatch
            }
        }

        return nonMaximumSuppression(
            detections = rows.mapNotNull { row -> parseRow(row, config) },
            iouThreshold = config.iouThreshold
        )
    }

    private fun parseRow(row: FloatArray, config: PersonDetectorConfig): DetectionBox? {
        if (row.size <= BOX_ATTRIBUTE_COUNT) return null

        val objectness = if (config.outputIncludesObjectness) row.getOrElse(4) { 0f } else 1f
        val scoreStartIndex = if (config.outputIncludesObjectness) {
            OBJECTNESS_ATTRIBUTE_COUNT
        } else {
            BOX_ATTRIBUTE_COUNT
        }
        val classScoreIndex = scoreStartIndex + config.personClassId
        if (classScoreIndex !in row.indices) return null

        val confidence = objectness * row[classScoreIndex]
        if (confidence < config.minConfidence) return null

        val centerX = row[0]
        val centerY = row[1]
        val width = row[2]
        val height = row[3]
        if (width < MIN_BOX_SIZE || height < MIN_BOX_SIZE) return null

        return DetectionBox.normalized(
            left = centerX - width / 2f,
            top = centerY - height / 2f,
            right = centerX + width / 2f,
            bottom = centerY + height / 2f,
            confidence = confidence,
            classId = config.personClassId,
            source = DetectionSource.Yolo
        )
    }

    internal fun nonMaximumSuppression(
        detections: List<DetectionBox>,
        iouThreshold: Float
    ): List<DetectionBox> {
        val kept = mutableListOf<DetectionBox>()
        for (candidate in detections.sortedByDescending { it.confidence }) {
            val overlapsKept = kept.any { existing ->
                iou(existing, candidate) > iouThreshold
            }
            if (!overlapsKept) {
                kept += candidate
            }
        }
        return kept
    }

    private fun looksAttributesFirst(output: Array<FloatArray>): Boolean {
        if (output.size < BOX_ATTRIBUTE_COUNT) return false
        val rowWidth = output.firstOrNull()?.size ?: return false
        return output.size < rowWidth && output.size in 5..256
    }

    private fun transpose(output: Array<FloatArray>): Array<FloatArray> {
        val rowCount = output.size
        val columnCount = output.firstOrNull()?.size ?: return emptyArray()
        return Array(columnCount) { columnIndex ->
            FloatArray(rowCount) { rowIndex ->
                output[rowIndex].getOrElse(columnIndex) { 0f }
            }
        }
    }

    private fun iou(first: DetectionBox, second: DetectionBox): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = first.area + second.area - intersection
        if (union <= 0f) return 0f
        return intersection / union
    }
}
