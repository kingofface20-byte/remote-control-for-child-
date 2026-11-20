const express = require('express');
const db = require('../db');
const { requireSession } = require('../middleware/auth');
const { commandLimiter } = require('../middleware/rateLimit');

const router = express.Router();
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ID_RE = /^[\w.\-]{1,64}$/;

// Create a command for a device
router.post('/', requireSession, commandLimiter, (req, res) => {
  const deviceId = String(req.body.deviceId || '').trim();
  const pkg = String(req.body.package || '').trim();

  if (!ID_RE.test(deviceId)) return res.status(400).json({ error: 'invalid deviceId' });
  const isScreenOff = pkg === 'screen_off';
  if (!isScreenOff && (pkg.length > 200 || !PKG_RE.test(pkg))) {
    return res.status(400).json({ error: 'invalid package name' });
  }
  if (!db.deviceExists(deviceId)) return res.status(404).json({ error: 'device not found' });

  res.status(201).json(db.addCommand(deviceId, pkg));
});

// Command history
router.get('/', requireSession, (req, res) => {
  res.json(db.listCommands(50));
});

module.exports = router;
