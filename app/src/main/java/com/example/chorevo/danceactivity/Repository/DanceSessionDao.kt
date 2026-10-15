package com.example.chorevo.danceactivity.Repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.chorevo.danceactivity.DanceSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DanceSessionDao {

    /** Handles insert. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: DanceSessionEntity)

    /** Handles latest flow. */
    @Query("SELECT * FROM dance_sessions ORDER BY tsEnd DESC LIMIT 1")
    fun latestFlow(): Flow<DanceSessionEntity?>

    /** Handles since flow. */
    @Query("SELECT * FROM dance_sessions WHERE tsEnd >= :sinceTs ORDER BY tsEnd ASC")
    fun sinceFlow(sinceTs: Long): Flow<List<DanceSessionEntity>>

    /** Handles unsynced. */
    @Query("SELECT * FROM dance_sessions WHERE synced = 0 ORDER BY tsEnd ASC")
    suspend fun unsynced(): List<DanceSessionEntity>

    /**
     * Unsynced sessions recorded by [uid], plus legacy sessions with no owner
     * (saved before ownership was tracked).
     */
    @Query("SELECT * FROM dance_sessions WHERE synced = 0 AND (ownerUid = :uid OR ownerUid = '') ORDER BY tsEnd ASC")
    suspend fun unsyncedFor(uid: String): List<DanceSessionEntity>

    /** Handles mark synced. */
    @Query("UPDATE dance_sessions SET synced = 1 WHERE sessionId = :id")
    suspend fun markSynced(id: String)
}
