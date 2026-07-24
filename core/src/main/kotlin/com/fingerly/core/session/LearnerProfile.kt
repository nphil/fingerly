package com.fingerly.core.session

/**
 * The learner model (SPEC §8a): batch analysis of attempt history into a
 * per-skill/per-hand weakness profile. Pure and cheap (classical statistics) —
 * runs between sessions, never during play (SPEC §1).
 */
object LearnerProfile {

    /** One historical attempt with the skills its passage demanded. */
    class AttemptRecord(
        val skills: Set<String>,
        val accuracyPercent: Float,
        val meanSignedErrMs: Long,
        val leftAccuracy: Float, // -1 when absent
        val rightAccuracy: Float,
        val epochMs: Long,
    )

    class SkillStat {
        var ema: Float = -1f
        var attempts: Int = 0

        internal fun add(accuracy: Float) {
            ema = if (ema < 0) accuracy else ema * 0.7f + accuracy * 0.3f
            attempts++
        }
    }

    class Report(
        val skillStats: Map<String, SkillStat>,
        /** + = plays late, - = rushes. */
        val timingBiasMs: Long,
        val leftEma: Float,
        val rightEma: Float,
        val totalAttempts: Int,
    ) {
        /** Skills measurably below [threshold] with enough data to trust. */
        fun weakestSkills(minAttempts: Int = 3, threshold: Float = 85f): List<String> =
            skillStats.entries
                .filter { it.value.attempts >= minAttempts && it.value.ema in 0f..threshold }
                .sortedBy { it.value.ema }
                .map { it.key }

        /** Weaker hand by measured EMA; -1 accuracy means no data for a hand. */
        fun weakerHand(): Int = when {
            leftEma < 0 || leftEma <= rightEma -> com.fingerly.core.song.ChartNote.HAND_LEFT
            else -> com.fingerly.core.song.ChartNote.HAND_RIGHT
        }
    }

    fun analyze(records: List<AttemptRecord>): Report {
        val skills = HashMap<String, SkillStat>()
        var biasEma = 0.0
        var biasSeeded = false
        var leftEma = -1f
        var rightEma = -1f
        for (r in records.sortedBy { it.epochMs }) {
            for (s in r.skills) skills.getOrPut(s) { SkillStat() }.add(r.accuracyPercent)
            if (!biasSeeded) {
                biasEma = r.meanSignedErrMs.toDouble()
                biasSeeded = true
            } else {
                biasEma = biasEma * 0.7 + r.meanSignedErrMs * 0.3
            }
            if (r.leftAccuracy >= 0) {
                leftEma = if (leftEma < 0) r.leftAccuracy else leftEma * 0.7f + r.leftAccuracy * 0.3f
            }
            if (r.rightAccuracy >= 0) {
                rightEma = if (rightEma < 0) r.rightAccuracy else rightEma * 0.7f + r.rightAccuracy * 0.3f
            }
        }
        return Report(
            skillStats = skills,
            timingBiasMs = biasEma.toLong(),
            leftEma = leftEma,
            rightEma = rightEma,
            totalAttempts = records.size,
        )
    }
}
