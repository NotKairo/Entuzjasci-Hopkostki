// Definicje komend slash (JSON API Discorda) i ich obsługa.
// Każda komenda: { data, permission, defer: 'action' | 'ephemeral', execute(ix, bot), autocomplete? }

import { UNIT_CHOICES, MAX_TIMEOUT_MS, toMs, formatDuration, discordTimestamp } from './duration.js';
import { P } from './permissions.js';
import * as embeds from './embeds.js';
import { avatarUrl } from './rest.js';
import {
  ActionError,
  checkTarget,
  getGuildContext,
  banUser,
  unbanUser,
  kickUser,
  timeoutUser,
  untimeoutUser,
  warnUser,
  sendModLog,
} from './moderation.js';

// ---------- Budowanie opcji ----------
const T = { SUB: 1, STRING: 3, INTEGER: 4, USER: 6, CHANNEL: 7 };
const CH = { TEXT: 0, ANNOUNCEMENT: 5, FORUM: 15 };
const GUILD_ONLY = { contexts: [0] };

const user = (name, description, required = false) => ({ type: T.USER, name, description, required });
const str = (name, description, extra = {}) => ({ type: T.STRING, name, description, ...extra });
const int = (name, description, extra = {}) => ({ type: T.INTEGER, name, description, ...extra });
const sub = (name, description, options = []) => ({ type: T.SUB, name, description, options });
const reasonOpt = (description, required = true) => str('powod', description, { required, max_length: 500 });
const perm = (bits) => String(bits);

export class ReplyError extends ActionError {}

// ---------- Pomocnicze ----------
function targetFrom(ix, name = 'uzytkownik') {
  const target = ix.getUser(name);
  if (!target) throw new ReplyError('Nie znaleziono użytkownika.');
  return { target, targetMember: ix.getMember(name) };
}

async function ensureTarget(ix, bot, action, name) {
  const { target, targetMember } = targetFrom(ix, name);
  const gctx = await getGuildContext(bot);
  const problem = checkTarget({ gctx, moderator: ix.member, target, targetMember, action });
  if (problem) throw new ReplyError(problem);
  return { target, targetMember };
}

const snowflakeTime = (id) => Number((BigInt(id) >> 22n) + 1420070400000n);

// ---------- Kary ----------
const ban = {
  permission: P.BAN_MEMBERS,
  defer: 'action',
  data: {
    name: 'ban',
    description: 'Banuje użytkownika na określony czas lub na zawsze',
    default_member_permissions: perm(P.BAN_MEMBERS),
    ...GUILD_ONLY,
    options: [
      user('uzytkownik', 'Kogo zbanować', true),
      reasonOpt('Powód bana'),
      int('czas', 'Na ile (puste = ban permanentny)', { min_value: 1, max_value: 1000 }),
      str('jednostka', 'Jednostka czasu (domyślnie dni)', { choices: UNIT_CHOICES }),
      int('usun_wiadomosci', 'Usunąć ostatnie wiadomości użytkownika?', {
        choices: [
          { name: 'Nie usuwaj', value: 0 },
          { name: 'Z ostatniej godziny', value: 3600 },
          { name: 'Z ostatnich 24 godzin', value: 86400 },
          { name: 'Z ostatnich 7 dni', value: 604800 },
        ],
      }),
    ],
  },
  async execute(ix, bot) {
    const { target } = await ensureTarget(ix, bot, 'ban');
    const amount = ix.opt('czas');
    await banUser(bot, {
      interaction: ix,
      target,
      moderator: ix.user,
      reason: ix.opt('powod'),
      duration: amount ? { amount, unit: ix.opt('jednostka') ?? 'd' } : null,
      deleteMessageSeconds: ix.opt('usun_wiadomosci') ?? 0,
    });
  },
};

