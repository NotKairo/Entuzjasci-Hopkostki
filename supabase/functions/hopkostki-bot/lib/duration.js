// Jednostki czasu używane w komendach + poprawna polska odmiana ("1 dzień", "2 dni", "5 tygodni").

export const MINUTE = 60_000;
export const HOUR = 60 * MINUTE;
export const DAY = 24 * HOUR;

export const UNITS = {
  m: { ms: MINUTE, label: '⏱️ Minuty', forms: ['minuta', 'minuty', 'minut'] },
  h: { ms: HOUR, label: '🕐 Godziny', forms: ['godzina', 'godziny', 'godzin'] },
  d: { ms: DAY, label: '📅 Dni', forms: ['dzień', 'dni', 'dni'] },
  w: { ms: 7 * DAY, label: '🗓️ Tygodnie', forms: ['tydzień', 'tygodnie', 'tygodni'] },
  mo: { ms: 30 * DAY, label: '📆 Miesiące (30 dni)', forms: ['miesiąc', 'miesiące', 'miesięcy'] },
};

// Discord pozwala na timeout maksymalnie 28 dni.
export const MAX_TIMEOUT_MS = 28 * DAY;

export const UNIT_CHOICES = Object.entries(UNITS).map(([value, unit]) => ({ name: unit.label, value }));

export function plural(n, [one, few, many]) {
  if (n === 1) return one;
  const n10 = n % 10;
  const n100 = n % 100;
  if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
  return many;
}

export function isValidUnit(unit) {
  return Object.prototype.hasOwnProperty.call(UNITS, unit);
}

export function toMs(amount, unit) {
  if (!isValidUnit(unit)) throw new Error(`Nieznana jednostka czasu: ${unit}`);
  return amount * UNITS[unit].ms;
}

export function formatDuration(amount, unit) {
  if (!isValidUnit(unit)) return `${amount}`;
  return `${amount} ${plural(amount, UNITS[unit].forms)}`;
}

// Znacznik czasu Discorda, np. <t:1700000000:R> -> "za 3 dni".
export function discordTimestamp(ms, style = 'f') {
  return `<t:${Math.floor(ms / 1000)}:${style}>`;
}
