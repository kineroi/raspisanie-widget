'use strict';
// Журнал посещаемости: копия на этом компьютере (%APPDATA%\Моё расписание\attendance.json)
// и общий файл на Google Диске учителя («Мой диск\Моё расписание\посещаемость.json»), через который
// компьютер и телефон обмениваются отметками. ФИО учеников не отправляются ни в нейросеть, ни в BilimClass.
const { app } = require('electron');
const fs = require('fs');
const path = require('path');
const JSZip = require('jszip');
const T = require('../shared/attendance');
const { findGoogleDrive } = require('./attestation');

const SYNC_FOLDER = 'Моё расписание';
const SYNC_FILE = 'посещаемость.json';

let cache = null;
/** Как прошла последняя синхронизация: { state: 'ok'|'nodrive'|'error'|'never', at, message }. */
let status = { state: 'never', at: 0, message: '' };
let syncing = null;

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

function writeAtomic(dest, text) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  const tmp = dest + '.tmp';
  fs.writeFileSync(tmp, text, 'utf8');
  fs.renameSync(tmp, dest);
}

function writeLocal(j) {
  cache = T.normalize(j);
  writeAtomic(file(), JSON.stringify(cache));
  return cache;
}

/** Сохранить изменения из окна: объединяем, а не перезаписываем — вдруг уже пришли отметки с телефона. */
function save(j) {
  return writeLocal(T.merge(load(), j));
}

function remoteFile() {
  const drive = findGoogleDrive();
  return drive ? path.join(drive, SYNC_FOLDER, SYNC_FILE) : '';
}

/**
 * Обменяться отметками с телефоном через файл на Google Диске.
 * Возвращает true, если в журнал на компьютере пришло что-то новое.
 */
function sync() {
  if (!syncing) {
    syncing = runSync().finally(() => {
      syncing = null;
    });
  }
  return syncing;
}

async function runSync() {
  const remote = remoteFile();
  if (!remote) {
    status = { state: 'nodrive', at: Date.now(), message: 'Google Диск для компьютера не найден — журнал только на этом компьютере.' };
    return false;
  }
  try {
    let theirs = null;
    try {
      theirs = JSON.parse(await fs.promises.readFile(remote, 'utf8'));
    } catch (err) {
      // файла ещё нет — создадим; файл есть, но не читается (Диск как раз его обновляет) — попробуем позже
      if (err.code !== 'ENOENT') throw err;
    }
    const mine = load();
    const merged = T.merge(mine, theirs || T.empty());
    const changed = !T.same(merged, mine);
    if (changed) writeLocal(merged);
    if (!theirs || !T.same(merged, theirs)) writeAtomic(remote, JSON.stringify(merged));
    status = { state: 'ok', at: Date.now(), message: '' };
    return changed;
  } catch (err) {
    status = { state: 'error', at: Date.now(), message: `Не удалось синхронизировать с Google Диском: ${err.message}` };
    return false;
  }
}

const syncStatus = () => ({ ...status, file: remoteFile() });

/** Классы и ученики из Word-шаблона (таблицы «№ | ФИО | дни»). */
async function importDocx(docx) {
  const zip = await JSZip.loadAsync(fs.readFileSync(docx));
  const doc = zip.file('word/document.xml');
  if (!doc) return { ok: false, message: 'Это не документ Word (.docx).' };
  const parsed = T.parseTemplate(await doc.async('string'));
  if (!parsed.length) return { ok: false, message: 'В документе не нашлось таблиц со столбцами «№» и «ФИО».' };
  const j = load();
  const added = T.importClasses(j, parsed);
  writeLocal(j);
  return { ok: true, journal: j, ...added };
}

async function exportDocx(month, dest) {
  const zip = new JSZip();
  for (const [name, text] of Object.entries(T.docxFiles(load(), month))) zip.file(name, text);
  fs.writeFileSync(dest, await zip.generateAsync({ type: 'nodebuffer', compression: 'DEFLATE' }));
  return dest;
}

function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

/** Предупреждения для главного окна: текст и вид. */
function alerts() {
  return T.alerts(load(), todayIso()).map((a) => ({ kind: a.kind, text: T.alertText(a) }));
}

const hasClasses = () => T.sortedClasses(load()).length > 0;

module.exports = { load, save, sync, syncStatus, importDocx, exportDocx, alerts, hasClasses };
