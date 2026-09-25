const { SlashCommandBuilder, PermissionFlagsBits, InteractionContextType, MessageFlags } = require('discord.js');
const { sendModLog } = require('../lib/moderation');
const { simpleEmbed, successEmbed } = require('../lib/embeds');
const { replyError } = require('../lib/respond');

const TWO_WEEKS_MS = 14 * 24 * 60 * 60 * 1000;

module.exports = {
  permission: PermissionFlagsBits.ManageMessages,
  data: new SlashCommandBuilder()
    .setName('clear')
    .setDescription('Usuwa ostatnie wiadomości na kanale')
    .setDefaultMemberPermissions(PermissionFlagsBits.ManageMessages)
    .setContexts(InteractionContextType.Guild)
    .addIntegerOption((o) =>
      o.setName('ilosc').setDescription('Ile wiadomości sprawdzić (1–100)').setRequired(true).setMinValue(1).setMaxValue(100),
    )
    .addUserOption((o) => o.setName('uzytkownik').setDescription('Usuń tylko wiadomości tej osoby')),

  async execute(interaction) {
    const amount = interaction.options.getInteger('ilosc', true);
    const user = interaction.options.getUser('uzytkownik');
    const channel = interaction.channel;
    if (!channel?.bulkDelete) return replyError(interaction, 'Na tym kanale nie da się usuwać wiadomości.');

    await interaction.deferReply({ flags: MessageFlags.Ephemeral });
    const fetched = await channel.messages.fetch({ limit: amount });
    const toDelete = fetched.filter(
      (m) => Date.now() - m.createdTimestamp < TWO_WEEKS_MS && (!user || m.author.id === user.id),
    );
    const deleted = await channel.bulkDelete(toDelete, true);

    await sendModLog(interaction.guild, {
      embeds: [
        simpleEmbed(
          'info',
          '🧹 Wyczyszczono wiadomości',
          `**Kanał:** ${channel}\n**Moderator:** <@${interaction.user.id}>\n**Usunięto:** ${deleted.size}${user ? `\n**Tylko od:** <@${user.id}>` : ''}`,
        ),
      ],
    });
    const skipped = fetched.size - toDelete.size;
    const note = skipped && !user ? `\n-# Pominięto ${skipped} wiadomości starszych niż 14 dni (ograniczenie Discorda).` : '';
    await interaction.editReply({ embeds: [successEmbed(`Usunięto **${deleted.size}** wiadomości.${note}`)] });
  },
};
