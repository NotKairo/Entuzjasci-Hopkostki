// Wspólne przygotowanie testów: osobny katalog danych na każdy plik testowy.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

process.env.DATA_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'hopkostki-test-'));

module.exports = { dataDir: process.env.DATA_DIR };
