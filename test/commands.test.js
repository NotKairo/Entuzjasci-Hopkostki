require('./helpers');
const test = require('node:test');
const assert = require('node:assert/strict');
const { loadCommands } = require('../src/commands');

test('wszystkie komendy mają poprawne definicje slash', () => {
  const commands = loadCommands();
  const names = [...commands.keys()].sort();
  assert.deepEqual(names, ['ban', 'clear', 'historia', 'kick', 'lock', 'pomoc', 'slowmode', 'sprawa', 'timeout', 'unban', 'unlock', 'untimeout', 'warn']);
  for (const command of commands.values()) {
    const json = command.data.toJSON();
    assert.ok(json.description.length <= 100, json.name);
    assert.ok(JSON.stringify(json).length < 8000, json.name);
  }
  const warn = commands.get('warn').data.toJSON();
  assert.deepEqual(warn.options.map((o) => o.name), ['dodaj', 'status', 'usun', 'wyczysc', 'ranking']);
});
