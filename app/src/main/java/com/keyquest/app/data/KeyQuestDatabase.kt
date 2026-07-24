package com.keyquest.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SongEntity::class,
        PassageEntity::class,
        PracticeSessionEntity::class,
        PassageAttemptEntity::class,
        MidiRecordingEntity::class,
        SrsCardEntity::class,
        AppSettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class KeyQuestDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun passageDao(): PassageDao
    abstract fun practiceSessionDao(): PracticeSessionDao
    abstract fun passageAttemptDao(): PassageAttemptDao
    abstract fun midiRecordingDao(): MidiRecordingDao
    abstract fun srsCardDao(): SrsCardDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var instance: KeyQuestDatabase? = null

        fun get(context: Context): KeyQuestDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KeyQuestDatabase::class.java,
                    "keyquest.db",
                ).build().also { instance = it }
            }
    }
}
