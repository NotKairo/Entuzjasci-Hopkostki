// API panelu konfiguracyjnego. Lokalny panel (http://localhost:3000) przekazuje tu żądania
// z nagłówkiem x-panel-password — samo API działa na Supabase razem z botem.

import { DEFAULT_CONFIG } from './defaults.js';
import { getGuildContext, unbanUser, sendModLog, describeError } from './moderation.js';
import { avatarUrl, guildIconUrl } from './rest.js';
import { simpleEmbed } from './embeds.js';
import { ensureSetup } from './cron.js';
import { COMMAND_META } from './commands.js';

const TEXT_CHANNELS = new Set([0, 5]);
const hex = (n) => `#${Number(n ?? 0).toString(16).padStart(6, '0')}`;

async function status(bot) {
  const [stats, recent, setup, cron, gateway] = await Promise.all([
    bot.store.stats(),
    bot.store.listCases({ limit: 8 }),
    bot.store.getState('setup'),
    bot.store.getState('cron_last_run'),
    bot.store.getState('gateway_status'),
  ]);
  const result = {
    ready: false,
    error: null,
    bot: null,
    guild: null,
    stats,
    recent: recent.items,
    setup: { ...(setup ?? {}), tokenConfigured: Boolean(bot.env.token), selfUrl: bot.env.selfUrl },
    cron,
    gateway,
    // Czy hasło panelu jest ustawione na stałe przez sekret (wtedy nie da się go zmienić tutaj).
    passwordFixed: Boolean(bot.env.panelPassword),
  };
  if (!bot.discord) {
    result.error = 'Brak sekretu DISCORD_TOKEN w Supabase (Edge Functions → Secrets).';
    return result;
  }
  try {
    const gctx = await getGuildContext(bot);
    result.ready = true;
    result.bot = { id: gctx.botUser.id, tag: gctx.botUser.username, avatar: avatarUrl(gctx.botUser, 128) };
    result.guild = {
      id: gctx.guild.id,
      name: gctx.guild.name,
      icon: guildIconUrl(gctx.guild),
      memberCount: gctx.guild.memberCount,
    };
  } catch (error) {
    result.error = describeError(error);
  }
  return result;
}

async function guildInfo(bot) {
  if (!bot.discord) return { channels: [], roles: [] };
  const gctx = await getGuildContext(bot);
  const channels = await bot.discord.get(`/guilds/${gctx.guild.id}/channels`);
  const categories = new Map(channels.filter((c) => c.type === 4).map((c) => [c.id, c.name]));
  return {
    channels: channels
      .filter((c) => TEXT_CHANNELS.has(c.type))
      .sort((a, b) => a.position - b.position)
      .map((c) => ({ id: c.id, name: c.name, category: categories.get(c.parent_id) ?? null })),
    // permissions + position pozwalają panelowi policzyć samodzielnie, kto ma dostęp do jakiej komendy
    // (zakładka "Uprawnienia") bez kolejnego zapytania do Discorda.
    roles: gctx.rawRoles
      .filter((r) => r.id !== gctx.guild.id && !r.managed)
      .sort((a, b) => b.position - a.position)
      .map((r) => ({ id: r.id, name: r.name, color: hex(r.color), permissions: r.permissions, position: r.position })),
  };
}

const ok = (body) => ({ status: 200, body });
const fail = (status, error) => ({ status, body: { error } });

export async function handlePanel(bot, { method, path, query = {}, body = {} }) {
  const { store } = bot;

  if (method === 'GET' && path === '/status') return ok(await status(bot));
  if (method === 'GET' && path === '/guild') return ok(await guildInfo(bot));
  if (method === 'GET' && path === '/config') return ok({ config: await store.getConfig(), defaults: DEFAULT_CONFIG });
  if (method === 'PUT' && path === '/config') return ok({ config: await store.updateConfig(body ?? {}) });
  if (method === 'GET' && path === '/commands') return ok({ commands: COMMAND_META });

  if (method === 'POST' && path === '/setup') {
    if (!bot.discord) return fail(503, 'Brak sekretu DISCORD_TOKEN w Supabase.');
    return ok(await ensureSetup(bot, { force: true }));
  }

  // Zmiana hasła panelu — tylko gdy hasło NIE jest ustawione na stałe przez sekret PANEL_PASSWORD
  // (wtedy wywołujący już przeszedł uwierzytelnienie tym hasłem, więc "obecne" nie trzeba podawać osobno).
  if (method === 'POST' && path === '/password') {
    if (bot.env.panelPassword) {
      return fail(400, 'Hasło jest ustawione na stałe przez sekret PANEL_PASSWORD w Supabase (Edge Functions → Secrets) — zmień je tam.');
    }
    const next = String(body?.next ?? '');
    if (next.length < 8) return fail(400, 'Nowe hasło musi mieć co najmniej 8 znaków.');
    await store.setState('panel_password', next);
    return ok({ ok: true });
  }

  if (method === 'GET' && path === '/cases') {
    const limit = Math.min(Number(query.limit) || 25, 100);
    const page = Math.max(Number(query.page) || 1, 1);
    const user = String(query.user ?? '').trim();
    const isId = /^\d{15,25}$/.test(user);
    const result = await store.listCases({
      userId: isId ? user : undefined,
      search: user && !isId ? user : undefined,
      type: query.type || undefined,
      limit,
      offset: (page - 1) * limit,
    });
    return ok({ ...result, page, limit });
  }

  if (method === 'GET' && path === '/warns') return ok({ users: await store.warnRanking(), now: Date.now() });

  const warnMatch = /^\/warns\/(\d+)$/.exec(path);
  if (method === 'DELETE' && warnMatch) {
    const warn = await store.removeWarn(Number(warnMatch[1]));
    if (!warn) return fail(404, 'Nie ma takiego ostrzeżenia');
    if (warn.caseId) await store.updateCase(warn.caseId, { note: 'Ostrzeżenie usunięte w panelu' });
    if (bot.discord) {
      await sendModLog(bot, await store.getConfig(), {
        embeds: [
          simpleEmbed('success', `🗑️ Usunięto ostrzeżenie #${warn.id} (panel)`, `**Użytkownik:** <@${warn.userId}>\n**Treść:** ${warn.reason} (${warn.points} pkt)`),
        ],
      });
    }
    return ok({ ok: true });
  }

  if (method === 'GET' && path === '/tempbans') return ok({ bans: await store.listTempBans(), now: Date.now() });

  const unbanMatch = /^\/tempbans\/(\d+)\/unban$/.exec(path);
  if (method === 'POST' && unbanMatch) {
    const ban = (await store.listTempBans()).find((b) => b.userId === unbanMatch[1]);
    if (!ban) return fail(404, 'Nie ma takiego bana');
    if (!bot.discord) return fail(503, 'Bot nie jest skonfigurowany (brak DISCORD_TOKEN).');
    const target = await bot.discord.get(`/users/${ban.userId}`).catch(() => null);
    if (!target) {
      await store.removeTempBan(ban.guildId, ban.userId);
      return fail(404, 'Nie znaleziono użytkownika — usunięto wpis.');
    }
    try {
      const { botUser } = await getGuildContext(bot);
      await unbanUser(bot, { target, moderator: botUser, reason: 'Ban zdjęty w panelu', announce: false });
      return ok({ ok: true });
    } catch (error) {
      await store.removeTempBan(ban.guildId, ban.userId);
      return fail(400, describeError(error));
    }
  }

  return fail(404, 'Nie znaleziono');
}
