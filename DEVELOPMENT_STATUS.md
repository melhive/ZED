# ZED — Development Status

_Last updated: 2026-11-13_

## Overview

Native Android personal AI assistant (Kotlin, Jetpack Compose). Three intelligence layers: deterministic native commands (offline, no model), on-device LiteRT-LM models, optional online providers. Built remotely with GitHub Actions.

| Item | Value |
|---|---|
| Repository | https://github.com/melhive/ZED (public) |
| Working branch | `develop` (CI on every push) |
| Default branch | `main` (stable merges) |
| Latest commit | see `git log -1` (this file is updated with each milestone) |
| Build status | ✅ GitHub Actions **Android Build** green: core tests, Android unit tests, debug APK |
| Releases | ✅ Signed release **v0.1.32** published (latest). Install/share link: https://github.com/melhive/ZED/releases/latest/download/zed.apk. Every push to `main` publishes the version in `VERSION` (bump it for a new release; re-pushing the same version replaces that release). App id `com.melhive.zed`, signed with the key in `/root/zed-signing` (back it up — updates need the same key). |

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
| P8 ZED LIVE | ✅ Compiled | VAD unit-tested; **STT/TTS/barge-in need device validation** |
| P9 Background tasks | ✅ Compiled | State machine + graph unit-tested; dependents start when prerequisites finish; WorkManager execution needs device validation |
| P10 Advanced intelligence | ✅ First version | FTS + BM25 retrieval tested; skills registry; research via provider search |
| P11 Security & release | 🔶 In progress | Security controls + audit done; release workflow verified green (unsigned); signing secrets and device benchmarks pending |

## Test results (latest CI run)

| Suite | Tests | Result |
|---|---|---|
| `:core:test` (JVM) | 36 | ✅ pass (also runs locally in Termux via `scripts/test-core.sh`) |
| `:app:testDebugUnitTest` (Robolectric, MockWebServer, Compose UI, orchestrator) | 28 | ✅ pass |
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

## Full self-control (v0.1.37)

- All answering rules (≤25, newest first, newest overrides) reach the model; facts considered raised to 1000.
- Taught pronunciations/aliases mirrored into DataStore settings (`taughtPronunciations`/`taughtAliases`) so backups include them; restored on start when local copies are empty.
- `settings.set` adds live_sound, hide_beep, speech_recognition, announce_reminders, event_announce, alarm_briefing, ai_model, model_backend. New tools `memory.pin`, `memory.confirm`, `chats.clear` (undoable via `Deleter.conversations`).

## Stability (v0.1.36)

- `LocalModelRuntime.unload` cancels and then takes `generationLock` (closing the engine under a running generation was a native use-after-free); `onTrimMemory` frees nothing while the orchestrator is busy or LIVE is open.
- `OnDeviceSpeechToText.Engine.lock`: one utterance at a time; unload only when free.
- `CrashReporter`: survivable errors (background threads, coroutines, main-thread callbacks outside Compose; max 5 per 30 s) are recorded and the app keeps running; `ApplicationExitInfo` (native crash, ANR, low memory) collected at start with traces; reports mirrored to Download/ZED via MediaStore. LIVE scope has a `CoroutineExceptionHandler`; reminder/boot receivers catch errors; wake settings read off the main thread.
- `MODIFY_AUDIO_SETTINGS` added (Bluetooth earphone mic).

## Announcements, earphones, keyboard (v0.1.35)

- **Announcer** (`agents/Announcer.kt`): reminders (from `ReminderReceiver`), calendar events (one exact alarm for the next event, `eventAnnounceMinutes` before; re-planned at start, boot, every 3 h and after calendar tools via `ToolManager.afterSuccess`), and a briefing after an alarm: `NEXT_ALARM_CLOCK_CHANGED` fires when an alarm starts ringing, so a check 90 s later briefs unless the next alarm is within 20 min (snoozed). Opens the overlay with `EXTRA_ANNOUNCE` (needs display-over-apps; otherwise speaks in the background). `reminder.snooze` uses the last fired reminder.
- **Earphones** (`voice/AudioRoute.kt`): `headsetAudio` = auto | earphone_mic. Earphone-mic mode prefers the headset mic (Bluetooth via communication device/SCO during LIVE) and plays the neural voice on the built-in speaker (`AudioTrack.setPreferredDevice`, MSG_ROUTE to the voice process); barge-in is off then (echo).
- **Keyboard**: overlay and LIVE apply `imePadding` to the whole panel, shrink the orb while typing, and scroll captions, so the text field stays above the keyboard.

