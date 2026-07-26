# Fingerly Learning Architecture

> Evidence base: **`docs/RESEARCH.md`** (59 findings — ADHD, music learning,
> reinforcement) and **`docs/RESEARCH-GAMIFICATION.md`** (70 findings — game
> engagement mechanics, game feel, dark patterns, educational gamification).
> Each finding carries its citation, sample, effect size and an independent
> verifier's status. Only CONFIRMED findings at MODERATE or STRONG strength may
> drive a parameter change here.

## The 80% cliff is a scheduling property, not a personality trait

The single most useful thing either research pass produced. `SessionEngine`
used to select work as *the lowest-mastery passage in difficulty order*, so
passages were consumed easiest-first and the remaining work became
monotonically harder as the song filled in — the last 20% was, by
construction, the hardest 20%. Combined with an invisible denominator (no
"N of M passages" existed anywhere) and a song-completion reward sitting at
maximum delay-discount distance, that is precisely the shape that produces
abandonment near the end.

Fixes, shipped together:
- **Work proceeds in song order**, difficulty as a local tiebreak only. Marginal
  cost is now roughly flat across the song, the piece is learned as music, and
  SPEC §2.7's positional boss chapter becomes coherent.
- **A reachable finish line.** "Song complete" = every passage clean at
  `SESSION_TARGET_INDEX` (both hands, 55% tempo) — weeks away, not years.
  Performance tempo is a *separate named achievement*, never framed as
  unfinished business, so completion cannot be gamed by playing everything slowly.
- **Two clean reps to bank a rung.** One lucky 85% used to retire a passage
  permanently, which meant the laziest strategy that satisfied the completion
  metric was scraping 85% once per passage at half tempo.
- **A completion event**, so a finished song stops re-serving itself forever.

Two separate engines on a shared substrate. Keep this boundary: they model
different kinds of learning and are tuned by different research.

```
                    ┌─────────────────────────────┐
                    │  Measurement (HitJudge)      │  per-press truth:
                    │  accuracy · timing · hands   │  shared by everything
                    └──────┬───────────────┬──────┘
                           │               │
        ┌──────────────────▼──┐      ┌─────▼──────────────────┐
        │ FoundationsTrainer  │      │ SessionEngine           │
        │ basics acquisition  │      │ song learning           │
        │ (recall/recognition)│      │ (motor sequences)       │
        │ atoms · error       │      │ passages · difficulty   │
        │ taxonomy · mastery  │      │ ladder · FSRS · drills  │
        └──────────┬──────────┘      └─────┬──────────────────┘
                   │                       │
                    └──────┬───────────────┘
                           ▼
                 LearnerProfile (§8a)
                 per-skill EMAs · timing bias · hand asymmetry
                 batch-analyzed between sessions, never during play
```

## FoundationsTrainer (basics)

Modular by contract:
- **Tunables** live only in `FoundationsTrainer.Config` — injectable, one line
  per parameter, adjust from evidence.
- **Curriculum** is data: `AtomDef(id, label, introTip, promptNote)`. New skills
  (black keys, intervals, chords) are appended definitions, not engine edits.
- **Error taxonomy** (octave / neighbor / other per wrong press) selects
  targeted micro-tips; extend the taxonomy in `recordResults`.

### The load-bearing decision: it is a RECALL test

The prompt names a key ("Press F") and nothing on screen shows where it is —
no falling rectangle, no note name on the note, no pre-lit key, no white-key
letters. Only the middle-C dot stays as a fixed landmark. Retrieval practice
beats restudy at g = 0.50 (Rowland 2014, 159 comparisons, adults, cue→response
material), and the moderator that matters is that the benefit GROWS with
retrieval effort: recall > recognition. Before this change the drill drew the
answer at the target key's x-position with its name printed on it, so
"Find C" was passable by tracking a rectangle — the mastery map certified a
skill that was never tested.

Corrective feedback therefore comes only AFTER the attempt: hold 4s, then light
the anchoring black-key group (2-group for C/D/E, 3-group for F/G/A/B) — the
landmark rule shown, not restated as text. A revealed prompt earns nothing.

### Notation is the task, letter names are the scaffold (SPEC §4a-F)

The trainer's original shape — "press F", find the white key — trained an
intermediate representation that fluent readers bypass, and made note names the
gate that §4 says they must never be. The correction is not to bolt a reading
module on afterwards. Part-task training splits into fractionation, segmentation
and simplification, and fractionation produces **negative** transfer when the
components fire simultaneously in the whole task (Wickens et al. 2013). Piano is
the paradigm simultaneous-component task, so a "note names now, reading later"
module is the one shape the evidence forbids.

