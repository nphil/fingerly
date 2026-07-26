package com.fingerly.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SongEntity::class,
        PassageEntity::class,
        PracticeSessionEntity::class,
        PassageAttemptEntity::class,
        MidiRecordingEntity::class,
        SrsCardEntity::class,
        AppSettingEntity::class,
        FoundationsTrialEntity::class,
        FoundationsProbeEntity::class,
    ],
    version = 6,
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
    abstract fun foundationsTrialDao(): FoundationsTrialDao
    abstract fun foundationsProbeDao(): FoundationsProbeDao

    companion object {
        /** Adds `demonstrated` so a scaffolded trial is distinguishable. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE foundations_trials " +
                        "ADD COLUMN demonstrated INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** Adds the cold-read table (SPEC §4a-F item F3). */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS foundations_probes (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "excerptId TEXT NOT NULL, tier INTEGER NOT NULL, " +
                        "atEpochMs INTEGER NOT NULL, dayIndex INTEGER NOT NULL, " +
                        "firstAttemptOfSitting INTEGER NOT NULL, " +
                        "noteCount INTEGER NOT NULL, hits INTEGER NOT NULL, " +
                        "misses INTEGER NOT NULL, extras INTEGER NOT NULL, " +
                        "pitchAccuracy REAL NOT NULL, avgAbsErrorMs INTEGER NOT NULL, " +
                        "meanSignedErrMs INTEGER NOT NULL, timingCoverage REAL NOT NULL, " +
                        "leftAccuracy REAL NOT NULL, rightAccuracy REAL NOT NULL, " +
                        "handsTogetherOnsets INTEGER NOT NULL, scaffoldState INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_foundations_probes_dayIndex " +
                        "ON foundations_probes (dayIndex)",
                )
            }
        }

        @Volatile
        private var instance: FingerlyDatabase? = null

        fun get(context: Context): FingerlyDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FingerlyDatabase::class.java,
                    "fingerly.db",
                )
                    // Real migrations from here on. `foundations_trials` is the
                    // independent variable of SPEC §4a-F's falsification check,
                    // and the module ahead of us bumps this schema repeatedly —
                    // a destructive fallback would silently delete the history
                    // at exactly the moment it starts to matter.
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .build().also { instance = it }
            }
    }
}
