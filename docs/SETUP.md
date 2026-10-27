# Setup

You set this up once. It takes about 20 minutes and needs three accounts: Twilio (calls), Deepgram (transcription) and, optionally, Anthropic (smarter detection).

## Why calls go through Twilio

Android doesn't let apps hear cellular calls. So Hold Assistant places the call itself over the internet (VoIP) through your own Twilio account. That way the app can hear the line, transcribe it and keep it out of your ears while you're on hold.

## 1. Twilio

1. Sign up at twilio.com. **Upgrade the account (add funds).** Trial accounts can only call numbers you've verified, and they play a trial message, so a bank's line won't work on a trial.
2. **Caller ID.** You have two options:
   - Recommended: verify your own cell number under *Phone Numbers → Manage → Verified Caller IDs*. Banks then see your real number, which helps when they look up your account by caller ID.
   - Or buy a Twilio number (about a dollar a month) and use that.
3. **API key.** Go to *Account → API keys & tokens → Create API key* (type: Standard). Note the SID (`SK…`) and the secret.
4. **Functions.** Go to *Functions and Assets → Services → Create Service* (name it `hold-assistant`).
   - Add a function at path `/token`, set it to **Public**, and paste in [`twilio/functions/token.js`](../twilio/functions/token.js).
   - Add a function at path `/outgoing`, set it to **Protected**, and paste in [`twilio/functions/outgoing.js`](../twilio/functions/outgoing.js).
   - Under *Settings → Environment Variables*, add:

     | Key | Value |
     |---|---|
     | `API_KEY_SID` | the `SK…` SID from step 3 |
     | `API_KEY_SECRET` | the secret from step 3 |
     | `TWIML_APP_SID` | from step 5 (come back for this) |
     | `CALLER_ID` | your verified cell or Twilio number, like `+15125550100` |
     | `APP_SECRET` | any long random string; you'll type it into the app |

     Keep *Add my Twilio Credentials (ACCOUNT_SID) and (AUTH_TOKEN) to ENV* checked.
   - Click **Deploy All**. Note the domain, for example `hold-assistant-1234.twil.io`.
5. **TwiML App.** Go to *Voice → Manage → TwiML apps → Create*. Set the Voice Request URL to `https://<your domain>/outgoing` (HTTP POST). Copy the App SID (`AP…`) into `TWIML_APP_SID` from step 4, then deploy again.
6. Check it: open `https://<your domain>/token?key=<APP_SECRET>` in a browser. You should see `{"token":"eyJ…"}`.

## 2. Deepgram

Sign up at deepgram.com (new accounts get free credit), create an API key and copy it.

## 3. Anthropic (optional)

Create a key at console.anthropic.com. With it, Claude Haiku labels transcript lines the built-in rules can't place, which means fewer false alarms from hold ads. It also helps sort unclear contacts during import.

## 4. Install the app

1. Open the repo's **Releases** page on your phone (sign in to GitHub in the browser, since the repo is private) and download `hold-assistant.apk` from **Latest build**.
2. Open it. Android will ask you to allow installs from your browser. Allow it, then install.
3. Every push to `main` builds a new APK. Install it over the old one; your data stays.

## 5. In the app

1. **Settings:** enter the token URL (`https://<your domain>/token`), the app secret, the Deepgram key and, optionally, the Anthropic key.
2. **Import banks and companies from contacts:** the app reads your address book (Google contacts sync into it) and pre-checks contacts that look like institutions. Uncheck any you don't need, then tap **Add**.
3. Tap the call button on a line, start your music, and wait for the chime.

## Costs (rough; check current pricing)

- Twilio: a few cents per minute for the outbound call and the app's VoIP leg, plus about a dollar a month if you buy a number.
- Deepgram: under a cent per minute of streaming transcription.
- Claude Haiku: a tiny fraction of a cent per check. Most lines are handled by the free rules.

## Troubleshooting

- **"Token server rejected the app secret":** the app secret in the app doesn't match `APP_SECRET` in Twilio.
- **Call fails right away (Twilio 31xxx or 21xxx errors):** check `TWIML_APP_SID`, `CALLER_ID`, and that the account is upgraded. For calls outside the US, enable the country under *Voice → Settings → Geo permissions*.
- **No transcript:** check the Deepgram key. The call screen shows an error if Deepgram rejects it.
- **Music doesn't resume after "Back to hold":** some players don't resume after a transient interruption. Press play once; later alerts pause and resume normally.
