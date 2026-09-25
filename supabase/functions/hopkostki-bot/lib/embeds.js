// Wszystkie embedy bota (zwykłe obiekty JSON API Discorda). Wygląd akcji (kolor, emoji, tytuły, opisy)
// pochodzi z konfiguracji, dzięki czemu można go zmieniać w panelu bez ruszania kodu.

import { formatDuration, discordTimestamp } from './duration.js';
import { avatarUrl, guildIconUrl } from './rest.js';

export const ACTION_LABELS = {
  ban: 'Ban',
  unban: 'Unban',
  kick: 'Kick',
  timeout: 'Timeout',
  untimeout: 'Zdjęcie timeoutu',
  warn: 'Ostrzeżenie',
};

export const COLORS = {
  info: 0x5865f2,
  success: 0x57f287,
  error: 0xed4245,
  warning: 0xfee75c,
  muted: 0x2b2d31,
};

export function colorInt(hex, fallback = COLORS.info) {
  const n = Number.parseInt(String(hex).replace('#', ''), 16);
  return Number.isFinite(n) ? n : fallback;
}

export function escapeMarkdown(text) {
  return String(text ?? '').replace(/([\\*_~`|>])/g, '\\$1');
}

export function fillTemplate(text, vars) {
  return String(text ?? '').replace(/\{(\w+)\}/g, (match, key) => (key in vars ? vars[key] : match));
}

function durationText(ctx) {
  if (ctx.duration) return formatDuration(ctx.duration.amount, ctx.duration.unit);
  if (ctx.action === 'ban') return 'Permanentny';
  return null;
}

export function templateVars(ctx) {
  return {
    uzytkownik: `<@${ctx.target.id}>`,
    nick: escapeMarkdown(ctx.target.username),
    moderator: `<@${ctx.moderator.id}>`,
    moderatorNick: escapeMarkdown(ctx.moderator.username),
    powod: ctx.reason,
    czas: durationText(ctx) ?? '—',
    serwer: escapeMarkdown(ctx.guild.name),
    sprawa: `#${ctx.caseId}`,
    typ: ctx.action === 'ban' ? (ctx.duration ? 'tymczasowo' : 'permanentnie') : '',
  };
}

function withEmoji(style, text) {
  const cleaned = text.replace(/\s{2,}/g, ' ').trim();
  return style.emoji ? `${style.emoji} ${cleaned}` : cleaned;
}

function timeLines(ctx) {
  const lines = [];
  const duration = durationText(ctx);
  if (duration) lines.push(`**Czas:** ${duration}`);
  if (ctx.expiresAt) {
    lines.push(`**Wygasa:** ${discordTimestamp(ctx.expiresAt, 'f')} (${discordTimestamp(ctx.expiresAt, 'R')})`);
  }
  return lines;
}

function warnLines(ctx, { points, totals }) {
  if (ctx.action !== 'warn' || !ctx.warn) return [];
  const lines = [];
  if (points) lines.push(`**Punkty:** +${ctx.warn.points}`);
  if (totals) lines.push(`**Aktywne ostrzeżenia:** ${ctx.warn.count} (łącznie **${ctx.warn.totalPoints} pkt**)`);
  if (ctx.warn.expiresAt) {
    lines.push(`**Ostrzeżenie wygasa:** ${discordTimestamp(ctx.warn.expiresAt, 'f')} (${discordTimestamp(ctx.warn.expiresAt, 'R')})`);
  }
  return lines;
}

function footer(text, guild) {
  const icon = guildIconUrl(guild);
  return icon ? { text, icon_url: icon } : { text };
}

// Embed publikowany na kanale (odpowiedź na komendę lub kanał ogłoszeń).
export function buildChannelEmbed(ctx, config) {
  const style = config.actions[ctx.action];
  const vars = templateVars(ctx);
  const lines = [
    withEmoji(style, fillTemplate(style.description, vars)),
    '',
    `**Użytkownik:** <@${ctx.target.id}> (\`${ctx.target.username}\`)`,
    `**Moderator:** <@${ctx.moderator.id}>${ctx.auto ? ' (automatycznie)' : ''}`,
    `**Powód:** ${ctx.reason}`,
    ...timeLines(ctx),
    ...warnLines(ctx, { points: config.warns.showPointsToUser, totals: false }),
    `**Data:** ${discordTimestamp(ctx.createdAt, 'f')}`,
  ];
  return {
    color: colorInt(style.color),
    title: withEmoji(style, fillTemplate(style.title, vars)),
    description: lines.join('\n'),
    thumbnail: { url: avatarUrl(ctx.target) },
    footer: footer(`Sprawa #${ctx.caseId} • ${ctx.guild.name}`, ctx.guild),
    timestamp: new Date(ctx.createdAt).toISOString(),
  };
}

// Embed wysyłany prywatnie do ukaranego użytkownika.
export function buildDmEmbed(ctx, config) {
  const style = config.actions[ctx.action];
  const vars = templateVars(ctx);
  const showPoints = config.warns.showPointsToUser;
  const lines = [withEmoji(style, fillTemplate(style.dmDescription, vars)), '', `**Powód:** ${ctx.reason}`];
  lines.push(...timeLines(ctx));
  lines.push(...warnLines(ctx, { points: showPoints, totals: showPoints }));
  if (config.dmShowModerator) {
    lines.push(`**Moderator:** ${ctx.auto ? 'Automatyczna kara' : escapeMarkdown(ctx.moderator.username)}`);
  }
  lines.push(`**Data:** ${discordTimestamp(ctx.createdAt, 'f')}`);
  if (config.appealText && ['ban', 'kick', 'timeout', 'warn'].includes(ctx.action)) {
    lines.push('', `-# ${config.appealText}`);
  }
  const icon = guildIconUrl(ctx.guild);
  return {
    color: colorInt(style.color),
    author: icon ? { name: ctx.guild.name, icon_url: icon } : { name: ctx.guild.name },
    title: withEmoji(style, fillTemplate(style.dmTitle, vars)),
    description: lines.join('\n'),
    footer: { text: `Sprawa #${ctx.caseId}` },
    timestamp: new Date(ctx.createdAt).toISOString(),
  };
}

// Embed do kanału logów moderacji — pełne informacje, także te ukryte przed użytkownikami.
export function buildLogEmbed(ctx, config, { dmStatus, messageUrl } = {}) {
  const style = config.actions[ctx.action];
  const label = ctx.action === 'ban' && ctx.duration ? 'Tymczasowy ban' : ACTION_LABELS[ctx.action];
  const lines = [
    `**Użytkownik:** <@${ctx.target.id}> \`${ctx.target.username}\` (\`${ctx.target.id}\`)`,
    `**Moderator:** <@${ctx.moderator.id}>${ctx.auto ? ' (automatycznie)' : ''}`,
    `**Powód:** ${ctx.reason}`,
    ...timeLines(ctx),
    ...warnLines(ctx, { points: true, totals: true }),
    `**DM:** ${dmStatus ?? '➖ wyłączone'}`,
  ];
  if (messageUrl) lines.push(`**Wiadomość:** [przejdź](${messageUrl})`);
  return {
    color: colorInt(style.color),
    author: { name: `${label} | Sprawa #${ctx.caseId}`, icon_url: avatarUrl(ctx.target, 64) },
    description: lines.join('\n'),
    footer: { text: `ID użytkownika: ${ctx.target.id}` },
    timestamp: new Date(ctx.createdAt).toISOString(),
  };
}

export function simpleEmbed(type, title, description) {
  const embed = { color: COLORS[type] ?? COLORS.info, title };
  if (description) embed.description = description;
  return embed;
}

export const errorEmbed = (description) => simpleEmbed('error', '❌ Nie udało się', description);
export const successEmbed = (description) => simpleEmbed('success', '✅ Gotowe', description);

// Pasek "jak bardzo ktoś przegina" liczony względem najwyższego progu eskalacji.
export function severityBar(points, maxPoints) {
  const segments = 10;
  const max = Math.max(maxPoints, 1);
  const filled = Math.min(segments, Math.round((points / max) * segments));
  const ratio = points / max;
  const block = ratio >= 1 ? '🟥' : ratio >= 0.5 ? '🟧' : '🟨';
  return `${block.repeat(filled)}${'⬛'.repeat(segments - filled)}`;
}
