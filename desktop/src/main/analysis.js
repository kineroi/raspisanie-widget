'use strict';
// Справки СОР/СОЧ и ПО: журналы за четверть из BilimClass (только чтение), тексты целей по работам
// через нейросеть (уходят только файлы работ и общие цифры, без фамилий), сохранение в Excel.
const fs = require('fs');
const path = require('path');
const { app } = require('electron');
const JSZip = require('jszip');
const store = require('./store');
const bilim = require('./bilim');
const journal = require('./journal');
const ai = require('./ai');
const xlsx = require('./analysis-xlsx');
const A = require('../shared/analysis');
const AI = require('../shared/ai');
const L = require('../shared/logic');

const MAX_FILE_BYTES = 15 * 1024 * 1024; // больше нейросеть в одном запросе не примет
const MIME = { '.pdf': 'application/pdf', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp' };

let loaded = null; // { quarter, eduYear, classes: [A.build], at }
let sample = null; // самопроверка: выдуманные журналы и временная папка

// ---------- Журналы за четверть ----------

async function load(quarter) {
  if (sample) {
    loaded = { quarter, eduYear: L.eduYear(new Date()), classes: sample.journals.map(A.build), at: Date.now() };
    return { ok: true, ...loaded };
  }
  if (!store.get('refresh')) return { ok: false, message: 'BilimClass не подключён. Войдите в главном окне.' };
  const infos = journal.discoverJournals(store.readWeeks());
  if (!infos.length) return { ok: false, message: 'Не найдено ни одного журнала. Нажмите «Обновить» в главном окне.' };
  try {
    const journals = await bilim.withToken((token, school) =>
      journal.fetchJournals(token, school, infos, (periods) => periods.find((p) => p.n === quarter) || null));
    const classes = journals.map(A.build).filter((a) => a.items.length);
    loaded = { quarter, eduYear: L.eduYear(new Date()), classes, at: Date.now() };
    return { ok: true, ...loaded };
  } catch (e) {
    return { ok: false, message: e instanceof bilim.HttpError || e.network ? bilim.errorText(e) : 'Не удалось загрузить журналы.' };
  }
}

// ---------- Настройки и сохранённые тексты ----------

const textKey = (cls, quarter) => `${loaded ? loaded.eduYear : L.eduYear(new Date())}|${quarter}|${cls}`;

function settings() {
  return {
    school: store.get('anSchool', ''),
    subject: store.get('anSubject', 'Иностранный язык'),
    reasons: store.get('anReasons', A.REASONS),
    plan: store.get('anPlan', A.PLAN),
    folder: defaultFolder(loaded ? loaded.quarter : 1),
  };
}

function saveSettings(s) {
  const v = {};
  if (s.school != null) v.anSchool = String(s.school);
  if (s.subject != null) v.anSubject = String(s.subject);
  if (s.reasons != null) v.anReasons = String(s.reasons);
  if (s.plan != null) v.anPlan = String(s.plan);
  store.set(v);
}

/** Тексты класса: { items: {label: {unit, achieved, difficult, difficulties, files}}, difficulties } */
function getTexts(cls, quarter) {
  return (store.get('anTexts', {}) || {})[textKey(cls, quarter)] || { items: {}, difficulties: '' };
}

function setTexts(cls, quarter, texts) {
  const all = store.get('anTexts', {}) || {};
  all[textKey(cls, quarter)] = texts;
  store.set({ anTexts: all });
}

// ---------- Файлы работ ----------

/** Текст из .docx (абзацы и строки таблиц). */
async function docxText(buf) {
  const zip = await JSZip.loadAsync(buf);
  const doc = zip.file('word/document.xml');
  if (!doc) return '';
  const xml = await doc.async('string');
  return xml
    .replace(/<w:tab\/>/g, '\t')
    .replace(/<\/w:tc>/g, ' | ')
    .replace(/<\/w:p>/g, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&')
    .replace(/[ \t]+\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Работы → { texts: [строки docx], files: [{mime, data}] } или ошибка с понятным текстом. */
async function readWorks(paths) {
  const texts = [];
  const files = [];
  for (const p of paths) {
    const ext = path.extname(p).toLowerCase();
    const name = path.basename(p);
    const st = fs.statSync(p);
    if (ext === '.docx') {
      texts.push(`Файл «${name}»:\n${await docxText(fs.readFileSync(p))}`);
    } else if (MIME[ext]) {
      if (st.size > MAX_FILE_BYTES) throw new Error(`Файл «${name}» больше 15 МБ — нейросеть его не примет. Сожмите PDF или прикрепите docx.`);
      files.push({ mime: MIME[ext], data: fs.readFileSync(p).toString('base64') });
    } else if (ext === '.doc') {
      throw new Error(`Файл «${name}» в старом формате .doc. Откройте его в Word и сохраните как .docx или PDF.`);
    } else {
      throw new Error(`Файл «${name}»: подходят docx, PDF, JPG, PNG.`);
    }
  }
  return { texts, files };
}

// ---------- Нейросеть ----------

const SYSTEM =
  'Ты помогаешь учителю английского языка в казахстанской школе составить анализ по итогам ' +
  'суммативного (СОР, СОЧ) или промежуточного (ПО) оценивания. Тебе дают саму работу (задания, цели обучения, ' +
  'критерии оценивания, дескрипторы) и общие итоги класса без фамилий.\n' +
  'Верни ТОЛЬКО объект JSON без пояснений:\n' +
  '{"unit": "название раздела/темы работы на английском, как в работе, без кавычек; пусто, если нет",\n' +
  ' "objectives": "цели обучения из работы: код и текст, каждая с новой строки",\n' +
  ' "achieved": "достигнутые цели: критерий оценивания на английском, как в работе, и код цели в скобках, каждая с новой строки",\n' +
  ' "difficult": "цели, вызвавшие затруднения, в том же формате",\n' +
  ' "difficulties": "1–2 предложения на русском: какие задания вызвали затруднения и в чём именно ошибаются ученики, с кодами целей в скобках"}\n' +
  'Правила:\n' +
  '1. Бери цели, коды и критерии ТОЛЬКО из работы. Не придумывай коды; если кода в работе нет, пиши без кода.\n' +
  '2. Обычно аудирование и чтение — достигнутые цели, письмо и говорение — затруднения. Учитывай итоги класса: ' +
  'чем ниже качество и больше учеников на низком и среднем уровне, тем больше целей в затруднениях.\n' +
  '3. Если все ученики на высоком уровне, в "difficult" напиши "Затруднений не выявлено", а "difficulties" оставь пустым.\n' +
  '4. В "difficulties" опирайся на конкретные задания работы (что нужно было написать, рассказать, прочитать). ' +
  'Пиши со строчной буквы после двоеточия, без названия работы в начале.\n' +
  '5. Без разметки, без **, без нумерации.';

async function prepare(cls, quarter, label, paths) {
  const a = loaded && loaded.classes.find((c) => c.cls === cls);
  const it = a && a.items.find((x) => x.label === label);
  if (!it) return { ok: false, message: 'Сначала загрузите журналы.' };
  const cfg = {
    key: store.get('aiKey'), provider: store.get('aiProvider', ''), base: store.get('aiBase', ''), model: store.get('aiModel', ''),
  };
  if (!cfg.key) return { ok: false, message: 'Нужен ключ нейросети — добавьте его в главном окне (карточка «Нейросеть»).' };
  const p = AI.resolve(cfg);
  let works;
  try {
    works = await readWorks(paths);
  } catch (e) {
    return { ok: false, message: e.message };
  }
  const kind = a.mode === 'po' ? 'промежуточное оценивание' : label.startsWith('СОЧ') ? 'суммативное оценивание за четверть' : 'суммативное оценивание за раздел';
  const text = `Работа: ${label} (${kind}), ${a.grade || a.cls} класс, ${quarter} четверть.\n` +
    `Итоги класса: ${A.statsForAi(it)}\n\n` +
    (works.texts.length ? works.texts.join('\n\n') : 'Работа — во вложенных файлах.');
  try {
    const out = await ai.completeJson(cfg, { system: SYSTEM, text, files: works.files });
    const t = {
      unit: String(out.unit || '').replace(/[«»"]/g, '').trim(),
      objectives: String(out.objectives || '').trim(),
      achieved: String(out.achieved || '').trim(),
      difficult: String(out.difficult || '').trim() || A.NO_DIFFICULTIES,
      difficulties: String(out.difficulties || '').trim(),
      files: paths.map((x) => path.basename(x)),
    };
    const texts = getTexts(cls, quarter);
    texts.items[label] = t;
    setTexts(cls, quarter, texts);
    return { ok: true, texts: t, by: p.name };
  } catch (e) {
    if (e && e.code === 'pdf') return { ok: false, message: `${p.name} не читает PDF. Прикрепите docx или фото страниц, либо подключите ключ Google Gemini.` };
    if (e && e.code === 0 && e.message === 'empty') return { ok: false, message: `${p.name} не вернул ответ. Попробуйте ещё раз.` };
    return { ok: false, message: ai.explain(e, p).replace(' Текст расписан без ИИ.', '') };
  }
}

// ---------- Excel ----------

function defaultFolder(quarter) {
  const y = loaded ? loaded.eduYear : L.eduYear(new Date());
  const root = sample ? sample.root : fs.existsSync('D:\\') ? 'D:\\' : app.getPath('documents');
  return path.join(root, `Анализы СОР и СОЧ ${y}-${y + 1} учебный год`, `${quarter} четверть`);
}

function today() {
  const d = new Date();
  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
}

async function save(cls, quarter, texts) {
  const a = loaded && loaded.classes.find((c) => c.cls === cls);
  if (!a) return { ok: false, message: 'Сначала загрузите журналы.' };
  setTexts(cls, quarter, texts);
  const s = settings();
  const dir = defaultFolder(quarter);
  const file = path.join(dir, A.fileName(a, s.subject));
  try {
    fs.mkdirSync(dir, { recursive: true });
    await xlsx.write(file, {
      analysis: a, quarter, school: s.school, teacher: a.teacher, subject: s.subject, date: today(),
      texts: texts.items || {}, difficulties: texts.difficulties || '', reasons: s.reasons, plan: s.plan,
    });
    return { ok: true, file };
  } catch (e) {
    if (e && (e.code === 'EBUSY' || e.code === 'EPERM')) return { ok: false, message: `Файл «${path.basename(file)}» открыт в Excel. Закройте его и сохраните ещё раз.` };
    return { ok: false, message: `Не удалось сохранить: ${e.message}` };
  }
}

/** Самопроверка: выдуманные журналы, справки — во временную папку. */
function useSample(journals, root) {
  sample = { journals, root };
}

module.exports = { useSample, load, settings, saveSettings, getTexts, setTexts, prepare, save, defaultFolder, docxText };
