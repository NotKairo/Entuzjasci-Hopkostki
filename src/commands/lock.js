const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, ChannelType, MessageFlags } = require('discord.js');
const { sendModLog } = require('../lib/moderation');
const { simpleEmbed, successEmbed } = require('../lib/embeds');
const { replyError } = require('../lib/respond');

// Jeden plik obsługuje /lock i /unlock (blokada pisania dla @everyone).
function build(name, description) {
  return new SlashCommandBuilder()
    .setName(name)
    .setDescription(description)
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageChannels)
    .setContexts(InteractionContextType.Guild)
    .addChannelOption((o) =>
      o.setName('kanal').setDescription('Kanał (domyślnie obecny)').addChannelTypes(ChannelType.GuildText, ChannelType.GuildAnnouncement),
    )
    .addStringOption((o) => o.setName('powod').setDescription('Powód').setMaxLength(500));
}

async function toggle(interaction, locked) {
  const channel = interaction.options.getChannel('kanal') ?? interaction.channel;
  const reason = interaction.options.getString('powod') ?? 'Nie podano powodu';
  if (!channel?.permissionOverwrites) return replyError(interaction, 'Tego kanału nie da się zablokować.');

  const everyone = interaction.guild.roles.everyone;
  await channel.permissionOverwrites.edit(
    everyone,
    { SendMessages: locked ? false : null, SendMessagesInThreads: locked ? false : null },
    { reason: `${interaction.user.username}: ${reason}` },
  );

  const title = locked ? '🔒 Kanał zablokowany' : '🔓 Kanał odblokowany';
  const body = locked
    ? `Pisanie na tym kanale zostało tymczasowo wyłączone.\n**Powód:** ${reason}`
    : `Można znowu pisać na tym kanale.\n**Powód:** ${reason}`;
  await channel.send({ embeds: [simpleEmbed(locked ? 'error' : 'success', title, body)] }).catch(() => {});
  await sendModLog(interaction.guild, {
    embeds: [simpleEmbed('info', title, `**Kanał:** ${channel}\n**Moderator:** <@${interaction.user.id}>\n**Powód:** ${reason}`)],
  });
  await interaction.reply({
    embeds: [successEmbed(`${channel} ${locked ? 'zablokowany' : 'odblokowany'}.`)],
    flags: MessageFlags.Ephemeral,
  });
}

module.exports = [
  {
    permission: PermissionFlagsBits.ManageChannels,
    data: build('lock', 'Blokuje pisanie na kanale'),
    execute: (interaction) => toggle(interaction, true),
  },
  {
    permission: PermissionFlagsBits.ManageChannels,
    data: build('unlock', 'Odblokowuje pisanie na kanale'),
    execute: (interaction) => toggle(interaction, false),
  },
];
