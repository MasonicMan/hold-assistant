# Hold Assistant: Android App Spec

## Goal
An Android app for calls to automated lines (banks, large companies). While on hold, it plays the user's own audio (Spotify or video) and interrupts it only when the call needs the user: a human picks up, or an IVR menu needs input. A live status and transcript sit in the notification shade so nothing said before the user tuned in is lost.

## Key architectural decision
The call must run **through the app over VoIP**, not the native dialer. Android 10+ blocks third-party apps from capturing cellular call audio, so this is the only reliable way to receive the remote audio stream.

- Call endpoint: Twilio Voice SDK (or SIP via Telnyx)
- Tradeoffs to accept: VoIP caller ID, per-minute cost, some systems that call back or need the user's real number will not work well
- Scope: calls to automated lines (banks, big companies)

## Components
1. **Kotlin Android app** with a foreground service that owns the call
2. **Streaming speech-to-text** on the remote audio (Deepgram, Google STT, or on-device Whisper/Vosk)
3. **Classifier pipeline** (below)
4. **Audio router**: user's media audio vs. call audio, using Android audio focus and media sessions
5. **Ring buffer**: last ~15 seconds of call audio, replayed at pickup so the greeting isn't missed
6. **Notification**: live state, latest transcript lines (expandable), and a "press 1"-style DTMF button when in a menu
7. **Probe module** (verification prompts, below)

## Classifier pipeline (cheapest first)
1. **Repeat match**: fuzzy-match new transcript text against earlier segments. A match means `hold`.
2. **Phrase rules**: regex/fuzzy matching against curated lists per state.
3. **Small LLM** (Haiku-class): only for text tiers 1-2 can't place. Runs every few seconds on the last ~30 s of transcript and returns a state plus confidence.

### States
- `hold`: music, silence, "your call is important to us," "thank you for holding," "estimated wait time," "all agents are currently assisting," "answered in the order received"
- `ivr_menu`: "press 1," "say or enter," "for billing," "tell me what you're calling about." Interrupts the user's audio, since menus need input.
- `human`: "my name is," "how can I help you," "thank you for calling X, this is," "may I have your name"
- `unsure`: anything else. **Treat `unsure` as `human` for alerting**: a false alarm costs a second, a missed human costs the user's place in line.
- Special cases worth alerting on: "this call may be recorded" (informational), "you've reached the end of the menu," "experiencing high call volume" (often a callback offer or disconnect).

Rules: a phrase match is a strong signal, not a verdict. Human-style cues ("my name is," a question aimed at the user) override a hold match, because a human may also say "thank you for holding."

### Per-number learning
Store each dialed number's fingerprints (hold message, menu, greeting). On repeat calls to the same number, anything not matching its known hold loop is almost certainly a human, so common numbers need little to no LLM inference. Log transcripts and user corrections to mine and refine phrase lists. An LLM can bootstrap the starter lists, but they should be checked against real calls.

## Verification probe (when `unsure` persists)
1. Classifier stays `unsure` for a few seconds (never during a known menu).
2. App mutes the user's mic and speaks a short, honest, clearly automated prompt, e.g.: "Hi, I'm an automated assistant holding for someone. Could you say the word 'pineapple' so I can connect them?"
3. If the transcript contains the keyword within ~8 s, treat as `human`: alert, switch to call audio.
4. Otherwise return to hold mode and keep listening. Cooldown: at most one probe per unsure event.

Keyword rules:
- Random per call from a vetted list. Multi-syllable, common, no homophones (e.g. pineapple, umbrella, tomorrow, elephant).
- Never use words IVRs act on: agent, representative, yes, operator, billing.
- Fuzzy-match, since STT will sometimes mangle it.
- Ask for the word twice, and have the LLM check context around a match, to guard against IVRs that echo back ("You said pineapple, is that correct?").
- Probing an IVR can reset or misroute it, so make probing opt-in per call.
- The prompt must disclose it is automated and must not pose as the user.
- Implementation: Twilio custom audio input, or server-side with bidirectional media streams (cleaner).

## Playback behavior
- Normal state: user's Spotify/video audio plays; call audio is not routed to the ear.
- On `human`, `ivr_menu`, or `unsure`-escalation: loud alert, pause/duck the media session, fade in call audio, replay the ring buffer.

## Legal note
Transcribing calls can touch recording-consent laws in some states. Automated lines usually announce "this call may be recorded," which generally covers it, but worth being aware of.

## Suggested build order
1. **Classifier test harness (standalone script)**: sample transcripts for each state, phrase lists, repeat-match, LLM tier. This is the riskiest piece and cheap to validate first.
2. **Probe logic** as a state in the harness: timing rules and keyword list, tested on transcripts only.
3. **Android project scaffold**: foreground service, notification, Twilio call, STT wiring, classifier stubbed.
4. **Audio routing**: media session handling, alert, fade-in, ring buffer replay.
5. **Probe audio injection** into the live call.
6. **Per-number fingerprint storage** and transcript logging.

## Rough effort
Working prototype (VoIP call + streaming STT + repeat/phrase detection + notification): a weekend or two for someone comfortable with Android. The long tail is classification reliability, audio focus quirks, and battery use.

## Existing alternatives (worth a glance before building)
Pixel phones have a built-in "Hold for Me" feature, and some companies offer callback queues.
