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
