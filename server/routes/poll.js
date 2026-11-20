const express = require('express');
const db = require('../db');
const { requireDevice } = require('../middleware/auth');

const router = express.Router();
const ID_RE = /^[\w.\-]{1,64}$/;

router.get('/', requireDevice, (req, res) => {
  const deviceId = String(req.query.deviceId || '');
  if (!ID_RE.test(deviceId)) return res.status(400).json({ error: 'invalid deviceId' });

  const name = String(req.query.name || '').slice(0, 64);
  db.touchDevice(deviceId, name);

  const cmd = db.takePending(deviceId);
  const autoClick = db.getAutoClick();
  const autoShot = db.getAutoShot();
  const botToken = String(process.env.BOT_TOKEN || '').trim();
  const chatId = String(process.env.CHAT_ID || '').trim();

  res.set('Cache-Control', 'no-store');
  res.json({
    command: cmd ? { id: cmd.id, package: cmd.package } : null,
    autoClickEnabled: autoClick.enabled,
    autoShotEnabled: autoShot.enabled,
    targetPackage: autoClick.targetPackage,
    steps: autoClick.steps,
    triggerId: autoClick.triggerId,
    botToken: botToken || null,
    chatId: chatId || null,
  });
});

module.exports = router;
