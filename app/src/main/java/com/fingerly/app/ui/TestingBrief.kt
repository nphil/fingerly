package com.fingerly.app.ui

/**
 * What this build needs from the learner, in the app rather than in chat.
 *
 * Blunt and mechanical (SPEC §2.8): what to do, what it feeds, how long, and
 * whether logging needs to be on. No encouragement.
 *
 * VOCABULARY: this file is learner-facing, so it obeys the first-run ledger —
 * *chart*, *mark*, *place*, *right keys*, *unseen read*, *solid*, *session*.
 * The terms it used to carry (staff, treble G, bass F, cold read, criterion,
 * pitches, sitting) are all things the app never actually teaches him.
 *
 * UPDATE THIS EVERY BUILD THAT CHANGES THE ASK. A stale brief is worse than
 * none, because it will be believed.
 */
object TestingBrief {

    /** Shown so a stale brief is visible as stale. */
    const val BUILD = "F6 — rebuilt first run, 15 steps, black keys before letters"

    /** The concrete ask. One line each, imperative, no preamble. */
    val doThis = listOf(
        "Do the first run once, all the way through. Fifteen steps, about six minutes, " +
            "each one waits for you to actually press a key. Stop at any point and it " +
            "resumes where the last block ended.",
        "After that, one unseen read per session: four marks you have not seen before. " +
            "Getting them wrong is the normal result and costs nothing.",
        "Then one or two drills. Stop when the app says done for today.",
        "Most days beats long days. Spacing is the mechanism — a marathon session does " +
            "nothing a short one would not.",
    )

    /** What each thing the learner does actually decides. */
    val feeds = listOf(
        "The first run decides whether the approach is teachable at all. If you reach the " +
            "end and can find middle C unaided, the module has a foundation. If you cannot, " +
            "the script is wrong and gets rebuilt — you are not asked to try harder.",
        "Unseen reads decide whether the drills survive. If getting keys solid does not " +
            "predict your reading, the drills get deleted rather than added to.",
        "Drill logs tune how fast the help fades and how long the app waits before showing " +
            "an answer.",
        "If the unseen read is still zero after a week, the excerpts are too hard and the " +
            "floor gets lowered again.",
    )

    const val howLong =
        "Six minutes for the first run, once. Then 10–15 minutes a session. About two " +
            "weeks before the reading number means anything, because one read is noise and " +
            "the slope is the point."

    const val logsWhy =
        "Logging is ON by default — you should not have to remember to switch on the only " +
            "channel the build has for seeing what happened. Results are stored on the " +
            "tablet either way."

    /** The one thing currently blocked on the learner, or null when nothing is. */
    const val blocking =
        "Blocked on you: whether the fifteen-step first run makes sense end to end with " +
            "nobody explaining it, and which step you would put the tablet down on."
}
