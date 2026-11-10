# ZENITH

**Think. Learn. Organize. Automate. Execute.**

ZENITH is a native Android personal AI assistant written in Kotlin with Jetpack Compose. It is useful with **no model and no internet**, gets smarter with an **on-device model** (Google LiteRT-LM, e.g. Gemma 4 E2B), and can optionally use **online providers** (Gemini, OpenAI, Claude, or any OpenAI-compatible endpoint).

## Highlights

- **Native intelligence (offline, no model):** files and folders, alarms, timers, calendar events, reminders, task list, memory, conversation search, settings and workflows through deterministic commands. Say `help` to see them.
- **On-device AI:** LiteRT-LM runtime with a verified model catalog (Gemma 4 E2B/E4B, Qwen3 0.6B), resumable SHA-256-checked downloads, import, CPU/GPU backends.
- **Online AI (optional):** provider-independent streaming layer; keys encrypted with Android Keystore.
- **Chat Mode:** persistent conversations, streaming replies, voice input, text attachments, tool execution with visible results.
- **ZENITH LIVE:** full-screen voice assistant with an animated orb driven by real events, sentence-streamed speech, barge-in with echo cancellation, mute/speaker/cancel controls.
- **Jarvis-style assistant (v0.1.12–0.1.13):** default digital assistant (hold power/home), Quick Settings tile, home widget, “Hey Zenith” wake word, personalities, real-time voice with Gemini Live, phone control (calls, texts, apps, music, volume, flashlight, Do Not Disturb, navigation), “what did I miss?”, daily briefing with weather and calendar, routines (voice, time, Bluetooth triggers), camera vision and Home Assistant smart home. Online AI uses native multi-step tool calling.
- **Natural offline voices:** download realistic neural voices (Supertonic 3 and Kokoro, 38 voices, ~130 MB each) that run fully on the phone, or use any phone voice. Choose the voice, speaking speed and pitch in More → Voice.
- **Task engine:** WorkManager-backed background tasks (organize folders with plan approval, index documents, download models) with real progress, cancellation, retry, dependencies and conflict detection.
- **Local knowledge:** on-device document index (SQLite FTS4 + BM25) used as optional context.
- **Security first:** every action goes through validation → skill/capability checks → confirmation → execution → audit. AI output is treated as untrusted.

## Download

**On your Android phone, open:**

### 👉 https://github.com/melhive/ZENITH/releases/latest/download/zenith.apk

Tap the downloaded file and choose **Install** (allow "Install unknown apps" for your browser the first time).

- This link always gives the **newest version** — share it with anyone.
- Updates install **over** the old version and keep your data. ZENITH also shows an **Update available** card when a new release is out.
- All versions: https://github.com/melhive/ZENITH/releases

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, packages, intelligence layers, tool pipeline, task engine |
| [SECURITY.md](docs/SECURITY.md) | Security controls and permissions |
| [MODEL_SUPPORT.md](docs/MODEL_SUPPORT.md) | LiteRT-LM integration, verified models, online providers |
| [VOICE_MODE.md](docs/VOICE_MODE.md) | ZENITH LIVE pipeline and its limitations |
| [BUILDING.md](docs/BUILDING.md) | Remote builds, installing the APK, release signing |
| [ROADMAP.md](docs/ROADMAP.md) | Milestones and what's next |
| [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) | Current state, test results, known issues |

## Project layout

```
ZENITH/
├── core/      pure Kotlin: parser, tools, security, tasks, retrieval, VAD, prompts, skills (+ tests)
├── app/       Android app: UI, data, voice, models, providers, tasks, integrations (+ tests)
├── docs/      documentation
├── scripts/   test-core.sh (run core tests in Termux)
└── .github/workflows/  android-build.yml, release.yml
```

## Status

Actively developed. See [DEVELOPMENT_STATUS.md](DEVELOPMENT_STATUS.md) for what is verified, what only compiles, and what still needs testing on a physical device.
