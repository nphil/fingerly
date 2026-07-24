package com.fingerly.app.data

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
    version = 2,
    exportSchema = true,
)
abstract class FingerlyDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun passageDao(): PassageDao
    abstract fun practiceSessionDao(): PracticeSessionDao
    abstract fun passageAttemptDao(): PassageAttemptDao
    abstract fun midiRecordingDao(): MidiRecordingDao
    abstract fun srsCardDao(): SrsCardDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var instance: FingerlyDatabase? = null

        fun get(context: Context): FingerlyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FingerlyDatabase::class.java,
                    "fingerly.db",
                )
                    // Single-user app, pre-1.0: schema changes may drop local data.
                    // Proper migrations start once real practice history exists.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
