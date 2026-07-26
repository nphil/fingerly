package com.fingerly.core.notation

/**
 * Where things are on an 88-key piano, as pure testable arithmetic.
 *
 * The first-run script teaches the keyboard by its black-key grouping rather
 * than by letter names, so every rule it states — "the middle group has three
 * groups either side", "the white key left of a pair is a C", "that is three
 * white keys left" — has to be exactly true of the real layout. Stating one of
 * them wrongly to a learner who cannot yet check it is the worst failure
 * available here, so they live here and are unit-tested.
 *
 * The instrument is a KORG LP-380U: 88 keys, A0–C8, confirmed from Korg's
 * published specification.
 */
object KeyGeography {

    const val LOWEST = 21 // A0
    const val HIGHEST = 108 // C8
    const val MIDDLE_C = 60

    /** Semitone offsets within an octave that are black keys. */
    private val BLACK = booleanArrayOf(
        false, true, false, true, false, false, true, false, true, false, true, false,
    )

    fun isBlack(midi: Int): Boolean = BLACK[midi % 12]

    /** Count of white keys on an 88-key board. */
    fun whiteKeyCount(): Int = (LOWEST..HIGHEST).count { !isBlack(it) }

    /** Index of [midi] among the white keys, 0-based; -1 for a black key. */
    fun whiteIndex(midi: Int): Int {
        if (isBlack(midi)) return -1
        var n = 0
        for (m in LOWEST until midi) if (!isBlack(m)) n++
        return n
    }

    /**
     * Signed distance in WHITE keys from [from] to [to] — the only unit wrong
     * presses are ever reported in, because it is the one a beginner can count
     * without knowing a single letter name. Black keys resolve to the white key
     * immediately below them.
     */
    fun whiteKeyDelta(from: Int, to: Int): Int =
        whiteIndex(whiteBelow(to)) - whiteIndex(whiteBelow(from))

    private fun whiteBelow(midi: Int): Int = if (isBlack(midi)) midi - 1 else midi

    // ------------------------------------------------------------ black groups

    /** True when [midi] is a black key belonging to a group of two. */
    fun inPairGroup(midi: Int): Boolean = isBlack(midi) && (midi % 12 == 1 || midi % 12 == 3)

    /** True when [midi] is a black key belonging to a group of three. */
    fun inTripleGroup(midi: Int): Boolean =
        isBlack(midi) && (midi % 12 == 6 || midi % 12 == 8 || midi % 12 == 10)

    /**
     * Which group of two [midi] belongs to, counted from the left starting at 0,
     * or -1. A0's lone black key (A♯0) belongs to no complete group at all —
     * the script points at it and tells the learner to ignore it, so it must not
     * silently count as either kind.
     */
    fun pairGroupIndex(midi: Int): Int {
        if (!inPairGroup(midi)) return -1
        // The lowest complete pair on this board is C#1/D#1, and it is group 0,
        // so the script's "three groups either side" counts from a real group.
        return (midi / 12) - 2
    }

    fun tripleGroupIndex(midi: Int): Int {
        if (!inTripleGroup(midi)) return -1
        return (midi / 12) - 1
    }

    /** The lone black key at the bottom of the board, part of no group. */
    fun isOrphanBlackKey(midi: Int): Boolean = midi == 22 // A#0

    /** Complete groups of two on this board. The script says "seven". */
    fun completePairGroups(): Int =
        (LOWEST..HIGHEST).filter { inPairGroup(it) }
            .groupBy { pairGroupIndex(it) }
            .count { it.value.size == 2 }

    fun completeTripleGroups(): Int =
        (LOWEST..HIGHEST).filter { inTripleGroup(it) }
            .groupBy { tripleGroupIndex(it) }
            .count { it.value.size == 3 }

    /**
     * The pair group middle C sits against. The script's locating rule is
     * "three groups to its left and three to its right", so this must be the
     * exact middle of [completePairGroups].
     */
    fun middleCPairGroupIndex(): Int = pairGroupIndex(MIDDLE_C + 1)

    /** Every C on the board. The script says "eight". */
    fun allCs(): List<Int> = (LOWEST..HIGHEST).filter { it % 12 == 0 }

    /** The five white keys under a right hand in C position. */
    val C_POSITION = intArrayOf(60, 62, 64, 65, 67)
}
