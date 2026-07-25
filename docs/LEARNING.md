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

### Config parameters and their rationale

| Parameter | Default | Rationale |
|---|---|---|
| drillLength | 8 (~45s) | ADHD literature: short task blocks with timers/checklists sustain attention (Baylor thesis; MTNA strategies) |
| strongPadCount | 3 | Interleaving + high success rate protects engagement (gamification-for-ADHD reviews: dynamic difficulty, frequent wins) |
| masteryAcc / masteryLatMs | 0.92 / 2500ms | Mastery = fluent retrieval, not slow correctness (retrieval-fluency literature); latency is the automaticity signal |
| emaAlpha | 0.25 | Recency-weighted evidence; small enough that one bad drill doesn't erase mastery |
| testPassPercent | 93 | Checkpoint must demonstrate near-ceiling mixed recall |
| sittingDrillCap | 5 | Suggested stopping point: bounded sessions, hard finish states (SPEC §2.7/§3) |

Design choices from ADHD research applied here:
- Mastery map ALWAYS visible first (big-picture-first; externalized progress).
- Identical drill structure every time (predictability).
- Gentle in-the-moment feedback; teaching happens in between-drill summaries —
  adults with ADHD learned probabilistic tasks better when learning load sits
  on delayed/summary feedback (Nature Sci. Reports 2018, feedback timing).
- Named stopping points, never open-ended (SPEC §2.7).

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
