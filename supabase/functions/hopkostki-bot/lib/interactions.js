// Obsługa interakcji z Discorda (HTTP Interactions). Discord wymaga odpowiedzi w 3 sekundy,
// więc komendy są od razu "odraczane" (type 5), a właściwa praca dzieje się w tle.

import { COMMAND_MAP } from './commands.js';
import { hasModAccess, describeError, ActionError, announceElsewhere } from './moderation.js';
import { errorEmbed } from './embeds.js';

const TYPE = { PING: 1, COMMAND: 2, AUTOCOMPLETE: 4 };
const RESPONSE = { PONG: 1, MESSAGE: 4, DEFERRED: 5, AUTOCOMPLETE: 8 };
export const EPHEMERAL = 64;

function flattenOptions(options = []) {
  const values = new Map();
  let sub = null;
  let focused = null;
  const walk = (list) => {
    for (const option of list ?? []) {
      if (option.type === 1 || option.type === 2) {
        sub = option.type === 1 ? option.name : sub;
        walk(option.options);
      } else {
        values.set(option.name, option.value);
        if (option.focused) focused = option.value;
      }
    }
  };
  walk(options);
  return { values, sub, focused };
}

export function wrapInteraction(body, bot) {
  const data = body.data ?? {};
  const { values, sub, focused } = flattenOptions(data.options);
  const resolved = data.resolved ?? {};
  const hook = `/webhooks/${body.application_id}/${body.token}`;
  const member = body.member
    ? { id: body.member.user.id, roles: body.member.roles ?? [], permissions: body.member.permissions ?? '0' }
    : null;

  return {
    raw: body,
    guildId: body.guild_id,
    channelId: body.channel_id ?? body.channel?.id ?? null,
    name: data.name,
    sub,
    user: body.member?.user ?? body.user,
    member,
    ephemeral: false,
    opt: (name) => values.get(name),
    focused: () => focused,
    getUser(name) {
      const id = values.get(name);
      return id ? resolved.users?.[id] ?? null : null;
    },
    getMember(name) {
      const id = values.get(name);
      const found = id ? resolved.members?.[id] : null;
      return found ? { ...found, id } : null;
    },
    edit: (payload) => bot.discord.patch(`${hook}/messages/@original`, payload),
    delete: () => bot.discord.delete(`${hook}/messages/@original`),
    followUp: (payload) => bot.discord.post(hook, payload),
  };
}

export async function replyError(ix, message) {
  const payload = { content: '', embeds: [errorEmbed(message)] };
  try {
    if (ix.ephemeral) return await ix.edit(payload);
    // Publiczna odpowiedź była odroczona — usuwamy ją, żeby błąd nie wisiał na kanale.
    await ix.delete().catch(() => {});
    return await ix.followUp({ ...payload, flags: EPHEMERAL });
  } catch (error) {
    console.warn(`[interakcja] Nie udało się odpowiedzieć błędem: ${error.message}`);
    return null;
  }
}

const ephemeralMessage = (text) => ({ type: RESPONSE.MESSAGE, data: { flags: EPHEMERAL, embeds: [errorEmbed(text)] } });

/**
 * Zwraca { response } — odpowiedź HTTP dla Discorda — oraz opcjonalnie task() do wykonania w tle.
 */
export async function handleInteraction(body, bot) {
  if (body.type === TYPE.PING) return { response: { type: RESPONSE.PONG } };
  if (!body.guild_id) return { response: ephemeralMessage('Komendy działają tylko na serwerze.') };
  if (bot.env.guildId && body.guild_id !== bot.env.guildId) {
    return { response: ephemeralMessage('Ten bot działa tylko na serwerze Entuzjaści Hopkostki.') };
  }

  const command = COMMAND_MAP.get(body.data?.name);
  if (!command) return { response: ephemeralMessage('Nieznana komenda — spróbuj ponownie za chwilę.') };

  const ix = wrapInteraction(body, bot);
  const config = await bot.store.getConfig();

  if (body.type === TYPE.AUTOCOMPLETE) {
    let choices = [];
    if (command.autocomplete && hasModAccess(ix.member, command.permission, config)) {
      choices = await command.autocomplete(ix, bot).catch(() => []);
    }
    return { response: { type: RESPONSE.AUTOCOMPLETE, data: { choices } } };
  }
  if (body.type !== TYPE.COMMAND) return { response: ephemeralMessage('Nieobsługiwany typ interakcji.') };

  if (command.permission && !hasModAccess(ix.member, command.permission, config)) {
    return { response: ephemeralMessage('Nie masz uprawnień do tej komendy.') };
  }

  const mode = typeof command.defer === 'function' ? command.defer(ix) : command.defer;
  ix.ephemeral = mode === 'ephemeral' || announceElsewhere(config, ix.channelId);

  const task = async () => {
    try {
      await command.execute(ix, bot);
    } catch (error) {
      if (!(error instanceof ActionError)) console.error(`[/${ix.name}]`, error);
      await replyError(ix, describeError(error));
    }
  };
  return { response: { type: RESPONSE.DEFERRED, data: ix.ephemeral ? { flags: EPHEMERAL } : {} }, task, ix };
}
