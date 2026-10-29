'use strict';
// Аттестация: нейросеть определяет вид документа, программа кладёт копию в папку «Аттестация»
// на Google Диске (через программу «Google Диск для компьютера» — это обычная папка, например G:\Мой диск).
const fs = require('fs');
const path = require('path');
const store = require('./store');
const ai = require('./ai');
const { docxText } = require('./analysis');
const T = require('../shared/attestation');
const AI = require('../shared/ai');

const MAX_FILE_BYTES = 15 * 1024 * 1024; // больше нейросеть в одном запросе не примет
const MIME = { '.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp' };

/** Где Google Диск: диск с папкой «Мой диск» (или «My Drive»), чаще всего G:. */
function findGoogleDrive() {
  const letters = 'GHIJKLMNOPQRSTUVWXYZDEF'.split('');
  for (const l of letters) {
    for (const name of ['Мой диск', 'My Drive']) {
      const p = `${l}:\\${name}`;
      try {
        if (fs.statSync(p).isDirectory()) return p;
      } catch (e) {
        // такого диска или папки нет — ищем дальше
      }
    }
  }
  return '';
}

/** Папка «Аттестация»: выбранная вручную, иначе на найденном Google Диске. */
function root() {
  const saved = store.get('attRoot', '');
  if (saved) return saved;
  const drive = findGoogleDrive();
  return drive ? path.join(drive, T.ROOT_FOLDER) : '';
}

function settings() {
  return { root: root(), drive: findGoogleDrive(), sections: T.SECTIONS, levels: T.LEVELS };
}

function setRoot(folder) {
  store.set({ attRoot: String(folder || '') || null });
  return settings();
}

/** Определить вид документа. Возвращает { ok, meta } или { ok: false, message }. */
async function classify(file) {
  const cfg = {
    key: store.get('aiKey'), provider: store.get('aiProvider', ''), base: store.get('aiBase', ''), model: store.get('aiModel', ''),
  };
  if (!cfg.key) return { ok: false, message: 'Нужен ключ нейросети (Настройки → Нейросеть). Пока заполните поля вручную.' };
  const p = AI.resolve(cfg);
  const ext = path.extname(file).toLowerCase();
  const name = path.basename(file);
  let text = `Документ «${name}» во вложении.`;
  const files = [];
  try {
    if (ext === '.docx') {
      text = `Документ «${name}»:\n${await docxText(fs.readFileSync(file))}`;
    } else if (MIME[ext]) {
      if (fs.statSync(file).size > MAX_FILE_BYTES) return { ok: false, message: `Файл больше 15 МБ — нейросеть его не примет. Заполните поля вручную.` };
      files.push({ mime: MIME[ext], data: fs.readFileSync(file).toString('base64') });
    } else {
      return { ok: false, message: 'Распознать можно PDF, JPG, PNG и docx. Заполните поля вручную.' };
    }
  } catch (e) {
    return { ok: false, message: `Не удалось прочитать файл: ${e.message}` };
  }
  try {
    const out = await ai.completeJson(cfg, { system: T.SYSTEM, text, files });
    return { ok: true, meta: T.parseClassification(out), by: p.name };
  } catch (e) {
    if (e && e.code === 'pdf') return { ok: false, message: `${p.name} не читает PDF. Подключите ключ Google Gemini или добавьте фото документа.` };
    return { ok: false, message: ai.explain(e, p).replace(' Текст расписан без ИИ.', ' Заполните поля вручную.') };
  }
}

/** Скопировать документ в нужную папку. Оригинал остаётся на месте. */
function save(file, meta) {
  const base = root();
  if (!base) return { ok: false, message: 'Не найдена папка Google Диска. Укажите папку «Аттестация» вручную.' };
  try {
    const t = T.target(T.parseClassification(meta), path.extname(file));
    const dir = path.join(base, ...t.parts);
    fs.mkdirSync(dir, { recursive: true });
    const name = T.uniqueName(t.name, (n) => fs.existsSync(path.join(dir, n)));
    const dest = path.join(dir, name);
    fs.copyFileSync(file, dest, fs.constants.COPYFILE_EXCL);
    return { ok: true, path: dest };
  } catch (e) {
    return { ok: false, message: `Не удалось сохранить: ${e.message}` };
  }
}

module.exports = { settings, setRoot, classify, save, root };