## Undo, replies, screen control, on-device hearing (v0.1.34)

- **Undo** (`tools/Undo.kt`): `UndoLog` (30 min, in memory) + `Deleter` used by every delete path (executors and Memory screen); files go to `.zed-trash` in their granted folder (`SafFileSystem.trash`, emptied at start); `app.undo` tool; global `UndoBar` in `ZedRoot`.
- **Message replies**: `ZedNotificationListener` keeps each messaging notification's RemoteInput action; `message.reply` fills and sends it (name boundary resolved against chat titles), falls back to SMS or a prefilled WhatsApp chat; `messages.read`.
- **Screen control** (`assist/ZedAccessibilityService.kt`): reads the top non-ZED app window, taps by label (node click, gesture fallback), scrolls, types (ACTION_SET_TEXT), global actions; `screen.read` summarized by Gemma.
- **On-device ASR** (`voice/OnDeviceSpeechToText.kt`, pack `asr-nemo-en`): NeMo streaming FastConformer CTC 80 ms int8 via sherpa-onnx; measured locally with the real engine: ~2 s load, RTF 0.3 on 2 threads, ZED's voices transcribed correctly. `SpokenText.normalize` turns spoken numbers into digits/times (`SpokenTextTest`). Setting `speechEngine` (auto/android).
- **Wake word**: ASR verification was measured and rejected (the recognizer hears “Hey Zed” as “he said”: 102→30/170). Instead a second phoneme model (`kws-phone-zh-en`) runs in parallel (OR): High 68→91/170 (FA 1→4/100), Maximum 103→112 (FA 10→13).
- **Conversation mode** (overlay): follow-up listening after an answer, auto-close when quiet. **SelfTest** and **SpeedLog** in Diagnostics.

## Eight AI-assistant sounds and a spoken greeting (v0.1.32)

- **Sounds** (`SoundSynth`): Hologram (default), Reactor, Neural, Scanner, Armor, Sonar, Core, Command, using FM "glass" tones, resonant sweeps, electric crackle, saturated low impacts and a Freeverb-style reverb; tails fade over the last 12%. Retired ids fall back to Hologram.
- **Greeting** (`core/voice/Greetings.kt`, `GreetingsTest`): ~40 variations, time-of-day aware, never twice in a row, addressing the user by title (“call me sir”) or name. Spoken by `LiveSessionController.start(greet = true)` from the overlay (wake word / assistant shortcut) and by the hands-free wake path; setting `greetOnCall` (More → Voice, or “turn off the greeting”).

## Deeper sounds, focused conversations (v0.1.31)

- **Sounds** (`SoundSynth`): Core, Turbine, Mech, Command, built from low fundamentals (55–220 Hz) with rich harmonics, steep 330 Hz high-pass and 4.2 kHz low-pass so phone speakers play them; LIVE "got it" is `SoundSynth.ack()` (low thunk). Retired ids fall back to Core. `SoundSynthTest` checks richness and darkness (700–2600 zero crossings/s).
- **Focus**: model history is only the current sitting (`history(…, sessionGapMs = 3 h)`), dialogue state resets after the gap, `MemoryRanker.rank(bridges = false)` for answers (no second-hand associations), preference rules kept out of the profile, and the context guide tells Gemma never to raise past topics on its own (`FocusTest`).

## Full control and learning from the user (v0.1.30)

