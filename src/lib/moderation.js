// Wspólna logika wszystkich kar: sprawdzenie hierarchii, zapis sprawy, DM, wykonanie akcji,
// ogłoszenie na kanale i log. Komendy tylko zbierają dane i wołają funkcje z tego pliku.

const { PermissionFlagsBits, MessageFlags, DiscordAPIError } = require('discord.js');
const { getStore } = require('./db');
const { toMs, MAX_TIMEOUT_MS } = require('./duration');
const embeds = require('./embeds');

class ActionError extends Error {}

// Kody błędów API Discorda, które warto przetłumaczyć.
const API_ERRORS = {
  10007: 'Tego użytkownika nie ma na serwerze.',
  10013: 'Nie znaleziono takiego użytkownika.',
  10026: 'Ten użytkownik nie jest zbanowany.',
  50013: 'Brakuje mi uprawnień — sprawdź, czy moja rola jest wyżej niż rola tego użytkownika.',
};

function describeError(error) {
  if (error instanceof ActionError) return error.message;
  if (error instanceof DiscordAPIError && API_ERRORS[error.code]) return API_ERRORS[error.code];
  return `Nieoczekiwany błąd: ${error.message ?? error}`;
}

function hasModAccess(member, permission) {
  if (!member?.permissions) return false;
  if (member.permissions.has(PermissionFlagsBits.Administrator)) return true;
  if (permission && member.permissions.has(permission)) return true;
  const modRoles = getStore().config.modRoleIds;
  return modRoles.some((id) => member.roles.cache.has(id));
}

const CAPABILITY = {
  ban: (m) => m.bannable,
  kick: (m) => m.kickable,
  timeout: (m) => m.moderatable,
  untimeout: (m) => m.moderatable,
  warn: () => true,
};

// Zwraca komunikat błędu albo null, jeśli akcję można wykonać.
function checkTarget({ guild, moderatorMember, target, targetMember, action }) {
  if (moderatorMember && target.id === moderatorMember.id) return 'Nie możesz użyć tej komendy na sobie.';
  if (target.id === guild.client.user.id) return 'Nie mogę ukarać samego siebie. 🥲';
  if (target.id === guild.ownerId) return 'Nie można ukarać właściciela serwera.';

  if (!targetMember) {
    return action === 'ban' ? null : 'Tego użytkownika nie ma na serwerze.';
  }
  if (
    moderatorMember &&
    moderatorMember.id !== guild.ownerId &&
    targetMember.roles.highest.comparePositionTo(moderatorMember.roles.highest) >= 0
  ) {
    return 'Ten użytkownik ma rolę równą lub wyższą od Twojej.';
  }
  if (action === 'timeout' && targetMember.permissions.has(PermissionFlagsBits.Administrator)) {
    return 'Nie można wyciszyć administratora.';
  }
  if (!CAPABILITY[action](targetMember)) {
    return 'Nie mam uprawnień, żeby to zrobić — moja rola musi być wyżej niż rola tego użytkownika.';
  }
  return null;
}

function auditReason(moderator, reason, caseId) {
  return `${moderator.username}: ${reason} (sprawa #${caseId})`.slice(0, 512);
}

async function sendModLog(guild, payload) {
  const channelId = getStore().config.modLogChannelId;
  if (!channelId) return null;
  const channel = guild.channels.cache.get(channelId);
  if (!channel?.isTextBased?.()) return null;
  return channel.send({ allowedMentions: { parse: [] }, ...payload }).catch((error) => {
    console.warn(`[log] Nie udało się wysłać logu: ${error.message}`);
    return null;
  });
}

function announceElsewhere(interaction) {
  const channelId = getStore().config.announceChannelId;
  return Boolean(channelId && interaction && channelId !== interaction.channelId);
}

// Odracza odpowiedź, żeby DM + akcja zmieściły się w limicie 3 sekund Discorda.
async function deferForAction(interaction) {
  if (!interaction || interaction.deferred || interaction.replied) return;
  await interaction.deferReply(announceElsewhere(interaction) ? { flags: MessageFlags.Ephemeral } : {});
}

async function publishAnnouncement({ guild, interaction, channel, payload }) {
  const config = getStore().config;
  const announceChannel = config.announceChannelId ? guild.channels.cache.get(config.announceChannelId) : null;

  if (interaction) {
    if (announceElsewhere(interaction) && announceChannel?.isTextBased()) {
      const message = await announceChannel.send(payload);
      await interaction.editReply({ embeds: [embeds.successEmbed(`Kara została ogłoszona na kanale ${announceChannel}.`)] });
      return message;
    }
    return interaction.editReply(payload);
  }

  const target = announceChannel?.isTextBased() ? announceChannel : channel;
  return target ? target.send(payload) : null;
}

/**
 * Wykonuje akcję moderacyjną od początku do końca.
 * perform(caseEntry) wykonuje właściwą operację na Discordzie i może zwrócić dodatkowe dane do embedów.
 * dmFirst: DM wysyłany przed akcją (ban/kick — potem nie będzie wspólnego serwera).
 */