What ships instead: every drill is the real task — read a notated pitch, play it
— with one axis simplified to triviality. The first axis simplified is note
count. Three landmark atoms (`landmark-c4`, `landmark-g4`, `landmark-f3`) each
show a single notehead on a real five-line staff with a real clef, and nothing
in words. They are deliberately **first** in the curriculum list, because the
drill draws its focus and its padding from the front of that list: reading is
present in the very first drill of the very first sitting, not at 87.5% of the
module.

Two mechanical consequences worth knowing:
- A landmark is one fixed pitch, so it carries no octave ladder (`maxRung = 0`)
  and the "evidence beyond the home octave" clause in `mastered()` is waived for
  it — otherwise these atoms could never be completed at all.
- A staff position names exactly one pitch, so `matchAnyOctave` is always false
  for a staff prompt. The any-octave slack exists only because a worded "F"
  prompt would otherwise demand an octave digit the learner has not been taught.

Middle C alternates clef per prompt on purpose: the reason it is *the* anchor is
that it is the same ledger line seen from either side, and a prompt that only
ever arrives in treble never teaches that. For the same reason the renderer
draws the **whole grand staff**, always, with the staff being read at full
strength and the other dimmed. The middle-C tip says "the line between the two
staves"; drawing one staff made that sentence refer to nothing, which is worse
than saying nothing.

### Onboarding is a demonstration, not an explanation

§2 forbids every conventional option: no theory prerequisites, no text over
three sentences, no vague instructions, no menus before playing. What is left is
to show the answer once. The first time an atom is ever prompted
(`Prompt.demonstrate`), the key lights, the black-key landmark lights and the
note is drawn — the learner copies it. That prompt is scored as *revealed*, so
it banks no hit and earns no day credit; it is scaffolding, and the very next
prompt of that atom withholds everything again. This is the same
scaffold-and-fade mechanism F2's `scaffoldAlpha` generalizes, and it is why the
module needs no tutorial screen.

### Config parameters and their rationale

| Parameter | Default | Rationale |
|---|---|---|
| drillLength | 8 (~60s) | Short blocks; effort quantum stated on the button, because what bites in ADHD is perceived cost at the decision to start (Chong et al. 2023, within-subject drug crossover) |
| padCount | 3 | Success padding from the learner's strongest material — NOT interleaving (see below) |
| sessionCriterionHits | 3 | Rawson & Dunlosky 2011 (n = 533 adults) measured the optimum directly: 3 correct recalls in the initial session |
| postCriterionHitsPerDay / criterionDays | 1 / 3 | …then one cold success on each of 3 spaced later sessions |
| stepUpAt / stepDownBelow | 0.85 / 0.70 | Clamp the achieved success rate and let difficulty float (Wilson et al. 2019). Note honestly: analytic result, no human participants |
| minPromptsPerRungStep | 4 | Never step a rung on a 1-observation denominator; ADHD learning rates are low, so one good rep is not evidence (Sethi et al. 2018, Brain) |
| maxRung | 2 | Ladder = how many octaves are in the prompt pool (C4 → +C3 → +C5). C2 is where a beginner's hands never go |
| revealAfterMs / forceAdvanceAfterMs | 4000 / 12000 | Feedback after retrieval; and a hard escape so a key you cannot find can never freeze the run |
| errorDecay | 0.85 | An exponentially-decayed proportion of RECENT trials predicts better than cumulative counts (Galyardt & Goldin 2015) — lifetime counters fossilise week-one behaviour |

Mastery is **counted, not estimated**: 3 unaided hits (with at least one outside
the home octave) plus cold success on 3 distinct days. The previous EMA-based
criterion was degenerate — an all-correct EMA equals exactly 1.0, so the 0.92
threshold was never actually estimated and mastery collapsed to "6 correct
prompts in one massed block".

### What the evidence told us NOT to do

- **Do not interleave the letter atoms.** For word-like cue→response pairs
  interleaving is significantly negative (Brunmair & Richter 2019: 59 studies,
  238 effects, g = −0.39 favouring blocked), and the music-domain contextual-
  interference studies either fail or reverse. Near-blocked with light spacing
  (no atom repeated within 2 positions) is what ships.
- **Do not port FSRS to foundations.** Schedule shape is nearly irrelevant once
  retrieval success is equated (Karpicke & Bauernschmidt 2011), and expanding
  schedules actively lose at a 2-day delay (Karpicke & Roediger 2007). FSRS's 17
  weights come from ~220M reviews with no demonstrated per-user recovery from
  small histories. A distinct-days counter gets the benefit for two integers.
- **Do not drill past the session criterion.** Extra within-session reps had no
  effect on retention at 1 or 4 weeks, while redistributing the same reps across
  sessions nearly doubled 4-week performance (Rohrer & Taylor 2006). The app
  refuses further reps of a criterion item and says so bluntly.
- **No streaks, no variable-ratio celebration.** Steep age-invariant delay
  discounting (Jackson & MacKillop 2016, N = 3,913, d = 0.43) makes a streak a
  delayed reward whose loss is felt immediately. The claimed partial-
  reinforcement resilience did not replicate in ADHD (Hulsbosch et al.).
