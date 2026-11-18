const express = require('express');
const db = require('../db');
const { requireSession } = require('../middleware/auth');

const router = express.Router();
const ONLINE_MS = 20 * 1000;

router.get('/', requireSession, (req, res) => {
  const now = Date.now();
  res.json(db.listDevices().map((d) => ({ ...d, online: now - d.lastSeen < ONLINE_MS })));
});

module.exports = router;