- **Full control** (`tools/executors/AppControl.kt`): `todo.delete`, `reminder.delete`, `calendar.delete` (one occurrence of a repeating event via a cancelled exception), `docs.remove`, `memory.forget everything`, `app.show` (any ZED screen, `memory/{tab}` route), and `settings.set` covering every user setting (voice by name, wake word and sensitivity, speech rate/pitch incl. faster/slower, animation, ask-before, title, city, briefing, persona, all switches). Offline parser rules for each (`CommandParserTest`).
- **Teaching** (`core/learning/Teaching.kt`, unit-tested in `TeachingTest`): pronunciations (`Pronunciations.apply` in `VoiceManager.speak`), answering rules (pinned memories with source `preference`, always in Gemma's context), shortcuts (`Aliases.expand` before understanding), speaking speed. Stored in `TeachingStore`; shown in Memory → Lessons.
- **Voice**: a killed `:voice` process restarts the chosen neural voice (≤3 times in 5 min) instead of falling back to the female system voice; a real load failure falls back for 2 min, then retries.
- **Insights**: `Insights.topics` needs an importance signal (to-do, date, event, worry) and ignores device commands; `Topics.prompt/parse` lets Gemma name topics while charging, kept only if grounded on ≥2 days.
- **UI**: delete buttons on Reminders and Schedule; no orb check mark; no panel light sweep; Live Brain Activity bars removed.

## Stabilising: Gemma-only, battery, calendar moves, wake word (v0.1.29)

- **Gemma-only**: `OnlineProviders.kt`, `GeminiLive.kt` and their tests removed; `ProviderRegistry` returns the on-device model or null; `IntelligenceMode.ONLINE` and the online/live settings removed; `SecretStore.removeLegacyAiKeys()` deletes old AI keys at start; Terms/Privacy updated.
- **Battery**: `DreamLogic.duplicates` computes keys once per memory; `Dreamer.dream` runs at most every 6 h (2 h while charging); leave-now hints use a 3-hourly planner plus one wake-up 40 min before an event with a place (was every 15 min); Home orb idle 24 fps, particles 15 fps, mini brain 20 fps; no model warm-up below 15% battery unless charging; Diagnostics → Background jobs.
- **Calendar moves**: `calendar.move` (parser rule with file/smart-home exclusions, follow-up from `calendar.create`, `CalendarMove` logic unit-tested; skips repeating events; keeps the event's length).
- **Wake word**: measured with the real engine (34 phrases × 5 conditions, 50 near-miss sentences): `maxActivePaths` 8 is the lever; Maximum (0.01/4.5/8) 106/170 vs High — the old top setting, unchanged — 81/170, at the cost of more look-alike wakes (16 of 100 near-miss sentences); extra spellings lowered recall and were dropped. Keywords are checked against the model's tokens.txt before loading (`WakeWords.safe`). Old top setting migrates to Maximum once.

## Deeper intelligence (v0.1.28)

Pure logic in `core/learning/Deeper.kt` (unit-tested in `DeeperIntelligenceTest`); app side in `learning/Dreamer.kt`.
- **Relationships**: `RelationPatterns` (exact phrasings) + Gemma lines "A | relation | B" (parsed strictly) into the `relations` table; `BrainBuilder.build(…, links)` adds labelled edges, reusing concept nodes; labels drawn on focused/close connections and in the connected list.
- **Dreaming** (after learning; model parts only when charging or already loaded, cancelled when the user is active): `DreamLogic.duplicates` merges memories (pinned > confirmed > most used > newest), relations, orphan pruning.
- **Insights**: `Insights.topics` (≥3 mentions on ≥2 days in 14 days, not covered by a reminder/task) and `Insights.habits` (same action within an hour on ≥3 days in 21 days, from the audit log). One new insight at a time, each offered once; Home card; a notification only within `Hints.allowed` (≤1 per 3 h, ≤3 a day).
- **Leave-now**: `HintWorker` every 15 min, `CalendarReader.nextEventWithPlace`, `Hints.leaveNow` (30–50 min before, once per event); tapping opens LIVE with "directions to …".
- **Corrections**: `Corrections.detect` in the orchestrator ("that's wrong[, it's X]"; asks when no fix is given); lessons saved with `source = correction` and flagged in the brief; Wrong button on replies (`correctReply`).
- **Brain by voice**: `brain.show` (parser rule + tool + selector trigger) sets `brainFocus` and opens the brain, which flies to `BrainGraph.find(query)`.

## Classic orb restored and improved (v0.1.27)

The user preferred the arc-reactor orb, so `PlasmaOrb` was removed and `ZedOrb` is the classic orb again, improved: one throttled frame clock (20 fps thinking, 12 fps light, 60 fps speaking) with accumulated rotation; inward ripples from the mic level; a mirrored 48-tick voice ring driven by `AudioLevels` (word pulse for phone voices); synapse sparks in the core while thinking; a check on completion; softer glow. Release 5 work is paused in `git stash` ("release 5 (deeper intelligence) — paused, unfinished").

## Orb and futuristic UI (v0.1.26)

- **Plasma orb** (`ui/components/PlasmaOrb.kt`, shape maths in `core/ui/Plasma.kt`, unit-tested): three drifting layers, inward ripples from the mic level, surface shaped by the real speech envelope. The neural voice runs in the `:voice` process, so `NeuralTextToSpeech.onLevels` → `NeuralVoiceService.MSG_LEVELS` → `AudioLevels` carries the loudness across; phone voices fall back to the word pulse. Own frame clock: 20 fps while thinking, 12 fps in Light mode. Android < 10 or < 3 GB RAM keeps the classic orb.
- **States**: action labels from `ActionLabels.progress` in the activity detail; synapse flashes while thinking; `JarvisHud` holds still while the model works.
- **Floating assistant**: `EdgeGlow`, `LiveCaptions` (streaming text), `ResultChips` and `ResultCard` from the new `AssistantOrchestrator.results` stream; drag anywhere, fling > 140 dp to dismiss; haptics on results.
- **Home console** (`ui/components/Console.kt`): battery, memory, network and Gemma rings with count-up numbers, live countdown to the next event/reminder/timer, tilt-reactive `ParticleField`.
- **Motion**: `LocalMotion` (animation level) app-wide; `ZedCard` scans in once (state kept across scrolling); tab haptics. **Everything visual needs the user's eyes on the phone.**

## Learning and the internet (v0.1.25)

- **Web research, no key** (`core/web/WebResearch.kt` — parsers for DuckDuckGo HTML, Bing and the Wikipedia API, readable-text extraction, source handling, unit-tested with fixtures; `web/WebSearcher.kt` — OkHttp, 15 s budget, parallel page reads, falls back to snippets). `web.research` no longer needs an online provider; offered automatically for questions about the world (`ToolSelector.looksFactual`). Answers get a source list (`Research.withSources`). Privacy: only `Research.query(...)` (one line, ≤120 chars) is sent. **Search pages can change or block automated requests; the three-service fallback and the error message need checking on the phone.**
- **Offline queue** (`web/PendingQueue.kt`): `web.research` and `weather.get` queue into `pending_requests` when offline; `PendingWorker` (network constraint) answers, adds the reply to the conversation and notifies. Home shows the waiting count.
- **Automatic learning** (`core/learning/Learning.kt`, `learning/Learner.kt`): pattern extraction always; Gemma extraction only when idle 75 s and charging, or model loaded and battery ≥ 40%; cancelled the moment the orchestrator becomes busy or LIVE opens. Facts saved with `source = learned` and confidence 0.8 (pattern) / 0.6 (model); sensitive text and duplicates filtered. Review card on Home; confirmed facts become `learned_confirmed`, confidence 1. Periodic worker every 12 h while charging.
- **Think twice** (`HardQuestion`): plan (side generation) → answer with the plan in the brief → self-check that may replace the answer. Text chat on-device only; skipped for voice.
- **Settings → Intelligence**: learning, web research, think twice, animation level (full / balanced / light).

## Understanding and reliable actions (v0.1.24)

Pure, unit-tested logic in `core/understanding/`; wired in `AssistantOrchestrator`.
- **Exam** (`core/src/test/.../ExamTest.kt`): ~155 real requests with a fixed clock — commands, tricky dates, durations, follow-ups and corrections, several requests in one sentence, tool repair, tool selection, recall. Every "Report" file that shows a mistake becomes a new line.
- **DialogueTracker**: per-conversation state (person, topic, last timed action, reminder id). Resolves pronouns ("call her"), relations from memories ("call my sister" → name), "remind me about it", "move it to 5" / "no, I meant Friday" (→ `reminder.reschedule`, new alarm/timer), and splits sentences only when every part is a command on its own.
- **Awareness brief** (`Awareness`, app side `AwarenessCollector`): time, place, battery, online, next event, countdowns, dialogue, profile, related memories, computed results and the selected tools, attached to the user message. The on-device system prompt (`PromptBuilder.localSystemPrompt`) no longer changes between messages.
- **ToolSelector** (≤5 tools per message on-device), **ToolRepair** (tool/arg aliases, date formats, durations; the user's own times override the model's unless the sentence is relative), lenient extraction of ```json / tool_code / bare-JSON calls.
- **MemoryRanker**: direct shared concepts, then two-step links, then pinned/strength; recency alone never selects a memory.
- **Faster**: model warm-up on app open, wake word and assistant overlay; instant replies (`QuickReplies`); voice replies capped at 256 tokens and 1–2 sentences; LIVE acknowledgement tick. **Needs the speed test on the phone to confirm no regression.**
- Online providers are still in the code; removing them (the plan's Gemma-only rule) is left for a later release.

## Foundation and Live Brain 2.0 (v0.1.21)

- **Database v2** (`ZedDatabase.MIGRATION_1_2`, schema `app/schemas/.../2.json`): memory strength (`uses`, `lastUsedAt`, `confidence`), reply timings on messages, and the `relations` and `pending_requests` tables later releases use — one upgrade instead of several. `MigrationTest` upgrades a v1 database and validates the result.
- **Safety copies:** `BackupManager.safetyCopyBeforeUpgrade` copies the database at process start, before Room migrates it (newest three kept; Backup & restore → Safety copies → Put back). **Weekly automatic backup** to a user-chosen folder (`AutoBackupWorker`, newest four kept, data and settings only).
- **Speed test** (Diagnostics): first-word wait and words per second with the model already loaded; the first result is the baseline; every run is appended to `Downloads/ZED reports/ZED-speed-*.txt` (`diagnostics/Diagnostics.kt`). **The baseline has to be recorded on the phone.**
- **Report** on any reply: the exchange, timings, audit-log actions and device/model state in one text file in `Downloads/ZED reports`. Nothing leaves the phone.
- **Recall signal:** when the model answers, the memories that share a meaningful word with the question are marked used (`MemoryRepository.markUsed`) and a `BrainEvent.Recalled` is emitted.
- **Live Brain 2.0** (`ui/screens/BrainScreen.kt`, layout in `core/brain/BrainGraph.kt`): 3D layout and perspective projection, drag to rotate, pinch to fly in, depth dimming, curved tapering connections, signal trails and sparks, boot sequence with HUD readout, recall pulses from the core, memory strength (brightness and distance from the core, unit-tested), time-lapse replay, synapse ticks and haptics, and `MiniBrain` on Home, in LIVE and in the floating assistant. **Looks, sounds and frame rate need device validation.**

## Live Brain, backup & restore, machine sounds (v0.1.20)

- **Live Brain** (Memory → Live Brain): a knowledge graph built on the fly from memories, tasks, reminders, indexed documents and conversations (`core/brain/BrainGraph.kt`: areas, shared-word concepts, force-directed layout — unit-tested). Drawn on a Compose canvas with glow, travelling signals, pan/zoom, search and type filters. No schema change. **Rendering needs device validation.**
- **Backup & restore** (More → Backup & restore): one `.zedbackup` file (`core/backup/BackupArchive.kt`: streamed entries with CRC-32, end marker; API keys AES-GCM/PBKDF2 under a user password — unit-tested incl. truncation, corruption and path-escape). Restore stages and verifies everything, then swaps it in at the next start (`BackupManager.applyPendingRestore`, Robolectric round-trip test). Models and voices optional. **Multi-GB files and the automatic restart need device validation.**
- **LIVE sounds** rebuilt as machine sounds (`core/voice/SoundSynth.kt`, band-limited pulse/saw, motor buzz, relay clicks, metal latch); tests check loudness, phone-speaker frequency range and brightness. **How they sound needs the user's ears.**

## Conversation intelligence (v0.1.5+)

- **Auto mode** (default): online provider (key set + connected) → on-device model → native. First downloaded model activates itself.
- Natural conversational system prompt; full chat history as context; one automatic retry on transient errors; regenerate.
- On-device: LiteRT-LM session reused across turns (only the new message is processed), compact stable system prompt.
- Without any model: small talk (personalized with the user's name), calculator, unit conversion, date math, and honest guidance.
- Orchestrator behaviour (history, tool round-trip, retry, native fallback, regenerate) is covered by tests with a scripted model.

## Fixed from first device feedback (v0.1.6)

- "Context window is full" errors: engine now created with an explicit window (Auto 4K/8K, selectable), history trimmed to fit, session reset before overflow, automatic recovery.
- Chat box gap above the keyboard; Home redesigned (orb opens LIVE, "Completed" no longer sticks).
- "Remember" phrasings broadened; conversational requests go to the model when available.
- LIVE: standard recognizer first, recognizer recreated after errors, microphone released before listening, barge-in only with hardware echo cancellation.
- Gemma 4 E2B confirmed compatible (Google: ~1.4–1.7 GB RAM on CPU, up to 32K context).

## Second device-feedback round (v0.1.7)

- LIVE: error codes shown and recorded in Problem reports; preflight check; system voice-input and typing fallbacks.
- UI: wrapping chip/button rows (no overlapping text), scrollable Memory tabs, Tasks overflow menu, title-case greeting with name prompt.
- Conversation: more human persona prompt, lean prompt for casual chat, broader native small talk with follow-ups and fun features.
- Still needed from the device: the exact LIVE error (Diagnostics → Problem reports → Share report) if it persists.

## Assistant release (v0.1.12 + v0.1.13)

| Area | What | Verification |
|---|---|---|
| Entry points | ACTION_ASSIST/VOICE_COMMAND activity (default assistant), Quick Settings tile, home widget | CI build; needs device |
| Personalities | Companion, Butler, Professional, Playful + user title | Core tests |
| Native tool calling | Gemini/OpenAI/Claude function calling, up to 8 chained steps, parallel calls, Gemini thought signatures echoed | Mock-server tests per provider |
| Gemini Live | WebSocket BidiGenerateContent, 16 kHz mic / 24 kHz playback, transcripts, tool calls, resumption, half duplex on speaker | Protocol from official docs; needs a key + device |
| Phone control | contacts, calls (always confirmed), SMS compose, apps, media keys, music search, volume, flashlight, DND, settings panels, maps, web, camera, status | 40+ parser tests; needs device |
| What did I miss | NotificationListenerService, in-memory only | Needs device |
| Briefing & routines | Open-Meteo weather, calendar read, briefing notification, routines with voice/time/Bluetooth triggers | Core tests (scheduling, presets, JSON); needs device |
| Vision | Photo (camera/gallery) to Gemini/OpenAI/Claude; LIVE camera; Gemini Live video frame | CI build |
| Home Assistant | REST client, entity matching, low-risk control vs always-confirmed locks/covers | Parser tests; needs an HA instance |
| Wake word | sherpa-onnx KWS (GigaSpeech zipformer), foreground microphone service, opens LIVE or answers hands-free | Real engine: model's own human recordings detected correctly; synthesized "Hey Zed" detected 4/4 (Kokoro voices) and 4/12 (Supertonic); 0/16 false alarms on near-miss phrases at normal sensitivity. The "-mobile" model variant crashes ONNX Runtime and is not used. Real voices need device testing; hands-free answering may be blocked by the speech service while ZED is in the background on some phones |
| UX | What's new history + post-update dialog + update notification; machine-style LIVE sounds (Arc, Servo, Droid, Data, Retro, Airlock); recognizer beep muted with crash-safe restore; sliding transitions | CI build |

## Natural offline voices (v0.1.10–v0.1.11)

- **Voice screen** (More → Voice, or Settings → Voice): current voice with Preview/Stop, speaking speed 0.5–2× and pitch 0.7–1.3× with presets (each change plays a short sample), downloadable voice packs, phone voices list.
- **Voice packs** (downloaded as background tasks, resumable, SHA-256 verified): Supertonic 3 — 10 voices, very fast, 31 languages, reads numbers itself (129 MB); Kokoro v1.0 — 28 American/British English voices, most expressive (132 MB). Downloading the first pack switches ZED to its recommended voice.
- **Engine:** sherpa-onnx 2.0.1 (static ONNX Runtime AAR, fetched and checksum-verified at build time, arm64 only). One shared `VoiceManager` serves Chat and LIVE and falls back to the phone voice if a neural voice cannot load.
- **Kokoro text pipeline:** `SpeechNormalizer` + `EnglishPhonemizer` (core, unit-tested; checked against the real lexicon) because sherpa-onnx 2.0's Kokoro frontend drops digits and unknown words.
- **v0.1.11 crash fix:** previewing a neural voice closed the app. Cause: sherpa-onnx's JNI looks up the generation callback by the exact signature `invoke([F)Ljava/lang/Integer;`, which Kotlin lambdas lack; it continued with a pending `NoSuchMethodError` and ART aborted the process (reproduced against the real library: `NoSuchMethodError … invoke([F)Ljava/lang/Integer;`). Synthesis now runs without a callback, in a separate `:voice` process (a native crash there cannot close the app; ZED falls back to the phone voice and records a problem report), and every voice thread/action catches and records errors.
- **Verified with the real engine** (sherpa-onnx 2.0.1 Linux arm64 build, same C++ code as the AAR, driven through the app's exact Kotlin config/phonemizer code on this phone's 8× Cortex-A55 CPU): Supertonic 3 — load 1.7 s, renders ~2× faster than real time (≈2 s per sentence at 5 steps), 10 voices produce clear audio, speed setting changes duration as expected. Kokoro — load 1.6 s + 4.5 s for the dictionary, renders ~1.4× *slower* than real time on this CPU (pauses between sentences on budget phones; the app measures and says so). 4 threads is fastest; 6–8 are slower on A55 cores.
- **Still needs the phone:** AudioTrack playback, barge-in and pitch with neural voices, the `:voice` process messaging, pack download + extraction (~260 MB free space needed during install).

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

1. Nothing has been run on a physical device yet; all device-dependent behaviour is unverified. Crash and error reports can now be shared from Diagnostics → Problem reports.
2. Small on-device models may not follow the `zed-tool` protocol reliably; native commands remain the dependable path for actions.
3. Knowledge index reads text formats only (no PDF text extraction yet).
4. Barge-in may miss the first syllable after interruption; quality depends on echo cancellation.
5. Default online model names (e.g. `gemini-2.5-flash`, `gpt-4.1-mini`) may be outdated — use "List models" in Model Manager.
6. Release signing requires repository secrets (see `docs/BUILDING.md`).

## Exact next steps

1. Install the release from https://github.com/melhive/ZED/releases/latest/download/zed.apk (uninstall any debug build first).
2. Device validation checklist:
   - Grant a folder in Files; run "create file test/hello.txt with content hi", "list files in test", "organize my downloads" → approve plan in Tasks.
   - "set an alarm for 7am", "set a timer for 1 minute", "schedule meeting tomorrow at 3pm".
   - "remind me to stretch in 2 minutes" with notifications allowed.
   - Model Manager → download Qwen3 0.6B (fast) then Gemma 4 E2B; Use → chat; record load time and responsiveness on CPU and GPU.
   - Add an API key for one online provider → Test connection → chat; try "research …".
   - ZED LIVE: talk, interrupt while it speaks, mute/unmute, end session while a task runs (task must continue).
3. Fix device findings, then add streaming offline STT (sherpa-onnx/Vosk) behind `SpeechToText`.
4. Add emulator-based instrumented UI tests to CI; record device benchmarks in `docs/MODEL_SUPPORT.md`.

## Continuation instruction (for a new session)

Read this file and `docs/ROADMAP.md`, run `git status` and `gh run list --limit 3`, then continue with "Exact next steps". Run `./scripts/test-core.sh` before pushing core changes; push to `develop` and verify the Android Build run is green before merging to `main`.
