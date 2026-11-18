const express = require('express');
const db = require('../db');
const { requireDevice } = require('../middleware/auth');

const router = express.Router();
const ID_RE = /^[\w.\-]{1,64}$/;

// The app calls this every 5 seconds
router.get('/', requireDevice, (req, res) => {
  const deviceId = String(req.query.deviceId || '');
  if (!ID_RE.test(deviceId)) return res.status(400).json({ error: 'invalid deviceId' });

  const name = String(req.query.name || '').slice(0, 64);
  db.touchDevice(deviceId, name);

  const cmd = db.takePending(deviceId);
  res.set('Cache-Control', 'no-store');
  res.json({ command: cmd ? { id: cmd.id, package: cmd.package } : null });
});

module.exports = router;
