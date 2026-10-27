# Setup

Setup happens inside the app: tap **Set up calling** on the home screen. This page explains what each step does, and the one part that has to happen on Twilio's website.

## What you need

| | Required? | Where you pay |
|---|---|---|
| Twilio account with funds | Yes | twilio.com (a card, then prepaid funds) |
| Anthropic API key | Optional, recommended | console.anthropic.com (web search for number lookups must be allowed in the Console's settings) |
| Deepgram API key | Only if your phone can't transcribe on its own | console.deepgram.com (free starting credit) |

**Why payment isn't in the app:** calls run through your own Twilio account. Twilio has no API for adding a card or upgrading an account; that's only possible on their website. Paying Twilio directly also means you pay their cost with no markup, and this app never handles card details.

## Steps in the app

1. **Twilio account.** Tap *Create account* and sign up. Then, in the Twilio console:
   - Tap **Upgrade**.
   - Add a card.
   - Add funds. $20 covers many hours on hold. Trial accounts can't call banks.

   Back in the app, paste the **Account SID** and **Auth Token** from the console home page and tap **Connect**. The app shows the account name and balance, and warns you if it's still a trial.
2. **Caller ID.** Enter your cell number and tap *Verify by phone call*. Twilio calls you; type the code the app shows. Banks then see your real number.
3. **Finish calling setup.** One tap. In your Twilio account, the app creates:
   - an API key,
   - a small *Hold Assistant* Function that dials out (the same code as [`twilio/functions/outgoing.js`](../twilio/functions/outgoing.js)),
   - a TwiML App pointing at that Function.

   It's safe to run again.
4. **AI key (optional).** Paste an Anthropic key. Claude Haiku then labels transcript lines the built-in rules can't place, which means fewer false alarms from hold ads. It also looks up the names of unknown numbers in your call history (a web search, about 1¢ per number, only when you tap it).
5. **Transcription.** On Android 13 and later, with Google's on-device speech model, transcription is free and runs on the phone. A Deepgram key is optional; it's more accurate on phone audio. Phones without on-device recognition need the Deepgram key.

## Install the app

1. On your phone, open the repo's **Releases** page. Sign in to GitHub in the browser, since the repo is private.
2. Download `hold-assistant.apk` from **Latest build** and open it.
3. Allow installs from your browser when Android asks.

Every push to `main` builds a new APK. Install it over the old one; your data stays.

## Costs (rough; check current pricing)

About 2½¢ per minute on a call: Twilio's outbound call plus the app's VoIP leg, plus Deepgram if you use it. On-device transcription is free, and Claude is a fraction of a cent per call. If you verify your own cell as the caller ID, there's no monthly fee. The home screen shows your Twilio balance.

## What's stored where

- **On the phone** (app-private storage): the Twilio Account SID and Auth Token, the API key the app created, and your Anthropic and Deepgram keys. Uninstalling the app removes them.
- **In your Twilio account:** the API key, the `hold-assistant` Function service and the *Hold Assistant* TwiML App. You can delete them from the Twilio console at any time.

## Troubleshooting

- **"Twilio didn't accept the Account SID and Auth Token":** copy them again from the console home page. The Auth Token is hidden until you click *Show*.
- **The call fails right away:** make sure the account is upgraded and the caller ID is verified. For calls outside the US, enable the country under *Voice → Settings → Geo permissions*.
- **No transcript with on-device transcription:** the app downloads Google's English speech model on first use, which can take a minute. If it still doesn't work, add a Deepgram key.
- **Music doesn't resume after "Back to hold":** some players don't resume after an interruption. Press play once.
