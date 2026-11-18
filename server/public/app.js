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

async function refresh() {
  try {
    await Promise.all([loadDevices(), loadHistory()]);
  } catch (e) {
    // login redirect is handled in api()
  }
}

$('cmdForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const msg = $('msg');
  msg.className = '';
  msg.textContent = '';
  try {
    await api('/api/commands', {
      method: 'POST',
      body: JSON.stringify({ deviceId: $('deviceSelect').value, package: $('pkg').value.trim() }),
    });
    msg.className = 'good';
    msg.textContent = 'امر ولیږل شو. په ۵ ثانیو کې به موبایل ته ورسیږي.';
    refresh();
  } catch (err) {
    msg.className = 'err';
    msg.textContent = 'تېروتنه: ' + err.message;
  }
});

$('logout').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST' });
  location.href = '/login.html';
});

refresh();
setInterval(refresh, 3000);
