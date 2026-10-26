'use strict';
// Настройки и данные программы в папке пользователя (%APPDATA%\Моё расписание).
// Пропуск BilimClass и ключ нейросети шифруются средствами Windows (safeStorage).
const { app, safeStorage } = require('electron');
const fs = require('fs');
const path = require('path');

const SECRET_KEYS = new Set(['token', 'refresh', 'aiKey', 'chatToken']);
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

// Оценки учеников — только в зашифрованном виде
const gradesFile = () => path.join(dir(), 'grades.bin');

function readGrades() {
  try {
    const raw = fs.readFileSync(gradesFile());
    const text = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(raw) : raw.toString('utf8');
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function writeGrades(obj) {
  const text = JSON.stringify(obj);
  const data = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(text) : Buffer.from(text, 'utf8');
  fs.mkdirSync(dir(), { recursive: true });
  const tmp = gradesFile() + '.tmp';
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, gradesFile());
}

function clearBilim() {
  try {
    fs.unlinkSync(gradesFile());
  } catch {
    // файла могло и не быть
  }
  set({ token: null, refresh: null, school: null, lastSync: null, needLogin: null, chatToken: null, userId: null, gradesAt: null });
  try {
    fs.unlinkSync(weeksFile());
  } catch {
    // файла могло и не быть
  }
}

module.exports = { get, set, readWeeks, writeWeeks, readGrades, writeGrades, clearBilim };