const unban = {
  permission: P.BAN_MEMBERS,
  defer: 'action',
  data: {
    name: 'unban',
    description: 'Zdejmuje bana z użytkownika',
    default_member_permissions: perm(P.BAN_MEMBERS),
    ...GUILD_ONLY,
    options: [
      str('uzytkownik', 'Zbanowany użytkownik (nick lub ID)', { required: true, autocomplete: true }),
      reasonOpt('Powód odbanowania', false),
    ],
  },
  async autocomplete(ix, bot) {
    const query = String(ix.focused() ?? '').toLowerCase();
    const hit = bot.cache.get('bans');
    let bans = hit && Date.now() - hit.at < 15_000 ? hit.value : null;
    if (!bans) {
      bans = await bot.discord.get(`/guilds/${ix.guildId}/bans`, { query: { limit: 1000 } }).catch(() => []);
      bot.cache.set('bans', { at: Date.now(), value: bans });
    }
    return bans
      .filter((b) => b.user.username.toLowerCase().includes(query) || b.user.id.includes(query))
      .slice(0, 25)
      .map((b) => ({ name: `${b.user.username} (${b.user.id})`.slice(0, 100), value: b.user.id }));
  },
  async execute(ix, bot) {
    const raw = String(ix.opt('uzytkownik')).replace(/[<@!>]/g, '').trim();
    if (!/^\d{15,25}$/.test(raw)) throw new ReplyError('Wybierz użytkownika z listy albo podaj jego ID.');
    const target = await bot.discord.get(`/users/${raw}`).catch(() => null);
    if (!target) throw new ReplyError('Nie znaleziono użytkownika o takim ID.');
    bot.cache.delete('bans');
    await unbanUser(bot, { interaction: ix, target, moderator: ix.user, reason: ix.opt('powod') ?? 'Nie podano powodu' });
  },
};

const kick = {
  permission: P.KICK_MEMBERS,
  defer: 'action',
  data: {
    name: 'kick',
    description: 'Wyrzuca użytkownika z serwera',
    default_member_permissions: perm(P.KICK_MEMBERS),
    ...GUILD_ONLY,
    options: [user('uzytkownik', 'Kogo wyrzucić', true), reasonOpt('Powód wyrzucenia')],
  },
  async execute(ix, bot) {
    const { target } = await ensureTarget(ix, bot, 'kick');
    await kickUser(bot, { interaction: ix, target, moderator: ix.user, reason: ix.opt('powod') });
  },
};

const timeout = {
  permission: P.MODERATE_MEMBERS,
  defer: 'action',
  data: {
    name: 'timeout',
    description: 'Wycisza użytkownika na określony czas (maks. 28 dni)',
    default_member_permissions: perm(P.MODERATE_MEMBERS),
    ...GUILD_ONLY,
    options: [
      user('uzytkownik', 'Kogo wyciszyć', true),
      int('czas', 'Na ile', { required: true, min_value: 1, max_value: 40320 }),
      str('jednostka', 'Jednostka czasu', { required: true, choices: UNIT_CHOICES }),
      reasonOpt('Powód wyciszenia'),
    ],
  },
  async execute(ix, bot) {
    const duration = { amount: ix.opt('czas'), unit: ix.opt('jednostka') };
    if (toMs(duration.amount, duration.unit) > MAX_TIMEOUT_MS) {
      throw new ReplyError('Discord pozwala na timeout maksymalnie **28 dni** (4 tygodnie). Na dłużej użyj `/ban`.');
    }
    const { target } = await ensureTarget(ix, bot, 'timeout');
    await timeoutUser(bot, { interaction: ix, target, moderator: ix.user, reason: ix.opt('powod'), duration });
  },
};

const untimeout = {
  permission: P.MODERATE_MEMBERS,
  defer: 'action',
  data: {
    name: 'untimeout',
    description: 'Zdejmuje timeout z użytkownika',
    default_member_permissions: perm(P.MODERATE_MEMBERS),
    ...GUILD_ONLY,
    options: [user('uzytkownik', 'Komu zdjąć timeout', true), reasonOpt('Powód', false)],
  },
  async execute(ix, bot) {
    const { target, targetMember } = await ensureTarget(ix, bot, 'untimeout');
    const until = targetMember?.communication_disabled_until;
    if (!until || new Date(until).getTime() < Date.now()) throw new ReplyError('Ten użytkownik nie ma timeoutu.');
    await untimeoutUser(bot, { interaction: ix, target, moderator: ix.user, reason: ix.opt('powod') ?? 'Nie podano powodu' });
  },
};

// ---------- Ostrzeżenia ----------
const ESCALATION_NAMES = { alert: 'powiadomienie moderacji', timeout: 'timeout', kick: 'kick', ban: 'ban' };
const CASE_NAMES = { ban: 'bany', kick: 'kicki', timeout: 'timeouty', warn: 'ostrzeżenia (łącznie)' };

function describeRule(rule) {
  const name = ESCALATION_NAMES[rule.action];
  if (rule.action === 'alert' || rule.action === 'kick') return name;
  return rule.amount > 0 ? `${name} na ${formatDuration(rule.amount, rule.unit)}` : `${name} permanentny`;
}

