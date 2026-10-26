'use strict';
/* global Analysis */
const A = Analysis;
const $ = (id) => document.getElementById(id);

let data = null; // { quarter, classes }
const texts = {}; // cls → { items: {label: {...}}, difficulties }
const files = {}; // `${cls}|${label}` → [пути]
const saveTimers = {};

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function button(label, cls, onClick) {
  const b = el('button', cls, label);
  b.addEventListener('click', onClick);
  return b;
}

function status(text, error) {
  $('status').textContent = text || '';
  $('status').className = error ? 'error small' : 'muted small';
}

/** Четверть по дате: сентябрь–октябрь — 1, ноябрь–декабрь — 2, январь–март — 3, апрель–июнь — 4. */
function guessQuarter() {
  const m = new Date().getMonth() + 1;
  if (m >= 9 && m <= 10) return 1;
  if (m >= 11) return 2;
  if (m <= 3) return 3;
  return 4;
}

const quarter = () => Number($('quarter').value);

function persist(cls) {
  clearTimeout(saveTimers[cls]);
  saveTimers[cls] = setTimeout(() => window.api.anSetTexts(cls, quarter(), texts[cls]), 500);
}

function textarea(value, rows, placeholder, onInput) {
  const t = el('textarea');
  t.rows = rows;
  t.placeholder = placeholder || '';
  t.value = value || '';
  t.addEventListener('input', () => onInput(t.value));
  return t;
}

// ---------- Настройки ----------

async function loadSettings() {
  const s = await window.api.anSettings();
  $('school').value = s.school;
  $('subject').value = s.subject;
  $('reasons').value = s.reasons;
  $('plan').value = s.plan;
  $('folder').textContent = s.folder;
}

for (const id of ['school', 'subject', 'reasons', 'plan']) {
  $(id).addEventListener('change', () => window.api.anSaveSettings({ [id]: $(id).value }));
}
$('openFolder').addEventListener('click', async () => {
  const s = await window.api.anSettings();
  window.api.anOpen(s.folder, false);
});

// ---------- Класс ----------

function statsTable(a) {
  const t = el('table');
  const head = el('tr');
  for (const h of ['Работа', 'Писали', 'Макс. балл', 'Низкий', 'Средний', 'Высокий', '% качества', '% успеваемости']) head.append(el('th', '', h));
  t.append(head);
  for (const it of a.items) {
    const tr = el('tr');
    const date = it.date ? ` · ${it.date.slice(8)}.${it.date.slice(5, 7)}` : '';
    for (const v of [`${it.label}${date}`, it.wrote, it.max, it.low, it.mid, it.high, `${it.quality}%`, `${it.success}%`]) tr.append(el('td', '', String(v)));
    t.append(tr);
  }
  return t;
}

function workBlock(a, it, refresh) {
  const cls = a.cls;
  const t = texts[cls].items[it.label] || (texts[cls].items[it.label] = {});
  const box = el('div', 'work');
  const head = el('div', 'row');
  head.style.justifyContent = 'space-between';
  head.append(el('h3', '', `${it.label}${t.unit ? ` «${t.unit}»` : ''}`));
  const key = `${cls}|${it.label}`;
  const picked = files[key] || [];
  const fileLine = el('div', 'files', picked.length
    ? `Работа: ${picked.map((p) => p.split(/[\\/]/).pop()).join(', ')}`
    : t.files && t.files.length ? `Тексты подготовлены по: ${t.files.join(', ')}` : 'Работа не прикреплена');
  const msg = el('div', 'small');
  const prep = button('✨ Подготовить тексты', 'primary', async () => {
    prep.disabled = true;
    msg.className = 'small muted';
    msg.textContent = 'Нейросеть читает работу… (до минуты)';
    const r = await window.api.anPrepare(cls, quarter(), it.label, files[key] || []);
    prep.disabled = false;
    if (!r.ok) {
      msg.className = 'error small';
      msg.textContent = r.message;
      return;
    }
    texts[cls].items[it.label] = r.texts;
    refresh(`Готово (${r.by}). Проверьте тексты.`);
  });
  prep.disabled = !picked.length;
  const attach = button('📎 Прикрепить работу', '', async () => {
    const paths = await window.api.anPickFiles();
    if (!paths.length) return;
    files[key] = paths;
    refresh();
  });
  const btns = el('div', 'row');
  btns.append(attach, prep);
  head.append(btns);
  box.append(head, fileLine, msg);

  const grid = el('div', 'grid2');
  const left = el('div');
  left.append(el('div', 'label', 'Достигнутые цели'),
    textarea(t.achieved, 4, 'Критерий (код цели), каждый с новой строки', (v) => { t.achieved = v; persist(cls); }));
  const right = el('div');
  right.append(el('div', 'label', 'Цели, вызвавшие затруднения'),
    textarea(t.difficult, 4, A.NO_DIFFICULTIES, (v) => { t.difficult = v; persist(cls); }));
  grid.append(left, right);
  box.append(grid);
  box.append(el('div', 'label', 'Затруднения для пункта 2'),
    textarea(t.difficulties, 2, 'В чём ошибаются ученики в этой работе', (v) => { t.difficulties = v; persist(cls); }));
  return box;
}

