const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, ChannelType, MessageFlags } = require('discord.js');
const { sendModLog } = require('../lib/moderation');
const { simpleEmbed, successEmbed } = require('../lib/embeds');
const { replyError } = require('../lib/respond');

function describeSeconds(seconds) {
  if (seconds === 0) return 'wyłączony';
  if (seconds < 60) return `${seconds} s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min${seconds % 60 ? ` ${seconds % 60} s` : ''}`;
  return `${Math.floor(seconds / 3600)} h${seconds % 3600 ? ` ${Math.floor((seconds % 3600) / 60)} min` : ''}`;
}

module.exports = {
  permission: PermissionFlagsBits.ManageChannels,
  data: new SlashCommandBuilder()
    .setName('slowmode')
    .setDescription('Ustawia tryb powolny na kanale')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageChannels)
    .setContexts(InteractionContextType.Guild)
    .addIntegerOption((o) =>
      o.setName('sekundy').setDescription('Odstęp między wiadomościami (0 = wyłącz, maks. 21600)').setRequired(true).setMinValue(0).setMaxValue(21600),
    )
    .addChannelOption((o) =>
      o.setName('kanal').setDescription('Kanał (domyślnie obecny)').addChannelTypes(ChannelType.GuildText, ChannelType.GuildForum),
    ),

  async execute(interaction) {
    const seconds = interaction.options.getInteger('sekundy', true);
    const channel = interaction.options.getChannel('kanal') ?? interaction.channel;
    if (!channel?.setRateLimitPerUser) return replyError(interaction, 'Na tym kanale nie da się ustawić trybu powolnego.');

    await channel.setRateLimitPerUser(seconds, `Slowmode ustawiony przez ${interaction.user.username}`);
    await sendModLog(interaction.guild, {
      embeds: [simpleEmbed('info', '🐢 Tryb powolny', `**Kanał:** ${channel}\n**Ustawienie:** ${describeSeconds(seconds)}\n**Moderator:** <@${interaction.user.id}>`)],
    });
    await interaction.reply({
      embeds: [successEmbed(`Tryb powolny na ${channel}: **${describeSeconds(seconds)}**.`)],
      flags: MessageFlags.Ephemeral,
    });
  },
};
