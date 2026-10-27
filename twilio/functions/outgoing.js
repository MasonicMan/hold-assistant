// Voice URL of the TwiML App. Twilio calls this when the app places a call;
// it dials the number the app asked for.
// Visibility: Protected (only Twilio, with a valid signature, can call it).
exports.handler = function (context, event, callback) {
  const twiml = new Twilio.twiml.VoiceResponse();
  const to = (event.To || '').trim();

  if (!/^\+[1-9][0-9]{7,14}$/.test(to)) {
    twiml.say('Sorry, that number is not valid.');
    return callback(null, twiml);
  }

  const dial = twiml.dial({ callerId: context.CALLER_ID, answerOnBridge: true });
  dial.number(to);
  return callback(null, twiml);
};
