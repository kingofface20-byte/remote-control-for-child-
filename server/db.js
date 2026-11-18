// Tiny JSON-file store. On Render free plan the disk is not persistent,
// so history is lost on restart; devices re-register on their next poll.
const fs = require('fs');
const path = require('path');

const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data', 'db.json');
const MAX_COMMANDS = 200;
const PENDING_EXPIRE_MS = 120 * 1000; // phone never picked it up
const SENT_EXPIRE_MS = 60 * 1000;     // phone picked it up but never reported

let state = { devices: {}, commands: [] };
let nextId = 1;
let saveTimer = null;

function load() {
  try {
    state = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    nextId = state.commands.reduce((m, c) => Math.max(m, c.id), 0) + 1;
  } catch (e) {
    // first run or unreadable file: start empty
  }
}

function save() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
      const tmp = DATA_FILE + '.tmp';
      fs.writeFileSync(tmp, JSON.stringify(state));
      fs.renameSync(tmp, DATA_FILE);
    } catch (e) {
      console.error('save failed:', e.message);
    }
  }, 500);
}

function sweep() {
  const now = Date.now();
  for (const c of state.commands) {
    if (c.status === 'pending' && now - c.createdAt > PENDING_EXPIRE_MS) {
      c.status = 'done';
      c.result = 'expired';
      c.doneAt = now;
    } else if (c.status === 'sent' && now - c.sentAt > SENT_EXPIRE_MS) {
      c.status = 'done';
      c.result = 'timeout';
      c.doneAt = now;
    }
  }
}

function touchDevice(id, name) {
  const now = Date.now();
  const d = state.devices[id];
  if (d) {
    d.lastSeen = now;
    d.name = name || d.name;
  } else {
    state.devices[id] = { id, name: name || id, firstSeen: now, lastSeen: now };
    save();
  }
}

function deviceExists(id) {
  return Boolean(state.devices[id]);
}

function listDevices() {
  return Object.values(state.devices).sort((a, b) => b.lastSeen - a.lastSeen);
}

function addCommand(deviceId, pkg) {
  const cmd = {
    id: nextId++,
    deviceId,
    package: pkg,
    status: 'pending',
    result: null,
    createdAt: Date.now(),
    sentAt: null,
    doneAt: null,
  };
  state.commands.push(cmd);
  if (state.commands.length > MAX_COMMANDS) {
    state.commands.splice(0, state.commands.length - MAX_COMMANDS);
  }
  save();
  return cmd;
}

function takePending(deviceId) {
  sweep();
  const cmd = state.commands.find((c) => c.deviceId === deviceId && c.status === 'pending');
  if (!cmd) return null;
  cmd.status = 'sent';
  cmd.sentAt = Date.now();
  save();
  return cmd;
}

function finishCommand(id, deviceId, result) {
  const cmd = state.commands.find((c) => c.id === id && c.deviceId === deviceId);
  if (!cmd || cmd.status !== 'sent') return null;
  cmd.status = 'done';
  cmd.result = result;
  cmd.doneAt = Date.now();
  save();
  return cmd;
}

function listCommands(limit) {
  sweep();
  return state.commands.slice(-limit).reverse();
}

module.exports = {
  load,
  touchDevice,
  deviceExists,
  listDevices,
  addCommand,
  takePending,
  finishCommand,
  listCommands,
};
