// Pomocnicze odpowiedzi na interakcje (błędy zawsze widzi tylko osoba, która użyła komendy).

const { MessageFlags } = require('discord.js');
const { errorEmbed } = require('./embeds');

async function replyError(interaction, message) {
  const payload = { embeds: [errorEmbed(message)], flags: MessageFlags.Ephemeral };
  try {
    if (interaction.deferred && interaction.ephemeral) return await interaction.editReply({ embeds: payload.embeds });
    if (interaction.deferred) {
      // Publiczna odpowiedź była odroczona — usuwamy ją, żeby błąd nie wisiał na kanale.
      await interaction.deleteReply().catch(() => {});
      return await interaction.followUp(payload);
    }
    if (interaction.replied) return await interaction.followUp(payload);
    return await interaction.reply(payload);
  } catch (error) {
    console.warn(`[interakcja] Nie udało się odpowiedzieć błędem: ${error.message}`);
    return null;
  }
}

function replyEphemeral(interaction, payload) {
  return interaction.reply({ ...payload, flags: MessageFlags.Ephemeral });
}

module.exports = { replyError, replyEphemeral };