export async function warnStatusEmbed(bot, target) {
  const config = await bot.store.getConfig();
  const summary = await bot.store.warnSummary(target.id);
  const counts = await bot.store.caseCounts(target.id);
  const { escalation, warns: warnConfig, actions } = config;
  const scale = escalation.rules.at(-1)?.points ?? 10;
  const nextRule = escalation.enabled ? escalation.rules.find((r) => r.points > summary.points) : null;

  const lines = [
    `**Użytkownik:** <@${target.id}> (\`${target.id}\`)`,
    `**Aktywne ostrzeżenia:** ${summary.count}`,
    `**Punkty:** **${summary.points} pkt**`,
    `**Poziom:** ${embeds.severityBar(summary.points, scale)} \`${summary.points}/${scale}\``,
  ];
  if (nextRule) lines.push(`**Następny próg:** ${nextRule.points} pkt → ${describeRule(nextRule)}`);
  if (summary.nextExpiry) lines.push(`**Najbliższe wygaśnięcie:** ${discordTimestamp(summary.nextExpiry, 'R')}`);
  const history = Object.entries(CASE_NAMES)
    .filter(([type]) => counts[type])
    .map(([type, name]) => `${name}: **${counts[type]}**`);
  if (history.length) lines.push(`**Historia kar:** ${history.join(' · ')}`);
  if (warnConfig.expiryDays > 0) lines.push(`-# Ostrzeżenia wygasają automatycznie po ${warnConfig.expiryDays} dniach.`);

  const fields = summary.warns.slice(0, 10).map((warn) => ({
    name: `#${warn.id} • ${warn.points} pkt • ${discordTimestamp(warn.createdAt, 'd')}`,
    value: `${warn.reason.slice(0, 200)}\n-# Moderator: <@${warn.moderatorId}> • ${
      warn.expiresAt ? `⏳ wygasa ${discordTimestamp(warn.expiresAt, 'R')}` : '♾️ nie wygasa'
    }`,
  }));
  if (summary.warns.length > 10) fields.push({ name: '…', value: `Oraz ${summary.warns.length - 10} starszych (pełna lista w panelu).` });
  if (!summary.warns.length) fields.push({ name: 'Brak aktywnych ostrzeżeń', value: 'Ten użytkownik jest czysty. ✨' });

  return {
    color: embeds.colorInt(actions.warn.color),
    title: `⚠️ Ostrzeżenia — ${target.username}`,
    thumbnail: { url: avatarUrl(target) },
    description: lines.join('\n'),
    fields,
    footer: { text: 'Widoczne tylko dla moderacji' },
  };
}

