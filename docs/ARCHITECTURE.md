# ZED Architecture

ZED is a native Android application (Kotlin, Jetpack Compose) split into two Gradle modules:

| Module | Type | Purpose |
|---|---|---|
| `:core` | Pure Kotlin/JVM | Deterministic logic with no Android dependencies: command parsing, time parsing, tool schemas and validation, security policy, task state machine, retrieval scoring, voice activity detection, prompt building, skills registry. Tested on any JVM (including Termux). |
| `:app` | Android application | Everything that touches Android: UI, Room, DataStore, Keystore, WorkManager, SAF, intents, audio, LiteRT-LM, HTTP providers. |

## Package map (`app/src/main/java/com/melhive/zed`)

| Package | Responsibility |
|---|---|
| `AppContainer`, `ZedApp` | Manual dependency container (one per process) and startup recovery |
| `agents/` | `AssistantOrchestrator` (one user turn end-to-end), `ConfirmationBroker`, `WorkflowEngine` |
| `intelligence/` | `IntelligenceProvider` abstraction, `ProviderRegistry`, `LocalModelRuntime` (LiteRT-LM), online providers (`providers/`) |
| `tools/` | `ToolManager` (the only path to Android operations), executors, capability checker |
| `tasks/` | `TaskEngine` (persistent queue) and `TaskWorker` (WorkManager execution) |
| `voice/` | STT/TTS interfaces + Android implementations, `BargeInMonitor`, `AudioFocus`, `LiveSessionController` |
| `files/` | `SafFileSystem` — file operations limited to user-granted folders |
| `knowledge/` | On-device document index (SQLite FTS4 + BM25 ranking) |
| `memory/` | Conversation, memory-fact and task-list repositories |
| `models/` | Verified model catalog, installed-model repository, resumable downloader |
| `database/` | Room entities, DAOs, database (schema exported to `app/schemas`) |
| `security/` | Keystore cipher, secret store, audit logger |
| `settings/` | DataStore-backed settings |
| `android/` | Notifications, system intents (alarms, timers, calendar), reminders, device info, permissions |
| `ui/` | Theme, components (orb, cards), navigation, screens |

## Three intelligence layers

```
             user text / speech
                     │
                     ▼
        ┌────────────────────────────┐
        │ 1. Native CommandParser    │  deterministic, offline, no model
        └────────────┬───────────────┘
            matched? │ no
                     ▼
        ┌────────────────────────────┐
        │ ProviderRegistry.active()  │
        │  2. LocalLiteRtProvider    │  on-device model (LiteRT-LM)
        │  3. Online provider        │  Gemini / OpenAI / Claude / compatible
        └────────────┬───────────────┘
           none?     │ yes
     native fallback ▼
              streamed reply ──► ToolCallExtractor ──► ToolManager ──► result fed back (≤ 3 rounds)
```

Native commands are always tried first, so alarms, files, reminders, tasks, memory and settings work with no model and no network.

## Tool execution pipeline (security boundary)

```
Request (native | local model | online model | workflow | UI)
  → ToolValidator      strict schema: unknown tools/args rejected, types/ranges checked, paths normalized
  → skill check        AI requests only reach tools of enabled skills
  → capability check   folder grant, notifications, clock/calendar app, network, online provider
  → ConfirmationPolicy DESTRUCTIVE always; MODIFY when requested by a model or "confirm every change"
  → executor           Android API (SAF, intents, AlarmManager, Room, WorkManager)
  → AuditLogger        every attempt recorded (executed / denied / invalid / failed / cancelled)
```

Model output is never executed directly. Tool calls must appear in a ```` ```zed-tool ```` fenced JSON block with only `tool` and scalar `args`; anything else is ignored.

## Task engine

Tasks are rows in Room (`tasks`, `task_logs`) executed by `TaskWorker` (WorkManager). States are enforced by `core.tasks.TaskStateMachine`:

```
QUEUED → PLANNING → AWAITING_CONFIRMATION → QUEUED → RUNNING → SUCCEEDED | FAILED | CANCELLED
FAILED/CANCELLED → QUEUED (explicit retry)
```

- **Organize** inspects a folder, stores a plan, waits for approval, then moves files one by one (progress = files processed).
- **Index** walks text files and reports files processed / total.
- **Model download** runs as foreground work (`dataSync`), reports bytes, verifies SHA-256.
- Dependencies (`dependsOn`) and conflicts (overlapping resource keys) are checked before execution.
- Closing ZED LIVE or leaving a screen never cancels tasks; only Cancel / Cancel all do.
- After process death, tasks that were mid-run are marked failed with an explicit retry (never silently re-run).

## Data

All data is local. Room database `zed.db` (version 1, schema exported). Settings in DataStore. Secrets in a separate SharedPreferences file, encrypted with an AES-256-GCM key held in Android Keystore. Backups and device transfer are disabled.
