import test from 'node:test';
import assert from 'node:assert/strict';
import { formatDuration, toMs, MAX_TIMEOUT_MS, UNIT_CHOICES } from '../supabase/functions/hopkostki-bot/lib/duration.js';

test('polska odmiana jednostek czasu', () => {
  const cases = [
    [1, 'd', '1 dzień'], [2, 'd', '2 dni'], [14, 'd', '14 dni'],
    [1, 'm', '1 minuta'], [3, 'm', '3 minuty'], [5, 'm', '5 minut'],
    [12, 'h', '12 godzin'], [22, 'h', '22 godziny'],
    [2, 'w', '2 tygodnie'], [5, 'w', '5 tygodni'],
    [1, 'mo', '1 miesiąc'], [3, 'mo', '3 miesiące'], [6, 'mo', '6 miesięcy'],
  ];
  for (const [n, unit, expected] of cases) assert.equal(formatDuration(n, unit), expected);
});

test('przeliczanie na milisekundy i limit timeoutu', () => {
  assert.equal(toMs(2, 'h'), 2 * 3_600_000);
  assert.equal(toMs(4, 'w'), MAX_TIMEOUT_MS);
  assert.ok(toMs(1, 'mo') > MAX_TIMEOUT_MS);
  assert.throws(() => toMs(1, 'x'));
  assert.deepEqual(UNIT_CHOICES.map((c) => c.value), ['m', 'h', 'd', 'w', 'mo']);
});
