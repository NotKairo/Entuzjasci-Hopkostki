const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType } = require('discord.js');
const { unbanUser } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

const BAN_CACHE_MS = 15_000;
let banCache = { at: 0, guildId: null, bans: [] };

async function fetchBans(guild) {
  if (banCache.guildId === guild.id && Date.now() - banCache.at < BAN_CACHE_MS) return banCache.bans;
  const bans = await guild.bans.fetch();
  banCache = { at: Date.now(), guildId: guild.id, bans: [...bans.values()] };
  return banCache.bans;
}

module.exports = {
  permission: PermissionFlagsBits.BanMembers,
  data: new SlashCommandBuilder()
    .setName('unban')
    .setDescription('Zdejmuje bana z użytkownika')
    .setDefaultMemberPermissions(PermissionFlagsBits.BanMembers)
    .setContexts(InteractionContextType.Guild)
    .addStringOption((o) =>
      o.setName('uzytkownik').setDescription('Zbanowany użytkownik (nick lub ID)').setRequired(true).setAutocomplete(true),
    )
    .addStringOption((o) => o.setName('powod').setDescription('Powód odbanowania').setMaxLength(500)),

  async autocomplete(interaction) {
    const query = interaction.options.getFocused().toLowerCase();
    const bans = await fetchBans(interaction.guild).catch(() => []);
    const choices = bans
      .filter((ban) => ban.user.username.toLowerCase().includes(query) || ban.user.id.includes(query))
      .slice(0, 25)
      .map((ban) => ({ name: `${ban.user.username} (${ban.user.id})`.slice(0, 100), value: ban.user.id }));
    await interaction.respond(choices);
  },

  async execute(interaction) {
    const raw = interaction.options.getString('uzytkownik', true).replace(/[<@!>]/g, '').trim();
    if (!/^\d{15,25}$/.test(raw)) return replyError(interaction, 'Wybierz użytkownika z listy albo podaj jego ID.');

    const target = await interaction.client.users.fetch(raw).catch(() => null);
    if (!target) return replyError(interaction, 'Nie znaleziono użytkownika o takim ID.');

    banCache.at = 0;
    await unbanUser({
      interaction,
      guild: interaction.guild,
      target,
      moderator: interaction.user,
      reason: interaction.options.getString('powod') ?? 'Nie podano powodu',
    });
  },
};
