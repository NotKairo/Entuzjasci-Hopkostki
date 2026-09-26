'use strict';

// ---------- Stałe ----------
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const ACTION_LABELS = { ban: 'Ban', unban: 'Unban', kick: 'Kick', timeout: 'Timeout', untimeout: 'Untimeout', warn: 'Ostrzeżenie' };
const CASE_LABELS = { ...ACTION_LABELS, untimeout: 'Zdjęcie timeoutu' };
const SLASH_NAMES = { ban: '/ban', unban: '/unban', kick: '/kick', timeout: '/timeout', untimeout: '/untimeout', warn: '/warn dodaj' };
const UNIT_FORMS = {
  m: ['minuta', 'minuty', 'minut'],
  h: ['godzina', 'godziny', 'godzin'],
  d: ['dzień', 'dni', 'dni'],
  w: ['tydzień', 'tygodnie', 'tygodni'],
  mo: ['miesiąc', 'miesiące', 'miesięcy'],
};
const UNIT_LABELS = { m: 'Minuty', h: 'Godziny', d: 'Dni', w: 'Tygodnie', mo: 'Miesiące' };
const RULE_ACTIONS = { alert: 'Alert', timeout: 'Timeout', kick: 'Kick', ban: 'Ban' };
const PLACEHOLDERS = ['{uzytkownik}', '{nick}', '{moderator}', '{moderatorNick}', '{powod}', '{czas}', '{serwer}', '{sprawa}', '{typ}'];
const ACTIVITY_LABELS = { custom: 'Własny opis', playing: 'Gra w', listening: 'Słucha', watching: 'Ogląda', competing: 'Rywalizuje w' };
const PRESENCE_PLACEHOLDERS = ['{czlonkowie}', '{online}', '{serwer}', '{ostrzezenia}', '{sprawy}'];
const VIEWS = ['pulpit', 'ustawienia', 'uprawnienia', 'glosowe', 'wiadomosci', 'embedy', 'ostrzezenia', 'sprawy', 'bany'];
const EMPTY_GUILD = { channels: [], voiceChannels: [], categories: [], roles: [], bot: null };
const BUTTON_STYLES = { niebieski: 'Niebieski', szary: 'Szary', zielony: 'Zielony', czerwony: 'Czerwony' };
const PANEL_PASSWORD_KEY = 'hopkostki-panel-password';
const SAMPLE = { targetId: '111', target: 'hurownik_og', modId: '222', mod: 'dfgbh65', reason: 'Wielokrotne łamanie zasad' };

const state = {
  config: null,
  draft: null,
  defaults: null,
  guild: EMPTY_GUILD,
  status: null,
  msg: null,
  sentMessages: [],
  view: 'pulpit',
  embedAction: 'ban',
  lastField: null,
  casesPage: 1,
  warnUsers: [],
  openWarnUsers: new Set(),
  reloadPending: false,
  commandMeta: null,
  lastActivityInput: null,
};

// ---------- Narzędzia ----------
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const clone = (value) => JSON.parse(JSON.stringify(value));
const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const pad = (n) => String(n).padStart(2, '0');
const stripEmoji = (text) =>
  String(text ?? '').replace(/[\p{Extended_Pictographic}\u{FE0F}\u{20E3}]/gu, '').replace(/\s{2,}/g, ' ').trim();

function getPath(obj, path) {
  return path.split('.').reduce((o, key) => o?.[key], obj);
}
function setPath(obj, path, value) {
  const keys = path.split('.');
  const last = keys.pop();
  keys.reduce((o, key) => o[key], obj)[last] = value;
}

function plural(n, [one, few, many]) {
  if (n === 1) return one;
  const n10 = n % 10;
  const n100 = n % 100;
  return n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14) ? few : many;
}
const formatDuration = (amount, unit) => `${amount} ${plural(amount, UNIT_FORMS[unit] ?? ['', '', ''])}`;

