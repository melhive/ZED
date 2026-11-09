# ZENITH — Development Status

_Last updated: 2026-11-09_

## Overview

Native Android personal AI assistant (Kotlin, Jetpack Compose). Three intelligence layers: deterministic native commands (offline, no model), on-device LiteRT-LM models, optional online providers. Built remotely with GitHub Actions.

| Item | Value |
|---|---|
| Repository | https://github.com/melhive/ZENITH (public) |
| Working branch | `develop` (CI on every push) |
| Default branch | `main` (stable merges) |
| Latest commit | see `git log -1` (this file is updated with each milestone) |
| Build status | ✅ GitHub Actions **Android Build** green: core tests, Android unit tests, debug APK |
| Releases | ✅ Signed release **v0.1.4** published. Install/share link: https://github.com/melhive/ZENITH/releases/latest/download/zenith.apk. Every push to `main` publishes the next version (installs over the old one). |

## Milestones

| Milestone | Status | Evidence |
|---|---|---|
| P0 Foundation | ✅ Done | Gradle KTS + version catalog, CI workflow, docs |
| P1 Native application | ✅ Compiled | 10 screens + navigation; **UI not yet seen on a device** |
| P2 Data & memory | ✅ Tested | Room v1 schema committed; DatabaseTest, MigrationTest |
| P3 Native intelligence | ✅ Tested | CommandParserTest, SecurityTest, ToolManagerTest |
| P4 Android integration | ✅ Compiled | SAF, alarm/timer/calendar intents, reminders, notifications; **needs device validation** |
| P5 Chat Mode | ✅ Compiled | Streaming, tools, voice input, attachments, conversation management |
| P6 Local AI | ✅ Integrated | Compiles against LiteRT-LM 0.19.1; **inference not yet run on a device** |
| P7 Model Manager & online AI | ✅ Compiled + parsing tested | ProvidersTest (OpenAI, Claude, Gemini SSE + errors); live calls need user keys |
| P8 ZENITH LIVE | ✅ Compiled | VAD unit-tested; **STT/TTS/barge-in need device validation** |
| P9 Background tasks | ✅ Compiled | State machine + graph unit-tested; dependents start when prerequisites finish; WorkManager execution needs device validation |
| P10 Advanced intelligence | ✅ First version | FTS + BM25 retrieval tested; skills registry; research via provider search |
| P11 Security & release | 🔶 In progress | Security controls + audit done; release workflow verified green (unsigned); signing secrets and device benchmarks pending |

## Test results (latest CI run)

| Suite | Tests | Result |
|---|---|---|
| `:core:test` (JVM) | 28 | ✅ pass (also runs locally in Termux via `scripts/test-core.sh`) |
| `:app:testDebugUnitTest` (Robolectric, MockWebServer, Compose UI) | 18 | ✅ pass |
| Release build (R8 minify + resource shrinking, APK + AAB) | — | ✅ builds (unsigned until signing secrets are added) |

Failed tests: none.

## Verification levels

| Feature | Tested | Compiled | Needs physical device | Blocked externally |
|---|---|---|---|---|
| Command parsing, validation, path policy, confirmation policy, tool-call extraction | ✅ | ✅ | | |
| Room persistence, FTS search, migrations guard | ✅ (Robolectric) | ✅ | | |
| ToolManager pipeline (validation, confirmation, denial, audit) | ✅ (Robolectric) | ✅ | | |
| Online provider streaming parsers | ✅ (mock server) | ✅ | | Real calls need API keys |
| Compose UI / navigation | components (orb all states, dialog) | ✅ | ✅ screens | |
| SAF file operations | | ✅ | ✅ | |
| Alarms, timers, calendar intents | | ✅ | ✅ (depends on installed clock/calendar apps) | |
| Reminders (AlarmManager), boot rescheduling, notifications | | ✅ | ✅ | |
| WorkManager tasks (organize plan/confirm/execute, index, download) | partial (logic) | ✅ | ✅ | |
| LiteRT-LM model loading + inference (CPU/GPU) | | ✅ | ✅ | |
| Model download (2–3 GB) with resume + SHA-256 | | ✅ | ✅ | |
| Keystore secret storage | | ✅ | ✅ | |
| STT / TTS / sentence streaming / barge-in / audio focus | VAD only | ✅ | ✅ | |

## Model integration status

- Runtime: LiteRT-LM 0.19.1 (arm64-v8a, x86_64), API verified from the published AAR.
- Catalog: Gemma 4 E2B (CPU 2.59 GB / GPU 2.01 GB), Gemma 4 E4B GPU (2.97 GB), Qwen3 0.6B (347 MB) — sizes, SHA-256 and Apache-2.0 licenses verified on Hugging Face; none gated.
- Import of `.litertlm` files supported. AI Edge Gallery's copy is not accessible to other apps.
- Not yet measured: load time, tokens/second, peak RAM on the target phone.

## Voice integration status

- STT: Android SpeechRecognizer (on-device recognizer preferred on API 31+). Turn-based per utterance — **not** a continuous audio stream.
- TTS: Android TextToSpeech, offline voice preferred; replies are spoken sentence-by-sentence while generating.
- Barge-in: AudioRecord VOICE_COMMUNICATION + AcousticEchoCanceler + energy VAD. Quality depends on the device.

## Security status

Tool pipeline enforced (validation → skill → capability → confirmation → execution → audit); no shell tool; SAF-only file access; Keystore-encrypted secrets; secret redaction; backups disabled; signing secrets only via CI. See `docs/SECURITY.md`.

## Known issues / limitations

1. Nothing has been run on a physical device yet; all device-dependent behaviour is unverified.
2. Small on-device models may not follow the `zenith-tool` protocol reliably; native commands remain the dependable path for actions.
3. Knowledge index reads text formats only (no PDF text extraction yet).
4. Barge-in may miss the first syllable after interruption; quality depends on echo cancellation.
5. Default online model names (e.g. `gemini-2.5-flash`, `gpt-4.1-mini`) may be outdated — use "List models" in Model Manager.
6. A configuration change (rotation) while in ZENITH LIVE restarts the voice session.
7. Release signing requires repository secrets (see `docs/BUILDING.md`).

## Exact next steps

1. Install the release from https://github.com/melhive/ZENITH/releases/latest/download/zenith.apk (uninstall any debug build first).
2. Device validation checklist:
   - Grant a folder in Files; run "create file test/hello.txt with content hi", "list files in test", "organize my downloads" → approve plan in Tasks.
   - "set an alarm for 7am", "set a timer for 1 minute", "schedule meeting tomorrow at 3pm".
   - "remind me to stretch in 2 minutes" with notifications allowed.
   - Model Manager → download Qwen3 0.6B (fast) then Gemma 4 E2B; Use → chat; record load time and responsiveness on CPU and GPU.
   - Add an API key for one online provider → Test connection → chat; try "research …".
   - ZENITH LIVE: talk, interrupt while it speaks, mute/unmute, end session while a task runs (task must continue).
3. Fix device findings, then add streaming offline STT (sherpa-onnx/Vosk) behind `SpeechToText`.
4. Add emulator-based instrumented UI tests to CI; record device benchmarks in `docs/MODEL_SUPPORT.md`.

## Continuation instruction (for a new session)

Read this file and `docs/ROADMAP.md`, run `git status` and `gh run list --limit 3`, then continue with "Exact next steps". Run `./scripts/test-core.sh` before pushing core changes; push to `develop` and verify the Android Build run is green before merging to `main`.
