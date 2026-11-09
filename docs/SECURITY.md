# Security

## Principles

1. **AI output is untrusted input.** Models can only *request* actions; the app decides.
2. **Least privilege.** No broad storage permission, no root, no overlay, no accessibility service, no calendar permission.
3. **Local by default.** Nothing leaves the device unless the user selects an online provider.

## Controls

| Area | Control | Where |
|---|---|---|
| Tool calls | Strict schema validation; unknown tools/arguments rejected; nested JSON rejected; max 3 calls per reply | `core/security/ToolCallExtractor`, `core/tools/ToolValidator` |
| File paths | Relative only; `..`, absolute paths, URIs, reserved and control characters rejected; depth/length limits | `core/security/PathPolicy` |
| File access | Storage Access Framework tree grants chosen by the user; scoped storage respected | `files/SafFileSystem` |
| Destructive actions | Always confirmed by the user; root folders cannot be deleted/renamed; move falls back to copy+verify+delete | `core/security/ConfirmationPolicy`, `tools/ToolManager` |
| Model-requested changes | Every modifying action requested by a model requires confirmation and shows "Requested by the AI model" | `ToolManager` |
| Skills | Disabled skills are invisible and denied to models | `ToolManager`, `core/skills` |
| Shell | There is no shell or code-execution tool | `core/tools/ToolCatalog` |
| Credentials | API keys/tokens encrypted with Android Keystore AES-256-GCM; never logged; redacted from errors and audit logs | `security/` , `core/security/SecretRedactor` |
| Network | HTTPS required for custom endpoints (plain HTTP only for localhost) | `OpenAiProvider.status()` |
| Prompt injection | Retrieved document excerpts are labelled untrusted in the system prompt; they cannot trigger tools without passing the pipeline | `core/intelligence/PromptBuilder` |
| Microphone | Used only on the visible LIVE screen or while the mic button is active; session ends when the screen closes; "Mic on" indicator | `voice/`, `ui/screens/LiveScreen` |
| Audit | Every tool attempt is recorded and viewable in Diagnostics; pruned after 90 days | `security/AuditLogger` |
| Model files | Catalog downloads verified by size and SHA-256; imports hashed and labelled unverified unless they match the catalog | `models/` |
| Backups | `allowBackup=false`; data-extraction rules exclude everything | `AndroidManifest.xml` |
| Build secrets | Signing material only from CI secrets / untracked `keystore.properties`; `.gitignore` blocks keystores | `app/build.gradle.kts`, `.github/workflows/release.yml` |

## Permissions

| Permission | Why | When requested |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Optional online providers and model downloads | Install time (normal) |
| `RECORD_AUDIO` | Voice input and ZENITH LIVE | When the user starts voice |
| `POST_NOTIFICATIONS` | Task results and reminders | From Diagnostics or first need |
| `SET_ALARM` | AlarmClock intents | Install time (normal) |
| `SCHEDULE_EXACT_ALARM` | Optional exact reminder timing | User grants in system settings |
| `RECEIVE_BOOT_COMPLETED` | Re-schedule reminders after reboot | Install time (normal) |
| `FOREGROUND_SERVICE(_DATA_SYNC)` | Long model downloads via WorkManager | Install time (normal) |

## Reporting

This is a personal project. Open a private security advisory on the GitHub repository for vulnerabilities.
