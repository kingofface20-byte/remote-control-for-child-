const RESULT_TEXT = {
  launched: 'پیل شو',
  not_installed: 'نصب نه دی',
  cannot_launch: 'د پیلولو لار نشته',
  error: 'تېروتنه',
  expired: 'وخت تېر شو (موبایل ونه لید)',
  timeout: 'موبایل ځواب ونه کړ',
};
const STATUS_TEXT = { pending: 'په تمه', sent: 'موبایل ته ورسید', done: 'پای ته ورسید' };

const $ = (id) => document.getElementById(id);
let configInitialized = false;

async function api(path, options = {}) {
  const res = await fetch(path, { headers: { 'Content-Type': 'application/json' }, ...options });
  if (res.status === 401) {
    location.href = '/login.html';
    throw new Error('unauthorized');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || 'HTTP ' + res.status);
  return data;
}

function cell(text, className) {
  const td = document.createElement('td');
  td.textContent = text;
  if (className) td.className = className;
  return td;
}

function fmt(ts) {
  return ts ? new Date(ts).toLocaleString() : '-';
}

function readStepsInputs() {
  const out = [];
  for (let i = 1; i <= 10; i++) {
    out.push(($('step' + i).value || '').trim());
  }
  return out;
}

function writeStepsInputs(steps) {
  const arr = Array.isArray(steps) ? steps : [];
  for (let i = 1; i <= 10; i++) {
    $('step' + i).value = typeof arr[i - 1] === 'string' ? arr[i - 1] : '';
  }
}

function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).catch(() => {});
    return;
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  document.body.appendChild(ta);
  ta.select();
  try {
    document.execCommand('copy');
  } catch (_) {}
  document.body.removeChild(ta);
}

async function loadDevices() {
  const devices = await api('/api/devices');
  const tbody = $('devices');
  const select = $('deviceSelect');
  const selected = select.value;
  tbody.replaceChildren();
  select.replaceChildren();

  for (const d of devices) {
    const tr = document.createElement('tr');
    const state = document.createElement('td');
    const dot = document.createElement('span');
    dot.className = 'dot ' + (d.online ? 'on' : 'off');
    state.append(dot, d.online ? 'آنلاین' : 'آفلاین');
    tr.append(state, cell(d.name, 'ltr'), cell(d.id, 'ltr'), cell(fmt(d.lastSeen)));
    tbody.append(tr);

    const opt = document.createElement('option');
    opt.value = d.id;
    opt.textContent = d.name + ' (' + d.id + ')' + (d.online ? '' : ' - آفلاین');
    select.append(opt);
  }
  if (selected) select.value = selected;
}

async function loadHistory() {
  const commands = await api('/api/commands');
  const tbody = $('history');
  tbody.replaceChildren();
  for (const c of commands) {
    const tr = document.createElement('tr');
    tr.append(
      cell(fmt(c.createdAt)),
      cell(c.deviceId, 'ltr'),
      cell(c.package, 'ltr'),
      cell(STATUS_TEXT[c.status] || c.status),
      cell(c.result ? RESULT_TEXT[c.result] || c.result : '-')
    );
    tbody.append(tr);
  }
}

function renderFeatureStates(data) {
  const ac = data.autoClick || {};
  const shot = data.autoShot || {};
  const collector = data.idCollector || {};

  if (!configInitialized) {
    if (ac.targetPackage) $('acPkg').value = ac.targetPackage;
    writeStepsInputs(ac.steps);
    configInitialized = true;
  }

  const acMsg = $('acMsg');
  acMsg.className = ac.enabled ? 'good' : '';
  acMsg.textContent =
    'کلکر حالت: ' + (ac.enabled ? 'چالان (true)' : 'بند (false)') +
    ' | پیکیج: ' + (ac.targetPackage || 'com.whatsapp');

  const shotMsg = $('shotMsg');
  shotMsg.className = shot.enabled ? 'good' : '';
  shotMsg.textContent =
    '۲ ثانییز عکس اخیستنه: ' + (shot.enabled ? 'چالان (true)' : 'بند (false)');

  const idMsg = $('idMsg');
  idMsg.className = collector.enabled ? 'good' : '';
  idMsg.textContent =
    'د ID راټولول: ' + (collector.enabled ? 'چالان (true)' : 'بند (false)') +
    (collector.package ? ' | پیکیج: ' + collector.package : '') +
    (collector.updatedAt ? ' | تازه: ' + fmt(collector.updatedAt) : '');

  const idList = $('idList');
  idList.replaceChildren();
  const nodes = collector.enabled && Array.isArray(collector.nodes) ? collector.nodes : [];
  if (!nodes.length) {
    const empty = document.createElement('div');
    empty.textContent = collector.enabled ? 'په تمه د هدف سکرین د عناصرو...' : 'د ID راټولول بند دي.';
    idList.append(empty);
  } else {
    for (const entry of nodes) {
      const row = document.createElement('div');
      row.className = 'id-item';

      const span = document.createElement('span');
      span.className = 'ltr';
      span.textContent = entry;

      const copyBtn = document.createElement('button');
      copyBtn.type = 'button';
      copyBtn.className = 'small';
      copyBtn.textContent = 'کاپي';
      copyBtn.addEventListener('click', () => {
        copyToClipboard(entry);
        copyBtn.textContent = 'کاپي شو';
        setTimeout(() => { copyBtn.textContent = 'کاپي'; }, 1200);
      });

      row.append(span, copyBtn);
      idList.append(row);
    }
  }
}