const warn = {
  permission: P.MODERATE_MEMBERS,
  // "dodaj" ogłasza karę publicznie, reszta podkomend odpowiada tylko moderatorowi.
  defer: (ix) => (ix.sub === 'dodaj' ? 'action' : 'ephemeral'),
  data: {
    name: 'warn',
    description: 'System ostrzeżeń',
    default_member_permissions: perm(P.MODERATE_MEMBERS),
    ...GUILD_ONLY,
    options: [
      sub('dodaj', 'Daje użytkownikowi ostrzeżenie', [
        user('uzytkownik', 'Kogo ostrzec', true),
        reasonOpt('Powód ostrzeżenia'),
        int('punkty', 'Ile punktów (domyślnie z konfiguracji)', { min_value: 1, max_value: 100 }),
      ]),
      sub('status', 'Pokazuje ostrzeżenia i punkty użytkownika (tylko dla moderacji)', [user('uzytkownik', 'Czyje ostrzeżenia', true)]),
      sub('usun', 'Usuwa jedno ostrzeżenie po numerze', [
        int('numer', 'Numer ostrzeżenia (#)', { required: true, min_value: 1 }),
        reasonOpt('Dlaczego usuwasz', false),
      ]),
      sub('wyczysc', 'Usuwa wszystkie ostrzeżenia użytkownika', [user('uzytkownik', 'Czyje ostrzeżenia wyczyścić', true)]),
      sub('ranking', 'Użytkownicy z największą liczbą punktów'),
    ],
  },
  async execute(ix, bot) {
    const { store } = bot;
    const config = await store.getConfig();

    if (ix.sub === 'dodaj') {
      const { target } = await ensureTarget(ix, bot, 'warn');
      if (target.bot) throw new ReplyError('Botów nie można ostrzegać.');
      return warnUser(bot, {
        interaction: ix,
        target,
        moderator: ix.user,
        reason: ix.opt('powod'),
        points: ix.opt('punkty') ?? config.warns.defaultPoints,
      });
    }

    if (ix.sub === 'status') {
      const target = ix.getUser('uzytkownik');
      return ix.edit({ embeds: [await warnStatusEmbed(bot, target)] });
    }

    if (ix.sub === 'usun') {
      const id = ix.opt('numer');
      const removed = await store.removeWarn(id);
      if (!removed) throw new ReplyError(`Nie ma aktywnego ostrzeżenia **#${id}**.`);
      const reason = ix.opt('powod') ?? 'Nie podano powodu';
      if (removed.caseId) await store.updateCase(removed.caseId, { note: `Ostrzeżenie usunięte przez ${ix.user.username}: ${reason}` });
      const left = await store.warnSummary(removed.userId);
      await sendModLog(bot, config, {
        embeds: [
          embeds.simpleEmbed(
            'success',
            `🗑️ Usunięto ostrzeżenie #${removed.id}`,
            [
              `**Użytkownik:** <@${removed.userId}>`,
              `**Usunął:** <@${ix.user.id}>`,
              `**Powód usunięcia:** ${reason}`,
              `**Treść ostrzeżenia:** ${removed.reason} (${removed.points} pkt)`,
              `**Pozostało:** ${left.count} ostrzeżeń (${left.points} pkt)`,
            ].join('\n'),
          ),
        ],
      });
      return ix.edit({
        embeds: [
          embeds.successEmbed(`Usunięto ostrzeżenie **#${removed.id}** użytkownika <@${removed.userId}> (-${removed.points} pkt).\nPozostało: **${left.points} pkt**.`),
        ],
      });
    }

    if (ix.sub === 'wyczysc') {
      const target = ix.getUser('uzytkownik');
      const removed = await store.clearWarns(target.id);
      if (!removed.length) throw new ReplyError(`<@${target.id}> nie ma żadnych ostrzeżeń.`);
      const points = removed.reduce((sum, w) => sum + w.points, 0);
      for (const w of removed) if (w.caseId) await store.updateCase(w.caseId, { note: `Wyczyszczone przez ${ix.user.username}` });
      await sendModLog(bot, config, {
        embeds: [
          embeds.simpleEmbed(
            'success',
            '🧹 Wyczyszczono ostrzeżenia',
            `**Użytkownik:** <@${target.id}>\n**Usunął:** <@${ix.user.id}>\n**Usunięto:** ${removed.length} ostrzeżeń (${points} pkt)`,
          ),
        ],
      });
      return ix.edit({ embeds: [embeds.successEmbed(`Usunięto **${removed.length}** ostrzeżeń (${points} pkt) użytkownika <@${target.id}>.`)] });
    }

    // ranking
    const rows = (await store.warnRanking()).slice(0, 15);
    const description = rows.length
      ? rows
          .map((row, i) => {
            const next = row.warns.map((w) => w.expiresAt).filter(Boolean).sort((a, b) => a - b)[0];
            const expiry = next ? ` • najbliższe wygasa ${discordTimestamp(next, 'R')}` : '';
            return `**${i + 1}.** <@${row.userId}> — **${row.points} pkt** (${row.count} ostrz.)${expiry}`;
          })
          .join('\n')
      : 'Nikt nie ma aktywnych ostrzeżeń. 🎉';
    return ix.edit({ embeds: [{ ...embeds.simpleEmbed('warning', '📊 Ranking ostrzeżeń', description), footer: { text: 'Widoczne tylko dla moderacji' } }] });
  },
};

// ---------- Historia i sprawy ----------
function caseLabel(entry) {
  return entry.type === 'ban' && entry.duration ? 'Tymczasowy ban' : embeds.ACTION_LABELS[entry.type];
}