async function runAction({
  action,
  guild,
  target,
  moderator,
  reason,
  duration = null,
  expiresAt = null,
  auto = false,
  dmFirst = false,
  announce = true,
  interaction = null,
  channel = null,
  perform,
}) {
  const store = getStore();
  const config = store.config;
  await deferForAction(interaction);

  const entry = store.addCase({
    type: action,
    guildId: guild.id,
    userId: target.id,
    userTag: target.username,
    moderatorId: moderator.id,
    moderatorTag: moderator.username,
    reason,
    duration,
    expiresAt,
    auto,
  });
  const ctx = { action, guild, target, moderator, reason, duration, expiresAt, auto, caseId: entry.id, createdAt: entry.createdAt };

  let dmMessage = null;
  let dmStatus = config.dmUsers ? null : '➖ wyłączone';
  const sendDm = async () => {
    if (!config.dmUsers) return;
    try {
      dmMessage = await target.send({ embeds: [embeds.buildDmEmbed(ctx, config)] });
      dmStatus = '✅ dostarczono';
    } catch {
      dmStatus = '❌ nie udało się (zamknięte DM)';
    }
  };

  if (dmFirst) await sendDm();
  try {
    Object.assign(ctx, (await perform(entry)) ?? {});
  } catch (error) {
    store.deleteCase(entry.id);
    if (dmMessage) await dmMessage.delete().catch(() => {});
    throw new ActionError(describeError(error));
  }
  if (!dmFirst) await sendDm();

  let message = null;
  if (announce) {
    const payload = {
      content: config.mentionTarget ? `<@${target.id}>` : undefined,
      embeds: [embeds.buildChannelEmbed(ctx, config)],
      allowedMentions: { users: config.mentionTarget ? [target.id] : [] },
    };
    message = await publishAnnouncement({ guild, interaction, channel, payload }).catch((error) => {
      console.warn(`[akcja] Nie udało się ogłosić kary: ${error.message}`);
      return null;
    });
    if (message) {
      store.addModMessage(message.id);
      store.updateCase(entry.id, { messageUrl: message.url });
    }
  }

  store.updateCase(entry.id, { dmStatus });
  await sendModLog(guild, { embeds: [embeds.buildLogEmbed(ctx, config, { dmStatus, messageUrl: message?.url })] });
  return { entry, message, ctx };
}

// ---------- Konkretne akcje ----------

async function banUser({ guild, target, moderator, reason, duration = null, deleteMessageSeconds = 0, ...rest }) {
  const store = getStore();
  const expiresAt = duration ? Date.now() + toMs(duration.amount, duration.unit) : null;
  return runAction({
    ...rest,
    action: 'ban',
    guild,
    target,
    moderator,
    reason,
    duration,
    expiresAt,
    dmFirst: true,
    perform: async (entry) => {
      await guild.members.ban(target.id, { reason: auditReason(moderator, reason, entry.id), deleteMessageSeconds });
      if (expiresAt) {
        store.setTempBan({ guildId: guild.id, userId: target.id, userTag: target.username, expiresAt, caseId: entry.id });
      } else {
        store.removeTempBan(guild.id, target.id);
      }
    },
  });
}

async function unbanUser({ guild, target, moderator, reason, ...rest }) {
  return runAction({
    ...rest,
    action: 'unban',
    guild,
    target,
    moderator,
    reason,
    perform: async (entry) => {
      await guild.bans.remove(target.id, auditReason(moderator, reason, entry.id));
      getStore().removeTempBan(guild.id, target.id);
    },
  });
}

async function kickUser({ guild, target, targetMember, moderator, reason, ...rest }) {
  return runAction({
    ...rest,
    action: 'kick',
    guild,
    target,
    moderator,
    reason,
    dmFirst: true,
    perform: (entry) => targetMember.kick(auditReason(moderator, reason, entry.id)),
  });
}

async function timeoutUser({ guild, target, targetMember, moderator, reason, duration, ...rest }) {
  const ms = toMs(duration.amount, duration.unit);
  return runAction({
    ...rest,
    action: 'timeout',
    guild,
    target,
    moderator,
    reason,
    duration,
    expiresAt: Date.now() + ms,
    perform: (entry) => targetMember.timeout(ms, auditReason(moderator, reason, entry.id)),
  });
}

async function untimeoutUser({ guild, target, targetMember, moderator, reason, ...rest }) {
  return runAction({
    ...rest,
    action: 'untimeout',
    guild,
    target,
    moderator,
    reason,
    perform: (entry) => targetMember.timeout(null, auditReason(moderator, reason, entry.id)),
  });
}

