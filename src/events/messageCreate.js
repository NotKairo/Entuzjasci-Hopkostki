// Każda odpowiedź na wiadomość bota o karze dostaje reakcję (domyślnie 🫓 :flatbread:).

const { Events } = require('discord.js');
const { getStore } = require('../lib/db');

module.exports = {
  name: Events.MessageCreate,
  async execute(message) {
    if (!message.inGuild() || message.author.bot) return;
    const repliedTo = message.reference?.messageId;
    if (!repliedTo) return;

    const store = getStore();
    const { replyReaction } = store.config;
    if (!replyReaction.enabled || !replyReaction.emoji || !store.isModMessage(repliedTo)) return;

    await message.react(replyReaction.emoji).catch((error) => {
      console.warn(`[reakcja] Nie udało się dodać ${replyReaction.emoji}: ${error.message}`);
    });
  },
};
