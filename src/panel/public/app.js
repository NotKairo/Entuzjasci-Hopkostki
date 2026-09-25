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
const RULE_ACTIONS = { alert: 'Alert 🔔', timeout: 'Timeout', kick: 'Kick', ban: 'Ban' };
const PLACEHOLDERS = ['{uzytkownik}', '{nick}', '{moderator}', '{moderatorNick}', '{powod}', '{czas}', '{serwer}', '{sprawa}', '{typ}'];
const EMOJI_PICKS = ['🫓', '🍞', '👀', '✅', '🔥', '💀', '🫡', '😂'];
const VIEWS = ['pulpit', 'ustawienia', 'embedy', 'ostrzezenia', 'sprawy', 'bany'];
const SAMPLE = { targetId: '111', target: 'hurownik_og', modId: '222', mod: 'dfgbh65', reason: 'Wielokrotne łamanie zasad' };

const state = {
  config: null,
  draft: null,
  defaults: null,
  guild: { channels: [], roles: [] },
  status: null,
  view: 'pulpit',
  embedAction: 'ban',
  lastField: null,
  casesPage: 1,
  warnUsers: [],
  openWarnUsers: new Set(),
  reloadPending: false,
};

// ---------- Narzędzia ----------
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const clone = (value) => JSON.parse(JSON.stringify(value));
const esc = (value) =>
  String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const pad = (n) => String(n).padStart(2, '0');

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

function formatUptime(ms) {
  const d = Math.floor(ms / DAY);
  const h = Math.floor((ms % DAY) / HOUR);
  const m = Math.floor((ms % HOUR) / MINUTE);
  if (d) return `${d} ${plural(d, UNIT_FORMS.d)} ${h} godz.`;
  if (h) return `${h} godz. ${m} min`;
  return `${m} min`;
}

async function api(path, options = {}) {
  const res = await fetch(`/api${path}`, {
    ...options,
    headers: { 'Content-Type': 'application/json', 'X-Panel': '1', ...(options.headers ?? {}) },
  });
  if (res.status === 401) {
    showLogin();
    throw new Error('Sesja wygasła — zaloguj się ponownie');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `Błąd ${res.status}`);
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

// ---------- Logowanie ----------
function showLogin() {
  $('#login').classList.remove('hidden');
  $('#app').classList.add('hidden');
  $('#savebar').classList.add('hidden');
  $('#login-password').focus();
}

$('#login-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  $('#login-error').textContent = '';
  try {
    await api('/login', { method: 'POST', body: JSON.stringify({ password: $('#login-password').value }) });
    $('#login-password').value = '';
    await start();
  } catch (error) {
    $('#login-error').textContent = error.message;
  }
});

$('#logout').addEventListener('click', async () => {
  await api('/logout', { method: 'POST' }).catch(() => {});
  location.reload();
});

// ---------- Start ----------
let started = false;
async function start() {
  $('#login').classList.add('hidden');
  $('#app').classList.remove('hidden');
  const [{ config, defaults }, guild] = await Promise.all([api('/config'), api('/guild')]);
  state.config = config;
  state.draft = clone(config);
  state.defaults = defaults;
  state.guild = guild;
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
  const auth = await fetch('/api/auth').then((r) => r.json());
  $('#logout').classList.toggle('hidden', !auth.required);
  if (auth.required && !auth.loggedIn) return showLogin();
  return start();
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

  $('#bot-state').textContent = status.ready ? 'online' : 'łączenie…';
  $('#bot-state').className = `state ${status.ready ? 'online' : 'offline'}`;
  if (status.bot) {
    $('#bot-name').textContent = status.bot.tag;
    $('#bot-avatar').src = status.bot.avatar;
    $('#bot-avatar').hidden = false;
    $('#bot-logo').hidden = true;
  }
  if (status.guild) {
    $('#guild-name').textContent = status.guild.name;
    $('#guild-members').textContent = `${status.guild.memberCount} członków • ID ${status.guild.id}`;
    if (status.guild.icon) {
      $('#guild-icon').src = status.guild.icon;
      $('#guild-icon').hidden = false;
    }
  } else {
    $('#guild-name').textContent = status.ready ? 'Bot nie jest na żadnym serwerze' : '—';
  }
  $('#status-text').textContent = status.ready ? '🟢 Online' : '🔴 Łączenie z Discordem…';
  $('#status-extra').textContent = status.ready
    ? `Ping ${status.ping} ms • działa od ${formatUptime(status.uptime)}`
    : 'Sprawdź token w pliku .env i konsolę bota.';

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
    state.guild = await api('/guild');
    renderChannelSelects();
    renderRoles();
  }
  if (state.view === 'embedy') renderPreview();
}

