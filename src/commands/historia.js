const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, EmbedBuilder, MessageFlags } = require('discord.js');
const { getStore } = require('../lib/db');
const { formatDuration, discordTimestamp } = require('../lib/duration');
const { ACTION_LABELS, COLORS } = require('../lib/embeds');

function caseLine(entry) {
  const label = entry.type === 'ban' && entry.duration ? 'Tymczasowy ban' : ACTION_LABELS[entry.type];
  const duration = entry.duration ? ` (${formatDuration(entry.duration.amount, entry.duration.unit)})` : '';
  const auto = entry.auto ? ' 🤖' : '';
  const note = entry.note ? `\n-# ${entry.note}` : '';
  return `**#${entry.id} ${label}${duration}**${auto} • ${discordTimestamp(entry.createdAt, 'd')} • <@${entry.moderatorId}>\n> ${entry.reason.slice(0, 150)}${note}`;
}

module.exports = {
  permission: PermissionFlagsBits.ModerateMembers,
  data: new SlashCommandBuilder()
    .setName('historia')
    .setDescription('Pełna historia kar użytkownika (tylko dla moderacji)')
    .setDefaultMemberPermissions(PermissionFlagsBits.ModerateMembers)
    .setContexts(InteractionContextType.Guild)
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Czyja historia').setRequired(true)),

  async execute(interaction) {
    const user = interaction.options.getUser('uzytkownik', true);
    const { total, items } = getStore().listCases({ userId: user.id, limit: 12 });

    let description = items.map(caseLine).join('\n\n') || 'Brak kar. Wzorowy użytkownik! ✨';
    if (description.length > 4000) description = `${description.slice(0, 3990)}…`;

    const embed = new EmbedBuilder()
      .setColor(COLORS.info)
      .setTitle(`📜 Historia — ${user.username}`)
      .setThumbnail(user.displayAvatarURL())
      .setDescription(description)
      .setFooter({ text: `Łącznie spraw: ${total}${total > items.length ? ` • pokazano ${items.length} najnowszych` : ''}` });

    await interaction.reply({ embeds: [embed], flags: MessageFlags.Ephemeral });
  },
};