function classCard(a) {
  const card = el('div', 'card');
  const render = (note) => {
    card.textContent = '';
    const kind = a.mode === 'po' ? 'промежуточное оценивание' : 'СОР и СОЧ';
    const h = el('h2', '', `${a.cls}`);
    h.append(el('span', 'muted', `${kind} · учеников: ${a.students}`));
    card.append(h, statsTable(a));
    if (note) card.append(el('div', 'small ok', note));
    for (const it of a.items) card.append(workBlock(a, it, render));

    const t = texts[a.cls];
    card.append(el('div', 'label', '2. Перечень затруднений (для справки)'));
    const area = textarea(t.difficulties, 4, 'Соберите из работ или напишите сами', (v) => { t.difficulties = v; persist(a.cls); });
    card.append(area);
    const row = el('div', 'row');
    row.style.marginTop = '8px';
    row.append(button('Собрать из работ', '', () => {
      t.difficulties = A.difficultiesText(a.items, t.items);
      area.value = t.difficulties;
      persist(a.cls);
    }));
    const saved = el('div', 'saved');
    const save = button('💾 Сохранить Excel', 'primary', async () => {
      save.disabled = true;
      const r = await saveClass(a);
      save.disabled = false;
      showSaved(saved, r);
    });
    row.append(save);
    card.append(row, saved);
  };
  render();
  return card;
}

function showSaved(box, r) {
  box.textContent = '';
  if (!r.ok) {
    box.append(el('span', 'error', r.message));
    return;
  }
  box.append(el('span', 'ok', `Сохранено: ${r.file}`));
  const open = el('a', '', 'Открыть');
  open.href = '#';
  open.addEventListener('click', (e) => { e.preventDefault(); window.api.anOpen(r.file, false); });
  const show = el('a', '', 'Показать в папке');
  show.href = '#';
  show.addEventListener('click', (e) => { e.preventDefault(); window.api.anOpen(r.file, true); });
  box.append(open, show);
}

async function saveClass(a) {
  clearTimeout(saveTimers[a.cls]);
  await window.api.anSaveSettings({
    school: $('school').value, subject: $('subject').value, reasons: $('reasons').value, plan: $('plan').value,
  });
  return window.api.anSave(a.cls, quarter(), texts[a.cls]);
}

// ---------- Загрузка ----------

async function load() {
  $('load').disabled = true;
  status('Загружаю журналы из BilimClass…');
  const r = await window.api.anLoad(quarter());
  $('load').disabled = false;
  const box = $('classes');
  box.textContent = '';
  if (!r.ok) {
    status(r.message, true);
    $('saveAll').disabled = true;
    return;
  }
  data = r;
  await loadSettings();
  if (!r.classes.length) {
    status(`За ${r.quarter} четверть в журналах нет оценок за СОР, СОЧ или ПО.`, true);
    $('saveAll').disabled = true;
    return;
  }
  status(`${r.quarter} четверть · классов: ${r.classes.length}. Прикрепите работы, подготовьте тексты, проверьте и сохраните.`);
  for (const a of r.classes) {
    texts[a.cls] = await window.api.anTexts(a.cls, r.quarter);
    if (!texts[a.cls].items) texts[a.cls].items = {};
    box.append(classCard(a));
  }
  $('saveAll').disabled = false;
}

$('load').addEventListener('click', load);
$('quarter').addEventListener('change', () => {
  $('classes').textContent = '';
  $('saveAll').disabled = true;
  status('Нажмите «Загрузить из BilimClass».');
});
$('saveAll').addEventListener('click', async () => {
  if (!data) return;
  $('saveAll').disabled = true;
  const done = [];
  const failed = [];
  for (const a of data.classes) {
    const r = await saveClass(a);
    if (r.ok) done.push(a.cls);
    else failed.push(`${a.cls}: ${r.message}`);
  }
  $('saveAll').disabled = false;
  if (failed.length) status(`Сохранено: ${done.join(', ') || 'ничего'}. Ошибки — ${failed.join('; ')}`, true);
  else status(`Сохранено ${done.length} справок в папку ${(await window.api.anSettings()).folder}`);
});

$('quarter').value = String(guessQuarter());
loadSettings();
load();
