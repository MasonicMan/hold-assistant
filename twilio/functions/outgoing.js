// Deployed automatically by the app's setup screen (see TwilioSetup.kt, which
// holds the same code). Voice URL of the "Hold Assistant" TwiML App: dials the
// number the app asks for, from the caller ID the app passes.
// Visibility: Protected (only Twilio, with a valid signature, can call it).
exports.handler = function (context, event, callback) {
  const twiml = new Twilio.twiml.VoiceResponse();
  const to = (event.To || '').trim();
  const from = (event.CallerId || '').trim();
  const e164 = /^\+[1-9][0-9]{7,14}$/;
  if (!e164.test(to) || !e164.test(from)) {
    twiml.say('Sorry, that number is not valid.');
    return callback(null, twiml);
  }
  const dial = twiml.dial({ callerId: from, answerOnBridge: true });
  dial.number(to);
  return callback(null, twiml);
};