function caseBadge(entry) {
  const color = state.config.actions[entry.type]?.color ?? '#99aab5';
  const label = entry.type === 'ban' && entry.duration ? 'Tempban' : CASE_LABELS[entry.type] ?? entry.type;
  return `<span class="badge" style="background:${esc(color)}">${esc(label)}</span>`;
}

function casesTable(items) {
  if (!items.length) return '<tr><td class="empty">Brak spraw. Spokojnie jak na Hopkostkach. 🫓</td></tr>';
  const rows = items
    .map((c) => {
      const duration = c.duration ? formatDuration(c.duration.amount, c.duration.unit) : c.type === 'ban' ? 'perm.' : '—';
      const expiry = c.expiresAt ? `<span class="note">do ${esc(shortDate(c.expiresAt))}</span>` : '';
      return `<tr>
        <td><strong>#${c.id}</strong></td>
        <td>${caseBadge(c)}</td>
        <td>${esc(c.userTag)}<span class="note">${esc(c.userId)}</span></td>
        <td>${esc(c.moderatorTag)}${c.auto ? ' 🤖' : ''}</td>
        <td class="reason">${esc(c.reason)}${c.note ? `<span class="note">📝 ${esc(c.note)}</span>` : ''}</td>
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
  renderEmbedTabs();
  fillInputs();
  $('#emoji-picks').innerHTML = EMOJI_PICKS.map((e) => `<button type="button" class="chip emoji" data-emoji="${e}">${e}</button>`).join('');
  $('#placeholder-chips').innerHTML = PLACEHOLDERS.map((p) => `<button type="button" class="chip" data-placeholder="${p}">${p}</button>`).join('');
  markDirty();
}

function fillInputs() {
  $$('[data-path]').forEach((el) => {
    const value = getPath(state.draft, el.dataset.path);
    if (el.type === 'checkbox') el.checked = Boolean(value);
    else el.value = value ?? '';
  });
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

$('#emoji-picks').addEventListener('click', (event) => {
  const emoji = event.target.closest('[data-emoji]')?.dataset.emoji;
  if (!emoji) return;
  state.draft.replyReaction.emoji = emoji;
  fillInputs();
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
        <td><button type="button" class="btn ghost small" data-rule-del title="Usuń próg">✕</button></td>
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
    toast('Zapisano ustawienia ✅');
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
      return `<button type="button" class="tab ${action === state.embedAction ? 'active' : ''}" data-action="${action}" style="--tab-color:${esc(style.color)}">${esc(style.emoji)} ${ACTION_LABELS[action]}</button>`;
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

// Odzwierciedla src/lib/embeds.js — przykładowe dane do podglądu.
function previewData(action) {
  const cfg = state.draft;
  const style = cfg.actions[action];
  const now = Date.now();
  const server = state.status?.guild?.name ?? 'Entuzjaści Hopkostki';
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

  const timeLines = [];
  if (durationText) timeLines.push(`**Czas:** ${durationText}`);
  if (ctx.expiresAt) timeLines.push(`**Wygasa:** ${ts(ctx.expiresAt, 'f')} (${ts(ctx.expiresAt, 'R')})`);
  const warnLines = (points, totals) => {
    if (!ctx.warn) return [];
    const lines = [];
    if (points) lines.push(`**Punkty:** +${ctx.warn.points}`);
    if (totals) lines.push(`**Aktywne ostrzeżenia:** ${ctx.warn.count} (łącznie **${ctx.warn.total} pkt**)`);
    if (ctx.warn.expiresAt) lines.push(`**Ostrzeżenie wygasa:** ${ts(ctx.warn.expiresAt, 'f')} (${ts(ctx.warn.expiresAt, 'R')})`);
    return lines;
  };
  const showPoints = cfg.warns.showPointsToUser;

  const channel = {
    content: cfg.mentionTarget ? `<@${SAMPLE.targetId}>` : '',
    title: withEmoji(style, fill(style.title)),
    description: [
      withEmoji(style, fill(style.description)),
      '',
      `**Użytkownik:** <@${SAMPLE.targetId}> (\`${SAMPLE.target}\`)`,
      `**Moderator:** <@${SAMPLE.modId}>`,
      `**Powód:** ${SAMPLE.reason}`,
      ...timeLines,
      ...warnLines(showPoints, false),
      `**Data:** ${ts(now, 'f')}`,
    ].join('\n'),
    footer: `Sprawa #42 • ${server}`,
    thumb: true,
  };

  const dmLines = [withEmoji(style, fill(style.dmDescription)), '', `**Powód:** ${SAMPLE.reason}`, ...timeLines, ...warnLines(showPoints, showPoints)];
  if (cfg.dmShowModerator) dmLines.push(`**Moderator:** ${SAMPLE.mod}`);
  dmLines.push(`**Data:** ${ts(now, 'f')}`);
  if (cfg.appealText && ['ban', 'kick', 'timeout', 'warn'].includes(action)) dmLines.push('', `-# ${cfg.appealText}`);
  const dm = { author: server, title: withEmoji(style, fill(style.dmTitle)), description: dmLines.join('\n'), footer: 'Sprawa #42' };

  return { color: style.color, channel, dm };
}

function messageHtml({ color, embed, content, slash }) {
  const time = new Date().toLocaleTimeString('pl-PL', { hour: '2-digit', minute: '2-digit' });
  const avatar = state.status?.bot?.avatar ? `<img src="${esc(state.status.bot.avatar)}" alt="">` : '🫓';
  const name = state.status?.bot?.tag?.split('#')[0] ?? 'Entuzjaści Hopkostki';
  return `<div class="msg">
    <div class="msg-avatar">${avatar}</div>
    <div>
      ${slash ? `<div class="msg-reply">↱ <b>${SAMPLE.mod}</b> użył <span class="mention">${slash}</span></div>` : ''}
      <div class="msg-head"><span class="msg-name">${esc(name)}</span><span class="msg-app">APP</span><span class="msg-time">Dzisiaj o ${time}</span></div>
      ${content ? `<div>${md(content)}</div>` : ''}
      <div class="embed" style="border-left-color:${esc(color)}">
        <div>
          ${embed.author ? `<div class="embed-author">${esc(embed.author)}</div>` : ''}
          <div class="embed-title">${md(embed.title)}</div>
          <div class="embed-desc">${md(embed.description)}</div>
        </div>
        ${embed.thumb ? '<div class="embed-thumb">🧑</div>' : '<div></div>'}
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
  if (!w.expiresAt) return '<span class="muted small">♾️ nie wygasa</span>';
  return `<div class="countdown" data-expires="${w.expiresAt}">${formatCountdown(w.expiresAt - Date.now())}</div>
    <div class="lifebar" title="Wygasa ${esc(fullDate(w.expiresAt))}"><i data-created="${w.createdAt}" data-until="${w.expiresAt}"></i></div>`;
}

function renderWarns() {
  const query = $('#warn-search').value.trim().toLowerCase();
  const users = state.warnUsers.filter((u) => !query || u.userTag?.toLowerCase().includes(query) || u.userId.includes(query));
  const scale = state.config.escalation.rules.at(-1)?.points ?? 10;

  if (!users.length) {
    $('#warn-users').innerHTML = `<p class="empty">${query ? 'Nic nie znaleziono.' : 'Nikt nie ma aktywnych ostrzeżeń. 🎉'}</p>`;
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
    toast('Ban zdjęty ✅');
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

init().catch((error) => toast(error.message, 'error'));
