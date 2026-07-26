# Fingerly

Personal piano-learning app for one specific Android tablet + USB MIDI piano.
**Full spec: `docs/SPEC.md`** — reference sections by number; all decisions there are
final. Ask only where the spec is silent.

## Stack
Kotlin · Jetpack Compose (menus/screens only) · custom Canvas/GL render layer for the
note highway/particles · Room (local SQLite) · MusicXML first-class, MIDI import
approximate. Target: Xiaomi Pad 8 Pro, Android 16/HyperOS 3, 120Hz (HyperOS caps
non-game apps; 144Hz panel), USB MIDI in.
No backend, no accounts, no analytics, no audio synthesis for live play.

Modules: `:core` = pure-Kotlin hot path (MIDI parsing, ring buffer, pools — JVM unit
tested), `:app` = Android.

## Performance rules (SPEC §1 — non-negotiable)
- Hold the highest available refresh (120Hz in practice) via `preferredDisplayModeId`;
  never assume the OS picked it.
- Keypress → visual response < 15ms. Latency test screen ships in-app (release too —
  the tablet installs release builds via Obtainium).
- Zero-allocation hot path: pre-allocated pools + lock-free SPSC queue. Any per-frame
  allocation in the render loop or MIDI pipeline is a bug.
- MIDI is parsed on its delivery thread and never touches the UI thread.
- Visible jank is a release blocker.

## Anti-patterns (SPEC §2 — design these OUT)
Theory prerequisites · text >3 sentences (use animation instead) · vague instructions
("comfortable position") · praise/cheerleading copy (blunt metrics only) · open-ended
sessions · decision menus before playing.

## Workflow
- One phase per push (SPEC §7); run that phase's acceptance gate before calling it done.
- **Update `app/ui/TestingBrief.kt` in any push that changes what the user should be
  testing.** The app tells him what to do, what it decides, how long, and whether
  logging must be on — he should never have to ask in chat. A stale brief is worse
  than none, because it will be believed.
- **Conventional commits always** (`feat:`, `fix:`, `feat!:`) — CI derives the version
  from them (SPEC §9).
- Run `./gradlew test` before pushing; CI fails the build on test failure.
- Prefer editing files over regenerating them.
- Keep chat replies concise; when asking the user to act, summarize their next
  steps in one paragraph max.
