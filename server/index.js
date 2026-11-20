const path = require('path');
const express = require('express');
const cookieParser = require('cookie-parser');
const db = require('./db');
const auth = require('./middleware/auth');
const { loginLimiter } = require('./middleware/rateLimit');

if (!process.env.TOKEN || !process.env.DASHBOARD_PASSWORD) {
  console.error('Set TOKEN and DASHBOARD_PASSWORD environment variables.');
  process.exit(1);
}

db.load();

const app = express();
app.set('trust proxy', 1); // Render sits behind a proxy
app.use(express.json({ limit: '10kb' }));
app.use(cookieParser());

app.get('/healthz', (req, res) => res.send('ok'));

app.post('/api/login', loginLimiter, (req, res) => {
  if (!auth.checkPassword(String(req.body.password || ''))) {
    return res.status(401).json({ error: 'wrong password' });
  }
  res.cookie('sid', auth.createSession(), {
    httpOnly: true,
    sameSite: 'strict',
    secure: req.secure,
    maxAge: 7 * 24 * 3600 * 1000,
  });
  res.json({ ok: true });
});

app.post('/api/logout', (req, res) => {
  auth.destroySession(req.cookies.sid);
  res.clearCookie('sid');
  res.json({ ok: true });
});

app.use('/api/commands', require('./routes/commands'));
app.use('/api/devices', require('./routes/devices'));
app.use('/api/poll', require('./routes/poll'));
app.use('/api/result', require('./routes/result'));
app.use('/api/autoclick', require('./routes/autoclick'));

// Dashboard page requires a login session
app.get('/', (req, res) => {
  if (!auth.isValid(req.cookies.sid)) return res.redirect('/login.html');
  res.sendFile(path.join(__dirname, 'public', 'index.html'));
});

app.use(express.static(path.join(__dirname, 'public'), { index: false }));

// JSON error handler (for example malformed JSON bodies)
app.use((err, req, res, next) => {
  res.status(err.status || 500).json({ error: 'request failed' });
});

const port = process.env.PORT || 3000;
app.listen(port, () => console.log('Server listening on port ' + port));
