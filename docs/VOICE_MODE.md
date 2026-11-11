# ZED LIVE — Voice Mode

## What is implemented

| Capability | Implementation | Status |
|---|---|---|
| Microphone capture | Android `SpeechRecognizer` (per utterance); `AudioRecord` (VOICE_COMMUNICATION) for barge-in | Compiled; needs device validation |
| Speech-to-text | `AndroidSpeechToText`: on-device recognizer (API 31+) when available, otherwise the system recognition service with `EXTRA_PREFER_OFFLINE` | Compiled; offline depends on installed language packs |
| Endpoint detection | Recognizer endpointing (1.2 s silence hint) | Compiled |
| Voice activity detection | `core.voice.VoiceActivityDetector` (adaptive energy VAD) for barge-in | Unit-tested |
| Text-to-speech | `VoiceManager` → offline neural voice (`NeuralTextToSpeech`, sherpa-onnx) or `AndroidTextToSpeech` (any installed phone voice); falls back to the phone voice if a neural voice cannot load | Compiled; neural voices need device validation |
| Voice choice, speed, pitch | Voice screen (More → Voice): 10 Supertonic + 28 Kokoro voices, phone voices, speed 0.5–2×, pitch 0.7–1.3× with presets and preview | Compiled |
| Streaming responses | Model output is spoken **sentence by sentence while it is still being generated** | Compiled |
| Barge-in | With headphones connected, `BargeInMonitor` (VOICE_RECOGNITION source, so the phone never enters call mode) detects speech, stops playback and starts listening. On the loudspeaker: tap the orb. | Compiled |
| Interruption | Tap the orb while speaking | Compiled |
| Mic mute / speaker toggle / cancel request / end session | Buttons on the LIVE screen | Compiled |
| Transcript | Session transcript on screen and persisted as a "ZED LIVE session" conversation | Compiled |
| Audio focus | `AUDIOFOCUS_GAIN_TRANSIENT` (USAGE_ASSISTANT); session pauses on focus loss (calls) | Compiled |
| Error recovery | Recoverable recognizer errors retry automatically; empty results re-listen once then idle | Compiled |
| Session vs tasks | Ending the session never cancels background tasks | Compiled |

## Offline neural voices

| Pack | Voices | Download | Strengths | Engine input |
|---|---|---|---|---|
| Supertonic 3 (int8) | 10 (F1–F5, M1–M5) | 129 MB | Very fast, reads numbers/currency itself, 31 languages | Raw text + `lang` from the phone locale |
| Kokoro v1.0 (int8) | 28 English (American + British) | 132 MB | Most expressive | Phonemes from ZED's `EnglishPhonemizer` |

- Packs come from the sherpa-onnx `tts-models` release; size and SHA-256 are pinned in `core.voice.VoiceCatalog` and verified after a resumable download (a background task, like model downloads). Only the needed files are extracted to `files/voices/<pack>/`.
- The sherpa-onnx 2.0.1 AAR (static ONNX Runtime) is downloaded and checksum-verified by `app/build.gradle.kts`; it is not committed. Only the arm64-v8a library is packaged (~24 MB); on x86/x86_64 neural voices report "not supported" and the phone voice is used.
- **Why ZED phonemizes Kokoro text itself:** since sherpa-onnx 2.0, Kokoro's built-in frontend is dictionary-only and silently skips unknown words — including every digit and common inflections such as "minutes" or "emails". ZED normalizes text (`SpeechNormalizer`: numbers, times, dates, money, units, symbols, abbreviations) and converts it with `EnglishPhonemizer` (dictionary → -s/-ed/-ing/'s rules → compounds → acronyms → letter-to-sound), then passes phonemes via `GenerationConfig.phonemeCodepoints`. Verified against the real 178k-entry lexicon: no words dropped for typical assistant replies.
- Playback: a dedicated synthesis thread renders one sentence ahead while an `AudioTrack` (16-bit PCM, media usage) plays; `stop()` flushes immediately (barge-in) and the generation callback aborts in-progress synthesis. Orb pulses come from the audio's loudness. Pitch uses `PlaybackParams` (tempo preserved).
- Memory: a loaded neural voice uses roughly 100–300 MB; it is released when ZED goes to the background under memory pressure and reloads on next use (Kokoro's dictionary takes ~2–3 s to load).

## Honest limitations

- **Speech recognition is turn-based per utterance**, not a continuous raw-audio stream. The conversation loop is continuous (listen → think → speak → listen) but each user utterance is recognized as one request by Android's recognizer.
- After barge-in the recognizer starts ~200 ms after speech was detected, so the first syllable may be lost.
- Echo cancellation quality varies by phone. Barge-in uses stricter thresholds when no echo canceller is available; it can be turned off in Settings.
- With phone voices the speaking orb pulses on TTS word boundaries (`onRangeStart`); engines that do not report word ranges show a steady speaking state. Neural voices pulse on the real audio level.
- Neural voice speed on the phone is not measured yet. Supertonic is expected to start almost instantly; Kokoro takes noticeably longer per sentence on mid-range phones.
- Supertonic voice names (Aria, Atlas, …) are ZED labels for the F1–F5/M1–M5 presets. Kokoro's quality grades come from the Kokoro-82M voice list.
- Letter-to-sound rules for names not in the dictionary are approximate.
- Offline STT requires the device's offline speech pack for your language; Diagnostics shows what is available.

## Replaceability

`SpeechToText` and `TextToSpeechEngine` are interfaces. A streaming offline recognizer (for example sherpa-onnx or Vosk) can be added by implementing `SpeechToText` and changing the factory in `AppContainer` — the session controller does not change.

## Privacy

The microphone is used only while the LIVE screen is visible (or the chat mic button is active). A red "Mic on" pill shows while listening. Leaving the screen ends the session and releases the microphone.
