package com.keyquest.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface SongDao {
    @Insert
    suspend fun insert(song: SongEntity): Long

    @Query("SELECT * FROM songs ORDER BY difficultyRank")
    suspend fun all(): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun byId(id: Long): SongEntity?
}

@Dao
interface PassageDao {
    @Insert
    suspend fun insertAll(passages: List<PassageEntity>): List<Long>

    @Query("SELECT * FROM passages WHERE songId = :songId ORDER BY orderIndex")
    suspend fun forSong(songId: Long): List<PassageEntity>
}

@Dao
interface PracticeSessionDao {
    @Insert
    suspend fun insert(session: PracticeSessionEntity): Long

    @Update
    suspend fun update(session: PracticeSessionEntity)

    @Query("SELECT * FROM practice_sessions ORDER BY startedAtEpochMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PracticeSessionEntity>
}

@Dao
interface PassageAttemptDao {
    @Insert
    suspend fun insert(attempt: PassageAttemptEntity): Long

    @Query("SELECT * FROM passage_attempts WHERE passageId = :passageId ORDER BY startedAtEpochMs")
    suspend fun forPassage(passageId: Long): List<PassageAttemptEntity>
}

@Dao
interface MidiRecordingDao {
    @Insert
    suspend fun insert(recording: MidiRecordingEntity): Long

    @Query("SELECT * FROM midi_recordings WHERE passageId = :passageId ORDER BY recordedAtEpochMs")
    suspend fun forPassage(passageId: Long): List<MidiRecordingEntity>
}

@Dao
interface SrsCardDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: SrsCardEntity): Long

    @Query("SELECT * FROM srs_cards WHERE dueAtEpochMs <= :nowEpochMs ORDER BY dueAtEpochMs")
    suspend fun due(nowEpochMs: Long): List<SrsCardEntity>
}

@Dao
interface AppSettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?
}
