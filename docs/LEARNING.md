# Fingerly Learning Architecture

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

- 2026-07: Initial architecture. Foundations trainer added after user
  feedback (basics gap); ladder restructured to single-axis steps after log
  analysis showed multi-axis promotion cliffs; promotion requires two
  consecutive clean reps after observed promote/fail ping-pong.
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
