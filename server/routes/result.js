const express = require('express');
const db = require('../db');
const { requireDevice } = require('../middleware/auth');

const router = express.Router();
const ID_RE = /^[\w.\-]{1,64}$/;
const ALLOWED = ['launched', 'not_installed', 'cannot_launch', 'error'];

router.post('/', requireDevice, (req, res) => {
  const deviceId = String(req.body.deviceId || '');
  const commandId = Number(req.body.commandId);
  const result = String(req.body.result || '');

  if (!ID_RE.test(deviceId) || !Number.isInteger(commandId) || !ALLOWED.includes(result)) {
    return res.status(400).json({ error: 'invalid body' });
  }

  const cmd = db.finishCommand(commandId, deviceId, result);
  if (!cmd) return res.status(404).json({ error: 'command not found or already finished' });
  res.json({ ok: true });
});

module.exports = router;
