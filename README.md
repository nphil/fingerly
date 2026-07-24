# Fingerly

Personal piano-learning app for a Xiaomi Pad 8 Pro + USB MIDI digital piano.
Single user, fully local — no accounts, no backend, no analytics.

Everything about what this app is and how it must behave lives in
**[`docs/SPEC.md`](docs/SPEC.md)**. Read that first.

## Repo layout

- `:core` — pure-Kotlin hot path: zero-allocation MIDI byte parser, pre-allocated
  event pool, lock-free SPSC ring buffer, latency stats. JVM unit tests, no Android
  dependencies.
- `:app` — the Android app: USB MIDI wiring, 144Hz immersive shell, latency test
  screen (debug builds), Room schema, first-run checklist.

## Building

```bash
./gradlew test              # unit tests (runs in CI on every push to main)
./gradlew assembleDebug     # debug APK with latency test screen
./gradlew assembleRelease   # release APK (signed if keystore present, see below)
```

Requires JDK 17+ and the Android SDK (platform 36).

## CI / install loop (SPEC §9)

Trunk-based: every push to `main` triggers `.github/workflows/android.yml`, which

1. runs unit tests (build fails on test failure),
2. derives the version — `versionCode` = run number; `versionName` from conventional
   commits (`feat:` → minor, `fix:` → patch, `feat!:`/`BREAKING CHANGE` → major),
   falling back to `0.1.<run>` if commits aren't conventional,
3. assembles a release APK, and
4. attaches it to an auto-created GitHub Release.

**To install on the tablet:** open this repo's **Releases** page in the tablet's
browser → download the APK from the latest release → open it → allow
"install unknown apps" for the browser when prompted. Repeat per release; Android
updates in place as long as the signing key is unchanged.

### Release signing secrets

Set these GitHub Actions secrets (repo → Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `KEYSTORE_B64` | `base64 -w0 keystore.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Generate a keystore once with:

```bash
keytool -genkeypair -v -keystore keystore.jks -alias fingerly \
  -keyalg RSA -keysize 2048 -validity 10000
```

Until the secrets exist, CI signs the release APK with the debug key so the install
loop still works. **Note:** switching from debug-signed to release-signed builds
changes the app signature — uninstall the old build once before installing the first
properly signed one.

## First run on the tablet (HyperOS)

The app shows a first-run checklist for these, but for the record (SPEC §1):

- Exclude Fingerly from battery optimization (HyperOS throttles aggressively).
- Set the per-app refresh rate to 144Hz (HyperOS may lock third-party apps to 60Hz).
- Connect the piano over USB-C; the app uses class-compliant USB MIDI.
