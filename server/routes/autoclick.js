const express = require('express');
const db = require('../db');
const { requireSession } = require('../middleware/auth');

const router = express.Router();
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

router.get('/', requireSession, (req, res) => {
  res.json({
    autoClick: db.getAutoClick(),
    autoShot: db.getAutoShot(),
  });
});

router.post('/', requireSession, (req, res) => {
  const body = req.body || {};

  let nextAutoClick = db.getAutoClick();
  let nextAutoShot = db.getAutoShot();

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
      if (val.length > 200) {
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

  res.json({
    autoClick: nextAutoClick,
    autoShot: nextAutoShot,
  });
});

module.exports = router;
