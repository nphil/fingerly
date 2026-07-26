# KeyQuest — Personal Piano Learning App (Working Title)

A single-user Android tablet app that teaches piano via USB MIDI, designed around one
specific learner's psychology and hardware. Not a commercial product. No accounts, no
backend, no analytics. Everything local.

---

## 1. Target Hardware & Platform (fixed, do not generalize)

- **Device**: Xiaomi Pad 8 Pro — Snapdragon 8 Elite, Adreno 830, 12GB RAM,
  11.2" 3200×2136 @ 144Hz panel (HyperOS caps non-game apps at 120Hz — 120Hz is
  the operating target), Android 16 / HyperOS 3, USB-C 3.2
- **Piano**: Digital piano over USB MIDI (class-compliant). The piano produces its own
  sound — the app does NOT synthesize audio for live playing.
- **Stack**: Kotlin, Jetpack Compose for menus/screens. Custom Canvas/GL render layer
  for the note highway + particles (Compose alone is not trusted for the hot path).
- **Storage**: Local SQLite (Room). MusicXML/MIDI files on local storage.

### Performance requirements (priority #1 — non-negotiable)
- Request and hold the highest available display mode explicitly
  (preferredDisplayModeId). HyperOS caps non-game apps at 120Hz, so **120Hz is the
  target**; take 144Hz only if the OS ever grants it. Never assume the OS picked it.
- Keypress → visual response: **< 15ms** (<2 frames @120Hz). Build a latency test
  screen into debug builds.
- **Zero-allocation hot path**: pre-allocated object pools for MIDI events, notes,
  particles. No GC pressure in the render loop or MIDI pipeline. Any per-frame
  allocation is a bug.
- MIDI events processed on their delivery thread, pushed to render loop via lock-free
  queue. MIDI data never touches the UI thread.
- Any visible stutter/jank is a release blocker, not a polish item.

### HyperOS gotchas (must handle)
- On first run, prompt user to exclude app from battery optimization (HyperOS
  throttles aggressively).
- HyperOS may lock third-party apps to 60Hz — request high refresh in code AND
  surface a first-run checklist item for the per-app refresh setting.

### Fullscreen game behavior
- Immersive sticky mode (hide system bars), landscape lock, keep-screen-on during
  sessions.
- Optional "Focus mode": request DND access, suppress notifications during practice.
- App opens directly into today's session — no menu navigation required to start
  (activation-energy design; see §3).

### Tablet audio (playback only, latency-insensitive)
- Piano soundfont playback for: demo of target passage, "listen before you play,"
  and replaying the user's own past recordings. Use a good free SF2/SFZ piano
  soundfont.

---

## 2. Learner Profile (drives all pedagogy & UX decisions)

The user is an engineer, complete musical beginner. Verified patterns:

1. **Builds first, understands second** — never gate playing behind theory. Hands on
   keys in minute one. Explain a concept only after he's already played it.
2. **One concept per exchange with a confirmation gate** — micro-lessons: single idea
   → checkpoint → advance. Plus a zero-friction **"I'm lost" button** that decomposes
   the current task into something smaller (slower tempo → one hand → fewer bars →
   single shape). Decompose, never repeat.
3. **Needs anchors in known concepts** — use engineering language: notation is a
   chart (pitch = y, time = x), intervals are deltas, practice is iterative tuning.
   Never "feel" language.
4. **Text fails at a threshold; visuals rescue** — any concept needing >3 sentences
   becomes an animation instead. Animated hand positions, visual chord shapes,
   animated step-throughs.
5. **Exact, verifiable instructions** — "thumb on middle C, finger 3 on E," never
   "find a comfortable position." All feedback verified against actual MIDI input.
6. **Real projects, not exercises** — song-first curriculum (§4). Every drill exists
   because a chosen song demands it, and the app says which song and where.
7. **The 80% cliff** — he abandons things at ~80% complete. Sessions are 10–15 min
   with a hard, named finish state. The final 20% of each song is explicitly
   gamified as a "completion boss fight."
8. **Blunt metrics, no cheerleading** — feedback is diagnostic: "Timing drifted on
   beats 2–4, left hand. Drill: 8 reps @ 50bpm." Progress = hard numbers (accuracy %,
   tempo, bars mastered). No "great job, almost there!" anywhere in the app. When
   something is genuinely hard (hand independence), state it plainly with a
   realistic timeline.

**Anti-patterns (design these OUT):** theory prerequisites, long text, vague
instructions, praise-based feedback, open-ended sessions, decision menus before
playing.