const historia = {
  permission: P.MODERATE_MEMBERS,
  defer: 'ephemeral',
  data: {
    name: 'historia',
    description: 'Pełna historia kar użytkownika (tylko dla moderacji)',
    default_member_permissions: perm(P.MODERATE_MEMBERS),
    ...GUILD_ONLY,
    options: [user('uzytkownik', 'Czyja historia', true)],
  },
  async execute(ix, bot) {
    const target = ix.getUser('uzytkownik');
    const { total, items } = await bot.store.listCases({ userId: target.id, limit: 12 });
    let description =
      items
        .map((c) => {
          const duration = c.duration ? ` (${formatDuration(c.duration.amount, c.duration.unit)})` : '';
          const note = c.note ? `\n-# ${c.note}` : '';
          return `**#${c.id} ${caseLabel(c)}${duration}**${c.auto ? ' 🤖' : ''} • ${discordTimestamp(c.createdAt, 'd')} • <@${c.moderatorId}>\n> ${c.reason.slice(0, 150)}${note}`;
        })
        .join('\n\n') || 'Brak kar. Wzorowy użytkownik! ✨';
    if (description.length > 4000) description = `${description.slice(0, 3990)}…`;
    return ix.edit({
      embeds: [
        {
          color: embeds.COLORS.info,
          title: `📜 Historia — ${target.username}`,
          description,
          footer: { text: `Łącznie spraw: ${total}${total > items.length ? ` • pokazano ${items.length} najnowszych` : ''}` },
        },
      ],
    });
  },
};

async function caseEmbed(bot, entry) {
  const config = await bot.store.getConfig();
  const lines = [
    `**Typ:** ${caseLabel(entry)}${entry.auto ? ' (automatycznie)' : ''}`,
    `**Użytkownik:** <@${entry.userId}> \`${entry.userTag}\` (\`${entry.userId}\`)`,
    `**Moderator:** <@${entry.moderatorId}>`,
    `**Powód:** ${entry.reason}`,
  ];
  if (entry.duration) lines.push(`**Czas:** ${formatDuration(entry.duration.amount, entry.duration.unit)}`);
  if (entry.expiresAt) lines.push(`**Wygasa:** ${discordTimestamp(entry.expiresAt, 'f')} (${discordTimestamp(entry.expiresAt, 'R')})`);
  lines.push(`**Data:** ${discordTimestamp(entry.createdAt, 'f')}`);
  if (entry.dmStatus) lines.push(`**DM:** ${entry.dmStatus}`);
  if (entry.note) lines.push(`**Notatka:** ${entry.note}`);
  if (entry.messageUrl) lines.push(`**Wiadomość:** [przejdź](${entry.messageUrl})`);
  return { color: embeds.colorInt(config.actions[entry.type]?.color), title: `🗂️ Sprawa #${entry.id}`, description: lines.join('\n') };
}

const sprawa = {
  permission: P.MODERATE_MEMBERS,
  defer: 'ephemeral',
  data: {
    name: 'sprawa',
    description: 'Podgląd i edycja spraw moderacyjnych',
    default_member_permissions: perm(P.MODERATE_MEMBERS),
    ...GUILD_ONLY,
    options: [
      sub('pokaz', 'Pokazuje szczegóły sprawy', [int('numer', 'Numer sprawy', { required: true, min_value: 1 })]),
      sub('powod', 'Zmienia powód w sprawie', [
        int('numer', 'Numer sprawy', { required: true, min_value: 1 }),
        str('nowy_powod', 'Nowy powód', { required: true, max_length: 500 }),
      ]),
    ],
  },
  async execute(ix, bot) {
    const id = ix.opt('numer');
    const entry = await bot.store.getCase(id);
    if (!entry) throw new ReplyError(`Nie ma sprawy **#${id}**.`);
    if (ix.sub === 'pokaz') return ix.edit({ embeds: [await caseEmbed(bot, entry)] });

    const newReason = ix.opt('nowy_powod');
    const updated = await bot.store.updateCase(id, { reason: newReason });
    if (entry.type === 'warn') await bot.store.updateWarnByCase(id, { reason: newReason });
    await sendModLog(bot, await bot.store.getConfig(), {
      embeds: [
        {
          ...embeds.successEmbed(`**Sprawa #${id}** — zmieniono powód\n**Było:** ${entry.reason}\n**Jest:** ${newReason}\n**Zmienił:** <@${ix.user.id}>`),
          title: '✏️ Edycja sprawy',
        },
      ],
    });
    return ix.edit({ embeds: [await caseEmbed(bot, updated)] });
  },
};

