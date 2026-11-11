# Roadmap

| Milestone | Scope | Status |
|---|---|---|
| P0 Foundation | Gradle, Android config, CI, docs | Done |
| P1 Native application | Compose UI, theme, navigation, 10 screens | Done (compiled; UI device check pending) |
| P2 Data & memory | Room (schema v1, exported), DataStore, persistence, migration policy | Done |
| P3 Native intelligence | Command router, tool registry, validation, permission architecture | Done (unit-tested) |
| P4 Android integration | SAF files/folders, alarms, timers, calendar, notifications, reminders | Done (device validation pending) |
| P5 Chat Mode | Conversations, provider abstraction, streaming, tool requests, voice input, attachments | Done |
| P6 Local AI | LiteRT-LM integration, model loading, streaming inference, resource management | Integrated (on-device inference validation pending) |
| P7 Model Manager & online AI | Catalog, download/import/remove, switching, Gemini/OpenAI/Claude/compatible, Keystore keys | Done |
| P8 ZED LIVE | STT, TTS, VAD, barge-in, audio focus, orb | Done (device validation pending) |
| P9 Background tasks | Queue, WorkManager, progress, notifications, history, in-app indicator | Done |
| P10 Advanced intelligence | Local retrieval, skills, context management, research | Done (first version) |
| P11 Security & release | Audit, tests, release workflow | In progress |

## Next

1. Physical-device validation pass (voice, barge-in, LiteRT-LM inference speed/memory on the target phone, alarms/calendar apps, notification permission flows) and fixes.
2. Streaming offline STT (sherpa-onnx or Vosk) behind the `SpeechToText` interface for true continuous recognition.
3. Native LiteRT-LM tool calling (`ToolProvider`/`OpenApiTool`) as an alternative to the text protocol for models that support it.
4. Embedding-based retrieval (LiteRT-LM `EmbeddingEngine`) alongside FTS/BM25.
5. PDF text extraction for the knowledge index.
6. Instrumented UI tests on an emulator in CI.
7. Device benchmarks (tokens/s, load time, peak memory) recorded per model.
