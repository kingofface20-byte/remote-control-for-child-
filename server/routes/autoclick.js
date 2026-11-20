const express = require('express');
const db = require('../db');
const { requireSession } = require('../middleware/auth');

const router = express.Router();
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/;

router.get('/', requireSession, (req, res) => {
  res.json(db.getAutoClick());
});

router.post('/', requireSession, (req, res) => {
  const enabled = Boolean(req.body.enabled);
  const rawPkg = typeof req.body.targetPackage === 'string' ? req.body.targetPackage.trim() : 'com.whatsapp';
  const pkg = rawPkg || 'com.whatsapp';
  const targetText = typeof req.body.targetText === 'string' ? req.body.targetText.trim() : '';

  if (pkg.length > 200 || !PKG_RE.test(pkg)) {
    return res.status(400).json({ error: 'invalid target package name' });
  }
  if (targetText.length > 200) {
    return res.status(400).json({ error: 'target text too long' });
  }

  const updated = db.setAutoClick({
    enabled,
    targetPackage: pkg,
    targetText,
  });
  res.json(updated);
});

module.exports = router;
