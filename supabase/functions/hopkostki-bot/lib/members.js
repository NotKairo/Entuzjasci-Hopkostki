// Nowe osoby na serwerze: automatyczne role, powitanie, pożegnanie i log wejść/wyjść (z wiekiem konta).
// Zdarzenia GUILD_MEMBER_* przychodzą z gatewaya tylko z intencją „Server Members”, którą bot włącza sam
// (flaga GATEWAY_GUILD_MEMBERS_LIMITED aplikacji), gdy któraś z tych funkcji jest włączona.

import { getApp, getGuildContext, isOurGuild, sendModLog } from './moderation.js';
import { avatarUrl } from './rest.js';
import { COLORS, colorInt, escapeMarkdown, fillTemplate } from './embeds.js';
import { discordTimestamp } from './duration.js';

const FLAG = { MEMBERS: 1 << 14, MEMBERS_LIMITED: 1 << 15, CONTENT: 1 << 18, CONTENT_LIMITED: 1 << 19 };
const RECENT_JOIN_MS = 30 * 60_000;
const SWEEP_EVERY_MS = 5 * 60_000;
const SWEEP_WINDOW_MS = 24 * 60 * 60_000;

export const membersIntentOn = (app) => Boolean((app?.flags ?? 0) & (FLAG.MEMBERS | FLAG.MEMBERS_LIMITED));
export const contentIntentOn = (app) => Boolean((app?.flags ?? 0) & (FLAG.CONTENT | FLAG.CONTENT_LIMITED));

export function membersFeaturesOn(config) {
  const m = config.members;
  return m.autoRole.enabled || m.welcome.enabled || m.goodbye.enabled || m.logJoins;
}

// Włącza w aplikacji intencje potrzebne do włączonych funkcji (tylko wersje "limited" da się włączyć przez API —
// wystarczą dla botów na mniej niż 100 serwerach). Zwraca aktualny stan dla panelu.
export async function ensureIntentFlags(bot, config) {
  const app = await getApp(bot);
  const needMembers = membersFeaturesOn(config) && !membersIntentOn(app);
  const needContent = config.tickets.enabled && !contentIntentOn(app);
  let flags = app.flags ?? 0;
  let error = null;
  if (needMembers || needContent) {
    try {
      const updated = await bot.discord.patch('/applications/@me', {
        flags: flags | (needMembers ? FLAG.MEMBERS_LIMITED : 0) | (needContent ? FLAG.CONTENT_LIMITED : 0),
      });
      flags = updated?.flags ?? flags;
      bot.cache.delete('app');
    } catch (e) {
      error = e.message;
    }
  }
  return { members: membersIntentOn({ flags }), content: contentIntentOn({ flags }), error };
}

// ---------- Zdarzenia ----------

function vars(member, guild, count) {
  const user = member.user ?? {};
  return {
    uzytkownik: `<@${user.id}>`,
    nick: escapeMarkdown(user.global_name ?? user.username ?? ''),
    serwer: escapeMarkdown(guild?.name ?? ''),
    liczba: count ?? '?',
  };
}

export async function giveAutoRoles(bot, guildId, member, autoRole) {
  const wanted = member.user?.bot ? autoRole.botRoleIds : autoRole.roleIds;
  const missing = wanted.filter((id) => !member.roles?.includes(id));
  for (const roleId of missing) {
    await bot.discord
      .put(`/guilds/${guildId}/members/${member.user.id}/roles/${roleId}`, undefined, { reason: 'Automatyczna rola dla nowej osoby' })
      .catch((error) => console.warn(`[autorole] ${member.user.id} ${roleId}: ${error.message}`));
  }
  return missing.length;
}

