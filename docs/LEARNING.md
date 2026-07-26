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

### The scaffold is a continuum that must reach zero (F2)

`Prompt.demonstrate` was a one-shot boolean: shown on first sighting, gone
forever after. That is a cliff, not a fade, and it left condition 4 of the
definition of done — "no atom is above `scaffoldAlpha = 0` at the time of the
read" — with nothing to evaluate, because no such number existed outside the
docs.

`AtomStats.scaffoldAlpha` is now that number: 1 = full help, 0 = none, and it
drives every aid there is. At 1 the answer is shown outright. Below 1 the reveal
arrives proportionally sooner than the configured hold. At 0 nothing is shown at
all — no reveal, no landmark tint, and not even the middle-C dot, which was
drawn unconditionally and would otherwise have made condition 4 permanently
unsatisfiable.

Two rules keep the fade honest:
- It moves **only on unaided success**. Handing help back after a failure makes
  the scaffold a reward for getting things wrong, and the fade would never
  terminate.
- `mastered()` requires `scaffoldAlpha <= 0`. An atom cannot be certified while
  the app is still helping with it — which was exactly the defect that let the
  old trainer certify visual tracking.

### The span: read every position the excerpts actually use

Three landmarks are anchors, not a vocabulary. The cold-read excerpts use
fifteen diatonic positions across G2–G4, and a unit test asserts that every
pitch appearing in any excerpt is one the `span-read` atom trains — so the
falsification check can never be run against material the module never taught.

Clef follows the pitch rather than a coin flip: below middle C reads in bass,
above it in treble, exactly as it will in the excerpts. Middle C alone keeps its
alternation, because being the same line from either side is the whole reason it
is the anchor.

### Hands together, and why one hand cannot fake it (F5)

The simplified axis here is "how many notes at once" — two — with no rhythm to
track and no sequence to remember. Both notes are emitted as chart notes at the
**same instant**, so wait mode holds until both have landed; a prompt cannot be
retired by playing half of it.

The pair is always at least 14 semitones apart, enforced by `pairFor` rather
than trusted to the pitch pools. MIDI carries pitch, not hands: a closer pair
could have been played with one hand, and certifying that would repeat exactly
the mistake that deleted the fingering atoms.

One consequence worth remembering: **prompt index and chart index are no longer
the same number.** Every per-prompt measurement — reveal, latency, wrong presses,
scaffold level — is keyed by chart index, so `promptChartStarts` maps between
them and the drill screen scores the whole block. A hands-together prompt's
latency is the slower of the two notes, because it is not finished until both
have arrived.

### The home screen is one action, not a dashboard

The map opened onto thirteen rows reading "0/3 today · 0/3 days" with no legend,
then two or three buttons. That is a decision menu before playing (SPEC §2), and
a progress display nobody can decode is worse than none — it looks like
information and carries none.

What ships instead: the app picks the single next thing and states it. A title
saying what you are about to do, one sentence of what it is, one button with the
cost on it ("Start · 8 notes, about a minute"). Nothing else is a control.

Standing state is two dim lines beneath. The **cold read is the headline**,
because it is the only number on this screen that is not self-graded — a mastery
map built from atoms the app itself chose can look green while nothing transfers,
which is the documented hazard of self-developed instruments. The mastery rows
still exist, behind a "Detail" toggle, and only there do they get a legend
explaining what the counters mean.

The four states are: no piano (say so, no button), a cold read is due, a drill is
due, or nothing is due today — and the last one says why more today would not
help, rather than leaving an empty screen.

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

## The cold read is the instrument (SPEC §4a-F item F3)

Everything else in this module is a proxy. The mastery map counts unaided hits
on atoms the app itself chose, which means it can certify a skill that transfers
nowhere — exactly the defect that made the previous trainer measure visual
tracking. The cold read is the one measurement that cannot be drilled for,
because the excerpt has never been seen.

Its rules are what make it worth having, and each of them is load-bearing:

- **It runs first, every sitting, from the very first one** — before any of it
  is learnable. A probe introduced once the learner is good at the material
  measures nothing about the journey.
- **It gates nothing and unlocks nothing.** Failing costs nothing. The instant a
  measurement has consequences the learner optimises for it, and the number
  stops being evidence.
- **Unseen is enforced.** Excerpts are consumed by id and never re-served;
  exhaustion is surfaced rather than silently wrapping around.
- **It is played in time, not spelled out.** `waitMode` is off, so the clock
  runs — condition 3 of the definition of done is about timing, and a wait-mode
  read would satisfy it trivially and falsely.
- **No scaffold.** No falling notes, no lit keys, no landmark tint, no reveal.
  The only moving thing is a cursor marking read position, which is not a
  verdict. `scaffoldState` is logged with every row so condition 4 is checkable
  after the fact rather than assumed.

Density is graded across four tiers for one reason: if every excerpt were hard,
the probe could floor at zero for a month, and a floored instrument is
indistinguishable from atoms that do not transfer. That distinction is the
entire content of the falsification check, so the instrument must be able to
register partial credit before it is trusted to report none.

Hands-together credit counts only at onsets where the parts are ≥14 semitones
apart. MIDI carries pitch, not hands; anything closer could have been played
with one hand. Same reasoning that deleted the fingering atoms.

## Change log

- 2026-07 (F3): the cold read ships. `ExcerptBank` holds 20 four-bar excerpts in
  G2–G4 across four density tiers, written one token per beat; `Staff` gained a
  horizontal ruler so x-from-time and barline placement are unit-tested rather
  than eyeballed; `StaffRenderer.drawExcerpt` draws the pair as one braced
  system; `NoteHighwayView.excerptMode` runs it wait-free with no scaffold; and
  `foundations_probes` records pitch accuracy, timing error and its coverage,
  per-hand accuracy, separable hands-together onsets, and the scaffold state at
  read time. The dependent variable of the falsification check now exists and is
  being logged from the first sitting.

- 2026-07 (roadmap reconciliation, F6a): two multi-agent passes — an end-to-end
  audit of the beginner journey and a reconciliation of the roadmap against all
  129 verified findings — agreed on one structural defect. **The 80% cliff had
  been re-created inside the basics module**, three commits after `23738fc`
  engineered it out of `SessionEngine`: an all-or-nothing payoff weeks away
  (11 atoms × a ≥4-distinct-day floor each, plus 3 days of clean cold reads),
  and no payoff today, because `sittingFinishLabel()` rendered only when
  `previewDrill()` returned null — i.e. only if the learner exhausted every
  atom's quota in one sitting. Stopping early, which is what actually happens,
  produced no named finish at all, breaking SPEC §3.5 on the one path the
  learner is on. The finish label is now unconditional and is a COUNT of work
  done, never a within-session quality score (Karpicke & Roediger 2007;
  Papoušek et al. — within-session metrics actively mislead about retention).
  No points, badges or invented currency were added to compensate: Deci et al.
  puts engagement-contingent rewards at d = −0.40 and informational verbal
  feedback at d = +0.33.
  Shipped with it: the letter-name song gate is deleted outright. `songGateOpen()`
  keyed on find-C/find-F/find-G — the exact layer SPEC §4 calls "optional, late,
  never a gate" and §4a-F demotes to fading scaffold — and it re-asserted on
  every launch because the "Songs anyway" escape only mutated a remembered
  local. Basics are now the *default* content, not a lock; the song path is
  always one tap away and the choice persists. Resumption, not gating, is the
  best-evidenced UI finding in the record (Ghibellini & Meier, recall ratio 0.99
  across 37 studies: resumption holds, unfinished-progress framing does not).
  Two F1 defects fixed in the same pass — demonstrations were being reported as
  the learner's retrieval failures, and an unplugged piano was diagnosed as
  "0/8 from memory" after ~100s of untouchable screen.

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
