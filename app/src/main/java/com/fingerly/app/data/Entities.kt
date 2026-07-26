package com.fingerly.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A song in the library (SPEC §4, §5). */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val composer: String,
    /** "musicxml" (first-class) or "midi" (approximate import) — SPEC §5. */
    val sourceFormat: String,
    val filePath: String,
    /** Position in the difficulty-ordered library; lower = easier. */
    val difficultyRank: Int,
    val importedAtEpochMs: Long,
)

/** A micro-passage (2–8 bars) produced by the decomposition engine (SPEC §4). */
@Entity(
    tableName = "passages",
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("songId")],
)
data class PassageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val startMeasure: Int,
    val endMeasure: Int,
    /** "LEFT", "RIGHT" or "BOTH". */
    val hand: String,
    /** Dependency+difficulty order within the song (SPEC §4). */
    val orderIndex: Int,
    /** Comma-separated skill tags (chord shapes, jumps, rhythm figures…). */
    val skillTags: String,
)

/** One practice session (SPEC §3). */
@Entity(tableName = "practice_sessions")
data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    /** "STANDARD" (10–15 min) or "RESCUE" (2 min) — SPEC §3. */
    val type: String,
    /** Named hard finish state, e.g. "4 bars clean @ 60bpm" (SPEC §3). */
    val finishStateLabel: String?,
)

/** One attempt at a passage within a session — the raw metric record (SPEC §2.8, §3). */
@Entity(
    tableName = "passage_attempts",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PassageEntity::class,
            parentColumns = ["id"],
            childColumns = ["passageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("passageId")],
)
data class PassageAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val passageId: Long,
    val startedAtEpochMs: Long,
    val tempoBpm: Int,
    /** Auto-difficulty tempo scale in effect, 1.0 = full speed (SPEC §3). */
    val tempoMultiplier: Float,
    val accuracyPercent: Float,
    val notesHit: Int,
    val notesMissed: Int,
    /** Wrong/extra notes played — they count against accuracy (SPEC §2.8). */
    val notesExtra: Int,
    /** Mean absolute timing error of hits, ms. */
    val avgAbsErrorMs: Long,
    /** Mean signed timing error (+ = late), ms — the rushing/dragging signal. */
    val meanSignedErrorMs: Long,
    /** Per-hand hit rate, -1 when the run had no notes for that hand. */
    val leftHandAccuracy: Float,
    val rightHandAccuracy: Float,
    /** "LEFT", "RIGHT" or "BOTH" — hands may be split by auto-difficulty (SPEC §3). */
    val handMode: String,
    /** True when the run used wait mode (untimed key-finding). */
    val waitMode: Boolean,
)

/** Recorded MIDI of a session/passage for before-vs-after playback (SPEC §3). */
@Entity(
    tableName = "midi_recordings",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId"), Index("passageId")],
)
data class MidiRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val passageId: Long?,
    val filePath: String,
    val recordedAtEpochMs: Long,
    val durationMs: Long,
)

/** FSRS scheduling state per passage; grade comes from measured metrics (SPEC §3). */
@Entity(
    tableName = "srs_cards",
    foreignKeys = [
        ForeignKey(
            entity = PassageEntity::class,
            parentColumns = ["id"],
            childColumns = ["passageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["passageId"], unique = true)],
)
data class SrsCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val passageId: Long,
    val stability: Double,
    val difficulty: Double,
    val reps: Int,
    val lapses: Int,
    val lastReviewedAtEpochMs: Long?,
    val dueAtEpochMs: Long,
)

/**
 * One foundations prompt, logged raw (SPEC §8a): the durable asset that lets
 * every threshold in the trainer be re-tuned later against real history.
 * Aggregates are derived from these rows, never the other way round.
 */
@Entity(
    tableName = "foundations_trials",
    indices = [Index("atomId"), Index("dayIndex")],
)
data class FoundationsTrialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atomId: String,
    /** The atom the drill was focused on (may differ from [atomId] for padding). */
    val focusAtomId: String,
    val atEpochMs: Long,
    /** Days since epoch — the spacing unit the criterion counts. */
    val dayIndex: Int,
    val expectedNote: Int,
    /** Correct on the first press with no reveal: the only thing that counts. */
    val unaided: Boolean,
    val revealed: Boolean,
    /**
     * The APP showed the answer of its own accord — the first meeting with a
     * symbol. Distinct from [revealed], which also covers a failed retrieval,
     * because the falsification check must be able to exclude scaffolded trials.
     */
    @ColumnInfo(defaultValue = "0")
    val demonstrated: Boolean = false,
    /** Prompt→correct-key latency in ms, or -1 when never produced. */
    val latencyMs: Int,
    val wrongPressCount: Int,
    val octaveErrors: Int,
    val neighborErrors: Int,
    val otherErrors: Int,
)

/** Simple local key-value settings (first-run checklist state, current song, …). */
@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)
