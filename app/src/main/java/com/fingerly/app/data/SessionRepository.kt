package com.fingerly.app.data

import com.fingerly.core.session.AttemptResult
import com.fingerly.core.session.HAND_BOTH
import com.fingerly.core.session.LearnerProfile
import com.fingerly.core.session.Passage
import com.fingerly.core.session.PassageProgress
import com.fingerly.core.session.PracticeSetting
import com.fingerly.core.song.ChartNote
import com.fingerly.core.song.Score
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Bridges the pure session engine (:core) and Room. All calls are IO-dispatched;
 * nothing here touches the render loop or MIDI thread (SPEC §1).
 */
class SessionRepository(private val db: FingerlyDatabase) {

    /**
     * Ensures the song + its decomposition exist in the DB.
     * Returns core-passage-id → DB row id.
     */
    suspend fun ensureSong(
        score: Score,
        filePath: String,
        composer: String,
        difficultyRank: Int,
        passages: List<Passage>,
    ): Map<Int, Long> = withContext(Dispatchers.IO) {
        val existing = db.songDao().byFilePath(filePath)
        val songId = existing?.id ?: db.songDao().insert(
            SongEntity(
                title = score.title,
                composer = composer,
                sourceFormat = "musicxml",
                filePath = filePath,
                difficultyRank = difficultyRank,
                importedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        val rows = db.passageDao().forSong(songId)
        if (rows.size == passages.size) {
            passages.associate { p ->
                p.id to rows.first { it.startMeasure == p.startMeasure }.id
            }
        } else {
            val ids = db.passageDao().insertAll(
                passages.map { p ->
                    PassageEntity(
                        songId = songId,
                        startMeasure = p.startMeasure,
                        endMeasure = p.endMeasure,
                        hand = "BOTH",
                        orderIndex = p.difficultyRank,
                        skillTags = p.skills.sorted().joinToString(","),
                    )
                },
            )
            passages.mapIndexed { i, p -> p.id to ids[i] }.toMap()
        }
    }

    /** Rebuilds per-passage learning state from recorded attempts + SRS rows. */
    suspend fun loadProgress(idMap: Map<Int, Long>): MutableMap<Int, PassageProgress> =
        withContext(Dispatchers.IO) {
            val out = HashMap<Int, PassageProgress>()
            for ((coreId, dbId) in idMap) {
                val attempts = db.passageAttemptDao().forPassage(dbId)
                if (attempts.isEmpty()) continue
                val pr = PassageProgress()
                for (a in attempts) {
                    pr.record(
                        AttemptResult(
                            accuracyPercent = a.accuracyPercent,
                            hits = a.notesHit,
                            misses = a.notesMissed,
                            extras = a.notesExtra,
                            avgAbsErrMs = a.avgAbsErrorMs,
                            meanSignedErrMs = a.meanSignedErrorMs,
                            leftAccuracy = a.leftHandAccuracy,
                            rightAccuracy = a.rightHandAccuracy,
                        ),
                        ladderIndexOf(a),
                    )
                }
                db.srsCardDao().forPassage(dbId)?.let { card ->
                    pr.stability = card.stability
                    pr.fsrsDifficulty = card.difficulty
                    pr.lastReviewMs = card.lastReviewedAtEpochMs ?: 0L
                    pr.dueAtMs = card.dueAtEpochMs
                    if (card.stability > 0) {
                        pr.intervalDays = com.fingerly.core.session.Fsrs.intervalDays(
                            com.fingerly.core.session.Fsrs.Card(card.stability, card.difficulty),
                        )
                    }
                }
                out[coreId] = pr
            }
            out
        }

    suspend fun startSession(): Long = withContext(Dispatchers.IO) {
        db.practiceSessionDao().insert(
            PracticeSessionEntity(
                startedAtEpochMs = System.currentTimeMillis(),
                endedAtEpochMs = null,
                type = "STANDARD",
                finishStateLabel = null,
            ),
        )
    }

    suspend fun endSession(sessionId: Long, finishLabel: String) =
        withContext(Dispatchers.IO) {
            val session = db.practiceSessionDao().recent(50).firstOrNull { it.id == sessionId }
                ?: return@withContext
            db.practiceSessionDao().update(
                session.copy(
                    endedAtEpochMs = System.currentTimeMillis(),
                    finishStateLabel = finishLabel,
                ),
            )
        }

    suspend fun recordAttempt(
        sessionId: Long,
        passageDbId: Long,
        setting: PracticeSetting,
        baseTempoBpm: Double,
        result: AttemptResult,
        progress: PassageProgress,
    ) = withContext(Dispatchers.IO) {
        db.passageAttemptDao().insert(
            PassageAttemptEntity(
                sessionId = sessionId,
                passageId = passageDbId,
                startedAtEpochMs = System.currentTimeMillis(),
                tempoBpm = (baseTempoBpm * setting.tempoMultiplier).toInt(),
                tempoMultiplier = setting.tempoMultiplier.toFloat(),
                accuracyPercent = result.accuracyPercent,
                notesHit = result.hits,
                notesMissed = result.misses,
                notesExtra = result.extras,
                avgAbsErrorMs = result.avgAbsErrMs,
                meanSignedErrorMs = result.meanSignedErrMs,
                leftHandAccuracy = result.leftAccuracy,
                rightHandAccuracy = result.rightAccuracy,
                handMode = handModeName(setting.hand),
                waitMode = setting.wait,
            ),
        )
        db.srsCardDao().upsert(
            SrsCardEntity(
                id = db.srsCardDao().forPassage(passageDbId)?.id ?: 0,
                passageId = passageDbId,
                stability = progress.stability,
                difficulty = progress.fsrsDifficulty,
                reps = progress.attempts,
                lapses = 0,
                lastReviewedAtEpochMs = progress.lastReviewMs,
                dueAtEpochMs = progress.dueAtMs,
            ),
        )
    }

    /** Full attempt history as §8a profile records (skills come from passage tags). */
    suspend fun loadAttemptRecords(): List<LearnerProfile.AttemptRecord> =
        withContext(Dispatchers.IO) {
            val skillsByPassage = db.passageDao().all().associate { p ->
                p.id to p.skillTags.split(',').filter { it.isNotBlank() }.toSet()
            }
            db.passageAttemptDao().all().map { a ->
                LearnerProfile.AttemptRecord(
                    skills = skillsByPassage[a.passageId] ?: emptySet(),
                    accuracyPercent = a.accuracyPercent,
                    meanSignedErrMs = a.meanSignedErrorMs,
                    leftAccuracy = a.leftHandAccuracy,
                    rightAccuracy = a.rightHandAccuracy,
                    epochMs = a.startedAtEpochMs,
                )
            }
        }

    /** Persists an attempt recording (SPEC §3). */
    suspend fun saveRecording(
        context: android.content.Context,
        sessionId: Long,
        passageDbId: Long,
        bytes: ByteArray,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        val dir = java.io.File(context.filesDir, "recordings").apply { mkdirs() }
        val file = java.io.File(dir, "rec_${System.currentTimeMillis()}_$passageDbId.bin")
        file.writeBytes(bytes)
        db.midiRecordingDao().insert(
            MidiRecordingEntity(
                sessionId = sessionId,
                passageId = passageDbId,
                filePath = file.absolutePath,
                recordedAtEpochMs = System.currentTimeMillis(),
                durationMs = durationMs,
            ),
        )
    }

    class RecordingPair(
        val label: String,
        val first: MidiRecordingEntity,
        val latest: MidiRecordingEntity,
    )

    /** Per passage with history: earliest vs latest recording (SPEC §3). */
    suspend fun beforeAfterPairs(): List<RecordingPair> = withContext(Dispatchers.IO) {
        val songs = db.songDao().all().associateBy { it.id }
        db.passageDao().all().mapNotNull { p ->
            val recs = db.midiRecordingDao().forPassage(p.id)
            if (recs.size < 2) return@mapNotNull null
            val song = songs[p.songId]?.title ?: "?"
            RecordingPair(
                label = "$song · bars ${p.startMeasure}–${p.endMeasure}",
                first = recs.first(),
                latest = recs.last(),
            )
        }
    }

    fun readRecording(entity: MidiRecordingEntity): List<RecordingCodec.Event> =
        runCatching {
            RecordingCodec.decode(java.io.File(entity.filePath).readBytes())
        }.getOrElse { emptyList() }

    /** Raw per-prompt foundations rows (SPEC §8a): aggregates derive from these. */
    suspend fun saveFoundationsTrials(
        dayIndex: Int,
        focusAtomId: String,
        results: List<com.fingerly.core.session.FoundationsTrainer.PromptResult>,
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        db.foundationsTrialDao().insertAll(
            results.map { r ->
                var octave = 0
                var neighbor = 0
                var other = 0
                for (wrong in r.wrongPresses) {
                    when (
                        com.fingerly.core.session.FoundationsTrainer
                            .classifyError(r.expectedNote, wrong)
                    ) {
                        com.fingerly.core.session.FoundationsTrainer.ERROR_OCTAVE -> octave++
                        com.fingerly.core.session.FoundationsTrainer.ERROR_NEIGHBOR -> neighbor++
                        else -> other++
                    }
                }
                FoundationsTrialEntity(
                    atomId = r.atomId,
                    focusAtomId = focusAtomId,
                    atEpochMs = now,
                    dayIndex = dayIndex,
                    expectedNote = r.expectedNote,
                    unaided = r.unaided,
                    revealed = r.revealed,
                    demonstrated = r.demonstrated,
                    latencyMs = r.latencyMs,
                    wrongPressCount = r.wrongPresses.size,
                    octaveErrors = octave,
                    neighborErrors = neighbor,
                    otherErrors = other,
                )
            },
        )
    }

    suspend fun foundationsTrials(): List<FoundationsTrialEntity> =
        withContext(Dispatchers.IO) { db.foundationsTrialDao().all() }

    suspend fun getSetting(key: String): String? =
        withContext(Dispatchers.IO) { db.appSettingDao().get(key) }

    suspend fun putSetting(key: String, value: String) =
        withContext(Dispatchers.IO) { db.appSettingDao().put(AppSettingEntity(key, value)) }

    suspend fun allSongs(): List<SongEntity> =
        withContext(Dispatchers.IO) { db.songDao().all() }

    suspend fun recentSessions(limit: Int = 10): List<PracticeSessionEntity> =
        withContext(Dispatchers.IO) { db.practiceSessionDao().recent(limit) }

    suspend fun dueReviewCount(): Int = withContext(Dispatchers.IO) {
        db.srsCardDao().due(System.currentTimeMillis()).size
    }

    private fun handModeName(hand: Int): String = when (hand) {
        ChartNote.HAND_RIGHT -> "RIGHT"
        ChartNote.HAND_LEFT -> "LEFT"
        HAND_BOTH -> "BOTH"
        else -> "BOTH"
    }

    /** The ladder rung an old attempt ran at, reconstructed from its stored setting. */
    private fun ladderIndexOf(a: PassageAttemptEntity): Int = when {
        a.handMode == "BOTH" && a.tempoMultiplier >= 0.99f -> 0
        a.handMode == "BOTH" && a.tempoMultiplier >= 0.84f -> 1
        a.handMode == "BOTH" && a.tempoMultiplier >= 0.69f -> 2
        a.handMode == "BOTH" -> 3
        a.waitMode -> 9 // wait rungs; exact hand/bars nuance not needed for resume
        a.tempoMultiplier >= 0.69f -> 7
        else -> 8
    }
}
