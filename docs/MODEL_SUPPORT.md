# Model Support

## Runtimes

| Layer | Runtime | Status |
|---|---|---|
| Native | Deterministic `CommandParser` (no model) | Implemented, unit-tested |
| On-device | Google **LiteRT-LM** `com.google.ai.edge.litertlm:litertlm-android:0.19.1` | Integrated; compiles against the real API. **Inference requires physical-device validation.** |
| Online | Gemini API, OpenAI (Chat Completions + Responses for research), Anthropic Messages API, OpenAI-compatible endpoints | Implemented over HTTPS + SSE; parsing unit-tested with a mock server. Live calls need your API key. |

### LiteRT-LM facts (verified from the published AAR, Nov 2026)

- Native libraries: `arm64-v8a`, `x86_64` only (the APK is restricted to these ABIs).
- `minSdkVersion` 24 (ZED uses 26).
- API used: `Engine(EngineConfig(modelPath, backend, cacheDir))`, `engine.initialize()`, `engine.createConversation(ConversationConfig(systemInstruction, initialMessages, samplerConfig))`, `conversation.sendMessageAsync(text): Flow<Message>`, `conversation.cancelProcess()`.
- Backends: `Backend.CPU()`, `Backend.GPU()` (also NPU variants not used yet).
- Model format: `.litertlm`.

## Verified download catalog

Checked against the Hugging Face API (file size, SHA-256 of the LFS object, license, gating):

| Model | File | Size | License | Gated |
|---|---|---|---|---|
| Gemma 4 E2B Instruct | `litert-community/gemma-4-E2B-it-litert-lm` / `gemma-4-E2B-it.litertlm` | 2.59 GB | Apache-2.0 | No |
| Gemma 4 E2B Instruct (GPU) | same repo / `gemma-4-E2B-it-gpu.litertlm` | 2.01 GB | Apache-2.0 | No |
| Gemma 4 E4B Instruct (GPU) | `litert-community/gemma-4-E4B-it-litert-lm` / `gemma-4-E4B-it-gpu.litertlm` | 2.97 GB | Apache-2.0 | No |
| Qwen3 0.6B int4 | `litert-community/Qwen3-0.6B-int4` / `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` | 347 MB | Apache-2.0 | No |

RAM recommendations in the catalog (6 GB for E2B, 8 GB for E4B, 3 GB for Qwen3 0.6B) are conservative estimates used for the compatibility check, **not measured guarantees**. Context length shown is the conservative value ZED assumes; the actual maximum depends on the model build.

### What is NOT assumed

- A model downloaded in **Google AI Edge Gallery** lives in that app's private storage and is not accessible to ZED. Download again in ZED or import a `.litertlm` file you copied yourself.
- GPU acceleration depends on the device GPU and driver. If `GPU` fails to load, switch to `CPU` in Model Manager.
- Gemma 3 1B (`litert-community/Gemma3-1B-IT`) is gated on Hugging Face and is not in the catalog. A token field exists for future gated models.
- Nothing is downloaded automatically. Every download is user-initiated, shows its size and compatibility first, and is refused when the device clearly cannot run it.

## Model Manager features

| Feature | Status |
|---|---|
| View installed / active model, sizes, verification state | Implemented |
| Switch active model, CPU/GPU backend, load/unload | Implemented |
| Download catalog models (resumable, SHA-256 verified, background task with byte progress) | Implemented |
| Import `.litertlm` via the system file picker (hashed, matched against catalog) | Implemented |
| Remove models | Implemented |
| Compatibility (ABI, RAM, free storage) | Implemented (heuristic) |
| Online provider selection, API keys (Keystore), model name, list models, test connection | Implemented |

## Tool use with models

Both local and online models use the same text protocol (fenced `zed-tool` JSON blocks) so behaviour is identical across providers and every call passes the `ToolManager`. Small on-device models may not follow the protocol reliably; native commands remain the dependable path for device actions.

## Online research

`web.research` uses the provider's own hosted search: Gemini `google_search` tool, Claude `web_search_20260209` server tool, OpenAI Responses API `web_search` tool. OpenAI-compatible endpoints do not support research.
