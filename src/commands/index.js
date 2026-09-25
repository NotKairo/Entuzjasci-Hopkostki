// Ładuje wszystkie komendy z tego folderu (plik może eksportować jedną komendę albo tablicę).

const fs = require('node:fs');
const path = require('node:path');

function loadCommands() {
  const commands = new Map();
  const files = fs.readdirSync(__dirname).filter((file) => file.endsWith('.js') && file !== 'index.js');
  for (const file of files) {
    const exported = require(path.join(__dirname, file));
    for (const command of [].concat(exported)) {
      if (!command?.data || typeof command.execute !== 'function') {
        throw new Error(`Plik komendy ${file} nie eksportuje { data, execute }`);
      }
      commands.set(command.data.name, command);
    }
  }
  return commands;
}

module.exports = { loadCommands };
