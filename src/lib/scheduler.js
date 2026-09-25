// Co 30 sekund: zdejmuje wygasłe tymczasowe bany i usuwa ostrzeżenia, którym skończyło się odliczanie.

const { getStore } = require('./db');
const { expireTempBan, logExpiredWarns } = require('./moderation');

const INTERVAL_MS = 30_000;

function startScheduler(client) {
  let running = false;

  const tick = async () => {
    if (running) return;
    running = true;
    try {
      const store = getStore();
      for (const ban of store.dueTempBans()) await expireTempBan(client, ban);
      const expired = store.expireWarns();
      if (expired.length) await logExpiredWarns(client, expired);
    } catch (error) {
      console.error('[scheduler]', error);
    } finally {
      running = false;
    }
  };

  tick();
  return setInterval(tick, INTERVAL_MS);
}

module.exports = { startScheduler };
