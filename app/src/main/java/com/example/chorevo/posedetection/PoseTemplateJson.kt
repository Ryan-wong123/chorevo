package com.example.chorevo.posedetection

/** Serializes pose templates for storage outside Firestore document size limits. */
object PoseTemplateJson {

    fun toJson(template: PoseTemplate): String {
        return buildString {
            append('{')
            appendKey("fps")
            append(template.fps)
            append(',')
            appendKey("frames")
            appendPoseFrames(template.frames)
            append(',')
            appendKey("tracks")
            appendPoseTracks(template.tracks)
            append(',')
            appendKey("defaultTrackId")
            appendNullableString(template.defaultTrackId)
            append(',')
            appendKey("selectionPreview")
            appendSelectionPreview(template.selectionPreview)
            append(',')
            appendKey("qualityWarnings")
            appendQualityWarnings(template.qualityWarnings)
            append(',')
            appendKey("handTracks")
            appendHandTracks(template.handTracks)
            append('}')
        }
    }

    fun fromJson(json: String): PoseTemplate {
        val root = JsonParser(json).parseValue() as? Map<*, *>
            ?: error("Pose template JSON must be an object")

        return PoseTemplate(
            fps = root.intValue("fps", 2),
            frames = root.listValue("frames").mapNotNull { it.toPoseFrame() },
            tracks = root.listValue("tracks").mapNotNull { it.toPoseTrack() },
            defaultTrackId = root.stringValue("defaultTrackId"),
            selectionPreview = root.mapValue("selectionPreview")?.toPoseSelectionPreview(),
            qualityWarnings = root.listValue("qualityWarnings").mapNotNull { it.toPoseAnalysisWarning() },
            handTracks = root.listValue("handTracks").mapNotNull { it.toHandTrack() }
        )
    }