export async function onMemberJoin(bot, member) {
  if (!(await isOurGuild(bot, member?.guild_id))) return;
  const { members } = await bot.store.getConfig();
  // Przy weryfikacji członkostwa (regulamin) rolę dostaje się dopiero po akceptacji — patrz onMemberUpdate.
  if (members.autoRole.enabled && !member.pending) await giveAutoRoles(bot, member.guild_id, member, members.autoRole);

  const needsGuild = members.welcome.enabled || members.logJoins;
  const gctx = needsGuild ? await getGuildContext(bot, { force: true }).catch(() => null) : null;
  const user = member.user;

  if (members.welcome.enabled && members.welcome.channelId && !user.bot) {
    const w = members.welcome;
    const v = vars(member, gctx?.guild, gctx?.guild?.memberCount);
    await bot.discord
      .post(`/channels/${w.channelId}/messages`, {
        content: `<@${user.id}>`,
        allowed_mentions: { users: [user.id] },
        embeds: [
          {
            color: colorInt(w.color, COLORS.success),
            ...(w.title ? { title: fillTemplate(w.title, v) } : {}),
            description: fillTemplate(w.message, v),
            thumbnail: { url: avatarUrl(user) },
          },
        ],
      })
      .catch((error) => console.warn(`[powitanie] ${error.message}`));
  }

  if (members.logJoins) {
    const created = Number((BigInt(user.id) >> 22n) + 1420070400000n);
    const young = Date.now() - created < 7 * 24 * 60 * 60_000;
    await sendModLog(bot, await bot.store.getConfig(), {
      embeds: [
        {
          color: young ? COLORS.warning : COLORS.success,
          author: { name: `${user.username} dołączył(a)`, icon_url: avatarUrl(user, 64) },
          description: `<@${user.id}> \`${user.id}\`\n**Konto założone:** ${discordTimestamp(created, 'f')} (${discordTimestamp(created, 'R')})${young ? '\n**Uwaga:** konto ma mniej niż 7 dni.' : ''}`,
          footer: { text: `Członków: ${gctx?.guild?.memberCount ?? '?'}` },
        },
      ],
    });
  }
}

// Po zaakceptowaniu regulaminu (pending: true → false) dostaje role, których nie mógł dostać przy wejściu.
export async function onMemberUpdate(bot, member) {
  if (member?.pending || !(await isOurGuild(bot, member?.guild_id))) return;
  const { members } = await bot.store.getConfig();
  if (!members.autoRole.enabled) return;
  const joined = member.joined_at ? new Date(member.joined_at).getTime() : 0;
  if (Date.now() - joined > RECENT_JOIN_MS) return;
  await giveAutoRoles(bot, member.guild_id, member, members.autoRole);
}

export async function onMemberLeave(bot, data) {
  if (!(await isOurGuild(bot, data?.guild_id))) return;
  const config = await bot.store.getConfig();
  const { members } = config;
  if (!members.goodbye.enabled && !members.logJoins) return;
  const gctx = await getGuildContext(bot, { force: true }).catch(() => null);
  const user = data.user;
  if (members.goodbye.enabled && members.goodbye.channelId && !user.bot) {
    await bot.discord
      .post(`/channels/${members.goodbye.channelId}/messages`, {
        embeds: [{ color: COLORS.muted, description: fillTemplate(members.goodbye.message, vars(data, gctx?.guild, gctx?.guild?.memberCount)) }],
        allowed_mentions: { parse: [] },
      })
      .catch((error) => console.warn(`[pożegnanie] ${error.message}`));
  }
  if (members.logJoins) {
    await sendModLog(bot, config, {
      embeds: [{ color: COLORS.muted, author: { name: `${user.username} wyszedł/wyszła`, icon_url: avatarUrl(user, 64) }, description: `<@${user.id}> \`${user.id}\`` }],
    });
  }
}

// Zapas w cronie (co 5 min): osoby, które weszły w ostatniej dobie, a nie mają automatycznych ról
// (np. weszły, gdy bot był akurat rozłączony z gatewayem).
export async function autoRoleSweep(bot) {
  const { members } = await bot.store.getConfig();
  if (!members.autoRole.enabled) return 0;
  const last = await bot.store.getState('autorole_sweep');
  if (last && Date.now() - last < SWEEP_EVERY_MS) return 0;
  await bot.store.setState('autorole_sweep', Date.now());
  if (!membersIntentOn(await getApp(bot))) return 0;
  const { guild } = await getGuildContext(bot);
  let given = 0;
  let after = '0';
  for (let page = 0; page < 10; page += 1) {
    const list = await bot.discord.get(`/guilds/${guild.id}/members`, { query: { limit: 1000, after } });
    if (!list?.length) break;
    for (const member of list) {
      const joined = member.joined_at ? new Date(member.joined_at).getTime() : 0;
      if (member.pending || Date.now() - joined > SWEEP_WINDOW_MS) continue;
      given += await giveAutoRoles(bot, guild.id, member, members.autoRole);
    }
    after = list.at(-1).user.id;
    if (list.length < 1000) break;
  }
  return given;
}