function fullDate(ms) {
  return new Date(ms).toLocaleString('pl-PL', { day: 'numeric', month: 'long', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function shortDate(ms) {
  return new Date(ms).toLocaleString('pl-PL', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function relTime(ms) {
  const diff = ms - Date.now();
  const abs = Math.abs(diff);
  let text;
  if (abs >= 30 * DAY) text = formatDuration(Math.round(abs / (30 * DAY)), 'mo');
  else if (abs >= DAY) text = formatDuration(Math.round(abs / DAY), 'd');
  else if (abs >= HOUR) text = formatDuration(Math.round(abs / HOUR), 'h');
  else text = formatDuration(Math.max(1, Math.round(abs / MINUTE)), 'm');
  return diff >= 0 ? `za ${text}` : `${text} temu`;
}
function formatCountdown(ms) {
  if (ms <= 0) return 'wygasa…';
  const total = Math.floor(ms / 1000);
  const d = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${d ? `${d} ${plural(d, UNIT_FORMS.d)} ` : ''}${pad(h)}:${pad(m)}:${pad(s)}`;
}

// Strona stoi na GitHub Pages, a API panelu w funkcji Edge na Supabase (Supabase nie serwuje stron HTML).
// Lokalny wrapper (npm run panel) sam przekazuje wywołania do bota, więc tam ścieżki zostają względne.
const REMOTE_API = 'https://ucjmbdogtzztrkorqzjq.supabase.co/functions/v1/hopkostki-bot/panel/';
const API_BASE = ['localhost', '127.0.0.1'].includes(location.hostname) ? '' : REMOTE_API;

// Hasło żyje tylko w tej karcie (sessionStorage).
function getPanelPassword() {
  try {
    return sessionStorage.getItem(PANEL_PASSWORD_KEY) || '';
  } catch {
    return '';
  }
}
function setPanelPassword(value) {
  try {
    sessionStorage.setItem(PANEL_PASSWORD_KEY, value);
  } catch {
    /* prywatna karta / zablokowany storage — hasło trzeba będzie wpisać ponownie po odświeżeniu */
  }
}
function clearPanelPassword() {
  try {
    sessionStorage.removeItem(PANEL_PASSWORD_KEY);
  } catch {}
}

async function api(path, options = {}) {
  const res = await fetch(API_BASE + path.replace(/^\/+/, ''), {
    ...options,
    headers: { 'Content-Type': 'application/json', 'x-panel-password': getPanelPassword(), ...(options.headers ?? {}) },
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) {
    clearPanelPassword();
    showLoginGate(data.error || 'Złe hasło panelu.');
    throw new Error(data.error || 'Wymagane logowanie.');
  }
  if (!res.ok) {
    const message = data.error || `Błąd ${res.status}`;
    if ([500, 502, 503].includes(res.status)) showConnectionError(message);
    throw new Error(message);
  }
  hideConnectionError();
  return data;
}

let toastTimer = null;
function toast(message, type = 'ok') {
  const el = $('#toast');
  el.textContent = message;
  el.className = `toast show ${type === 'error' ? 'error' : ''}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.remove('show'), 3000);
}

// ---------- Błędy połączenia z botem ----------
function showConnectionError(message) {
  const el = $('#conn-error');
  el.innerHTML = `<strong>Brak połączenia z botem.</strong> ${esc(message)}`;
  el.classList.remove('hidden');
}
function hideConnectionError() {
  $('#conn-error').classList.add('hidden');
}

// ---------- Logowanie ----------
function showLoginGate(message) {
  const gate = $('#login-gate');
  const wasHidden = gate.classList.contains('hidden');
  gate.classList.remove('hidden');
  $('#app').classList.add('hidden');
  if (message) $('#login-error').textContent = message;
  if (wasHidden) {
    $('#login-password').value = '';
    $('#login-password').focus();
  }
}
function revealApp() {
  $('#login-gate').classList.add('hidden');
  $('#app').classList.remove('hidden');
  $('#login-error').textContent = '';
}

$('#login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  setPanelPassword($('#login-password').value);
  $('#login-submit').disabled = true;
  try {
    await api('/status'); // samo wywołanie sprawdza, czy hasło jest poprawne
    await init();
  } catch (error) {
    $('#login-error').textContent = error.message;
  } finally {
    $('#login-submit').disabled = false;
  }
});

$('#logout-btn').addEventListener('click', () => {
  clearPanelPassword();
  showLoginGate();
});

// ---------- Start ----------
let started = false;
async function start() {
  const [{ config, defaults }, guild, { commands }] = await Promise.all([
    api('/config'),
    api('/guild').catch(() => EMPTY_GUILD),
    api('/commands').catch(() => ({ commands: [] })),
  ]);
  state.config = config;
  state.draft = clone(config);
  state.defaults = defaults;
  state.guild = { ...EMPTY_GUILD, ...guild };
  // Opisy komend w Discordzie mają emoji — w panelu pokazujemy sam tekst.
  state.commandMeta = commands.map((c) => ({ ...c, description: stripEmoji(c.description) }));
  if (!state.msg) state.msg = { editingId: null, data: blankMessage() };
  revealApp();
  renderConfigUi();
  route();
  if (!started) {
    started = true;
    refreshStatus();
    setInterval(refreshStatus, 15_000);
    setInterval(tick, 1000);
  }
}

async function init() {
  try {
    await start();
  } catch (error) {
    // Hasło zostało już wyczyszczone i pokazany ekran logowania przez api() — nie ma sensu tu dobijać.
    if (getPanelPassword()) {
      showConnectionError(error.message);
      setTimeout(init, 10_000);
    }
  }
}

function boot() {
  if (getPanelPassword()) init();
  else showLoginGate();
}

// ---------- Nawigacja ----------
function route() {
  const hash = location.hash.slice(1);
  state.view = VIEWS.includes(hash) ? hash : 'pulpit';
  $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${state.view}`));
  $$('#nav a').forEach((a) => a.classList.toggle('active', a.dataset.view === state.view));
  if (state.view === 'pulpit') refreshStatus();
  if (state.view === 'ostrzezenia') loadWarns();
  if (state.view === 'sprawy') loadCases();
  if (state.view === 'bany') loadBans();
  if (state.view === 'embedy') renderPreview();
  if (state.view === 'uprawnienia') renderPermissions();
  if (state.view === 'glosowe') loadVoice();
  if (state.view === 'wiadomosci') {
    renderMessageEditor();
    loadSentMessages();
  }
}
window.addEventListener('hashchange', route);

// ---------- Status / pulpit ----------
async function refreshStatus() {
  if (!state.config) return;
  let status;
  try {
    status = await api('/status');
  } catch {
    $('#bot-state').textContent = 'panel offline';
    $('#bot-state').className = 'state offline';
    return;
  }
  const wasReady = state.status?.ready;
  state.status = status;

  $('#bot-state').textContent = status.ready ? 'działa na Supabase' : 'wymaga konfiguracji';
  $('#bot-state').className = `state ${status.ready ? 'online' : 'offline'}`;
  if (status.bot) {
    $('#bot-name').textContent = status.bot.tag;
    const avatar = $('#bot-avatar');
    avatar.onerror = () => {
      avatar.hidden = true;
      $('#bot-logo').hidden = false;
    };
    avatar.src = status.bot.avatar;
    avatar.hidden = false;
    $('#bot-logo').hidden = true;
  }
  if (status.guild) {
    $('#guild-name').textContent = status.guild.name;
    $('#guild-members').textContent = `${status.guild.memberCount ?? "?"} członków • ID ${status.guild.id}`;
    if (status.guild.icon) {
      $('#guild-icon').src = status.guild.icon;
      $('#guild-icon').hidden = false;
    }
  } else {
    $('#guild-name').textContent = status.ready ? 'Bot nie jest na żadnym serwerze' : '—';
  }
  const lastCron = status.cron?.at;
  $('#status-text').innerHTML = status.ready ? '<span class="dot ok"></span>Działa' : '<span class="dot bad"></span>Nie działa';
  $('#status-extra').textContent = status.ready
    ? `Ostatnie zadania okresowe: ${lastCron ? relTime(lastCron) : 'jeszcze nie było'}`
    : status.error ?? 'Sprawdź sekrety w Supabase.';
  renderSetup(status);
  renderGatewayLine(status.gateway);

  const s = status.stats;
  const cards = [
    ['Sprawy łącznie', s.cases, '#5865f2'],
    ['Aktywne ostrzeżenia', s.activeWarns, state.config.actions.warn.color],
    ['Tymczasowe bany', s.tempBans, state.config.actions.ban.color],
    ['Bany', s.byType.ban ?? 0, state.config.actions.ban.color],
    ['Timeouty', s.byType.timeout ?? 0, state.config.actions.timeout.color],
    ['Kicki', s.byType.kick ?? 0, state.config.actions.kick.color],
  ];
  $('#stats').innerHTML = cards
    .map(([label, value, color]) => `<div class="stat" style="--accent:${esc(color)}"><b>${value}</b><span>${label}</span></div>`)
    .join('');
  $('#recent-table').innerHTML = casesTable(status.recent);

  // Bot właśnie się połączył — dociągamy kanały i role do formularzy.
  if (status.ready && (!wasReady || !state.guild.channels.length)) {
    state.guild = { ...EMPTY_GUILD, ...(await api('/guild')) };
    renderChannelSelects();
    renderRoles();
    renderGenerators();
    renderBotWarnings();
    if (state.view === 'uprawnienia') renderPermissions();
    if (state.view === 'wiadomosci') renderMessageEditor();
  }
  if (state.view === 'embedy') renderPreview();
  renderPasswordStatus(status);
}

const gatewayOk = (gateway) => Boolean(gateway?.startedAt && !gateway.error && Date.now() - gateway.startedAt < 3 * MINUTE);

function renderSetup(status) {
  const setup = status.setup ?? {};
  const cronAt = status.cron?.at;
  const items = [
    [setup.tokenConfigured, 'Token bota (DISCORD_TOKEN) ustawiony w sekretach Supabase', setup.tokenConfigured ? '' : 'Supabase → Edge Functions → Secrets → dodaj DISCORD_TOKEN.'],
    [status.ready, 'Bot widzi serwer', status.ready ? status.guild?.name : status.error],
    [
      setup.endpoint && setup.endpoint === setup.selfUrl,
      'Discord wysyła komendy do Supabase (Interactions Endpoint URL)',
      setup.endpointError ? `Błąd: ${setup.endpointError}` : setup.selfUrl,
    ],
    [Boolean(setup.commandsRegisteredAt), 'Komendy slash zarejestrowane', setup.commandsRegisteredAt ? `ostatnio ${relTime(setup.commandsRegisteredAt)}` : 'zrobi się samo w ciągu minuty'],
    [cronAt && Date.now() - cronAt < 3 * MINUTE, 'Zadania okresowe (pg_cron co 30 s)', cronAt ? `ostatnio ${relTime(cronAt)}` : 'jeszcze nie uruchomione'],
    [
      gatewayOk(status.gateway),
      'Status „online” i opisy (gateway co minutę)',
      status.gateway?.error ?? (status.gateway?.startedAt ? `ostatnia sesja ${relTime(status.gateway.startedAt)}${status.gateway.mode === 'resume' ? ' (wznowiona)' : ''}` : 'uruchomi się w ciągu minuty'),
    ],
  ];
  $('#setup-list').innerHTML = items
    .map(([done, label, hint]) => `<li><span class="dot ${done ? 'ok' : 'wait'}"></span><div>${esc(label)}${hint ? `<small>${esc(hint)}</small>` : ''}</div></li>`)
    .join('');
}

$('#setup-run').addEventListener('click', async () => {
  $('#setup-run').disabled = true;
  try {
    await api('/setup', { method: 'POST' });
    toast('Komendy zarejestrowane');
  } catch (error) {
    toast(error.message, 'error');
  }
  $('#setup-run').disabled = false;
  refreshStatus();
});

function caseBadge(entry) {
  const color = state.config.actions[entry.type]?.color ?? '#99aab5';
  const label = entry.type === 'ban' && entry.duration ? 'Tempban' : CASE_LABELS[entry.type] ?? entry.type;
  return `<span class="badge" style="background:${esc(color)}">${esc(label)}</span>`;
}

function casesTable(items) {
  if (!items.length) return '<tr><td class="empty">Brak spraw.</td></tr>';
  const rows = items
    .map((c) => {
      const duration = c.duration ? formatDuration(c.duration.amount, c.duration.unit) : c.type === 'ban' ? 'perm.' : '—';
      const expiry = c.expiresAt ? `<span class="note">do ${esc(shortDate(c.expiresAt))}</span>` : '';
      return `<tr>
        <td><strong>#${c.id}</strong></td>
        <td>${caseBadge(c)}</td>
        <td>${esc(c.userTag)}<span class="note">${esc(c.userId)}</span></td>
        <td>${esc(c.moderatorTag)}${c.auto ? ' (auto)' : ''}</td>
        <td class="reason">${esc(c.reason)}${c.note ? `<span class="note">Notatka: ${esc(c.note)}</span>` : ''}</td>
        <td>${duration}${expiry}</td>
        <td>${esc(shortDate(c.createdAt))}</td>
      </tr>`;
    })
    .join('');
  return `<thead><tr><th>#</th><th>Typ</th><th>Użytkownik</th><th>Moderator</th><th>Powód</th><th>Czas</th><th>Data</th></tr></thead><tbody>${rows}</tbody>`;
}

// ---------- Formularze konfiguracji ----------
function renderConfigUi() {
  renderChannelSelects();
  renderRoles();
  renderRules();
  renderActivities();
  renderPermissions();
  renderGenerators();
  renderBotWarnings();
  renderEmbedTabs();
  fillInputs();
  $('#presence-chips').innerHTML = PRESENCE_PLACEHOLDERS.map((p) => `<button type="button" class="chip" data-presence-placeholder="${p}">${p}</button>`).join('');
  $('#placeholder-chips').innerHTML = PLACEHOLDERS.map((p) => `<button type="button" class="chip" data-placeholder="${p}">${p}</button>`).join('');
  markDirty();
}

function fillInputs() {
  $$('[data-path]').forEach((el) => {
    const value = getPath(state.draft, el.dataset.path);
    if (el.type === 'checkbox') el.checked = Boolean(value);
    else el.value = value ?? '';
  });
  const dashboardColor = state.draft.tempVoice?.dashboard?.color;
  if (/^#[0-9a-f]{6}$/i.test(dashboardColor ?? '')) $('#dashboard-color-picker').value = dashboardColor;
  fillEmbedFields();
}

function readValue(el) {
  if (el.type === 'checkbox') return el.checked;
  if (el.type === 'number') return Math.max(0, Math.floor(Number(el.value) || 0));
  return el.value;
}

function onFieldChange(event) {
  const el = event.target;
  if (el.dataset.path) {
    setPath(state.draft, el.dataset.path, readValue(el));
    markDirty();
    if (el.dataset.path.startsWith('warns') || el.dataset.path === 'dmShowModerator' || el.dataset.path === 'appealText' || el.dataset.path === 'mentionTarget') {
      renderPreview();
    }
  } else if (el.dataset.embedField) {
    const field = el.dataset.embedField;
    state.draft.actions[state.embedAction][field] = el.value;
    if (field === 'color' && /^#[0-9a-f]{6}$/i.test(el.value)) $('#embed-color-picker').value = el.value;
    renderEmbedTabs();
    renderPreview();
    markDirty();
  }
}
document.addEventListener('input', onFieldChange);
document.addEventListener('change', onFieldChange);

function renderChannelSelects() {
  $$('[data-channel-select]').forEach((select) => {
    const current = getPath(state.draft, select.dataset.path) ?? '';
    const emptyLabel = select.dataset.channelSelect === 'none' ? '— wyłączone —' : 'Kanał, na którym użyto komendy';
    let html = `<option value="">${emptyLabel}</option>`;
    html += state.guild.channels
      .map((c) => `<option value="${c.id}">#${esc(c.name)}${c.category ? ` · ${esc(c.category)}` : ''}</option>`)
      .join('');
    if (current && !state.guild.channels.some((c) => c.id === current)) {
      html += `<option value="${esc(current)}">(nieznany kanał ${esc(current)})</option>`;
    }
    select.innerHTML = html;
    select.value = current;
  });
}

function renderRoles() {
  const box = $('#roles-list');
  if (!state.guild.roles.length) {
    box.innerHTML = '<p class="muted small">Brak ról do wyświetlenia — bot nie jest jeszcze połączony z serwerem.</p>';
    return;
  }
  box.innerHTML = state.guild.roles
    .map((role) => {
      const color = role.color === '#000000' ? '#99aab5' : role.color;
      const checked = state.draft.modRoleIds.includes(role.id) ? 'checked' : '';
      return `<label class="role-item"><input type="checkbox" data-role="${role.id}" ${checked}><span class="role-dot" style="background:${esc(color)}"></span>${esc(role.name)}</label>`;
    })
    .join('');
}
$('#roles-list').addEventListener('change', () => {
  state.draft.modRoleIds = $$('[data-role]').filter((el) => el.checked).map((el) => el.dataset.role);
  markDirty();
});

// ---------- Uprawnienia komend ----------
// Ta sama logika co po stronie bota (moderation.js/roleHasAccess) — liczona tu, żeby pokazać
// podgląd na żywo bez proszenia bota o każdą kombinację roli i komendy.
const ADMINISTRATOR_BIT = 8n;
function hasBit(bitsStr, bit) {
  const bits = BigInt(bitsStr || 0);
  return (bits & bit) === bit;
}
function roleCanUseDefault(role, permission, modRoleIds) {
  if (hasBit(role.permissions, ADMINISTRATOR_BIT)) return true;
  if (!permission) return true;
  if (hasBit(role.permissions, BigInt(permission))) return true;
  return modRoleIds.includes(role.id);
}
function roleCanUse(role, cmd, config) {
  if (hasBit(role.permissions, ADMINISTRATOR_BIT)) return true;
  const override = config.commandPermissions?.[cmd.name];
  if (Array.isArray(override)) return override.includes(role.id);
  return roleCanUseDefault(role, cmd.permission, config.modRoleIds);
}

function renderPermissions() {
  if (!state.commandMeta) return;
  const roles = state.guild.roles;
  const overrides = state.draft.commandPermissions ?? (state.draft.commandPermissions = {});

  $('#permission-commands').innerHTML = state.commandMeta
    .map((cmd) => {
      const active = Array.isArray(overrides[cmd.name]);
      const selected = new Set(active ? overrides[cmd.name] : roles.filter((r) => roleCanUseDefault(r, cmd.permission, state.draft.modRoleIds)).map((r) => r.id));
      const roleChecks = roles
        .map(
          (r) => `<label class="role-item"><input type="checkbox" data-perm-role="${r.id}" data-perm-cmd="${cmd.name}" ${selected.has(r.id) ? 'checked' : ''} ${active ? '' : 'disabled'}><span class="role-dot" style="background:${esc(r.color === '#000000' ? '#99aab5' : r.color)}"></span>${esc(r.name)}</label>`,
        )
        .join('');
      return `<div class="perm-command" data-cmd="${cmd.name}">
        <div class="perm-command-head">
          <div><code>/${esc(cmd.name)}</code> <span class="muted small">${esc(cmd.description)}</span></div>
          <label class="toggle small"><input type="checkbox" data-perm-toggle="${cmd.name}" ${active ? 'checked' : ''}><span></span>Ogranicz do wybranych ról</label>
        </div>
        <div class="muted small">Domyślnie: ${esc(cmd.permissionLabel)}${state.draft.modRoleIds.length ? ' albo rola moderatora z Ustawień' : ''}</div>
        <div class="roles-list compact">${roleChecks || '<p class="muted small">Brak ról na serwerze.</p>'}</div>
      </div>`;
    })
    .join('');

  renderPermissionMatrix();
}

function renderPermissionMatrix() {
  const roles = state.guild.roles;
  const cmds = state.commandMeta;
  if (!roles?.length || !cmds?.length) {
    $('#permission-matrix').innerHTML = '<tr><td class="empty">Bot nie jest jeszcze połączony z serwerem — role pojawią się tutaj automatycznie.</td></tr>';
    return;
  }
  const head = `<thead><tr><th>Rola</th>${cmds.map((c) => `<th title="${esc(c.description)}">/${esc(c.name)}</th>`).join('')}</tr></thead>`;
  const body = roles
    .map((r) => {
      const cells = cmds.map((c) => `<td class="perm-cell">${roleCanUse(r, c, state.draft) ? '<span class="yes">tak</span>' : '<span class="no">—</span>'}</td>`).join('');
      return `<tr><td><span class="role-dot" style="background:${esc(r.color === '#000000' ? '#99aab5' : r.color)}"></span>${esc(r.name)}</td>${cells}</tr>`;
    })
    .join('');
  $('#permission-matrix').innerHTML = head + `<tbody>${body}</tbody>`;
}

$('#permission-commands').addEventListener('change', (event) => {
  const toggleName = event.target.dataset.permToggle;
  if (toggleName) {
    if (event.target.checked) {
      const cmd = state.commandMeta.find((c) => c.name === toggleName);
      state.draft.commandPermissions[toggleName] = state.guild.roles
        .filter((r) => roleCanUseDefault(r, cmd.permission, state.draft.modRoleIds))
        .map((r) => r.id);
    } else {
      delete state.draft.commandPermissions[toggleName];
    }
    renderPermissions();
    markDirty();
    return;
  }
  const roleId = event.target.dataset.permRole;
  const cmdName = event.target.dataset.permCmd;
  if (!roleId || !cmdName) return;
  const list = state.draft.commandPermissions[cmdName] ?? [];
  state.draft.commandPermissions[cmdName] = event.target.checked ? [...new Set([...list, roleId])] : list.filter((id) => id !== roleId);
  renderPermissionMatrix();
  markDirty();
});

// ---------- Progi automatycznych kar ----------
const optionList = (map, selected) =>
  Object.entries(map).map(([value, label]) => `<option value="${value}" ${value === selected ? 'selected' : ''}>${label}</option>`).join('');

function renderRules() {
  const rules = state.draft.escalation.rules;
  const body = rules
    .map((rule, i) => {
      const noTime = rule.action === 'alert' || rule.action === 'kick';
      return `<tr data-rule="${i}">
        <td><input type="number" min="1" value="${rule.points}" data-rule-field="points" aria-label="Próg punktów"></td>
        <td><select data-rule-field="action" aria-label="Akcja">${optionList(RULE_ACTIONS, rule.action)}</select></td>
        <td><input type="number" min="0" value="${rule.amount}" data-rule-field="amount" aria-label="Czas" ${noTime ? 'disabled' : ''}></td>
        <td><select data-rule-field="unit" aria-label="Jednostka" ${noTime ? 'disabled' : ''}>${optionList(UNIT_LABELS, rule.unit)}</select></td>
        <td><button type="button" class="btn ghost small" data-rule-del>Usuń</button></td>
      </tr>`;
    })
    .join('');
  $('#rules-table').innerHTML = `<thead><tr><th>Próg (pkt)</th><th>Akcja</th><th>Czas</th><th>Jednostka</th><th></th></tr></thead>
    <tbody>${body || '<tr><td colspan="5" class="empty">Brak progów</td></tr>'}</tbody>
    <tfoot><tr><td colspan="5" class="muted small">Czas 0 przy banie = ban permanentny. Timeout: maks. 28 dni (0 = 28 dni).</td></tr></tfoot>`;
}

function onRuleChange(event) {
  const row = event.target.closest('[data-rule]');
  const field = event.target.dataset.ruleField;
  if (!row || !field) return;
  event.stopPropagation();
  const rule = state.draft.escalation.rules[Number(row.dataset.rule)];
  rule[field] = field === 'points' || field === 'amount' ? Math.max(0, Math.floor(Number(event.target.value) || 0)) : event.target.value;
  if (field === 'action') renderRules();
  markDirty();
}
$('#rules-table').addEventListener('input', onRuleChange);
$('#rules-table').addEventListener('change', onRuleChange);
$('#rules-table').addEventListener('click', (event) => {
  const row = event.target.closest('[data-rule-del]') && event.target.closest('[data-rule]');
  if (!row) return;
  state.draft.escalation.rules.splice(Number(row.dataset.rule), 1);
  renderRules();
  markDirty();
});
$('#add-rule').addEventListener('click', () => {
  const last = state.draft.escalation.rules.at(-1);
  state.draft.escalation.rules.push({ points: (last?.points ?? 0) + 5, action: 'timeout', amount: 1, unit: 'h' });
  renderRules();
  markDirty();
});

// ---------- Status bota (opisy) ----------
function renderActivities() {
  const list = state.draft.presence.activities;
  const body = list
    .map(
      (a, i) => `<tr data-activity="${i}">
        <td><select data-activity-field="type" aria-label="Rodzaj">${optionList(ACTIVITY_LABELS, a.type)}</select></td>
        <td class="grow"><input type="text" maxlength="128" value="${esc(a.text)}" data-activity-field="text" aria-label="Tekst opisu"></td>
        <td><button type="button" class="btn ghost small" data-activity-del>Usuń</button></td>
      </tr>`,
    )
    .join('');
  $('#activities-table').innerHTML = `<thead><tr><th>Rodzaj</th><th>Tekst</th><th></th></tr></thead>
    <tbody>${body || '<tr><td colspan="3" class="empty">Brak opisów — bot będzie tylko „online”.</td></tr>'}</tbody>`;
  $('#add-activity').disabled = list.length >= 10;
}

function onActivityChange(event) {
  const row = event.target.closest('[data-activity]');
  const field = event.target.dataset.activityField;
  if (!row || !field) return;
  event.stopPropagation();
  state.draft.presence.activities[Number(row.dataset.activity)][field] = event.target.value;
  state.lastActivityInput = field === 'text' ? event.target : state.lastActivityInput;
  markDirty();
}
$('#activities-table').addEventListener('input', onActivityChange);
$('#activities-table').addEventListener('change', onActivityChange);
$('#activities-table').addEventListener('focusin', (event) => {
  if (event.target.dataset.activityField === 'text') state.lastActivityInput = event.target;
});
$('#activities-table').addEventListener('click', (event) => {
  const row = event.target.closest('[data-activity-del]') && event.target.closest('[data-activity]');
  if (!row) return;
  state.draft.presence.activities.splice(Number(row.dataset.activity), 1);
  state.lastActivityInput = null;
  renderActivities();
  markDirty();
});
$('#add-activity').addEventListener('click', () => {
  state.draft.presence.activities.push({ type: 'custom', text: '' });
  renderActivities();
  markDirty();
  const inputs = $$('#activities-table [data-activity-field="text"]');
  inputs.at(-1)?.focus();
});
$('#presence-chips').addEventListener('click', (event) => {
  const text = event.target.closest('[data-presence-placeholder]')?.dataset.presencePlaceholder;
  const field = state.lastActivityInput?.isConnected ? state.lastActivityInput : $$('#activities-table [data-activity-field="text"]').at(-1);
  if (!text || !field) return;
  const start = field.selectionStart ?? field.value.length;
  const end = field.selectionEnd ?? field.value.length;
  field.value = (field.value.slice(0, start) + text + field.value.slice(end)).slice(0, 128);
  field.focus();
  field.setSelectionRange(start + text.length, start + text.length);
  field.dispatchEvent(new Event('input', { bubbles: true }));
});

// ---------- Hasło panelu ----------
function renderPasswordStatus(status) {
  const text = $('#password-status');
  const form = $('#password-form');
  if (!text || !form) return;
  text.textContent = 'Po zmianie poprzednie hasło przestaje działać (także to z sekretu PANEL_PASSWORD w Supabase).';
  form.classList.remove('hidden');
}

$('#password-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const next = $('#password-new').value;
  const repeat = $('#password-repeat').value;
  if (next !== repeat) return toast('Hasła nie są takie same.', 'error');
  $('#password-submit').disabled = true;
  try {
    await api('/password', { method: 'POST', body: JSON.stringify({ next }) });
    setPanelPassword(next);
    $('#password-new').value = '';
    $('#password-repeat').value = '';
    toast('Hasło panelu zmienione');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    $('#password-submit').disabled = false;
  }
});

function renderGatewayLine(gateway) {
  const line = $('#gateway-line');
  if (!line) return;
  if (!gateway?.startedAt) {
    line.textContent = 'gateway: jeszcze nie połączony';
    return;
  }
  const fresh = Date.now() - (gateway.endedAt ?? gateway.startedAt) < 3 * MINUTE;
  const current = gateway.activity ? ` • „${gateway.activity}”` : '';
  line.innerHTML = gateway.error
    ? `<span class="dot bad"></span>${esc(gateway.error)}`
    : `<span class="dot ${fresh ? 'ok' : 'wait'}"></span>gateway ${relTime(gateway.endedAt ?? gateway.startedAt)}${esc(current)}`;
}

// ---------- Kanały głosowe na żądanie ----------
function renderBotWarnings() {
  const show = (el, missing, what) => {
    el.classList.toggle('hidden', !missing?.length);
    if (missing?.length) {
      el.innerHTML = `<strong>Bot nie ma uprawnień: ${missing.map(esc).join(', ')}.</strong> Nadaj je roli bota (Ustawienia serwera → Role), inaczej ${what} nie zadziała.`;
    }
  };
  show($('#voice-warning'), state.guild.bot?.missingVoice, 'tworzenie kanałów głosowych');
  show($('#roles-warning'), state.guild.bot?.missingRoles, 'rozdawanie ról');
}

function channelOptions(list, selected, emptyLabel, prefix = '') {
  let html = `<option value="">${esc(emptyLabel)}</option>`;
  html += list
    .map((c) => `<option value="${c.id}" ${c.id === selected ? 'selected' : ''}>${prefix}${esc(c.name)}${c.category ? ` · ${esc(c.category)}` : ''}</option>`)
    .join('');
  if (selected && !list.some((c) => c.id === selected)) html += `<option value="${esc(selected)}" selected>(nieznany kanał ${esc(selected)})</option>`;
  return html;
}

function renderGenerators() {
  const list = state.draft.tempVoice.generators;
  const body = list
    .map(
      (g, i) => `<tr data-gen="${i}">
        <td><select data-gen-field="hubId" aria-label="Kanał do dołączenia">${channelOptions(state.guild.voiceChannels, g.hubId, '— wybierz kanał —')}</select></td>
        <td><select data-gen-field="categoryId" aria-label="Kategoria">${channelOptions(state.guild.categories, g.categoryId, 'Ta sama co kanał do dołączenia')}</select></td>
        <td class="grow"><input type="text" maxlength="90" value="${esc(g.name)}" data-gen-field="name" aria-label="Nazwa nowego kanału"></td>
        <td><input type="number" min="0" max="99" value="${g.limit}" data-gen-field="limit" aria-label="Limit osób"></td>
        <td><label class="toggle small"><input type="checkbox" data-gen-field="private" ${g.private ? 'checked' : ''}><span></span>Prywatny</label></td>
        <td><button type="button" class="btn ghost small" data-gen-del>Usuń</button></td>
      </tr>`,
    )
    .join('');
  $('#generators-table').innerHTML = `<thead><tr><th>Kanał do dołączenia</th><th>Kategoria nowych kanałów</th><th>Nazwa nowego kanału</th><th>Limit</th><th>Na start</th><th></th></tr></thead>
    <tbody>${body || '<tr><td colspan="6" class="empty">Dodaj kanał, na który trzeba wejść, żeby dostać własny kanał.</td></tr>'}</tbody>`;
  $('#add-generator').disabled = list.length >= 10;
}

function onGeneratorChange(event) {
  const row = event.target.closest('[data-gen]');
  const field = event.target.dataset.genField;
  if (!row || !field) return;
  event.stopPropagation();
  const generator = state.draft.tempVoice.generators[Number(row.dataset.gen)];
  if (field === 'private') generator.private = event.target.checked;
  else if (field === 'limit') generator.limit = Math.min(99, Math.max(0, Math.floor(Number(event.target.value) || 0)));
  else generator[field] = event.target.value;
  markDirty();
}
$('#generators-table').addEventListener('input', onGeneratorChange);
$('#generators-table').addEventListener('change', onGeneratorChange);
$('#generators-table').addEventListener('click', (event) => {
  const row = event.target.closest('[data-gen-del]') && event.target.closest('[data-gen]');
  if (!row) return;
  state.draft.tempVoice.generators.splice(Number(row.dataset.gen), 1);
  renderGenerators();
  markDirty();
});
$('#add-generator').addEventListener('click', () => {
  state.draft.tempVoice.generators.push({ hubId: '', categoryId: '', name: 'Kanał {nick}', limit: 0, private: false });
  renderGenerators();
  markDirty();
});
$('#dashboard-color-picker').addEventListener('input', (event) => {
  event.stopPropagation();
  const field = $('[data-path="tempVoice.dashboard.color"]');
  field.value = event.target.value.toUpperCase();
  field.dispatchEvent(new Event('input', { bubbles: true }));
});

async function loadVoice() {
  try {
    const { channels } = await api('/voice');
    const rows = channels
      .map(
        (c) => `<tr>
          <td><strong>${esc(c.name ?? 'kanał')}</strong><span class="note">${esc(c.channelId)}</span></td>
          <td><span class="note">ID właściciela</span>${esc(c.ownerId)}</td>
          <td>${c.members}</td>
          <td>${c.private ? 'prywatny' : 'publiczny'}${c.limit ? ` • limit ${c.limit}` : ''}</td>
          <td>${esc(relTime(c.createdAt))}</td>
          <td><button type="button" class="btn danger small" data-voice-del="${esc(c.channelId)}">Usuń kanał</button></td>
        </tr>`,
      )
      .join('');
    $('#voice-table').innerHTML = channels.length
      ? `<thead><tr><th>Kanał</th><th>Właściciel</th><th>Osób</th><th>Ustawienia</th><th>Utworzony</th><th></th></tr></thead><tbody>${rows}</tbody>`
      : '<tr><td class="empty">Nikt nie ma teraz własnego kanału.</td></tr>';
  } catch (error) {
    toast(error.message, 'error');
  }
}
$('#voice-refresh').addEventListener('click', loadVoice);
$('#voice-table').addEventListener('click', async (event) => {
  const id = event.target.closest('[data-voice-del]')?.dataset.voiceDel;
  if (!id || !confirm('Usunąć ten kanał? Osoby na nim zostaną rozłączone.')) return;
  try {
    await api(`/voice/${id}`, { method: 'DELETE' });
    toast('Kanał usunięty');
  } catch (error) {
    toast(error.message, 'error');
  }
  loadVoice();
});

// ---------- Wiadomości (z przyciskami / listą ról) ----------
function blankMessage() {
  return {
    channelId: '',
    content: '',
    embed: { enabled: false, title: '', description: '', color: '#5865F2', image: '', footer: '' },
    roles: { mode: 'none', placeholder: '', multiple: true, items: [] },
  };
}

const roleById = (id) => state.guild.roles.find((r) => r.id === id);

function renderMessageEditor() {
  if (!state.msg) return;
  const { data, editingId } = state.msg;
  $('#msg-editor-title').textContent = editingId ? 'Edycja wysłanej wiadomości' : 'Nowa wiadomość';
  $('#msg-send').textContent = editingId ? 'Zapisz zmiany' : 'Wyślij';
  $('#msg-cancel').classList.toggle('hidden', !editingId);
  $('#msg-channel').innerHTML = channelOptions(state.guild.channels, data.channelId, '— wybierz kanał —', '#');
  $('#msg-channel').disabled = Boolean(editingId);
  $('#msg-content').value = data.content;
  $('#msg-embed-enabled').checked = data.embed.enabled;
  $('#msg-embed-title').value = data.embed.title;
  $('#msg-embed-description').value = data.embed.description;
  $('#msg-embed-color').value = data.embed.color;
  if (/^#[0-9a-f]{6}$/i.test(data.embed.color)) $('#msg-embed-color-picker').value = data.embed.color;
  $('#msg-embed-image').value = data.embed.image;
  $('#msg-embed-footer').value = data.embed.footer;
  $$('input[name="role-mode"]').forEach((el) => (el.checked = el.value === data.roles.mode));
  $('#msg-placeholder').value = data.roles.placeholder;
  $('#msg-multiple').checked = data.roles.multiple;
  renderMessageItems();
  syncMessageSections();
}

function syncMessageSections() {
  const { data } = state.msg;
  $('#msg-embed-fields').classList.toggle('hidden', !data.embed.enabled);
  $('#msg-roles').classList.toggle('hidden', data.roles.mode === 'none');
  $('#msg-select-options').classList.toggle('hidden', data.roles.mode !== 'select');
  renderMessagePreview();
}

function renderMessageItems() {
  const { mode, items } = state.msg.data.roles;
  const assignable = state.guild.roles.filter((r) => r.assignable);
  const roleSelect = (selected) => {
    let html = '<option value="">— wybierz rolę —</option>';
    html += assignable.map((r) => `<option value="${r.id}" ${r.id === selected ? 'selected' : ''}>${esc(r.name)}</option>`).join('');
    if (selected && !assignable.some((r) => r.id === selected)) {
      html += `<option value="${esc(selected)}" selected>${esc(roleById(selected)?.name ?? 'nieznana rola')} (bot nie może jej nadać)</option>`;
    }
    return html;
  };
  const rows = items
    .map(
      (item, i) => `<tr data-item="${i}">
        <td><select data-item-field="roleId" aria-label="Rola">${roleSelect(item.roleId)}</select></td>
        <td class="grow"><input type="text" maxlength="80" value="${esc(item.label)}" data-item-field="label" placeholder="${esc(roleById(item.roleId)?.name ?? 'Nazwa roli')}" aria-label="Napis"></td>
        <td>${
          mode === 'buttons'
            ? `<select data-item-field="style" aria-label="Kolor przycisku">${Object.entries(BUTTON_STYLES).map(([v, l]) => `<option value="${v}" ${v === item.style ? 'selected' : ''}>${l}</option>`).join('')}</select>`
            : `<input type="text" maxlength="100" value="${esc(item.description)}" data-item-field="description" placeholder="Opis (opcjonalnie)" aria-label="Opis">`
        }</td>
        <td><button type="button" class="btn ghost small" data-item-del>Usuń</button></td>
      </tr>`,
    )
    .join('');
  const third = mode === 'buttons' ? 'Kolor' : 'Opis na liście';
  $('#msg-items').innerHTML = `<thead><tr><th>Rola</th><th>Napis (puste = nazwa roli)</th><th>${third}</th><th></th></tr></thead>
    <tbody>${rows || '<tr><td colspan="4" class="empty">Dodaj role, które można będzie sobie wybrać.</td></tr>'}</tbody>`;
  $('#msg-add-item').disabled = items.length >= 25;
}

function readMessageForm() {
  const { data } = state.msg;
  data.channelId = $('#msg-channel').value;
  data.content = $('#msg-content').value;
  data.embed.enabled = $('#msg-embed-enabled').checked;
  data.embed.title = $('#msg-embed-title').value;
  data.embed.description = $('#msg-embed-description').value;
  data.embed.color = $('#msg-embed-color').value;
  data.embed.image = $('#msg-embed-image').value.trim();
  data.embed.footer = $('#msg-embed-footer').value;
  data.roles.mode = $('input[name="role-mode"]:checked')?.value ?? 'none';
  data.roles.placeholder = $('#msg-placeholder').value;
  data.roles.multiple = $('#msg-multiple').checked;
}

function onMessageFormChange(event) {
  if (!state.msg) return;
  const el = event.target;
  const row = el.closest('[data-item]');
  if (row && el.dataset.itemField) {
    state.msg.data.roles.items[Number(row.dataset.item)][el.dataset.itemField] = el.value;
    if (el.dataset.itemField === 'roleId') renderMessageItems();
    renderMessagePreview();
    return;
  }
  const modeBefore = state.msg.data.roles.mode;
  readMessageForm();
  if (el.id === 'msg-embed-color' && /^#[0-9a-f]{6}$/i.test(el.value)) $('#msg-embed-color-picker').value = el.value;
  if (state.msg.data.roles.mode !== modeBefore) renderMessageItems();
  syncMessageSections();
}
$('#view-wiadomosci').addEventListener('input', onMessageFormChange);
$('#view-wiadomosci').addEventListener('change', onMessageFormChange);
$('#msg-embed-color-picker').addEventListener('input', (event) => {
  event.stopPropagation();
  $('#msg-embed-color').value = event.target.value.toUpperCase();
  $('#msg-embed-color').dispatchEvent(new Event('input', { bubbles: true }));
});
$('#msg-items').addEventListener('click', (event) => {
  const row = event.target.closest('[data-item-del]') && event.target.closest('[data-item]');
  if (!row) return;
  state.msg.data.roles.items.splice(Number(row.dataset.item), 1);
  renderMessageItems();
  renderMessagePreview();
});
$('#msg-add-item').addEventListener('click', () => {
  const used = new Set(state.msg.data.roles.items.map((i) => i.roleId));
  const next = state.guild.roles.find((r) => r.assignable && !used.has(r.id));
  state.msg.data.roles.items.push({ roleId: next?.id ?? '', label: '', description: '', style: 'niebieski' });
  renderMessageItems();
  renderMessagePreview();
});
$('#msg-cancel').addEventListener('click', () => {
  state.msg = { editingId: null, data: blankMessage() };
  renderMessageEditor();
});
$('#msg-send').addEventListener('click', async () => {
  readMessageForm();
  const { editingId, data } = state.msg;
  $('#msg-send').disabled = true;
  try {
    if (editingId) await api(`/messages/${editingId}`, { method: 'PUT', body: JSON.stringify(data) });
    else await api('/messages', { method: 'POST', body: JSON.stringify(data) });
    toast(editingId ? 'Wiadomość zaktualizowana' : 'Wiadomość wysłana');
    if (!editingId) {
      state.msg = { editingId: null, data: { ...blankMessage(), channelId: data.channelId } };
      renderMessageEditor();
    }
    loadSentMessages();
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    $('#msg-send').disabled = false;
  }
});

function renderMessagePreview() {
  const d = state.msg.data;
  const time = new Date().toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
  const avatar = state.status?.bot?.avatar ? `<img src="${esc(state.status.bot.avatar)}" alt="">` : 'EH';
  const name = state.status?.bot?.tag?.split('#')[0] ?? 'Entuzjaści Hopkostki';
  const hasEmbed = d.embed.enabled && (d.embed.title || d.embed.description || d.embed.image);
  const embed = hasEmbed
    ? `<div class="embed" style="border-left-color:${esc(/^#[0-9a-f]{6}$/i.test(d.embed.color) ? d.embed.color : '#5865F2')}">
        <div>
          ${d.embed.title ? `<div class="embed-title">${md(d.embed.title)}</div>` : ''}
          ${d.embed.description ? `<div class="embed-desc">${md(d.embed.description)}</div>` : ''}
          ${/^https:\/\//i.test(d.embed.image) ? `<img class="embed-image" src="${esc(d.embed.image)}" alt="">` : ''}
        </div>
        ${d.embed.footer ? `<div class="embed-footer">${esc(d.embed.footer)}</div>` : ''}
      </div>`
    : '';
  const label = (item) => item.label || roleById(item.roleId)?.name || 'Rola';
  let components = '';
  if (d.roles.mode === 'buttons' && d.roles.items.length) {
    components = `<div class="dc-buttons">${d.roles.items.map((i) => `<span class="dc-button ${esc(i.style)}">${esc(label(i))}</span>`).join('')}</div>`;
  } else if (d.roles.mode === 'select' && d.roles.items.length) {
    components = `<div class="dc-select"><span>${esc(d.roles.placeholder || 'Wybierz role')}</span><i></i></div>
      <div class="dc-options">${d.roles.items.map((i) => `<div><b>${esc(label(i))}</b>${i.description ? `<small>${esc(i.description)}</small>` : ''}</div>`).join('')}</div>`;
  }
  const empty = !d.content && !hasEmbed;
  $('#msg-preview').innerHTML = `<div class="msg">
    <div class="msg-avatar">${avatar}</div>
    <div>
      <div class="msg-head"><span class="msg-name">${esc(name)}</span><span class="msg-app">APP</span><span class="msg-time">Dzisiaj o ${time}</span></div>
      ${empty ? '<div class="muted small">Wpisz treść albo włącz embed.</div>' : ''}
      ${d.content ? `<div>${md(d.content)}</div>` : ''}
      ${embed}
      ${components}
    </div>
  </div>`;
}

async function loadSentMessages() {
  try {
    const { messages } = await api('/messages');
    state.sentMessages = messages;
    const channelName = (id) => state.guild.channels.find((c) => c.id === id)?.name ?? id;
    const modeText = (roles) => (roles.mode === 'buttons' ? `przyciski (${roles.items.length})` : roles.mode === 'select' ? `lista (${roles.items.length})` : '—');
    const rows = messages
      .map((m) => {
        const snippet = m.data.content || m.data.embed.title || m.data.embed.description || '';
        return `<tr>
          <td>#${esc(channelName(m.channelId))}</td>
          <td class="reason">${esc(snippet.slice(0, 120))}</td>
          <td>${esc(modeText(m.data.roles))}</td>
          <td>${esc(shortDate(m.updatedAt))}</td>
          <td class="nowrap"><button type="button" class="btn ghost small" data-msg-edit="${m.id}">Edytuj</button>
            <button type="button" class="btn danger small" data-msg-del="${m.id}">Usuń</button></td>
        </tr>`;
      })
      .join('');
    $('#msg-sent').innerHTML = messages.length
      ? `<thead><tr><th>Kanał</th><th>Treść</th><th>Wybór ról</th><th>Zmieniona</th><th></th></tr></thead><tbody>${rows}</tbody>`
      : '<tr><td class="empty">Nie wysłano jeszcze żadnej wiadomości z panelu.</td></tr>';
  } catch (error) {
    toast(error.message, 'error');
  }
}
$('#msg-sent').addEventListener('click', async (event) => {
  const editId = event.target.closest('[data-msg-edit]')?.dataset.msgEdit;
  const delId = event.target.closest('[data-msg-del]')?.dataset.msgDel;
  if (editId) {
    const found = state.sentMessages.find((m) => String(m.id) === editId);
    if (!found) return;
    state.msg = { editingId: found.id, data: { ...blankMessage(), ...clone(found.data) } };
    renderMessageEditor();
    $('#view-wiadomosci').scrollIntoView({ behavior: 'smooth' });
    return;
  }
  if (!delId || !confirm('Usunąć tę wiadomość z Discorda?')) return;
  try {
    await api(`/messages/${delId}`, { method: 'DELETE' });
    toast('Wiadomość usunięta');
    if (String(state.msg?.editingId) === delId) state.msg = { editingId: null, data: blankMessage() };
    renderMessageEditor();
  } catch (error) {
    toast(error.message, 'error');
  }
  loadSentMessages();
});

// ---------- Zapis ----------
function isDirty() {
  return state.draft && JSON.stringify(state.draft) !== JSON.stringify(state.config);
}
function markDirty() {
  $('#savebar').classList.toggle('hidden', !isDirty());
}

$('#save').addEventListener('click', async () => {
  $('#save').disabled = true;
  try {
    const { config } = await api('/config', { method: 'PUT', body: JSON.stringify(state.draft) });
    state.config = config;
    state.draft = clone(config);
    renderConfigUi();
    renderPreview();
    toast('Zapisano ustawienia');
  } catch (error) {
    toast(error.message, 'error');
  } finally {
    $('#save').disabled = false;
  }
});

$('#discard').addEventListener('click', () => {
  state.draft = clone(state.config);
  renderConfigUi();
  renderPreview();
});

window.addEventListener('beforeunload', (event) => {
  if (isDirty()) event.preventDefault();
});

// ---------- Edytor embedów ----------
function renderEmbedTabs() {
  $('#embed-tabs').innerHTML = Object.keys(ACTION_LABELS)
    .map((action) => {
      const style = state.draft.actions[action];
      return `<button type="button" class="tab ${action === state.embedAction ? 'active' : ''}" data-action="${action}" style="--tab-color:${esc(style.color)}">${ACTION_LABELS[action]}</button>`;
    })
    .join('');
}

function fillEmbedFields() {
  const style = state.draft.actions[state.embedAction];
  $$('[data-embed-field]').forEach((el) => {
    if (document.activeElement !== el) el.value = style[el.dataset.embedField] ?? '';
  });
  if (/^#[0-9a-f]{6}$/i.test(style.color)) $('#embed-color-picker').value = style.color;
}

$('#embed-tabs').addEventListener('click', (event) => {
  const action = event.target.closest('[data-action]')?.dataset.action;
  if (!action) return;
  state.embedAction = action;
  renderEmbedTabs();
  fillEmbedFields();
  renderPreview();
});

$('#embed-color-picker').addEventListener('input', (event) => {
  event.stopPropagation();
  const field = $('[data-embed-field="color"]');
  field.value = event.target.value.toUpperCase();
  field.dispatchEvent(new Event('input', { bubbles: true }));
});

document.addEventListener('focusin', (event) => {
  if (event.target.dataset?.embedField && event.target.dataset.embedField !== 'color') state.lastField = event.target;
});

$('#placeholder-chips').addEventListener('click', (event) => {
  const text = event.target.closest('[data-placeholder]')?.dataset.placeholder;
  const field = state.lastField ?? $('[data-embed-field="description"]');
  if (!text) return;
  const start = field.selectionStart ?? field.value.length;
  const end = field.selectionEnd ?? field.value.length;
  field.value = field.value.slice(0, start) + text + field.value.slice(end);
  field.focus();
  field.setSelectionRange(start + text.length, start + text.length);
  field.dispatchEvent(new Event('input', { bubbles: true }));
});

$('#embed-reset').addEventListener('click', () => {
  state.draft.actions[state.embedAction] = clone(state.defaults.actions[state.embedAction]);
  renderEmbedTabs();
  $$('[data-embed-field]').forEach((el) => (el.value = state.draft.actions[state.embedAction][el.dataset.embedField] ?? ''));
  fillEmbedFields();
  renderPreview();
  markDirty();
});

// Mini-markdown Discorda do podglądu.
function md(text) {
  const names = { [SAMPLE.targetId]: SAMPLE.target, [SAMPLE.modId]: SAMPLE.mod };
  return String(text ?? '')
    .split('\n')
    .map((raw) => {
      let line = raw;
      const small = line.startsWith('-# ');
      if (small) line = line.slice(3);
      const html = esc(line)
        .replace(/&lt;(a?):(\w+):(\d+)&gt;/g, (_, a, name, id) => `<img class="emoji" alt=":${name}:" src="https://cdn.discordapp.com/emojis/${id}.${a ? 'gif' : 'webp'}?size=48">`)
        .replace(/&lt;@!?(\d+)&gt;/g, (_, id) => `<span class="mention">@${esc(names[id] ?? 'użytkownik')}</span>`)
        .replace(/&lt;t:(\d+):([a-zA-Z])&gt;/g, (_, ts, style) => `<span class="ts">${style === 'R' ? relTime(ts * 1000) : fullDate(ts * 1000)}</span>`)
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
      return small ? `<small>${html}</small>` : html;
    })
    .join('<br>');
}

const ts = (ms, style) => `<t:${Math.floor(ms / 1000)}:${style}>`;
const withEmoji = (style, text) => {
  const cleaned = String(text ?? '').replace(/\s{2,}/g, ' ').trim();
  return style.emoji ? `${style.emoji} ${cleaned}` : cleaned;
};

// Odzwierciedla supabase/functions/hopkostki-bot/lib/embeds.js — przykładowe dane do podglądu.
function previewData(action) {
  const cfg = state.draft;
  const style = cfg.actions[action];
  const now = Date.now();
  const server = state.status?.guild?.name ?? 'Entuzjaści Hopkostki';
  const brand = 'Entuzjaści Hopkostki 🫓';
  const ctx = { duration: null, expiresAt: null, warn: null };
  if (action === 'ban') Object.assign(ctx, { duration: '14 dni', expiresAt: now + 14 * DAY });
  if (action === 'timeout') Object.assign(ctx, { duration: '2 godziny', expiresAt: now + 2 * HOUR });
  if (action === 'warn') {
    ctx.warn = { points: 2, count: 3, total: 5, expiresAt: cfg.warns.expiryDays > 0 ? now + cfg.warns.expiryDays * DAY : null };
  }
  const durationText = ctx.duration ?? (action === 'ban' ? 'Permanentny' : null);
  const vars = {
    uzytkownik: `<@${SAMPLE.targetId}>`,
    nick: SAMPLE.target,
    moderator: `<@${SAMPLE.modId}>`,
    moderatorNick: SAMPLE.mod,
    powod: SAMPLE.reason,
    czas: durationText ?? '—',
    serwer: server,
    sprawa: '#42',
    typ: action === 'ban' ? 'tymczasowo' : '',
  };
  const fill = (text) => String(text ?? '').replace(/\{(\w+)\}/g, (m, key) => (key in vars ? vars[key] : m));
  const stamp = (ms) => `${ts(ms, 'f')}\n${ts(ms, 'R')}`;

  const timeFields = [];
  if (durationText) timeFields.push({ name: '⏱️ Czas trwania', value: `**${durationText}**`, inline: true });
  if (ctx.expiresAt) timeFields.push({ name: '📅 Wygasa', value: stamp(ctx.expiresAt), inline: true });
  const warnFields = (points, totals) => {
    if (!ctx.warn) return [];
    const fields = [];
    if (points) fields.push({ name: '🔢 Punkty', value: `**+${ctx.warn.points}**${totals ? ` (razem **${ctx.warn.total}**)` : ''}`, inline: true });
    if (ctx.warn.expiresAt) fields.push({ name: '⏳ Ostrzeżenie wygasa', value: stamp(ctx.warn.expiresAt), inline: true });
    return fields;
  };
  const issued = { name: '🗓️ Nałożono', value: stamp(now), inline: true };
  const reason = { name: '📝 Powód', value: SAMPLE.reason, code: true };
  const showPoints = cfg.warns.showPointsToUser;

  const channel = {
    content: cfg.mentionTarget ? `<@${SAMPLE.targetId}>` : '',
    author: `${server} • Moderacja`,
    title: withEmoji(style, fill(style.title)),
    description: fill(style.description).trim(),
    fields: [
      { name: '👤 Użytkownik', value: `<@${SAMPLE.targetId}>\n\`${SAMPLE.target}\``, inline: true },
      { name: '🛡️ Moderator', value: `<@${SAMPLE.modId}>`, inline: true },
      ...timeFields,
      issued,
      ...warnFields(showPoints, false),
      reason,
    ],
    footer: `Sprawa #42 • ${brand}`,
    thumb: { placeholder: true },
  };

  const dmFields = [...timeFields, issued, ...warnFields(showPoints, showPoints)];
  if (cfg.dmShowModerator) dmFields.push({ name: '🛡️ Moderator', value: SAMPLE.mod, inline: true });
  dmFields.push(reason);
  if (cfg.appealText && ['ban', 'kick', 'timeout', 'warn'].includes(action)) dmFields.push({ name: '💬 Odwołania', value: cfg.appealText });
  const dm = {
    author: server,
    title: withEmoji(style, fill(style.dmTitle)),
    description: fill(style.dmDescription).trim(),
    fields: dmFields,
    footer: `Sprawa #42 • ${brand}`,
    thumb: state.status?.guild?.icon ? { src: state.status.guild.icon } : { placeholder: true },
  };

  return { color: style.color, channel, dm };
}

// Pola embeda jak w Discordzie: do 3 pól "inline" w rzędzie, pozostałe na całą szerokość.
function fieldsHtml(fields = []) {
  if (!fields.length) return '';
  const items = fields
    .map((f) => {
      const value = f.code ? `<pre class="codeblock">${esc(f.value)}</pre>` : md(f.value);
      return `<div class="embed-field ${f.inline ? 'inline' : ''}"><div class="embed-field-name">${md(f.name)}</div><div class="embed-field-value">${value}</div></div>`;
    })
    .join('');
  return `<div class="embed-fields">${items}</div>`;
}

function thumbHtml(thumb) {
  if (!thumb) return '<div></div>';
  if (thumb.src) return `<div class="embed-thumb"><img src="${esc(thumb.src)}" alt=""></div>`;
  return '<div class="embed-thumb"></div>';
}

function messageHtml({ color, embed, content, slash }) {
  const time = new Date().toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
  const avatar = state.status?.bot?.avatar ? `<img src="${esc(state.status.bot.avatar)}" alt="">` : 'EH';
  const name = state.status?.bot?.tag?.split('#')[0] ?? 'Entuzjaści Hopkostki';
  const icon = state.status?.guild?.icon ? `<img class="embed-author-icon" src="${esc(state.status.guild.icon)}" alt="">` : '';
  return `<div class="msg">
    <div class="msg-avatar">${avatar}</div>
    <div>
      ${slash ? `<div class="msg-reply">↱ <b>${SAMPLE.mod}</b> użył <span class="mention">${slash}</span></div>` : ''}
      <div class="msg-head"><span class="msg-name">${esc(name)}</span><span class="msg-app">APP</span><span class="msg-time">Dzisiaj o ${time}</span></div>
      ${content ? `<div>${md(content)}</div>` : ''}
      <div class="embed" style="border-left-color:${esc(color)}">
        <div>
          ${embed.author ? `<div class="embed-author">${icon}${esc(embed.author)}</div>` : ''}
          <div class="embed-title">${md(embed.title)}</div>
          ${embed.description ? `<div class="embed-desc">${md(embed.description)}</div>` : ''}
          ${fieldsHtml(embed.fields)}
        </div>
        ${thumbHtml(embed.thumb)}
        <div class="embed-footer">${esc(embed.footer)} • Dzisiaj o ${time}</div>
      </div>
    </div>
  </div>`;
}

function renderPreview() {
  if (!state.draft || state.view !== 'embedy') return;
  const data = previewData(state.embedAction);
  $('#preview-channel').innerHTML = messageHtml({
    color: data.color,
    embed: data.channel,
    content: data.channel.content,
    slash: SLASH_NAMES[state.embedAction],
  });
  $('#preview-dm').innerHTML = state.draft.dmUsers
    ? messageHtml({ color: data.color, embed: data.dm })
    : '<p class="muted small">Wysyłanie DM jest wyłączone w ustawieniach.</p>';
}

// ---------- Ostrzeżenia ----------
async function loadWarns() {
  try {
    const data = await api('/warns');
    state.warnUsers = data.users;
    renderWarns();
  } catch (error) {
    toast(error.message, 'error');
  }
}

function lifeBar(w) {
  if (!w.expiresAt) return '<span class="muted small">nie wygasa</span>';
  return `<div class="countdown" data-expires="${w.expiresAt}">${formatCountdown(w.expiresAt - Date.now())}</div>
    <div class="lifebar" title="Wygasa ${esc(fullDate(w.expiresAt))}"><i data-created="${w.createdAt}" data-until="${w.expiresAt}"></i></div>`;
}

function renderWarns() {
  const query = $('#warn-search').value.trim().toLowerCase();
  const users = state.warnUsers.filter((u) => !query || u.userTag?.toLowerCase().includes(query) || u.userId.includes(query));
  const scale = state.config.escalation.rules.at(-1)?.points ?? 10;

  if (!users.length) {
    $('#warn-users').innerHTML = `<p class="empty">${query ? 'Nic nie znaleziono.' : 'Nikt nie ma aktywnych ostrzeżeń.'}</p>`;
    return;
  }
  $('#warn-users').innerHTML = users
    .map((u) => {
      const ratio = u.points / scale;
      const color = ratio >= 1 ? 'var(--red)' : ratio >= 0.5 ? '#f0883e' : 'var(--yellow)';
      const rows = u.warns
        .map(
          (w) => `<div class="warn-row">
            <strong>#${w.id}</strong>
            <span class="badge" style="background:var(--yellow)">${w.points} pkt</span>
            <div class="reason">${esc(w.reason)}<span class="note">od ${esc(w.moderatorTag)} • ${esc(shortDate(w.createdAt))}</span></div>
            <div>${lifeBar(w)}</div>
            <button type="button" class="btn danger small" data-del-warn="${w.id}">Usuń</button>
          </div>`,
        )
        .join('');
      return `<details class="warn-user" data-user="${u.userId}" ${state.openWarnUsers.has(u.userId) ? 'open' : ''}>
        <summary>
          <span class="who">${esc(u.userTag)}</span>
          <code>${esc(u.userId)}</code>
          <span class="muted small">${u.count} ${plural(u.count, ['ostrzeżenie', 'ostrzeżenia', 'ostrzeżeń'])}</span>
          <span class="points">${u.points} pkt</span>
          <span class="severity" title="${u.points}/${scale} pkt"><i style="width:${Math.min(100, ratio * 100)}%;background:${color}"></i></span>
        </summary>
        <div class="warn-list">${rows}</div>
      </details>`;
    })
    .join('');
  tick();
}

$('#warn-search').addEventListener('input', (event) => {
  event.stopPropagation();
  renderWarns();
});
$('#warn-users').addEventListener(
  'toggle',
  (event) => {
    const id = event.target.dataset?.user;
    if (!id) return;
    if (event.target.open) state.openWarnUsers.add(id);
    else state.openWarnUsers.delete(id);
  },
  true,
);
$('#warn-users').addEventListener('click', async (event) => {
  const id = event.target.closest('[data-del-warn]')?.dataset.delWarn;
  if (!id || !confirm(`Usunąć ostrzeżenie #${id}?`)) return;
  try {
    await api(`/warns/${id}`, { method: 'DELETE' });
    toast(`Usunięto ostrzeżenie #${id}`);
    loadWarns();
  } catch (error) {
    toast(error.message, 'error');
  }
});

// ---------- Sprawy ----------
async function loadCases() {
  const params = new URLSearchParams({
    page: state.casesPage,
    limit: 25,
    user: $('#case-search').value.trim(),
    type: $('#case-type').value,
  });
  try {
    const data = await api(`/cases?${params}`);
    $('#cases-table').innerHTML = casesTable(data.items);
    const pages = Math.max(1, Math.ceil(data.total / data.limit));
    $('#cases-pager').innerHTML = `
      <button type="button" class="btn ghost small" data-page="${data.page - 1}" ${data.page <= 1 ? 'disabled' : ''}>← Nowsze</button>
      <span class="muted small">Strona ${data.page} z ${pages} • ${data.total} spraw</span>
      <button type="button" class="btn ghost small" data-page="${data.page + 1}" ${data.page >= pages ? 'disabled' : ''}>Starsze →</button>`;
  } catch (error) {
    toast(error.message, 'error');
  }
}
let caseSearchTimer = null;
$('#case-search').addEventListener('input', (event) => {
  event.stopPropagation();
  clearTimeout(caseSearchTimer);
  caseSearchTimer = setTimeout(() => {
    state.casesPage = 1;
    loadCases();
  }, 300);
});
$('#case-type').addEventListener('change', (event) => {
  event.stopPropagation();
  state.casesPage = 1;
  loadCases();
});
$('#cases-pager').addEventListener('click', (event) => {
  const page = event.target.closest('[data-page]')?.dataset.page;
  if (!page) return;
  state.casesPage = Number(page);
  loadCases();
});

// ---------- Tymczasowe bany ----------
async function loadBans() {
  try {
    const { bans } = await api('/tempbans');
    const rows = bans
      .map(
        (b) => `<tr>
          <td><strong>${esc(b.userTag)}</strong><span class="note">${esc(b.userId)}</span></td>
          <td><div class="countdown" data-expires="${b.expiresAt}">${formatCountdown(b.expiresAt - Date.now())}</div><span class="note">${esc(fullDate(b.expiresAt))}</span></td>
          <td>#${b.caseId}</td>
          <td><button type="button" class="btn success small" data-unban="${esc(b.userId)}">Odbanuj teraz</button></td>
        </tr>`,
      )
      .join('');
    $('#bans-table').innerHTML = bans.length
      ? `<thead><tr><th>Użytkownik</th><th>Pozostało</th><th>Sprawa</th><th></th></tr></thead><tbody>${rows}</tbody>`
      : '<tr><td class="empty">Brak aktywnych tymczasowych banów.</td></tr>';
  } catch (error) {
    toast(error.message, 'error');
  }
}
$('#bans-table').addEventListener('click', async (event) => {
  const userId = event.target.closest('[data-unban]')?.dataset.unban;
  if (!userId || !confirm('Zdjąć tego bana teraz?')) return;
  try {
    await api(`/tempbans/${userId}/unban`, { method: 'POST' });
    toast('Ban zdjęty');
  } catch (error) {
    toast(error.message, 'error');
  }
  loadBans();
});

// ---------- Odliczanie (co sekundę) ----------
function tick() {
  const now = Date.now();
  let expired = false;
  $$('[data-expires]').forEach((el) => {
    const left = Number(el.dataset.expires) - now;
    el.textContent = formatCountdown(left);
    if (left <= 0) expired = true;
  });
  $$('[data-until]').forEach((el) => {
    const created = Number(el.dataset.created);
    const until = Number(el.dataset.until);
    const left = Math.max(0, Math.min(1, (until - now) / (until - created)));
    el.style.width = `${(left * 100).toFixed(2)}%`;
  });
  // Bot sprawdza wygasanie co 30 s — po chwili odświeżamy listę.
  if (expired && !state.reloadPending) {
    state.reloadPending = true;
    setTimeout(() => {
      state.reloadPending = false;
      if (state.view === 'ostrzezenia') loadWarns();
      if (state.view === 'bany') loadBans();
    }, 35_000);
  }
}

boot();