// ---------- Narzędzia kanałów ----------
const clear = {
  permission: P.MANAGE_MESSAGES,
  defer: 'ephemeral',
  data: {
    name: 'clear',
    description: 'Usuwa ostatnie wiadomości na kanale',
    default_member_permissions: perm(P.MANAGE_MESSAGES),
    ...GUILD_ONLY,
    options: [
      int('ilosc', 'Ile wiadomości sprawdzić (1–100)', { required: true, min_value: 1, max_value: 100 }),
      user('uzytkownik', 'Usuń tylko wiadomości tej osoby'),
    ],
  },
  async execute(ix, bot) {
    const only = ix.getUser('uzytkownik');
    const fetched = await bot.discord.get(`/channels/${ix.channelId}/messages`, { query: { limit: ix.opt('ilosc') } });
    const cutoff = Date.now() - 14 * 86_400_000 + 60_000;
    const ids = fetched.filter((m) => snowflakeTime(m.id) > cutoff && (!only || m.author.id === only.id)).map((m) => m.id);
    const reason = `Clear: ${ix.user.username}`;
    if (ids.length === 1) await bot.discord.delete(`/channels/${ix.channelId}/messages/${ids[0]}`, { reason });
    if (ids.length > 1) await bot.discord.post(`/channels/${ix.channelId}/messages/bulk-delete`, { messages: ids }, { reason });

    await sendModLog(bot, await bot.store.getConfig(), {
      embeds: [
        embeds.simpleEmbed(
          'info',
          '🧹 Wyczyszczono wiadomości',
          `**Kanał:** <#${ix.channelId}>\n**Moderator:** <@${ix.user.id}>\n**Usunięto:** ${ids.length}${only ? `\n**Tylko od:** <@${only.id}>` : ''}`,
        ),
      ],
    });
    const skipped = only ? 0 : fetched.length - ids.length;
    const note = skipped ? `\n-# Pominięto ${skipped} wiadomości starszych niż 14 dni (ograniczenie Discorda).` : '';
    return ix.edit({ embeds: [embeds.successEmbed(`Usunięto **${ids.length}** wiadomości.${note}`)] });
  },
};

function describeSeconds(seconds) {
  if (seconds === 0) return 'wyłączony';
  if (seconds < 60) return `${seconds} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min${seconds % 60 ? ` ${seconds % 60} s` : ''}`;
  return `${Math.floor(seconds / 3600)} h${seconds % 3600 ? ` ${Math.floor((seconds % 3600) / 60)} min` : ''}`;
}

const slowmode = {
  permission: P.MANAGE_CHANNELS,
  defer: 'ephemeral',
  data: {
    name: 'slowmode',
    description: 'Ustawia tryb powolny na kanale',
    default_member_permissions: perm(P.MANAGE_CHANNELS),
    ...GUILD_ONLY,
    options: [
      int('sekundy', 'Odstęp między wiadomościami (0 = wyłącz, maks. 21600)', { required: true, min_value: 0, max_value: 21600 }),
      { type: T.CHANNEL, name: 'kanal', description: 'Kanał (domyślnie obecny)', channel_types: [CH.TEXT, CH.FORUM] },
    ],
  },
  async execute(ix, bot) {
    const seconds = ix.opt('sekundy');
    const channelId = ix.opt('kanal') ?? ix.channelId;
    await bot.discord.patch(`/channels/${channelId}`, { rate_limit_per_user: seconds }, { reason: `Slowmode: ${ix.user.username}` });
    await sendModLog(bot, await bot.store.getConfig(), {
      embeds: [embeds.simpleEmbed('info', '🐢 Tryb powolny', `**Kanał:** <#${channelId}>\n**Ustawienie:** ${describeSeconds(seconds)}\n**Moderator:** <@${ix.user.id}>`)],
    });
    return ix.edit({ embeds: [embeds.successEmbed(`Tryb powolny na <#${channelId}>: **${describeSeconds(seconds)}**.`)] });
  },
};

