# ZENITH LIVE — Voice Mode

## What is implemented

| Capability | Implementation | Status |
|---|---|---|
| Microphone capture | Android `SpeechRecognizer` (per utterance); `AudioRecord` (VOICE_COMMUNICATION) for barge-in | Compiled; needs device validation |
| Speech-to-text | `AndroidSpeechToText`: on-device recognizer (API 31+) when available, otherwise the system recognition service with `EXTRA_PREFER_OFFLINE` | Compiled; offline depends on installed language packs |
| Endpoint detection | Recognizer endpointing (1.2 s silence hint) | Compiled |
| Voice activity detection | `core.voice.VoiceActivityDetector` (adaptive energy VAD) for barge-in | Unit-tested |
| Text-to-speech | `AndroidTextToSpeech`; prefers an installed voice that does not need the network | Compiled |
| Streaming responses | Model output is spoken **sentence by sentence while it is still being generated** | Compiled |
| Barge-in | While speaking, `BargeInMonitor` listens with the platform acoustic echo canceller + noise suppressor; detected speech stops playback, cancels generation if still running, and starts listening | Compiled; **reliability depends on the device echo canceller** |
| Interruption | Tap the orb while speaking | Compiled |
| Mic mute / speaker toggle / cancel request / end session | Buttons on the LIVE screen | Compiled |
| Transcript | Session transcript on screen and persisted as a "ZENITH LIVE session" conversation | Compiled |
| Audio focus | `AUDIOFOCUS_GAIN_TRANSIENT` (USAGE_ASSISTANT); session pauses on focus loss (calls) | Compiled |
| Error recovery | Recoverable recognizer errors retry automatically; empty results re-listen once then idle | Compiled |
| Session vs tasks | Ending the session never cancels background tasks | Compiled |

## Honest limitations

- **Speech recognition is turn-based per utterance**, not a continuous raw-audio stream. The conversation loop is continuous (listen → think → speak → listen) but each user utterance is recognized as one request by Android's recognizer.
- After barge-in the recognizer starts ~200 ms after speech was detected, so the first syllable may be lost.
- Echo cancellation quality varies by phone. Barge-in uses stricter thresholds when no echo canceller is available; it can be turned off in Settings.
- The speaking orb pulses on real TTS word boundaries (`onRangeStart`), not on the audio waveform; engines that do not report word ranges will show a steady speaking state.
- Offline STT requires the device's offline speech pack for your language; Diagnostics shows what is available.

## Replaceability

`SpeechToText` and `TextToSpeechEngine` are interfaces. A streaming offline recognizer (for example sherpa-onnx or Vosk) can be added by implementing `SpeechToText` and changing the factory in `AppContainer` — the session controller does not change.

## Privacy

The microphone is used only while the LIVE screen is visible (or the chat mic button is active). A red "Mic on" pill shows while listening. Leaving the screen ends the session and releases the microphone.