    private fun StringBuilder.appendPoseFrames(frames: List<PoseFrame>) {
        append('[')
        frames.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            append('{')
            appendKey("tms")
            append(frame.tMs)
            append(',')
            appendKey("landmarks")
            appendFloatList(frame.landmarks)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendPoseTracks(tracks: List<PoseTrack>) {
        append('[')
        tracks.forEachIndexed { index, track ->
            if (index > 0) append(',')
            append('{')
            appendKey("trackId")
            appendQuoted(track.trackId)
            append(',')
            appendKey("label")
            appendQuoted(track.label)
            append(',')
            appendKey("frames")
            appendPoseFrames(track.frames)
            append(',')
            appendKey("avgVisibility")
            append(track.avgVisibility)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendHandTracks(tracks: List<HandTrack>) {
        append('[')
        tracks.forEachIndexed { index, track ->
            if (index > 0) append(',')
            append('{')
            appendKey("trackId")
            appendQuoted(track.trackId)
            append(',')
            appendKey("frames")
            appendHandFrames(track.frames)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendHandFrames(frames: List<HandLandmarkFrame>) {
        append('[')
        frames.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            append('{')
            appendKey("tms")
            append(frame.tMs)
            append(',')
            appendKey("leftHand")
            appendFloatList(frame.leftHand)
            append(',')
            appendKey("rightHand")
            appendFloatList(frame.rightHand)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendSelectionPreview(preview: PoseSelectionPreview?) {
        if (preview == null) {
            append("null")
            return
        }

        append('{')
        appendKey("timeMs")
        append(preview.timeMs)
        append(',')
        appendKey("frameWidth")
        append(preview.frameWidth)
        append(',')
        appendKey("frameHeight")
        append(preview.frameHeight)
        append(',')
        appendKey("candidates")
        append('[')
        preview.candidates.forEachIndexed { index, candidate ->
            if (index > 0) append(',')
            append('{')
            appendKey("trackId")
            appendQuoted(candidate.trackId)
            append(',')
            appendKey("landmarks")
            appendFloatList(candidate.landmarks)
            append(',')
            appendKey("bounds")
            appendBounds(candidate.bounds)
            append('}')
        }
        append(']')
        append('}')
    }

    private fun StringBuilder.appendQualityWarnings(warnings: List<PoseAnalysisWarning>) {
        append('[')
        warnings.forEachIndexed { index, warning ->
            if (index > 0) append(',')
            append('{')
            appendKey("code")
            appendQuoted(warning.code)
            append(',')
            appendKey("message")
            appendQuoted(warning.message)
            append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendBounds(bounds: PoseBounds) {
        append('{')
        appendKey("left")
        append(bounds.left)
        append(',')
        appendKey("top")
        append(bounds.top)
        append(',')
        appendKey("right")
        append(bounds.right)
        append(',')
        appendKey("bottom")
        append(bounds.bottom)
        append('}')
    }

    private fun StringBuilder.appendFloatList(values: List<Float>) {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            append(value)
        }
        append(']')
    }

    private fun StringBuilder.appendKey(key: String) {
        appendQuoted(key)
        append(':')
    }

    private fun StringBuilder.appendNullableString(value: String?) {
        if (value == null) {
            append("null")
        } else {
            appendQuoted(value)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u")
                        append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
        append('"')
    }

    private fun Any?.toPoseFrame(): PoseFrame? {
        val map = this as? Map<*, *> ?: return null
        return PoseFrame(
            tMs = map.longValue("tms", 0L),
            landmarks = map.floatListValue("landmarks")
        )
    }

    private fun Any?.toPoseTrack(): PoseTrack? {
        val map = this as? Map<*, *> ?: return null
        return PoseTrack(
            trackId = map.stringValue("trackId").orEmpty(),
            label = map.stringValue("label").orEmpty(),
            frames = map.listValue("frames").mapNotNull { it.toPoseFrame() },
            avgVisibility = map.floatValue("avgVisibility", 0f)
        )
    }

    private fun Any?.toHandTrack(): HandTrack? {
        val map = this as? Map<*, *> ?: return null
        return HandTrack(
            trackId = map.stringValue("trackId").orEmpty(),
            frames = map.listValue("frames").mapNotNull { it.toHandLandmarkFrame() }
        )
    }

    private fun Any?.toHandLandmarkFrame(): HandLandmarkFrame? {
        val map = this as? Map<*, *> ?: return null
        return HandLandmarkFrame(
            tMs = map.longValue("tms", 0L),
            leftHand = map.floatListValue("leftHand"),
            rightHand = map.floatListValue("rightHand")
        )
    }

    private fun Map<*, *>.toPoseSelectionPreview(): PoseSelectionPreview {
        return PoseSelectionPreview(
            timeMs = longValue("timeMs", 0L),
            frameWidth = intValue("frameWidth", 0),
            frameHeight = intValue("frameHeight", 0),
            candidates = listValue("candidates").mapNotNull { it.toPoseSelectionCandidate() }
        )
    }

    private fun Any?.toPoseSelectionCandidate(): PoseSelectionCandidate? {
        val map = this as? Map<*, *> ?: return null
        return PoseSelectionCandidate(
            trackId = map.stringValue("trackId").orEmpty(),
            landmarks = map.floatListValue("landmarks"),
            bounds = map.mapValue("bounds")?.toPoseBounds() ?: PoseBounds()
        )
    }

    private fun Any?.toPoseAnalysisWarning(): PoseAnalysisWarning? {
        val map = this as? Map<*, *> ?: return null
        val code = map.stringValue("code")?.takeIf { it.isNotBlank() } ?: return null
        return PoseAnalysisWarning(
            code = code,
            message = map.stringValue("message").orEmpty()
        )
    }

    private fun Map<*, *>.toPoseBounds(): PoseBounds {
        return PoseBounds(
            left = floatValue("left", 0f),
            top = floatValue("top", 0f),
            right = floatValue("right", 1f),
            bottom = floatValue("bottom", 1f)
        )
    }

    private fun Map<*, *>.stringValue(key: String): String? {
        return this[key] as? String
    }

    private fun Map<*, *>.intValue(key: String, defaultValue: Int): Int {
        return (this[key] as? Number)?.toInt() ?: defaultValue
    }

    private fun Map<*, *>.longValue(key: String, defaultValue: Long): Long {
        return (this[key] as? Number)?.toLong() ?: defaultValue
    }

    private fun Map<*, *>.floatValue(key: String, defaultValue: Float): Float {
        return (this[key] as? Number)?.toFloat() ?: defaultValue
    }

    private fun Map<*, *>.listValue(key: String): List<Any?> {
        @Suppress("UNCHECKED_CAST")
        return this[key] as? List<Any?> ?: emptyList()
    }

    private fun Map<*, *>.mapValue(key: String): Map<*, *>? {
        return this[key] as? Map<*, *>
    }

    private fun Map<*, *>.floatListValue(key: String): List<Float> {
        return listValue(key).mapNotNull { (it as? Number)?.toFloat() }
    }

    private class JsonParser(private val input: String) {
        private var index: Int = 0

        fun parseValue(): Any? {
            skipWhitespace()
            val next = peekChar()
            return when (next) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                'n' -> {
                    expectLiteral("null")
                    null
                }
                't' -> {
                    expectLiteral("true")
                    true
                }
                'f' -> {
                    expectLiteral("false")
                    false
                }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expectChar('{')
            skipWhitespace()
            val map = linkedMapOf<String, Any?>()
            if (consumeChar('}')) return map

            while (true) {
                val key = parseString()
                skipWhitespace()
                expectChar(':')
                map[key] = parseValue()
                skipWhitespace()
                if (consumeChar('}')) return map
                expectChar(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expectChar('[')
            skipWhitespace()
            val list = mutableListOf<Any?>()
            if (consumeChar(']')) return list

            while (true) {
                list += parseValue()
                skipWhitespace()
                if (consumeChar(']')) return list
                expectChar(',')
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val out = StringBuilder()
            while (index < input.length) {
                val ch = input[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> out.append(parseEscape())
                    else -> out.append(ch)
                }
            }
            error("Unterminated string literal")
        }

        private fun parseEscape(): Char {
            val ch = input.getOrNull(index++) ?: error("Invalid escape sequence")
            return when (ch) {
                '"', '\\', '/' -> ch
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = input.substring(index, index + 4)
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> error("Unsupported escape sequence: \\$ch")
            }
        }

        private fun parseNumber(): Double {
            val start = index
            while (index < input.length && input[index] in NUMBER_CHARS) {
                index++
            }
            return input.substring(start, index).toDouble()
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index].isWhitespace()) {
                index++
            }
        }

        private fun peekChar(): Char {
            return input.getOrNull(index) ?: error("Unexpected end of JSON input")
        }

        private fun consumeChar(expected: Char): Boolean {
            skipWhitespace()
            if (input.getOrNull(index) == expected) {
                index++
                return true
            }
            return false
        }

        private fun expectChar(expected: Char) {
            skipWhitespace()
            val actual = input.getOrNull(index++) ?: error("Expected '$expected'")
            check(actual == expected) { "Expected '$expected' but found '$actual'" }
        }

        private fun expectLiteral(literal: String) {
            check(input.regionMatches(index, literal, 0, literal.length)) {
                "Expected '$literal'"
            }
            index += literal.length
        }

        companion object {
            private val NUMBER_CHARS = setOf('-', '+', '.', 'e', 'E') + ('0'..'9')
        }
    }
}
