package com.fingerly.app.ui

/**
 * What this build needs from the learner, in the app rather than in chat.
 *
 * The learner keeps having to ask "what am I testing, for how long, and does it
 * matter?" — which means the app was not carrying it. It is deliberately blunt
 * and mechanical (SPEC §2.8): what to do, what it feeds, how long, and whether
 * logging needs to be on. No encouragement, no "you're doing great".
 *
 * UPDATE THIS EVERY BUILD THAT CHANGES THE ASK. A stale brief is worse than
 * none, because it will be believed.
 */
object TestingBrief {

    /** Shown so a stale brief is visible as stale. */
    const val BUILD = "F5d — foundations only, LP-380U keyboard map, logging on by default"

    /** The concrete ask. One line each, imperative, no preamble. */
    val doThis = listOf(
        "First run walks you through finding middle C, treble G and bass F on your own piano. Five steps, each one waits for you to actually press the key. Redo it any time from Basics.",
        "Set your hands from the picture: right thumb on middle C, one finger per white key.",
        "One cold read per sitting. The first ones are four long notes on the landmarks, so you should be able to get some of them. Expect to miss plenty.",
        "Then one or two drills. Stop when the app says done for today.",
        "Most days beats long days. Spacing is the mechanism; a marathon session does nothing a short one would not.",
    )

    /** What each thing the learner does actually decides. */
    val feeds = listOf(
        "Cold reads decide whether the drills survive. If bringing keys to criterion does not predict your reading, the drills get deleted rather than added to.",
        "Drill logs tune how fast the help fades and how long the app waits before showing an answer.",
        "Hands-together prompts are new — whether they are playable at all decides if they stay.",
        "If the cold read is still zero after a week of sittings, the excerpts are too hard and the floor gets lowered again, not you pushed harder.",
    )

    const val howLong =
        "10–15 minutes a sitting. About two weeks of sittings before the reading number means anything, " +
            "because one read is noise and the whole point is the slope."

    const val logsWhy =
        "Logging is ON by default now — you should not have to remember to switch on the only channel " +
            "the build has for seeing what happened. Results are stored on the tablet either way."

    /** The one thing currently blocked on the learner, or null when nothing is. */
    const val blocking =
        "Blocked on you: whether the first cold reads score above zero, whether hands-together prompts are " +
            "physically playable, and whether this screen explains itself without being told."
}