- **No live evaluative HUD during a drill.** Adults with ADHD learned WORSE
  under immediate evaluative feedback than with a 3–6s delay (Gabay et al.
  2018, r = −0.38 to −0.39 with symptom severity), and a visibly falling
  accuracy percent is loss-framed (Aster et al. 2024). The drill shows only a
  depleting prompt bar; the numbers come after.
- **No fingering atoms.** MIDI carries pitch, not fingers, so "left little
  finger on C3" could be scored 100% with one index finger. Certifying an
  unobserved skill is exactly the subjective/objective dissociation that makes
  self-report untrustworthy (Low et al. 2018). Hand position becomes a one-time
  animation with no ✓ attached.
- **No fatigue detector, no background noise track, no placement test, no
  cumulative-minutes metric** — each rests on contradictory, children-only, or
  small-n evidence. Details in the workflow record.

Design choices from ADHD research that DID survive:
- Mastery map is the home view (big picture first; externalised progress).
- Identical drill structure every time (predictability).
- Monotone gain-framed counters ("3/3 today · 2/3 days") that never drop.
- Named finish state as a COUNT, not a clock — the adult time-on-task evidence
  is contradictory (Tucha et al. found steeper decline over 20 min; Fuermaier et
  al., 8 instruments, found no differential effect), which licenses no hardcoded
  session cap.

## SessionEngine (songs)

- **85% target band** (step down <70, up ≥92 with 2-clean-streak): Wilson et
  al. 2019, "The Eighty Five Percent Rule for optimal learning".
- **Difficulty ladder**: single-axis steps (tempo → hands → bars), wait-mode
  rungs before timed rungs = scaffolding & fading; desirable difficulties
  (Bjork) on the way up.
- **FSRS-4.5** scheduling with measured grades (SPEC §3): failures resurface
  in hours, cleans space out along the forgetting curve. Weights are the
  published defaults; §8a personalization refits them from this learner's
  history (motor skills decay slower than facts — expect stability inflation).
- **Deliberate practice** (Ericsson): micro-passages, immediate objective
  feedback, always at the edge, weakest material first.

## Improvement workflow

1. Find a paper / observe a pattern in the practice logs.
2. Locate the tunable (Config field, ladder shape, FSRS weights, taxonomy).
3. Change it in ONE place; unit tests pin behavior; ship; watch the logs.
4. Record the change and its rationale in this file.

## Change log

- 2026-07 (fundamentals module, F1): staff rendering shipped. `core/notation/Staff`
  computes diatonic staff positions and ledger lines as pure, unit-tested math;
  `StaffRenderer` turns them into pixels with four Bravura glyphs (SMuFL, SIL
  OFL). Three landmark atoms now pose their prompt as notation with no words,
  and they lead the curriculum so reading is present from sitting one. Two
  engine changes were required and are load-bearing: per-atom ladder ceilings
  (`AtomStats.maxRung`, seeded from the curriculum after deserialization so a
  saved blob cannot resurrect an old ladder shape) and the waiver of the
  beyond-home-octave mastery clause for pinned-pitch atoms.
- 2026-07: Initial architecture. Foundations trainer added after user
  feedback (basics gap); ladder restructured to single-axis steps after log
  analysis showed multi-axis promotion cliffs; promotion requires two
  consecutive clean reps after observed promote/fail ping-pong.
- 2026-07 (gamification review): 70-finding review of game engagement mechanics
  reconciled against the ADHD record. Net result was mostly *deletions and
  scheduling fixes*, not added reward layers — consistent with Sailer & Homner
  2020, where gamification's cognitive effect survives methodological rigour
  while the motivational effect does not. Shipped: song-order work selection
  (the 80% cliff), reachable finish line, two-clean-reps-to-bank, extension
  counting, live HUD removed from real practice, honest timing coverage, and
  two hot-path defects fixed. Streaks, variable-ratio celebration, hidden
  rubber-banding, energy systems, loot-box reveal ceremony and screen shake on
  the reading surface were all rejected — see RESEARCH-GAMIFICATION.md.
- 2026-07 (evidence review): foundations rebuilt as a recall test after a
  literature review found the drill was measuring visual tracking, not recall.
  Counted criterion replaced the degenerate EMA estimator; octave ladder added;
  interleaving and FSRS-for-basics explicitly rejected on evidence; live HUD
  suppressed during drills; fingering atoms deleted as unmeasurable; per-trial
  rows now logged to `foundations_trials` so every threshold here can be
  re-tuned against real history. Four measurement bugs fixed in the same pass:
  wrong presses were attributed to the NEXT prompt, unmeasured latency of 0ms
  counted as instant, the checkpoint's integer division made the pass mark
  14/14, and wait mode could freeze forever on a key the learner could not find.
