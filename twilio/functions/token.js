// GET /token?key=<APP_SECRET>  ->  {"token": "<Twilio Voice access token>"}
// Visibility: Public (the shared APP_SECRET is the gate).
exports.handler = function (context, event, callback) {
  const response = new Twilio.Response();
  response.appendHeader('Content-Type', 'application/json');

  if (!context.APP_SECRET || event.key !== context.APP_SECRET) {
    response.setStatusCode(401);
    response.setBody({ error: 'unauthorized' });
    return callback(null, response);
  }

  const AccessToken = Twilio.jwt.AccessToken;
  const token = new AccessToken(context.ACCOUNT_SID, context.API_KEY_SID, context.API_KEY_SECRET, {
    identity: 'hold-assistant',
    ttl: 3600,
  });
  token.addGrant(new AccessToken.VoiceGrant({
    outgoingApplicationSid: context.TWIML_APP_SID,
    incomingAllow: false,
  }));

  response.setBody({ token: token.toJwt() });
  return callback(null, response);
};