---

## 3. Practice Session Engine

Session = 10–15 min core, structured, auto-started:

1. **Warm-up (2 min)** — app opens straight into it. Zero decisions.
2. **Work zone (~8–10 min)** — ONE micro-passage (2–8 bars) from the current song.
   Difficulty auto-tuned to hold ~**85% success rate**: if accuracy drops, the
   engine slows tempo → splits hands → shrinks the passage, automatically. The user
   should never hit a wall.
3. **Spaced review (~3 min)** — passages resurfaced by an SRS scheduler (**FSRS**
   preferred, SM-2 acceptable for v1) adapted for motor skills: an item's "grade" =
   measured accuracy+tempo vs. target, not self-report.
4. **Victory lap (1 min)** — replay something already mastered. Every session ends
   on a success.
5. **Hard finish state** — session ends with a named, metric achievement:
   "4 bars clean @ 60bpm ✓" + reward moment (§6).

Optional "keep going?" extension after the finish state. Never open-ended by default.

**Momentum protection:**
- "Don't miss twice" logic instead of streaks. Missing one day triggers nothing; the
  app only flags a second consecutive miss.
- **2-minute rescue session** mode for busy days: one tiny passage, full completion
  ritual. Showing up tiny counts as a session.

**Progress evidence (fights "no visible progress"):**
- Record MIDI of every session. "You, 3 weeks ago vs. today" side-by-side playback
  per passage.
- Metrics dashboard: accuracy %, max clean tempo, bars mastered, per song. Numbers
  only, no vibes.

---

## 4. Curriculum: Song-First, Skills Backfilled

- User selects songs from the library. The **decomposition engine** parses MusicXML
  into micro-passages (2–8 bars), tags each with required skills (chord shapes, hand
  independence, jump patterns, rhythm figures), and orders them by dependency +
  difficulty.
