package com.example.chorevo.danceactivity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dance_sessions")
data class DanceSessionEntity(
    @PrimaryKey val sessionId: String,
    val tsStart: Long,
    val tsEnd: Long,
    val activityName: String,
    val activityType: String,
    val durationMs: Long,
    val smoothnessScore: Double,
    val balanceScore: Double,
    val timingConsistencyScore: Double,
    val alignmentScore: Double = 0.0,
    val qualityFlag: String,
    val analysisJson: String = "{}",
    val synced: Boolean = false
)
