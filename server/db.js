const fs = require('fs');
const path = require('path');

const DATA_FILE = process.env.DATA_FILE || path.join(__dirname, 'data', 'db.json');
const MAX_COMMANDS = 200;
const MAX_STEPS = 10;
const MAX_INSPECTOR_ITEMS = 80;
const PENDING_EXPIRE_MS = 120 * 1000;
const SENT_EXPIRE_MS = 60 * 1000;

function normalizeSteps(raw) {
  const arr = Array.isArray(raw) ? raw : [];
  const out = [];
  for (let i = 0; i < MAX_STEPS; i++) {
    out.push(typeof arr[i] === 'string' ? arr[i].trim() : '');
  }
  return out;
}

let state = {
  devices: {},
  commands: [],
  autoClick: {
    enabled: false,
    targetPackage: 'com.whatsapp',
    steps: Array(MAX_STEPS).fill(''),
    triggerId: '',
  },
  autoShot: {
    enabled: false,
  },
  idCollector: {
    enabled: false,
    deviceId: '',
    package: '',
    updatedAt: 0,
    nodes: [],
  },
};
let nextId = 1;
let saveTimer = null;

function load() {
  try {
    const parsed = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    const legacyText =
      parsed.autoClick && typeof parsed.autoClick.targetText === 'string'
        ? parsed.autoClick.targetText.trim()
        : '';
    const rawSteps =
      parsed.autoClick && Array.isArray(parsed.autoClick.steps)
        ? parsed.autoClick.steps
        : legacyText
          ? [legacyText]
          : [];

    state = {
      devices: parsed.devices && typeof parsed.devices === 'object' ? parsed.devices : {},
      commands: Array.isArray(parsed.commands) ? parsed.commands : [],
      autoClick: {
        enabled: Boolean(parsed.autoClick && parsed.autoClick.enabled),
        targetPackage:
          parsed.autoClick &&
          typeof parsed.autoClick.targetPackage === 'string' &&
          parsed.autoClick.targetPackage.trim()
            ? parsed.autoClick.targetPackage.trim()
            : 'com.whatsapp',
        steps: normalizeSteps(rawSteps),
        triggerId:
          parsed.autoClick && typeof parsed.autoClick.triggerId === 'string'
            ? parsed.autoClick.triggerId
            : '',
      },
      autoShot: {
        enabled: Boolean(parsed.autoShot && parsed.autoShot.enabled),
      },
      idCollector: {
        enabled: Boolean(parsed.idCollector && parsed.idCollector.enabled),
        deviceId:
          parsed.idCollector && typeof parsed.idCollector.deviceId === 'string'
            ? parsed.idCollector.deviceId
            : '',
        package:
          parsed.idCollector && typeof parsed.idCollector.package === 'string'
            ? parsed.idCollector.package
            : '',
        updatedAt:
          parsed.idCollector && Number(parsed.idCollector.updatedAt)
            ? Number(parsed.idCollector.updatedAt)
            : 0,
        nodes:
          parsed.idCollector && Array.isArray(parsed.idCollector.nodes)
            ? parsed.idCollector.nodes.slice(0, MAX_INSPECTOR_ITEMS)
            : [],
      },
    };
    nextId = state.commands.reduce((m, c) => Math.max(m, Number(c.id) || 0), 0) + 1;
  } catch (e) {
    // start with defaults
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

function getAutoClick() {
  return {
    enabled: Boolean(state.autoClick.enabled),
    targetPackage: state.autoClick.targetPackage || 'com.whatsapp',
    steps: normalizeSteps(state.autoClick.steps),
    triggerId: state.autoClick.triggerId || '',
  };
}

function setAutoClick({ enabled, targetPackage, steps }) {
  const nextEnabled = typeof enabled === 'boolean' ? enabled : Boolean(state.autoClick.enabled);
  const nextPkg =
    typeof targetPackage === 'string' && targetPackage.trim()
      ? targetPackage.trim()
      : state.autoClick.targetPackage || 'com.whatsapp';
  const nextSteps = Array.isArray(steps)
    ? normalizeSteps(steps)
    : normalizeSteps(state.autoClick.steps);

  const nextTriggerId = nextEnabled ? String(Date.now()) : state.autoClick.triggerId || '';

  state.autoClick = {
    enabled: nextEnabled,
    targetPackage: nextPkg,
    steps: nextSteps,
    triggerId: nextTriggerId,
  };
  save();
  return getAutoClick();
}

function getAutoShot() {
  return {
    enabled: Boolean(state.autoShot && state.autoShot.enabled),
  };
}

function setAutoShot(enabled) {
  state.autoShot = {
    enabled: Boolean(enabled),
  };
  save();
  return getAutoShot();
}

function getIdCollector() {
  return {
    enabled: Boolean(state.idCollector && state.idCollector.enabled),
    deviceId: state.idCollector ? state.idCollector.deviceId || '' : '',
    package: state.idCollector ? state.idCollector.package || '' : '',
    updatedAt: state.idCollector ? Number(state.idCollector.updatedAt) || 0 : 0,
    nodes: state.idCollector && Array.isArray(state.idCollector.nodes) ? state.idCollector.nodes : [],
  };
}

function setIdCollectorEnabled(enabled) {
  const nextEnabled = Boolean(enabled);
  state.idCollector = {
    ...getIdCollector(),
    enabled: nextEnabled,
    nodes: nextEnabled ? getIdCollector().nodes : [],
  };
  save();
  return getIdCollector();
}

function recordInspectorNodes(deviceId, pkg, nodes) {
  if (!state.idCollector || !state.idCollector.enabled) {
    return getIdCollector();
  }
  const cleanNodes = Array.isArray(nodes)
    ? nodes
        .filter((x) => typeof x === 'string' && x.trim())
        .map((x) => x.trim().slice(0, 240))
        .slice(0, MAX_INSPECTOR_ITEMS)
    : [];

  state.idCollector = {
    enabled: true,
    deviceId: String(deviceId || ''),
    package: String(pkg || ''),
    updatedAt: Date.now(),
    nodes: cleanNodes,
  };
  save();
  return getIdCollector();
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
  getAutoClick,
  setAutoClick,
  getAutoShot,
  setAutoShot,
  getIdCollector,
  setIdCollectorEnabled,
  recordInspectorNodes,
};
