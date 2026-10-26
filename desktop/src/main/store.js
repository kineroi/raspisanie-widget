'use strict';
// Настройки и данные программы в папке пользователя (%APPDATA%\Моё расписание).
// Пропуск BilimClass и ключ Gemini шифруются средствами Windows (safeStorage).
const { app, safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');

const SECRET_KEYS = new Set(['token', 'refresh', 'aiKey']);
let state = null;

const dir = () => app.getPath('userData');
const stateFile = () => path.join(dir(), 'state.json');
const weeksFile = () => path.join(dir(), 'weeks.json');

function load() {
  if (state) return state;
  try {
    state = JSON.parse(fs.readFileSync(stateFile(), 'utf8'));
  } catch {
    state = {};
  }
  return state;
}

function writeAtomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, file);
}

function save() {
  writeAtomic(stateFile(), JSON.stringify(state, null, 1));
}

function encrypt(v) {
  if (!v) return '';
  if (safeStorage.isEncryptionAvailable()) return 'enc:' + safeStorage.encryptString(v).toString('base64');
  return 'raw:' + v;
}

function decrypt(v) {
  if (!v) return '';
  try {
    if (v.startsWith('enc:')) return safeStorage.decryptString(Buffer.from(v.slice(4), 'base64'));
    if (v.startsWith('raw:')) return v.slice(4);
  } catch {
    return '';
  }
  return '';
}

function get(key, def = '') {
  const s = load();
  if (!(key in s)) return def;
  return SECRET_KEYS.has(key) ? decrypt(s[key]) : s[key];
}

function set(values) {
  const s = load();
  for (const [k, v] of Object.entries(values)) {
    if (v === undefined || v === null) delete s[k];
    else s[k] = SECRET_KEYS.has(k) ? encrypt(v) : v;
  }
  save();
}

function readWeeks() {
  try {
    return JSON.parse(fs.readFileSync(weeksFile(), 'utf8'));
  } catch {
    return null;
  }
}

function writeWeeks(arr) {
  writeAtomic(weeksFile(), JSON.stringify(arr));
}

function clearBilim() {
  set({ token: null, refresh: null, school: null, lastSync: null, needLogin: null });
  try {
    fs.unlinkSync(weeksFile());
  } catch {
    // файла могло и не быть
  }
}

module.exports = { get, set, readWeeks, writeWeeks, clearBilim };
