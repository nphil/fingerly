package com.fingerly.core.session

import com.fingerly.core.song.ChartNote

/** Measured outcome of one practice run. All values come from [HitJudge]-style measurement. */
class AttemptResult(
    val accuracyPercent: Float,
    val hits: Int,
    val misses: Int,
    val extras: Int,
    val avgAbsErrMs: Long,
    val meanSignedErrMs: Long, // + = late, - = early
    val leftAccuracy: Float, // -1 if no left-hand notes in the run
    val rightAccuracy: Float, // -1 if no right-hand notes in the run
)

/** Per-passage learning state, persisted between sessions. */
class PassageProgress(
    var attempts: Int = 0,
    var emaAccuracy: Float = -1f,
    /** Lowest (hardest) ladder index ever passed clean; MAX_VALUE = never. */
    var bestCleanIndex: Int = Int.MAX_VALUE,
    var intervalDays: Double = 0.0,
    var dueAtMs: Long = 0L,
    var leftEma: Float = -1f,
    var rightEma: Float = -1f,
    /** FSRS memory state (SPEC §3); 0 stability = never reviewed. */
    var stability: Double = 0.0,
    var fsrsDifficulty: Double = 0.0,
    var lastReviewMs: Long = 0L,
) {
    /** Consecutive clean reps at [pendingCleanIndex]; two are required to bank it. */
    var pendingCleanIndex: Int = Int.MAX_VALUE
    var pendingCleanCount: Int = 0

    fun record(result: AttemptResult, ladderIndex: Int) {
        attempts++
        emaAccuracy = ema(emaAccuracy, result.accuracyPercent)
        if (result.leftAccuracy >= 0) leftEma = ema(leftEma, result.leftAccuracy)
        if (result.rightAccuracy >= 0) rightEma = ema(rightEma, result.rightAccuracy)
        // Banking a rung permanently retires this passage from the work queue,
        // so it must not be purchasable with one lucky rep: require two clean
        // reps at the same rung. (Promotion already required two; clearing did
        // not, which let a single 85% at half tempo mark a passage done.)
        if (result.accuracyPercent >= AutoDifficulty.CLEAN_AT) {
            if (ladderIndex == pendingCleanIndex) {
                pendingCleanCount++
            } else {
                pendingCleanIndex = ladderIndex
                pendingCleanCount = 1
            }
            if (pendingCleanCount >= CLEAN_REPS_TO_BANK && ladderIndex < bestCleanIndex) {
                bestCleanIndex = ladderIndex
            }
        } else if (ladderIndex == pendingCleanIndex) {
            pendingCleanCount = 0
        }
    }

    /** The hand measured weaker so far; defaults to LEFT with no data (SPEC §2: beginners). */
    fun weakerHand(): Int = when {
        leftEma < 0 && rightEma < 0 -> ChartNote.HAND_LEFT
        leftEma < 0 -> ChartNote.HAND_LEFT
        rightEma < 0 -> ChartNote.HAND_RIGHT
        leftEma <= rightEma -> ChartNote.HAND_LEFT
        else -> ChartNote.HAND_RIGHT
    }

    private fun ema(prev: Float, value: Float): Float =
        if (prev < 0) value else prev * 0.6f + value * 0.4f

    companion object {
        /** Session goal rung: clean at ≤ this index counts as today's mastery. */
        const val SESSION_TARGET_INDEX = 3 // both hands, slow (see AutoDifficulty.ladder)

        /** Clean reps at one rung needed before that rung is banked. */
        const val CLEAN_REPS_TO_BANK = 2
    }
}

/**
 * The practice session state machine (SPEC §3): warm-up → work zone → spaced
 * review → victory lap → hard, named finish state. Auto-started, no decision
 * menus; difficulty rides [AutoDifficulty]; what to practice next comes from
 * measured weakness (lowest mastery first, weaker hand practiced first).
 *
 * Pure logic with an injected clock — fully unit-testable.
 */
