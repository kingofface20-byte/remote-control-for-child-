const crypto = require('crypto');

const SESSION_MS = 7 * 24 * 3600 * 1000;
const sessions = new Map(); // session id -> expiry timestamp

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function checkPassword(password) {
  return safeEqual(password || '', process.env.DASHBOARD_PASSWORD);
}

function createSession() {
  const id = crypto.randomBytes(32).toString('hex');
  sessions.set(id, Date.now() + SESSION_MS);
  return id;
}

function destroySession(id) {
  sessions.delete(id);
}

function isValid(id) {
  const expiry = sessions.get(id);
  if (!expiry) return false;
  if (expiry < Date.now()) {
    sessions.delete(id);
    return false;
  }
  return true;
}

// Used by the Android app: Authorization: Bearer <TOKEN>
function requireDevice(req, res, next) {
  const match = /^Bearer (.+)$/.exec(req.get('authorization') || '');
  if (match && safeEqual(match[1], process.env.TOKEN)) return next();
  res.status(401).json({ error: 'unauthorized' });
}

// Used by the dashboard: session cookie
function requireSession(req, res, next) {
  if (isValid(req.cookies.sid)) return next();
  res.status(401).json({ error: 'login required' });
}

module.exports = {
  checkPassword,
  createSession,
  destroySession,
  isValid,
  requireDevice,
  requireSession,
};