- Fundamentals are taught **just-in-time**: a skill drill appears only when the next
  passage requires it, labeled with why ("Gymnopédie No. 3, bars 5–8 needs this
  left-hand jump").
- Difficulty analysis must warn honestly: tapping a song far beyond current level
  shows a blunt estimate ("realistically 12+ months away") rather than letting the
  user walk off a cliff.

### Notation Acquisition Module (no theory study, ever)
- Falling-note highway (Synthesia-style) AND synced sheet notation shown
  **simultaneously, always**.
- **Scaffold fade**: on passages already mastered, falling notes fade out over
  repetitions; notation remains. The symbol→movement link is built through the
  hands, on material with zero other cognitive load.
- Train **interval reading** (distances: "up 2, same, down 3" — deltas, not absolute
  note names). Note names/theory vocabulary are optional, late, never a gate.
- Notation rendered as what it is: a chart. Y = pitch, X = time.

---

## 5. Content Pipeline

- **Primary format: MusicXML** (structured notation — hands, measures, fingering).
  First-class import.
- **MIDI import**: supported with heuristics (pitch-range hand split, quantization);
  label results "approximate." Fine for note highway, lossy for notation.
- **Camera scanning (OMR): explicitly deferred to v2.** Not in scope.
- Bundled starter library (all public domain; source MusicXML from MuseScore
  community / Mutopia), difficulty-ordered:
  1. Satie — Gymnopédie No. 1
  2. Satie — Gymnopédie No. 3  ← user's primary goal piece
  3. Satie — Gnossienne No. 1
  4. Chopin — Prelude in A major, Op. 28 No. 7
  5. Chopin — Prelude in E minor, Op. 28 No. 4
  6. Chopin — Prelude in B minor, Op. 28 No. 6
  7. Bach — Prelude in C, BWV 846
  8. Chopin — Waltz in A minor, B. 150
  9. Pachelbel — Canon in D (simplified arrangement)
  10. Chopin — Mazurka in G minor, Op. 67 No. 2
  11. Beethoven — Moonlight Sonata, 1st mvt
  12. Chopin — Waltz Op. 69 No. 2
  13. Chopin — Nocturne Op. 9 No. 2
  14. Debussy — Clair de Lune
- User can import additional MusicXML/MIDI files from local storage at any time.

---

## 6. Reward & Visual System ("juice" is functional, not cosmetic)

Tiered celebration architecture; vary visuals per tier so rewards don't habituate:

- **Micro (constant)**: particle hits on correct notes, combo counter, screen-edge
  glow.
- **Session complete**: metric stinger — accuracy % and tempo rolling up like
  counters, finish state named on screen.
- **Major milestone** (passage mastered / tempo target / 80%-cliff boss defeated):
  full-screen WoW-style takeover — light column, sound stinger, and the soundtrack
  is a **playback of the user's own recorded performance** of that passage.
- The final 20% of every song = visible "boss fight" chapter with its own milestone
  ladder and distinct visual identity.
- Art direction: high-quality, game-grade visuals throughout. 120fps under full
  particle load is a requirement, not a target (§1).

---

## 7. Build Phases (implement strictly in order; each phase shippable & testable)

- **Phase 1 — Foundation**: USB MIDI connection + event pipeline (zero-alloc),
  latency test screen, 144Hz immersive shell, Room schema.
- **Phase 2 — Note highway**: custom render layer, MusicXML parsing, falling notes
  synced to one hardcoded piece, live hit/miss detection with metrics.
- **Phase 3 — Session engine**: session structure (§3), 85% auto-difficulty,
  decomposition engine, finish states, session recording.
- **Phase 4 — Curriculum & SRS**: full starter library, skill tagging, just-in-time
  drills, FSRS review scheduler, progress dashboard, before/after playback.
- **Phase 5 — Notation module**: synced sheet rendering, scaffold fade,
  interval-reading trainer.
- **Phase 6 — Juice**: reward tiers, boss-fight chapters, art pass, soundfont
  playback, focus mode, rescue sessions, "I'm lost" button polish.

**Acceptance gates**: Phase 1 = measured latency <15ms on device. Phase 2 = zero
dropped frames at 120Hz with 200 simultaneous particles (perf test). Phase 3+ =
each feature verified against §2 anti-patterns.

### Roadmap amendment, 2026-07 (user decision): fundamentals-first

Phases 1–3 shipped as written, and much of Phase 4 (FSRS, skill tagging, learner
profile, drills, dashboard, recordings, library + MusicXML import) is built. In
practice all of it sits idle, because the learner is blocked on basics and the
song path is gated behind them.

**Decision: complete the fundamentals module before any further breadth.** The
module (SPEC §4a-F, below) absorbs work previously scheduled as Phase 5
(notation) and adds rhythm, which no phase covered at all. Rationale:

- Building breadth while blocked on basics produces many 80%-done systems — the
  exact failure mode §2.7 exists to design around.
- Notation is a *fundamental*, not a later polish item. Training key-finding by
  letter name builds an intermediate representation that fluent readers bypass,
  and §4 already states note names must be "optional, late, never a gate" —
  which the current foundations trainer violates by making them the gate.
- Rhythm has never been addressed anywhere in this spec. It is not optional.

**Constraint on this decision:** the module must carry an enumerable definition
of done (§4a-F), or "complete the fundamentals" becomes its own open-ended
project. Deferred until it is met: bundled starter library expansion, just-in-time
drill polish, boss chapters, the preference model (§4a), and the Phase 6 art pass.

### Out of scope (do not build)
Accounts/cloud sync, social features, OMR scanning, audio synthesis for live play,
iOS, phone layouts, multiple user profiles.

---

## 8. Notes for Claude Code (token-efficient workflow)

- This file lives at `docs/SPEC.md`. Keep `CLAUDE.md` minimal: stack, perf rules
  from §1, anti-patterns from §2, and a pointer to this spec — that's what gets
  read every session.
- Work **one phase per session/branch-less push**. Reference spec sections by
  number instead of restating them.
- All decisions in this spec are final — do not re-explore architecture choices
  (render layer, format priority, SRS choice, scope cuts). Ask only if the spec is
  genuinely silent.
- Prefer editing files over regenerating them. Run the perf/latency acceptance
  gates before declaring a phase done.

---

## 4a. Musical Preference Model — "score DNA" (user-directed addition, 2026-07)

Motivation is a first-class learning variable (§2.6: real projects, not exercises).
The app learns what THIS user likes and steers the curriculum toward it.

**Feature extraction (deterministic, from parsed MusicXML — no ML needed):**
per piece compute a fixed vector: tempo, mode (major/minor), note density
(notes/sec), harmonic density (mean simultaneous notes), pitch range, mean
register, rhythmic regularity (inter-onset-interval variance), off-beat onset
fraction, mean melodic interval + leap fraction, chromaticism (non-scale-tone
fraction), self-similarity/repetition across bars, hand-texture class
(melody+accompaniment vs polyphonic).

**Preference capture:** swipe/Tinder-style rating screen. Each card plays a
representative ~20s excerpt (soundfont once §6 lands; demo synth before that).
Like / dislike / "love" only — no sliders, no questionnaires.

**Model:** online logistic regression over the standardized feature vector
(per-feature weights, SGD update per swipe). Tiny data by design: ~12 features,
usable after 20–40 swipes. Cold start uses uncertainty sampling — queue the
piece that best discriminates the currently least-certain weights.

**Readout (blunt, §2.8):** state preferences as measured weights, e.g.
"minor mode +1.8, sparse texture +1.4, fast tempo −2.1 — you like slow, spare,
minor-key writing." Never "you're a romantic soul."

**Use:** rank candidate pieces by predicted preference × learnability (difficulty
within reach of measured skill), so suggestions are songs he'd like AND could
play soon. Feeds §4's honest difficulty estimate rather than replacing it.

**Sourcing:** public-domain MusicXML (Mutopia, MuseScore community, IMSLP where
available). Automatic scraping is out of scope; the app suggests candidates and
the user imports via the existing MusicXML import flow (§5).

---

## 4a-F. Fundamentals Module — scope and definition of done (2026-07)

The roadmap amendment above makes this module the whole project until it ships.
Its constraint was an *enumerable* definition of done. This section is it.

### The governing rule

**Every drill in this module is a complete instance of the real task — read a
notated thing and play it in time — with ONE axis simplified to triviality.
Never a component removed.**

This is not stylistic. Part-task training splits three ways: *fractionation*
trains in isolation components normally performed together, *segmentation*
trains consecutive chunks, *simplification* trains the whole task with one
parameter reduced (Wightman & Lintern 1985). A meta-analysis of 37 transfer
studies found part-task training "generally produced negative transfer when the
parts were performed concurrently in the whole transfer task but not when the
parts were performed in sequence" (Wickens, Hutchins, Carolan & Cumming 2013).
Piano is a paradigm concurrent-component task — pitch, rhythm and two hands fire
at once. A conventional "fundamentals module" (note names here, rhythm there,
hands later) is fractionation of concurrent components: the case that result
singles out.

**The operational test.** The rule is unusable without one, because "one hand" is
fractionation under the taxonomy and this section licenses it. A drill is
SIMPLIFICATION if the whole loop — read a notated symbol → produce it on the
keyboard → in time — is intact and one parameter's *range* is reduced. It is
FRACTIONATION if any of the three links is absent. Under this test: one hand is
simplification (loop intact, hand count reduced to 1); rhythm on one fixed pitch
is simplification (loop intact, pitch range reduced to 1); clapping rhythms is
fractionation (no notehead → key production); note-name flashcards is
fractionation (no in-time production). Apply the test, not the label.

**Three conditions from the same abstract, all binding.**

1. **Simplification must be adaptive, never stepped.** "ID training was
   successful when the increases were implemented adaptively but not when
   increased in fixed steps." Independent support for closed-loop difficulty
   (§3); it forbids F4 shipping a fixed rhythm ladder. It says nothing about the
   85% set-point, which remains contested on its own evidence.

2. **Variable priority is licensed.** "Variable-priority training of the whole
   task was a successful technique … PTT can be successful if the integrated
   parts are varied in the priority they are given to the learner." Keep the
   complete task and shift which axis is scored — rhythm this pass, pitch the
   next. This removes nothing, so it needs no license; the only edit required is
   that "with ONE axis simplified to triviality" must not be read as *requiring*
   every drill to simplify something. An unsimplified whole-task block with
   varied scoring priority is legal.

3. **The penalty is smallest for beginners, so this is a default, not a law.**
   "Both strategies provided evidence that experienced learners benefited less,
   or suffered more, from the strategy." This user is a complete beginner — the
   population the evidence says part-task training harms least. The rule is
   applied here at the weakest point in the paper's own moderator space. It is a
   strong default that a measured result may override. It is never a reason not
   to measure.

> **Citation status: CONFIRMED · MODERATE as applied.** Wickens, C. D., Hutchins,
> S., Carolan, T., & Cumming, J. (2013). Effectiveness of part-task training and
> increasing-difficulty training strategies: A meta-analysis approach. *Human
> Factors*, 55(2), 461–470. doi:10.1177/0018720812451994, PMID 23691838.
> Verified 2026-07 against the NCBI record; every quotation above is verbatim
> from the published abstract. **The previous UNVERIFIED flag was a records
> error** — the finding had never been entered in `docs/RESEARCH.md`, and
> "absent from our notes" was read as "does not exist".
>
> Taxonomy attribution: Wightman, D. C., & Lintern, G. (1985). Part-task training
> for tracking and manual control. *Human Factors*, 27(3), 267–283.
> doi:10.1177/001872088502700304 — not Wickens, who inherits it.
>
> **Magnitude:** the full text is paywalled and only the abstract was obtained.
> Every result is directional, with no pooled effect size, no CI and no subgroup
> *k*. Cite the direction; never attach a number.
>
> **Domain, and the one in-domain study, which runs the other way.** This is a
> human-factors transfer literature. The one part-task study located in a music
> domain is Ash, D. W., & Holding, D. H. (1990). Backward versus forward chaining
> in the acquisition of a keyboard skill. *Human Factors*, 32(2), 139–146.
> doi:10.1177/001872089003200202 — a musical keyboard task in which both chaining
> (segmentation) methods beat whole-task training during training, on criterion
> trials, and at one-week retention, with forward chaining ahead of backward. It
> supports segmentation, and it does not support any claim that backward chaining
> is the stronger form. The step from tracking tasks to reading notation at a
> keyboard remains inference from shared task structure, and it is the weakest
> link in this rule.

Concretely: a drill may make the rhythm trivial (one note, held until played), or
the pitch set trivial (one landmark), or the hand count trivial (one hand). It may
not train "note names" as a thing detached from playing them, and it may not train
"clapping rhythms" as a thing detached from notes.

### The terminal item is present from sitting one

Definition of done is a **cold read**: an unseen 4-bar excerpt in the C3–G4 span,
notation only, no scaffold, played hands-together at a stated slow tempo.

That item is measured from the *first* sitting, before any of it is learnable, and
every sitting after. It is a probe, never a gate — failing it costs nothing and
unlocks nothing. Two reasons: it is the only measurement that is not a proxy, and
it makes the module's own progress visible from day one rather than at the end,
which is the failure mode §2.7 exists to design around.

### Build order (reading first, deliberately)

Ordering is the load-bearing decision here. Reading is the *point* of the module,
so it ships in the first push, not the fifth. A build where reading lands at item
5 of 8 puts this project at "87.5% done, terminal capability not yet started" —
which is exactly the shape of the abandonment this module was created to prevent.

| # | Item | Ships |
|---|---|---|
| F0 | This section | with F1 |
| F1 | `StaffRenderer` + landmark atoms (C4, G4, F3) read off a real staff in wait mode | shipped |
| F6a | Delete the letter-name gate: basics become the **default**, never a lock | shipped |
| F3 | Cold-read probe + paced-tapping probe, logged every sitting | next |
| F2 | `staff-direction` (up on the staff = right on the keyboard) and `span-9` (every note in C3–G4), scaffold fade via `scaffoldAlpha` | then |
| F5 | Hands together — scored only where the two parts are ≥14 semitones apart | then |
| F4 | Rhythm — **gated on the paced-tapping probe below** | conditional |
| F6b | Composer: basics and song work served into one session arc | last |

**Reorder, 2026-07.** The original table shipped F3 third and F6 last, and both
contradicted this section's own prose. Three corrections, all ordering — no scope
cut, and the definition of done below is unchanged:

- **F3 moves to the front.** The paragraph above says the cold read is "measured
  from the *first* sitting", and the falsification check regresses cold-read
  accuracy on atoms-brought-to-criterion. The low end of that predictor range —
  few atoms at criterion — is being generated right now and cannot be
  reconstructed later. It is the only irreversible item in the order.
- **F6 splits.** Deleting the letter-name gate was pure deletion, not a bridge,
  and the gate it removed was live in shipped code — keyed on find-C/find-F/find-G,
  the exact layer §4 calls "never a gate". Holding that deletion until last kept a
  spec-illegal gate alive through the largest item in the module. Only the
  composer stays scheduled last.
- **F5 before F4.** F5 is required by condition 2 of the definition of done. F4 is
  conditional and droppable. Required before optional.

### Definition of done (all five, no partial credit)

1. Cold read of an unseen 4-bar C3–G4 excerpt, ≥90% correct pitches, no scaffold,
   on **3 distinct days**.
2. That read is hands-together where the excerpt is hands-together.
3. Timing is within the wait-free window at the stated tempo — i.e. the read is
   played *in time*, not spelled out.
4. No atom in the module is above `scaffoldAlpha = 0` at the time of the read.
5. The cold-read score is not the best of N. It is the first attempt of the sitting.

When all five hold, the module is done and the song path is the app.

### Falsification check (run it, believe it)

Regress cold-read accuracy on "atoms brought to criterion". If bringing atoms to
criterion does not predict the terminal item, **the atoms are wrong — delete
them.** Do not respond by adding more atoms. This check exists because a mastery
map that certifies skills the terminal item does not need is precisely the defect
that made the previous foundations trainer measure visual tracking.

### Two safety findings that bind the build

- **Do not build F4 (rhythm) on assumption — but do not gate it on the probe
  either.** The premise was overstated: Puyjarinet's d ≈ 1.19–1.39 is two adult
  measures, n = 21 and 18, one lab, and the "~38% unimpaired" figure rests on
  about 8 people (95% CI roughly 21–59%). No number that wide can gate the
  largest item in the module. What a paced-tapping probe actually buys is
  narrower and worth having: a **window constant** derived from this learner's
  own timing spread, a **maximum F4 tempo** (shortest notated inter-onset
  interval ≥ 6.25 × σ_read), and **one design fork** — whether the cold read's
  timing spread is reading latency or the hand. It is entirely within-subject:
  no published adult norm survives the change of effector from touchscreen to
  weighted key. Run it five times, immediately after the cold read, take the
  median, then retire it. It is not a progress measure.
- **Do not score hands-together on close voicing.** MIDI carries pitch, not hands.
  When the two parts are within ~13 semitones, "both hands" is unobservable —
  one hand can play the lot. Restricting scoring to parts ≥14 semitones apart is
  what keeps F5 from repeating the mistake that got the fingering atoms deleted
  (certifying an unobserved skill, §2.8).

### What this module explicitly does not add

Note-name naming as an end in itself (§4: names are "optional, late, never a
gate"), solfège, theory prerequisites (§2.1), interval *arithmetic* detached from
playing, or a placement test. Letter-name key-finding survives only as the
scaffold under a staff prompt, and fades to zero before done is claimable.

---

## 8a. Learner Model (user-directed addition, 2026-07)

The crux of the app: it continuously builds a profile of THIS user from every
attempt and caters lessons to it. Requirements:

- Every attempt records rich mistake data (wrong notes, timing bias, per-hand
  accuracy, per-skill errors once tagging exists) — already in place since
  Phase 3; never regress this.
- A batch analysis pass runs **between** sessions (menu/idle time, never during
  play — SPEC §1 hot-path rules): recompute per-skill weakness, learning rate
  per skill, forgetting curves (personalized FSRS parameters), optimal passage
  sizes and ladder step sizes for this learner.
- The profile drives: what to practice next, drill generation, decomposition
  granularity, difficulty step sizes, and review scheduling.
- Compute note: current data volumes (thousands of attempts, KBs) need only
  classical statistics on CPU — fast and exact. The NPU is reserved for future
  audio transcription (mic input mode), not the learner model, unless data
  volumes ever genuinely demand it.

---

## 9. Repo, CI/CD & Versioning (set up before Phase 1 code)

**Workflow**: trunk-based — all commits push directly to `main`. No PRs, no
feature branches (solo project).

**GitHub Actions** (`.github/workflows/android.yml`), triggered on every push to
`main`:
1. Set up JDK 17 + Gradle with caching (Gradle cache action — keeps CI minutes low).
2. Run unit tests; fail the build on test failure.
3. Assemble a signed release APK (signing keystore + passwords stored as GitHub
   Actions secrets: `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD` — decode keystore from base64 in the workflow).
4. Upload the APK as a workflow artifact AND attach it to an auto-created GitHub
   Release, so it's downloadable on the tablet from the Releases page.

**Automatic versioning** (zero manual management):
- `versionCode` = `github.run_number` (monotonic, guaranteed unique — passed into
  Gradle via `-PversionCode=`).
- `versionName` = derived from conventional commits using semantic rules:
  `feat:` → minor bump, `fix:` → patch, `feat!:`/`BREAKING CHANGE` → major.
  Implement with a lightweight action (e.g. a semver-from-commits action) that
  tags the repo and feeds the tag into `-PversionName=`. Fallback if commit
  messages aren't conventional: `0.1.<run_number>`.
- CLAUDE.md instruction: always write conventional commit messages
  (`feat: ...`, `fix: ...`) so versioning stays automatic.
- Each GitHub Release is named with the version and lists commits since the last
  release as changelog.

**Local install loop**: since Claude Code runs on the tablet itself, the practical
flow is: push → Actions builds → download APK from the Release on the tablet →
install (enable "install unknown apps" for the browser). Document this in the
repo README.