class SessionEngine(
    private val passages: List<Passage>,
    private val progress: MutableMap<Int, PassageProgress>,
    private val nowMs: () -> Long,
    /** §8a learner profile; enables just-in-time drills when present. */
    private val profile: LearnerProfile.Report? = null,
    private val warmupMs: Long = 2 * 60_000L,
    private val workMs: Long = 9 * 60_000L,
    private val reviewMs: Long = 3 * 60_000L,
) {

    enum class Phase { WARMUP, DRILL, WORK, REVIEW, VICTORY, DONE }

    class Step(
        val phase: Phase,
        val passage: Passage,
        val ladderIndex: Int,
        val setting: PracticeSetting,
        /** Why a drill exists, e.g. "Bars 5–8 needs: leaps · measured 62%" (SPEC §4). */
        val drillReason: String? = null,
    )

    private var phase = Phase.WARMUP
    private var phaseStartMs = 0L
    private var workPassage: Passage = passages.first()
    private var ladder: List<PracticeSetting> = emptyList()
    private var ladderIndex = 0
    private var reviewQueue = ArrayDeque<Passage>()
    private var currentReview: Passage? = null
    private var bestCleanLabel: String? = null
    private var totalAttempts = 0
    private var warmupAttempts = 0
    private var cleanStreak = 0
    private var drillReason: String? = null
    private var drilledPassageIds = HashSet<Int>()
    private var sittingStartMs = 0L
    private var bestCleanRung = Int.MAX_VALUE

    fun begin(): Step {
        phaseStartMs = nowMs()
        sittingStartMs = phaseStartMs
        // Warm-up: known material if any, else the easiest passage, at a rung
        // guaranteed to be comfortable. Zero decisions (SPEC §3).
        val warm = passages.minByOrNull { p ->
            val pr = progress[p.id]
            if (pr != null && pr.bestCleanIndex != Int.MAX_VALUE) {
                pr.bestCleanIndex - 100 // prefer mastered material
            } else {
                p.difficultyRank
            }
        } ?: passages.first()
        workPassage = warm
        ladder = AutoDifficulty.ladder(warm, progressOf(warm).weakerHand())
        ladderIndex = warmupIndexFor(warm)
        return currentStep()
    }

    fun current(): Step = currentStep()

    /** Feed one measured attempt; returns the next step (may change phase). */
    fun onAttempt(result: AttemptResult): Step {
        totalAttempts++
        val pr = progressOf(activePassage())
        pr.record(result, ladderIndex)
        // FSRS review with a measured grade (SPEC §3).
        val now = nowMs()
        val grade = Fsrs.gradeOf(result.accuracyPercent)
        val card = if (pr.stability <= 0.0) {
            Fsrs.initial(grade)
        } else {
            val elapsedDays = (now - pr.lastReviewMs) / 86_400_000.0
            Fsrs.review(Fsrs.Card(pr.stability, pr.fsrsDifficulty), grade, elapsedDays)
        }
        pr.stability = card.stability
        pr.fsrsDifficulty = card.difficulty
        pr.lastReviewMs = now
        pr.intervalDays = Fsrs.intervalDays(card)
        pr.dueAtMs = now + (pr.intervalDays * 86_400_000.0).toLong()
        if (result.accuracyPercent >= AutoDifficulty.CLEAN_AT) {
            recordFinishCandidate(activePassage(), ladder[ladderIndex], ladderIndex)
        }

        when (phase) {
            Phase.WARMUP -> {
                warmupAttempts++
                if (warmupAttempts >= 1 || elapsed() >= warmupMs) enterWork()
            }

            Phase.DRILL -> {
                // One drill rep, then the passage it exists for (SPEC §4),
                // back at its normal resume rung.
                drillReason = null
                phase = Phase.WORK
                selectWorkPassage()
            }

            Phase.WORK -> {
                // Promote only after two consecutive clean-plus reps: logs showed
                // a single lucky 100% causing a promote/fail ping-pong.
                if (result.accuracyPercent >= AutoDifficulty.STEP_UP_AT) {
                    cleanStreak++
                    if (cleanStreak >= 2) {
                        ladderIndex = (ladderIndex - 1).coerceAtLeast(0)
                        cleanStreak = 0
                    }
                } else {
                    cleanStreak = 0
                    ladderIndex = AutoDifficulty.adjust(
                        ladderIndex, ladder.lastIndex, result.accuracyPercent,
                    )
                }
                val masteredToday =
                    pr.bestCleanIndex <= PassageProgress.SESSION_TARGET_INDEX
                if (elapsed() >= workMs || (masteredToday && ladderIndex == 0)) enterReview()
            }

            Phase.REVIEW -> {
                if (reviewQueue.isEmpty() || elapsed() >= reviewMs) enterVictory()
                else currentReview = reviewQueue.removeFirst().also { setLadderFor(it, reviewIndexFor(it)) }
            }

            Phase.VICTORY -> {
                phase = Phase.DONE // one lap, always ends the session on success (SPEC §3)
            }

            Phase.DONE -> Unit
        }
        return currentStep()
    }

    /**
     * "I'm lost" (SPEC §2.2): decompose, never repeat — always steps at least
     * one rung easier immediately, no questions asked.
     */
    fun imLost(): Step {
        ladderIndex = (ladderIndex + 2).coerceAtMost(ladder.lastIndex)
        return currentStep()
    }

    /**
     * Optional "keep going?" after the finish state (SPEC §3). Counted, because
     * an unbounded extend button in front of a shrinking completion count is the
     * one genuine compulsion surface here (ADHD raises problematic-gaming risk).
     * The count and the wall-clock overrun are reported, never hidden.
     */
    fun extend(): Step {
        if (phase == Phase.DONE) {
            extensions++
            phase = Phase.WORK
            phaseStartMs = nowMs()
            selectWorkPassage()
        }
        return currentStep()
    }

    /** The hard, named finish state (SPEC §3). Blunt, metric, no cheerleading. */
    fun finishLabel(): String {
        val led = ledger()
        val base = bestCleanLabel
            ?: "$totalAttempts reps logged. No clean pass today. " +
            "${led.cleared} of ${led.total} passages."
        val overrun = if (extensions > 0) {
            "  ·  +$extensions extension${if (extensions > 1) "s" else ""}, " +
                "${elapsedSittingMs() / 60_000}min total"
        } else {
            ""
        }
        val done = when {
            led.completeAtTempo -> "  ·  SONG COMPLETE at performance tempo"
            led.complete -> "  ·  SONG COMPLETE — every passage clean. " +
                "Performance tempo is a separate ladder."
            else -> ""
        }
        return base + done + overrun
    }

    /** Live learning state for persistence. */
    fun progressFor(passageId: Int): PassageProgress? = progress[passageId]

    /**
     * How much of the song is finished, as a finite count (SPEC §4a/§2.7).
     * [cleared] counts passages clean at [PassageProgress.SESSION_TARGET_INDEX] —
     * the declared finish line, which is reachable in weeks. [atTempo] counts the
     * separate, also-named achievement of performance tempo, so "complete" is
     * never a euphemism for "played everything slowly".
     */
    class Ledger(val cleared: Int, val atTempo: Int, val total: Int) {
        val complete: Boolean get() = cleared >= total && total > 0
        val completeAtTempo: Boolean get() = atTempo >= total && total > 0
    }

    fun ledger(): Ledger = Ledger(
        cleared = passages.count {
            progressOf(it).bestCleanIndex <= PassageProgress.SESSION_TARGET_INDEX
        },
        atTempo = passages.count { progressOf(it).bestCleanIndex <= 0 },
        total = passages.size,
    )

    /** Times "keep going" was used this sitting — surfaced, never hidden. */
    var extensions = 0
        private set

    /** Wall-clock ms since the sitting began; the honest overrun signal. */
    fun elapsedSittingMs(): Long = nowMs() - sittingStartMs

    // ------------------------------------------------------------------ internals

    private fun activePassage(): Passage = when (phase) {
        Phase.REVIEW -> currentReview ?: workPassage
        else -> workPassage
    }

    private fun currentStep() =
        Step(phase, activePassage(), ladderIndex, ladder[ladderIndex], drillReason)

    private fun elapsed() = nowMs() - phaseStartMs

    private fun progressOf(p: Passage): PassageProgress =
        progress.getOrPut(p.id) { PassageProgress() }

    private fun enterWork() {
        phase = Phase.WORK
        phaseStartMs = nowMs()
        selectWorkPassage()
        maybeStartDrill()
    }

    /**
     * Just-in-time drill (SPEC §4): if the work passage demands a skill this
     * learner measurably struggles with, one focused rep at an easy rung comes
     * first — labeled with exactly why it exists. Never repeats per session.
     */
    private fun maybeStartDrill() {
        val report = profile ?: return
        if (workPassage.id in drilledPassageIds) return
        val weak = report.weakestSkills()
        val match = workPassage.skills.firstOrNull { it in weak } ?: return
        val stat = report.skillStats[match] ?: return
        drilledPassageIds.add(workPassage.id)
        drillReason = "Bars ${workPassage.startMeasure}–${workPassage.endMeasure} " +
            "needs: $match · you measure ${stat.ema.toInt()}% on it"
        phase = Phase.DRILL
        // Drill rung: the skill's content in wait mode — isolate the problem.
        setLadderFor(workPassage, Int.MAX_VALUE)
    }

    /**
     * Work target: the next un-cleared passage in SONG ORDER, with difficulty as
     * a local tiebreak only.
     *
     * This used to consume passages strictly easiest-first by difficultyRank,
     * which made the remaining work monotonically harder as the song filled in —
     * the last 20% was, by construction, the hardest 20%. That is the 80% cliff
     * as a scheduling property rather than a personality trait (SPEC §2.7).
     * Front-to-back keeps marginal cost roughly flat across the song, lets the
     * piece be learned as music rather than as a difficulty-sorted list, and
     * makes SPEC §2.7's positional boss chapter coherent.
     */
    private fun selectWorkPassage() {
        val remaining = passages.filter { p ->
            progressOf(p).bestCleanIndex > PassageProgress.SESSION_TARGET_INDEX
        }
        val target = if (remaining.isNotEmpty()) {
            // Song order; among passages starting in the same measure, easier first.
            remaining.minWithOrNull(
                compareBy({ it.startMeasure }, { it.difficultyRank }),
            )!!
        } else {
            // Song complete at criterion. Keep working the optional tempo ladder
            // on whatever is furthest from performance tempo, rather than
            // grinding one passage forever.
            passages.maxByOrNull { progressOf(it).bestCleanIndex }
                ?: passages.first()
        }
        workPassage = target
        val pr = progressOf(target)
        setLadderFor(
            target,
            when {
                // Fresh or never-clean material sits at the very easiest rung:
                // guaranteed early success, the ladder climbs from there
                // (SPEC §2.1/§3). MAX_VALUE is coerced to the ladder bottom.
                pr.attempts == 0 || pr.bestCleanIndex == Int.MAX_VALUE -> Int.MAX_VALUE
                // Otherwise exactly one rung harder than the best clean pass —
                // never a jump.
                else -> pr.bestCleanIndex - 1
            },
        )
    }

    private fun enterReview() {
        phase = Phase.REVIEW
        phaseStartMs = nowMs()
        val now = nowMs()
        reviewQueue = ArrayDeque(
            passages.filter { p ->
                p.id != workPassage.id && progressOf(p).attempts > 0 &&
                    progressOf(p).dueAtMs <= now
            }.sortedBy { progressOf(it).dueAtMs }.take(3),
        )
        if (reviewQueue.isEmpty()) {
            enterVictory()
        } else {
            currentReview = reviewQueue.removeFirst().also { setLadderFor(it, reviewIndexFor(it)) }
        }
    }

    private fun enterVictory() {
        phase = Phase.VICTORY
        phaseStartMs = nowMs()
        currentReview = null
        // Replay the best-mastered material one rung easier than its best —
        // every session ends on a success (SPEC §3).
        val best = passages.minByOrNull { p ->
            progressOf(p).let { if (it.bestCleanIndex == Int.MAX_VALUE) 1000 else it.bestCleanIndex }
        } ?: workPassage
        workPassage = best
        val idx = progressOf(best).bestCleanIndex
        setLadderFor(best, if (idx == Int.MAX_VALUE) AutoDifficulty.ladder(best, 0).lastIndex else (idx + 1))
    }

    private fun setLadderFor(p: Passage, index: Int) {
        ladder = AutoDifficulty.ladder(p, progressOf(p).weakerHand())
        ladderIndex = index.coerceIn(0, ladder.lastIndex)
        cleanStreak = 0
        if (phase == Phase.REVIEW) workPassage = workPassage // no-op, review uses currentReview
    }

    private fun reviewIndexFor(p: Passage): Int {
        val idx = progressOf(p).bestCleanIndex
        return if (idx == Int.MAX_VALUE) Int.MAX_VALUE else idx // MAX → easiest rung
    }

    private fun warmupIndexFor(p: Passage): Int {
        val pr = progressOf(p)
        return if (pr.bestCleanIndex == Int.MAX_VALUE) {
            AutoDifficulty.ladder(p, pr.weakerHand()).lastIndex // easiest rung
        } else {
            (pr.bestCleanIndex + 1).coerceAtMost(AutoDifficulty.ladder(p, pr.weakerHand()).lastIndex)
        }
    }

    private fun recordFinishCandidate(p: Passage, s: PracticeSetting, ladderIndex: Int) {
        val hand = when (s.hand) {
            ChartNote.HAND_RIGHT -> "right hand"
            ChartNote.HAND_LEFT -> "left hand"
            else -> "both hands"
        }
        // Keep the BEST clean pass of the session, not the last one. This
        // overwrote unconditionally, so the finish state reported whatever
        // happened to come last — often an easier rung than the day's best.
        if (ladderIndex > bestCleanRung) return
        bestCleanRung = ladderIndex
        val led = ledger()
        bestCleanLabel = "Bars ${p.startMeasure}–${p.startMeasure + s.bars - 1} · $hand · " +
            "${(s.tempoMultiplier * 100).toInt()}% tempo · clean ✓ · " +
            "${led.cleared} of ${led.total} passages"
    }
}

/** Blunt post-attempt diagnosis (SPEC §2.8): the dominant problem, stated plainly. */
object Diagnosis {
    fun of(result: AttemptResult): String {
        val issues = ArrayList<String>(3)
        if (result.extras > 0) issues.add("${result.extras} wrong notes")
        if (result.misses > 0) issues.add("${result.misses} missed")
        when {
            result.meanSignedErrMs > 35 -> issues.add("timing late avg ${result.meanSignedErrMs}ms")
            result.meanSignedErrMs < -35 -> issues.add("timing early avg ${-result.meanSignedErrMs}ms")
        }
        if (result.leftAccuracy in 0f..100f && result.rightAccuracy in 0f..100f &&
            result.rightAccuracy - result.leftAccuracy > 15f
        ) {
            issues.add("left hand ${result.leftAccuracy.toInt()}% vs right ${result.rightAccuracy.toInt()}%")
        }
        return if (issues.isEmpty()) {
            "Clean. ${result.hits} notes, avg err ${result.avgAbsErrMs}ms."
        } else {
            issues.joinToString(" · ")
        }
    }
}
