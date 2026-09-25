const test = require('node:test');
const assert = require('node:assert/strict');
const { formatDuration, toMs, MAX_TIMEOUT_MS, UNIT_CHOICES } = require('../src/lib/duration');

test('polska odmiana jednostek czasu', () => {
  assert.equal(formatDuration(1, 'd'), '1 dzień');
  assert.equal(formatDuration(2, 'd'), '2 dni');
  assert.equal(formatDuration(14, 'd'), '14 dni');
  assert.equal(formatDuration(1, 'm'), '1 minuta');
  assert.equal(formatDuration(3, 'm'), '3 minuty');
  assert.equal(formatDuration(5, 'm'), '5 minut');
  assert.equal(formatDuration(12, 'h'), '12 godzin');
  assert.equal(formatDuration(22, 'h'), '22 godziny');
  assert.equal(formatDuration(2, 'w'), '2 tygodnie');
  assert.equal(formatDuration(5, 'w'), '5 tygodni');
  assert.equal(formatDuration(1, 'mo'), '1 miesiąc');
  assert.equal(formatDuration(3, 'mo'), '3 miesiące');
  assert.equal(formatDuration(6, 'mo'), '6 miesięcy');
});

test('przeliczanie na milisekundy i limit timeoutu', () => {
  assert.equal(toMs(2, 'h'), 2 * 3_600_000);
  assert.equal(toMs(4, 'w'), MAX_TIMEOUT_MS);
  assert.ok(toMs(1, 'mo') > MAX_TIMEOUT_MS);
  assert.throws(() => toMs(1, 'x'));
  assert.deepEqual(UNIT_CHOICES.map((c) => c.value), ['m', 'h', 'd', 'w', 'mo']);
});
