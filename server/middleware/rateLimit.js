const rateLimit = require('express-rate-limit');

// At most 10 commands per minute per IP
const commandLimiter = rateLimit({
  windowMs: 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'too many commands, wait a minute' },
});

// At most 10 login attempts per 15 minutes per IP
const loginLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 10,
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'too many login attempts, try later' },
});

module.exports = { commandLimiter, loginLimiter };
