package com.example.chorevo.posedetection

import kotlin.math.abs

class ByteTrackDancerTracker(
    private val config: DancerTrackerConfig = DancerTrackerConfig()
) : DancerTracker {
    private data class Track(
        val ordinal: Int,
        val boxes: MutableList<TrackedDancerBox> = mutableListOf(),
        var lastBox: DetectionBox,
        var lastFrameIndex: Int,
        var lastTimestampMs: Long,
        var confidence: Float,
        var missedFrames: Int = 0,
        var ended: Boolean = false
    ) {
        val trackId: String
            get() = "track_$ordinal"
    }

    private val tracks = mutableListOf<Track>()
    private var nextTrackOrdinal = 1

    override fun update(
        frameIndex: Int,
        timestampMs: Long,
        detections: List<DetectionBox>
    ): List<TrackedDancerBox> {
        val usableDetections = PoseDetectionMerger
            .mergeDuplicateDetections(detections)
            .filter { detection ->
                detection.classId == DetectionBox.PERSON_CLASS_ID &&
                    detection.confidence >= config.lowConfidenceThreshold
            }

        val highConfidenceDetections = usableDetections
            .filter { it.confidence >= config.highConfidenceThreshold }
        val lowConfidenceDetections = usableDetections
            .filter { it.confidence < config.highConfidenceThreshold }

        val output = mutableListOf<TrackedDancerBox>()
        val matchedTrackIds = mutableSetOf<String>()
        val remainingHighDetections = highConfidenceDetections.toMutableList()
        val remainingLowDetections = lowConfidenceDetections.toMutableList()

        output += matchDetections(
            candidateTracks = activeTracks(),
            detections = remainingHighDetections,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            matchedTrackIds = matchedTrackIds
        )

        output += matchDetections(
            candidateTracks = activeTracks(),
            detections = remainingLowDetections,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            matchedTrackIds = matchedTrackIds
        )

        val remainingDetections = (remainingHighDetections.map { PendingDetection(it, true) } +
            remainingLowDetections.map { PendingDetection(it, false) }).toMutableList()
        output += matchPendingDetections(
            candidateTracks = recentlyLostTracks(),
            detections = remainingDetections,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            matchedTrackIds = matchedTrackIds
        )

        for (detection in remainingDetections.filter { it.highConfidence }.map { it.box }) {
            val track = createTrack(
                detection = detection,
                frameIndex = frameIndex,
                timestampMs = timestampMs
            ) ?: continue
            val trackedBox = track.toTrackedBox(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                box = detection,
                state = TrackState.Visible,
                confidence = detection.confidence
            )
            track.boxes += trackedBox
            output += trackedBox
            matchedTrackIds += track.trackId
        }

        output += markUnmatchedTracks(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            matchedTrackIds = matchedTrackIds
        )

        return output
    }

    override fun tracks(): List<DancerTrack> {
        return tracks.map { track ->
            DancerTrack(
                trackId = track.trackId,
                boxes = track.boxes.toList()
            )
        }
    }

    private fun matchDetections(
        candidateTracks: List<Track>,
        detections: MutableList<DetectionBox>,
        frameIndex: Int,
        timestampMs: Long,
        matchedTrackIds: MutableSet<String>
    ): List<TrackedDancerBox> {
        val output = mutableListOf<TrackedDancerBox>()
        var bestMatch = bestMatch(candidateTracks, detections, matchedTrackIds)

        while (bestMatch != null) {
            val track = bestMatch.track
            val detection = detections[bestMatch.detectionIndex]
            val state = if (track.missedFrames > 0) TrackState.Recovered else TrackState.Visible
            val trackedBox = track.toTrackedBox(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                box = detection,
                state = state,
                confidence = detection.confidence
            )

            track.lastBox = detection
            track.lastFrameIndex = frameIndex
            track.lastTimestampMs = timestampMs
            track.confidence = detection.confidence
            track.missedFrames = 0
            track.boxes += trackedBox

            output += trackedBox
            matchedTrackIds += track.trackId
            detections.removeAt(bestMatch.detectionIndex)

            bestMatch = bestMatch(candidateTracks, detections, matchedTrackIds)
        }

        return output
    }

    private fun matchPendingDetections(
        candidateTracks: List<Track>,
        detections: MutableList<PendingDetection>,
        frameIndex: Int,
        timestampMs: Long,
        matchedTrackIds: MutableSet<String>
    ): List<TrackedDancerBox> {
        val output = mutableListOf<TrackedDancerBox>()
        var bestMatch = bestMatch(
            candidateTracks = candidateTracks,
            detections = detections.map { it.box },
            matchedTrackIds = matchedTrackIds
        )

        while (bestMatch != null) {
            val track = bestMatch.track
            val detection = detections[bestMatch.detectionIndex].box
            val trackedBox = track.toTrackedBox(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                box = detection,
                state = TrackState.Recovered,
                confidence = detection.confidence
            )

            track.lastBox = detection
            track.lastFrameIndex = frameIndex
            track.lastTimestampMs = timestampMs
            track.confidence = detection.confidence
            track.missedFrames = 0
            track.boxes += trackedBox

            output += trackedBox
            matchedTrackIds += track.trackId
            detections.removeAt(bestMatch.detectionIndex)

            bestMatch = bestMatch(
                candidateTracks = candidateTracks,
                detections = detections.map { it.box },
                matchedTrackIds = matchedTrackIds
            )
        }

        return output
    }

    private fun markUnmatchedTracks(
        frameIndex: Int,
        timestampMs: Long,
        matchedTrackIds: Set<String>
    ): List<TrackedDancerBox> {
        val output = mutableListOf<TrackedDancerBox>()

        for (track in tracks) {
            if (track.ended || track.trackId in matchedTrackIds) continue

            track.missedFrames++
            val state = if (track.missedFrames > config.maxMissedFrames) {
                track.ended = true
                TrackState.Ended
            } else {
                TrackState.TemporarilyLost
            }
            val trackedBox = track.toTrackedBox(
                frameIndex = frameIndex,
                timestampMs = timestampMs,
                box = track.lastBox,
                state = state,
                confidence = track.confidence
            )
            track.boxes += trackedBox
            output += trackedBox
        }

        return output
    }

    private fun createTrack(
        detection: DetectionBox,
        frameIndex: Int,
        timestampMs: Long
    ): Track? {
        val liveTrackCount = tracks.count { !it.ended }
        if (liveTrackCount >= config.maxTracks) return null

        return Track(
            ordinal = nextTrackOrdinal++,
            lastBox = detection,
            lastFrameIndex = frameIndex,
            lastTimestampMs = timestampMs,
            confidence = detection.confidence
        ).also { tracks += it }
    }

    private fun activeTracks(): List<Track> {
        return tracks.filter { !it.ended && it.missedFrames == 0 }
    }

    private fun recentlyLostTracks(): List<Track> {
        return tracks.filter { track ->
            !track.ended && track.missedFrames in 1..config.maxMissedFrames
        }
    }

    private fun bestMatch(
        candidateTracks: List<Track>,
        detections: List<DetectionBox>,
        matchedTrackIds: Set<String>
    ): Match? {
        return candidateTracks
            .filter { it.trackId !in matchedTrackIds }
            .flatMap { track ->
                detections.mapIndexedNotNull { detectionIndex, detection ->
                    val score = matchScore(track.lastBox, detection)
                    if (score < config.iouMatchThreshold) return@mapIndexedNotNull null
                    Match(track = track, detectionIndex = detectionIndex, score = score)
                }
            }
            .maxByOrNull { it.score }
    }

    private fun matchScore(previous: DetectionBox, current: DetectionBox): Float {
        val iou = previous.bounds.iou(current.bounds)
        val centerDistance = abs(previous.bounds.centerX - current.bounds.centerX) +
            abs(previous.bounds.centerY - current.bounds.centerY)
        return iou - (centerDistance * 0.10f)
    }

    private fun Track.toTrackedBox(
        frameIndex: Int,
        timestampMs: Long,
        box: DetectionBox,
        state: TrackState,
        confidence: Float
    ): TrackedDancerBox {
        return TrackedDancerBox(
            trackId = trackId,
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            box = box,
            state = state,
            confidence = confidence
        )
    }

    private data class Match(
        val track: Track,
        val detectionIndex: Int,
        val score: Float
    )

    private data class PendingDetection(
        val box: DetectionBox,
        val highConfidence: Boolean
    )
}
