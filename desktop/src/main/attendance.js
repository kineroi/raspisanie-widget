'use strict';
// Журнал посещаемости: хранится только на этом компьютере (%APPDATA%\Моё расписание\attendance.json).
// ФИО учеников никуда не отправляются — ни в нейросеть, ни в BilimClass.
const { app } = require('electron');
const fs = require('fs');
const path = require('path');
const JSZip = require('jszip');
const T = require('../shared/attendance');

let cache = null;

const file = () => path.join(app.getPath('userData'), 'attendance.json');

function load() {
  if (cache) return cache;
  try {
    cache = T.normalize(JSON.parse(fs.readFileSync(file(), 'utf8')));
  } catch {
    cache = T.empty();
  }
  return cache;
}

function save(j) {
  cache = T.normalize(j);
  fs.mkdirSync(path.dirname(file()), { recursive: true });
  const tmp = file() + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(cache), 'utf8');
  fs.renameSync(tmp, file());
  return cache;
}

/** Классы и ученики из Word-шаблона (таблицы «№ | ФИО | дни»). */
async function importDocx(docx) {
  const zip = await JSZip.loadAsync(fs.readFileSync(docx));
  const doc = zip.file('word/document.xml');
  if (!doc) return { ok: false, message: 'Это не документ Word (.docx).' };
  const parsed = T.parseTemplate(await doc.async('string'));
  if (!parsed.length) return { ok: false, message: 'В документе не нашлось таблиц со столбцами «№» и «ФИО».' };
  const j = load();
  const added = T.importClasses(j, parsed);
  save(j);
  return { ok: true, journal: j, ...added };
}

async function exportDocx(month, dest) {
  const zip = new JSZip();
  for (const [name, text] of Object.entries(T.docxFiles(load(), month))) zip.file(name, text);
  fs.writeFileSync(dest, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
  return dest;
}

/** Предупреждения для главного окна: текст и вид. */
function alerts() {
  const today = new Date();
  const iso = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`;
  return T.alerts(load(), iso).map((a) => ({ kind: a.kind, text: T.alertText(a) }));
}

module.exports = { load, save, importDocx, exportDocx, alerts };