async function warnUser({ guild, target, moderator, reason, points, interaction = null, channel = null, ...rest }) {
  const store = getStore();
  const before = store.warnSummary(target.id).points;
  const result = await runAction({
    ...rest,
    action: 'warn',
    guild,
    target,
    moderator,
    reason,
    interaction,
    channel,
    perform: (entry) => {
      const warn = store.addWarn({
        guildId: guild.id,
        userId: target.id,
        userTag: target.username,
        moderatorId: moderator.id,
        moderatorTag: moderator.username,
        reason,
        points,
        caseId: entry.id,
      });
      const summary = store.warnSummary(target.id);
      return { warn: { id: warn.id, points, expiresAt: warn.expiresAt, count: summary.count, totalPoints: summary.points } };
    },
  });
  await applyEscalation({
    guild,
    target,
    before,
    after: result.ctx.warn.totalPoints,
    channel: channel ?? interaction?.channel ?? null,
  });
  return result;
}

// ---------- Automatyczne kary za punkty ostrzeżeń ----------

function crossedRule(rules, before, after) {
  const crossed = rules.filter((rule) => before < rule.points && after >= rule.points);
  return crossed.length ? crossed[crossed.length - 1] : null;
}

async function applyEscalation({ guild, target, before, after, channel }) {
  const { escalation, modRoleIds } = getStore().config;
  if (!escalation.enabled) return null;
  const rule = crossedRule(escalation.rules, before, after);
  if (!rule) return null;

  const alert = (description) =>
    sendModLog(guild, {
      content: modRoleIds.map((id) => `<@&${id}>`).join(' ') || undefined,
      allowedMentions: { roles: modRoleIds },
      embeds: [embeds.simpleEmbed('warning', '🚨 Próg ostrzeżeń przekroczony', description)],
    });

  const summary = `<@${target.id}> ma teraz **${after} pkt** ostrzeżeń (próg: ${rule.points} pkt).`;
  if (rule.action === 'alert') {
    await alert(`${summary}\nSprawdź \`/warn status\` i zdecyduj o dalszych krokach.`);
    return rule;
  }

  const bot = guild.client.user;
  const targetMember = await guild.members.fetch(target.id).catch(() => null);
  const problem = checkTarget({ guild, moderatorMember: null, target, targetMember, action: rule.action });
  if (problem) {
    await alert(`${summary}\nAutomatyczna kara (**${rule.action}**) nie została nałożona: ${problem}`);
    return rule;
  }

  const duration = rule.amount > 0 && rule.action !== 'kick' ? { amount: rule.amount, unit: rule.unit } : null;
  const base = {
    guild,
    target,
    targetMember,
    moderator: bot,
    auto: true,
    channel,
    reason: `Automatyczna kara: ${after} pkt ostrzeżeń (próg ${rule.points} pkt)`,
  };
  try {
    if (rule.action === 'ban') await banUser({ ...base, duration });
    if (rule.action === 'kick') await kickUser(base);
    if (rule.action === 'timeout') {
      const safe = duration && toMs(duration.amount, duration.unit) <= MAX_TIMEOUT_MS ? duration : { amount: 28, unit: 'd' };
      await timeoutUser({ ...base, duration: safe });
    }
  } catch (error) {
    await alert(`${summary}\nAutomatyczna kara nie powiodła się: ${describeError(error)}`);
  }
  return rule;
}

// ---------- Wygasanie ----------

async function expireTempBan(client, ban) {
  const store = getStore();
  const guild = client.guilds.cache.get(ban.guildId);
  if (!guild) return;
  const target = await client.users.fetch(ban.userId).catch(() => null);
  if (!target) {
    store.removeTempBan(ban.guildId, ban.userId);
    return;
  }
  try {
    await unbanUser({
      guild,
      target,
      moderator: client.user,
      reason: `Tymczasowy ban wygasł (sprawa #${ban.caseId})`,
      auto: true,
      announce: false,
    });
  } catch (error) {
    // Ktoś już zdjął bana ręcznie — wystarczy usunąć wpis.
    store.removeTempBan(ban.guildId, ban.userId);
    console.warn(`[tempban] ${target.username}: ${error.message}`);
  }
}

async function logExpiredWarns(client, warns) {
  for (const warn of warns) {
    const guild = client.guilds.cache.get(warn.guildId);
    if (!guild) continue;
    const left = getStore().warnSummary(warn.userId);
    await sendModLog(guild, {
      embeds: [
        embeds.simpleEmbed(
          'muted',
          `⌛ Ostrzeżenie #${warn.id} wygasło`,
          [
            `**Użytkownik:** <@${warn.userId}> \`${warn.userTag}\``,
            `**Powód:** ${warn.reason}`,
            `**Punkty:** -${warn.points}`,
            `**Pozostało:** ${left.count} ostrzeżeń (${left.points} pkt)`,
          ].join('\n'),
        ),
      ],
    });
  }
}

module.exports = {
  ActionError,
  describeError,
  hasModAccess,
  checkTarget,
  sendModLog,
  deferForAction,
  runAction,
  banUser,
  unbanUser,
  kickUser,
  timeoutUser,
  untimeoutUser,
  warnUser,
  crossedRule,
  applyEscalation,
  expireTempBan,
  logExpiredWarns,
};
