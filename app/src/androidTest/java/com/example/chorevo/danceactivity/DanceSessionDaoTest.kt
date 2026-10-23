package com.example.chorevo.danceactivity

import android.content.Context
import androidx.room.database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chorevo.danceactivity.repository.DanceSessionDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Database(
    entities = [DanceSessionEntity::class],
    version = 1,
    exportSchema = false
)
abstract class TestDanceDb : RoomDatabase() {
    abstract fun dao(): DanceSessionDao
}

class DanceSessionDaoTest {

    private lateinit var db: TestDanceDb
    private lateinit var dao: DanceSessionDao

    @Before
    fun setUp() {
        val ctx: Context = InstrumentationRegistry.getInstrumentation().targetContext

        db = Room.inMemoryDatabaseBuilder(ctx, TestDanceDb::class.java)
            .allowMainThreadQueries()
            .build()

        dao = db.dao()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    @Test
    fun insert_and_latestFlow_returnsNewestByTsEnd() = runTest {
        val s1 = DanceSessionEntity(
            sessionId = "s1",
            tsStart = 100,
            tsEnd = 200,
            activityName = "A",
            activityType = "T",
            durationMs = 100,
            smoothnessScore = 0.1,
            balanceScore = 0.2,
            timingConsistencyScore = 0.3,
            alignmentScore = 0.4,
            qualityFlag = "OK",
            analysisJson = "{}",
            synced = false
        )
        val s2 = s1.copy(sessionId = "s2", tsEnd = 999)

        dao.insert(s1)
        dao.insert(s2)

        val latest = dao.latestFlow().first()
        assertNotNull(latest)
        assertEquals("s2", latest!!.sessionId)
    }

    @Test
    fun markSynced_updatesRow_and_unsynced_excludesIt() = runTest {
        val s1 = DanceSessionEntity(
            sessionId = "s1",
            tsStart = 0,
            tsEnd = 10,
            activityName = "A",
            activityType = "T",
            durationMs = 10,
            smoothnessScore = 0.1,
            balanceScore = 0.2,
            timingConsistencyScore = 0.3,
            alignmentScore = 0.0,
            qualityFlag = "OK",
            analysisJson = "{}",
            synced = false
        )

        dao.insert(s1)
        assertEquals(1, dao.unsynced().size)

        dao.markSynced("s1")
        assertEquals(0, dao.unsynced().size)
    }

    @Test
    fun unsyncedFor_returnsOnlyThatAccountsAndLegacySessions() = runTest {
        fun session(id: String, owner: String) = DanceSessionEntity(
            sessionId = id,
            tsStart = 0,
            tsEnd = 10,
            activityName = "A",
            activityType = "T",
            durationMs = 10,
            smoothnessScore = 0.1,
            balanceScore = 0.2,
            timingConsistencyScore = 0.3,
            qualityFlag = "OK",
            ownerUid = owner
        )
        dao.insert(session("mine", "alice"))
        dao.insert(session("theirs", "bob"))
        dao.insert(session("legacy", ""))

        val ids = dao.unsyncedFor("alice").map { it.sessionId }.toSet()

        assertEquals(setOf("mine", "legacy"), ids)
    }

    @Test
    fun sinceFlow_returnsSessionsWithTsEndAfterSinceTs() = runTest {
        val s1 = DanceSessionEntity(
            sessionId = "s1",
            tsStart = 0,
            tsEnd = 100,
            activityName = "A",
            activityType = "T",
            durationMs = 10,
            smoothnessScore = 0.1,
            balanceScore = 0.2,
            timingConsistencyScore = 0.3,
            alignmentScore = 0.0,
            qualityFlag = "OK",
            analysisJson = "{}",
            synced = false
        )
        val s2 = s1.copy(sessionId = "s2", tsEnd = 500)

        dao.insert(s1)
        dao.insert(s2)

        val list = dao.sinceFlow(sinceTs = 200).first()
        assertEquals(1, list.size)
        assertEquals("s2", list[0].sessionId)
    }
}