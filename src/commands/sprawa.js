const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, EmbedBuilder, MessageFlags } = require('discord.js');
const { getStore } = require('../lib/db');
const { formatDuration, discordTimestamp } = require('../lib/duration');
const { ACTION_LABELS, colorInt, successEmbed } = require('../lib/embeds');
const { sendModLog } = require('../lib/moderation');
const { replyError } = require('../lib/respond');

function caseEmbed(entry) {
  const config = getStore().config;
  const label = entry.type === 'ban' && entry.duration ? 'Tymczasowy ban' : ACTION_LABELS[entry.type];
  const lines = [
    `**Typ:** ${label}${entry.auto ? ' (automatycznie)' : ''}`,
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

  return new EmbedBuilder()
    .setColor(colorInt(config.actions[entry.type]?.color))
    .setTitle(`🗂️ Sprawa #${entry.id}`)
    .setDescription(lines.join('\n'));
}

module.exports = {
  permission: PermissionFlagsBits.ModerateMembers,
  data: new SlashCommandBuilder()
    .setName('sprawa')
    .setDescription('Podgląd i edycja spraw moderacyjnych')
    .setDefaultMemberPermissions(PermissionFlagsBits.ModerateMembers)
    .setContexts(InteractionContextType.Guild)
    .addSubcommand((s) =>
      s
        .setName('pokaz')
        .setDescription('Pokazuje szczegóły sprawy')
        .addIntegerOption((o) => o.setName('numer').setDescription('Numer sprawy').setRequired(true).setMinValue(1)),
    )
    .addSubcommand((s) =>
      s
        .setName('powod')
        .setDescription('Zmienia powód w sprawie')
        .addIntegerOption((o) => o.setName('numer').setDescription('Numer sprawy').setRequired(true).setMinValue(1))
        .addStringOption((o) => o.setName('nowy_powod').setDescription('Nowy powód').setRequired(true).setMaxLength(500)),
    ),

  async execute(interaction) {
    const store = getStore();
    const id = interaction.options.getInteger('numer', true);
    const entry = store.getCase(id);
    if (!entry) return replyError(interaction, `Nie ma sprawy **#${id}**.`);

    if (interaction.options.getSubcommand() === 'pokaz') {
      return interaction.reply({ embeds: [caseEmbed(entry)], flags: MessageFlags.Ephemeral });
    }

    const oldReason = entry.reason;
    const newReason = interaction.options.getString('nowy_powod', true);
    store.updateCase(id, { reason: newReason });
    if (entry.type === 'warn') store.updateWarnByCase(id, { reason: newReason });
    await sendModLog(interaction.guild, {
      embeds: [
        successEmbed(`**Sprawa #${id}** — zmieniono powód\n**Było:** ${oldReason}\n**Jest:** ${newReason}\n**Zmienił:** <@${interaction.user.id}>`).setTitle(
          '✏️ Edycja sprawy',
        ),
      ],
    });
    return interaction.reply({ embeds: [caseEmbed(store.getCase(id))], flags: MessageFlags.Ephemeral });
  },
};