function lockCommand(name, description, locked) {
  return {
    permission: P.MANAGE_CHANNELS,
    defer: 'ephemeral',
    data: {
      name,
      description,
      default_member_permissions: perm(P.MANAGE_CHANNELS),
      ...GUILD_ONLY,
      options: [
        { type: T.CHANNEL, name: 'kanal', description: 'Kanał (domyślnie obecny)', channel_types: [CH.TEXT, CH.ANNOUNCEMENT] },
        reasonOpt('Powód', false),
      ],
    },
    async execute(ix, bot) {
      const channelId = ix.opt('kanal') ?? ix.channelId;
      const reason = ix.opt('powod') ?? 'Nie podano powodu';
      const channel = await bot.discord.get(`/channels/${channelId}`);
      const current = channel.permission_overwrites?.find((o) => o.id === ix.guildId) ?? { allow: '0', deny: '0' };
      const bits = P.SEND_MESSAGES | P.SEND_MESSAGES_IN_THREADS;
      let allow = BigInt(current.allow);
      let deny = BigInt(current.deny);
      if (locked) {
        deny |= bits;
        allow &= ~bits;
      } else {
        deny &= ~bits;
      }
      await bot.discord.put(
        `/channels/${channelId}/permissions/${ix.guildId}`,
        { type: 0, allow: String(allow), deny: String(deny) },
        { reason: `${ix.user.username}: ${reason}` },
      );

      const title = locked ? '🔒 Kanał zablokowany' : '🔓 Kanał odblokowany';
      const body = locked ? `Pisanie na tym kanale zostało tymczasowo wyłączone.\n**Powód:** ${reason}` : `Można znowu pisać na tym kanale.\n**Powód:** ${reason}`;
      await bot.discord.post(`/channels/${channelId}/messages`, { embeds: [embeds.simpleEmbed(locked ? 'error' : 'success', title, body)] }).catch(() => {});
      await sendModLog(bot, await bot.store.getConfig(), {
        embeds: [embeds.simpleEmbed('info', title, `**Kanał:** <#${channelId}>\n**Moderator:** <@${ix.user.id}>\n**Powód:** ${reason}`)],
      });
      return ix.edit({ embeds: [embeds.successEmbed(`<#${channelId}> ${locked ? 'zablokowany' : 'odblokowany'}.`)] });
    },
  };
}

// ---------- Pomoc ----------
const pomoc = {
  permission: null,
  defer: 'ephemeral',
  data: { name: 'pomoc', description: 'Lista komend bota moderacyjnego', ...GUILD_ONLY },
  async execute(ix, bot) {
    const { warns } = await bot.store.getConfig();
    return ix.edit({
      embeds: [
        {
          color: embeds.COLORS.info,
          title: '🫓 Bot moderacyjny — Entuzjaści Hopkostki',
          description: 'Jednostki czasu: **minuty, godziny, dni, tygodnie, miesiące** — wybierasz je z listy przy komendzie.',
          fields: [
            {
              name: '🔨 Kary',
              value: [
                '`/ban` — ban na czas lub permanentny (+ opcjonalne usuwanie wiadomości)',
                '`/unban` — zdjęcie bana (lista zbanowanych w podpowiedziach)',
                '`/timeout` — wyciszenie na czas (maks. 28 dni)',
                '`/untimeout` — zdjęcie wyciszenia',
                '`/kick` — wyrzucenie z serwera',
              ].join('\n'),
            },
            {
              name: '⚠️ Ostrzeżenia (punkty widzi tylko moderacja)',
              value: [
                '`/warn dodaj` — ostrzeżenie z punktami',
                '`/warn status` — ile ostrzeżeń i punktów ma osoba',
                '`/warn usun` · `/warn wyczysc` — usuwanie ostrzeżeń',
                '`/warn ranking` — kto najbardziej przegina',
                warns.expiryDays > 0 ? `-# Każde ostrzeżenie wygasa samo po **${warns.expiryDays} dniach**.` : '-# Ostrzeżenia nie wygasają automatycznie.',
              ].join('\n'),
            },
            {
              name: '🗂️ Sprawy i narzędzia',
              value: [
                '`/historia` — wszystkie kary użytkownika',
                '`/sprawa pokaz` · `/sprawa powod` — podgląd / edycja sprawy',
                '`/clear` — usuwanie wiadomości',
                '`/slowmode` — tryb powolny',
                '`/lock` · `/unlock` — blokada kanału',
              ].join('\n'),
            },
          ],
          footer: { text: 'Odpowiedz na wiadomość o karze, a bot doda reakcję 🫓' },
        },
      ],
    });
  },
};

export const COMMANDS = [
  ban,
  unban,
  kick,
  timeout,
  untimeout,
  warn,
  historia,
  sprawa,
  clear,
  slowmode,
  lockCommand('lock', 'Blokuje pisanie na kanale', true),
  lockCommand('unlock', 'Odblokowuje pisanie na kanale', false),
  pomoc,
];

export const COMMAND_MAP = new Map(COMMANDS.map((c) => [c.data.name, c]));
export const commandDefinitions = () => COMMANDS.map((c) => c.data);