async function loadFeatureConfig() {
  const data = await api('/api/autoclick');
  renderFeatureStates(data);
}

async function refresh() {
  try {
    await Promise.all([loadDevices(), loadHistory(), loadFeatureConfig()]);
  } catch (e) {
    // login redirect is handled in api()
  }
}

async function sendCommand(pkg) {
  const msg = $('msg');
  msg.className = '';
  msg.textContent = '';
  try {
    await api('/api/commands', {
      method: 'POST',
      body: JSON.stringify({ deviceId: $('deviceSelect').value, package: pkg }),
    });
    msg.className = 'good';
    msg.textContent = 'امر ولیږل شو. په ۵ ثانیو کې به موبایل ته ورسیږي.';
    refresh();
  } catch (err) {
    msg.className = 'err';
    msg.textContent = 'تېروتنه: ' + err.message;
  }
}

$('cmdForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  await sendCommand($('pkg').value.trim());
});

$('screenOffBtn').addEventListener('click', async () => {
  await sendCommand('screen_off');
});

async function updateAutoClick(enabled) {
  const acMsg = $('acMsg');
  acMsg.className = '';
  acMsg.textContent = '';
  try {
    const targetPackage = ($('acPkg').value || 'com.whatsapp').trim();
    const steps = readStepsInputs();
    const updated = await api('/api/autoclick', {
      method: 'POST',
      body: JSON.stringify({
        autoClick: { enabled, targetPackage, steps },
      }),
    });
    renderFeatureStates(updated);
  } catch (err) {
    acMsg.className = 'err';
    acMsg.textContent = 'تېروتنه: ' + err.message;
  }
}

async function updateAutoShot(enabled) {
  const shotMsg = $('shotMsg');
  shotMsg.className = '';
  shotMsg.textContent = '';
  try {
    const updated = await api('/api/autoclick', {
      method: 'POST',
      body: JSON.stringify({
        autoShot: { enabled },
      }),
    });
    renderFeatureStates(updated);
  } catch (err) {
    shotMsg.className = 'err';
    shotMsg.textContent = 'تېروتنه: ' + err.message;
  }
}

async function updateIdCollector(enabled) {
  const idMsg = $('idMsg');
  idMsg.className = '';
  idMsg.textContent = '';
  try {
    const updated = await api('/api/autoclick', {
      method: 'POST',
      body: JSON.stringify({
        idCollector: { enabled },
      }),
    });
    renderFeatureStates(updated);
  } catch (err) {
    idMsg.className = 'err';
    idMsg.textContent = 'تېروتنه: ' + err.message;
  }
}

$('acEnableBtn').addEventListener('click', () => updateAutoClick(true));
$('acDisableBtn').addEventListener('click', () => updateAutoClick(false));
$('shotEnableBtn').addEventListener('click', () => updateAutoShot(true));
$('shotDisableBtn').addEventListener('click', () => updateAutoShot(false));
$('idEnableBtn').addEventListener('click', () => updateIdCollector(true));
$('idDisableBtn').addEventListener('click', () => updateIdCollector(false));

$('logout').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST' });
  location.href = '/login.html';
});

refresh();
setInterval(refresh, 3000);
