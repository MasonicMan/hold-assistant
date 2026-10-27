# Hold Assistant

An Android app that waits on hold for you. Call your bank through the app, then go back to Spotify or a video. The app listens to the line, transcribes it live and interrupts your audio only when the call needs you: a person picks up, or a phone menu wants input. When it switches you over, it replays the last few seconds so you don't miss the greeting.

It also imports the banks, carriers and agencies from your contacts. It tells "Chase Bank" from "Chase Miller" using the number (toll-free?), the name (known companies, words like "bank" or "insurance") and personal signals (starred, birthday, "Mom"). Claude can give a second opinion on the unclear ones.

**Setup:** [docs/SETUP.md](docs/SETUP.md). **Original spec:** [docs/spec.md](docs/spec.md).

## How it works

```
Twilio VoIP call ──► HoldAudioDevice ──► Deepgram (live transcript)
                        │                       │
                        │ 15 s ring buffer       ▼
                        │                Classifier: repeat match → phrase rules
                        │                → per-number fingerprint → Claude Haiku
                        ▼                       │
            your ears (only when alerted) ◄─────┘ alert: pause your media, chime, replay
```

- **Hold:** your media plays and call audio stays out of your ears. The app sends silence to the call.
- **Alert** (person, menu, or unsure): it pauses your media through audio focus, plays a chime, replays the last 8 s quickly to catch up, then you're live. Menu options ("press 2 for billing") become buttons in the app and notification.
- **False alarm:** if the line goes back to hold, your media resumes on its own.
- **Optional keyword check:** when the app is unsure, it says "I'm an automated assistant… could you say 'pineapple'?" and listens for the word, guarding against IVRs that repeat it back.
- **Learning:** each call stores the number's hold messages and menus, so later calls to that number need fewer guesses. Every call's transcript is saved. You can correct labels and share a transcript as a test scenario.

## Repo layout

| Path | What |
|---|---|
| `android/core` | Pure Kotlin: classifier, probe, menu parsing, contact sorting, Claude calls. Unit-tested on any JVM. |
| `android/app` | The Android app (Compose UI, call service, audio, Deepgram, Twilio). |
| `python/` | Classifier harness for fast experiments on transcripts. |
| `scenarios/` | Gold-labeled transcripts shared by both test suites. Add real calls here. |
| `twilio/functions` | The two Twilio Functions the app needs. |

## Development

```
cd python && pip install -r requirements.txt && python run_harness.py -v   # harness
cd android && ./gradlew :core:test                                         # Kotlin logic
cd android && ./gradlew :app:assembleDebug                                 # APK (needs Android SDK)
```

CI builds the APK on every push and publishes it as the `latest` release.
