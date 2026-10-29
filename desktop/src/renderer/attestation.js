'use strict';
/* global Attestation */
const T = Attestation;
const $ = (id) => document.getElementById(id);

let settings = null;
/** Добавленные документы: { file, meta, state: 'wait'|'busy'|'ready'|'saved', message, saved } */
const docs = [];
let queue = Promise.resolve();

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

const baseName = (p) => String(p).split(/[\\/]/).pop();
const extOf = (p) => { const m = /\.[^.\\/]+$/.exec(String(p)); return m ? m[0] : ''; };

function renderRoot() {
  $('root').textContent = settings.root || 'не найдена';
  $('rootHint').textContent = !settings.root
    ? 'Google Диск для компьютера не найден. Установите его или укажите папку вручную («Изменить…»).'
    : settings.drive && settings.root.startsWith(settings.drive)
      ? 'Это Google Диск: всё, что попадёт в папку, само загрузится в облако.'
      : 'Папка выбрана вручную.';
  $('openRoot').disabled = !settings.root;
}

function emptyMeta() {
  return { section: 'other', level: '', date: '', kind: '', title: '', issuer: '' };
}

function field(label, input) {
  const l = el('label', '', label);
  l.append(input);
  return l;
}

function select(options, value, onChange) {
  const s = el('select');
  for (const [v, text] of options) {
    const o = el('option', '', text);
    o.value = v;
    s.append(o);
  }
  s.value = value;
  s.addEventListener('change', () => onChange(s.value));
  return s;
}

function input(type, value, onInput) {
  const i = el('input');
  i.type = type;
  i.value = value || '';
  i.addEventListener('input', () => onInput(i.value));
  return i;
}

function destText(d) {
  const t = T.target(T.parseClassification(d.meta), extOf(d.file));
  return `→ ${[T.ROOT_FOLDER, ...t.parts, t.name].join(' / ')}`;
}

function renderDoc(d) {
  const card = d.card || (d.card = el('div', 'card doc'));
  card.textContent = '';
  card.className = 'card doc' + (d.state === 'saved' ? ' saved' : '');
  card.append(el('h2', '', baseName(d.file)));
  const state = el('div', 'state ' + (d.error ? 'error' : d.state === 'saved' ? 'ok' : 'muted'), d.message || '');
  card.append(state);
  if (d.state === 'wait' || d.state === 'busy') return card;

  if (d.state === 'saved') {
    const row = el('div', 'row');
    const show = el('button', '', 'Показать в папке');
    show.addEventListener('click', () => window.api.attShow(d.saved));
    row.append(show);
    card.append(el('div', 'dest', d.saved), row);
    return card;
  }

  const section = T.sectionById(d.meta.section);
  const dest = el('div', 'dest', destText(d));
  const update = () => { dest.textContent = destText(d); };
  const grid = el('div', 'grid');
  grid.append(field('Раздел', select(T.SECTIONS.map((s) => [s.id, s.name]), section.id, (v) => {
    d.meta.section = v;
    renderDoc(d);
  })));
  if (section.levels) {
    grid.append(field('Уровень', select([['', 'Не указан'], ...T.LEVELS.map((l) => [l.id, l.name])], d.meta.level, (v) => {
      d.meta.level = v;
      update();
    })));
  } else {
    grid.append(el('div'));
  }
  grid.append(field('Вид документа', input('text', d.meta.kind, (v) => { d.meta.kind = v; update(); })));
  grid.append(field('Дата выдачи', input('date', d.meta.date, (v) => { d.meta.date = v; update(); })));
  const title = field('За что / мероприятие', input('text', d.meta.title, (v) => { d.meta.title = v; update(); }));
  title.style.gridColumn = '1 / -1';
  grid.append(title);
  card.append(grid);
  if (d.meta.issuer) card.append(el('div', 'small muted', `Выдал: ${d.meta.issuer}`));
  card.append(dest);

  const row = el('div', 'row');
  const save = el('button', 'primary', 'Сохранить');
  save.disabled = !settings.root;
  save.addEventListener('click', () => saveDoc(d));
  const remove = el('button', '', 'Убрать');
  remove.addEventListener('click', () => {
    docs.splice(docs.indexOf(d), 1);
    card.remove();
    refreshSaveAll();
  });
  row.append(save, remove);
  card.append(row);
  return card;
}

function refreshSaveAll() {
  $('saveAll').disabled = !settings.root || !docs.some((d) => d.state === 'ready');
}

async function saveDoc(d) {
  const r = await window.api.attSave(d.file, d.meta);
  if (r.ok) {
    d.state = 'saved';
    d.saved = r.path;
    d.error = false;
    d.message = 'Сохранено ✓';
  } else {
    d.error = true;
    d.message = r.message;
  }
  renderDoc(d);
  refreshSaveAll();
}

/** Распознаём по одному, чтобы не упереться в лимит нейросети. */
function addFiles(paths) {
  for (const file of paths) {
    if (!file || docs.some((d) => d.file === file)) continue;
    const ext = extOf(file).slice(1).toLowerCase();
    const d = { file, meta: emptyMeta(), state: 'wait', message: 'Ждёт распознавания…' };
    if (!T.EXTENSIONS.includes(ext)) {
      d.state = 'ready';
      d.error = true;
      d.message = 'Этот вид файла нейросеть не читает — заполните поля вручную.';
    }
    docs.push(d);
    $('docs').prepend(renderDoc(d));
    if (d.state === 'ready') continue;
    queue = queue.then(async () => {
      d.state = 'busy';
      d.message = 'Нейросеть читает документ…';
      renderDoc(d);
      const r = await window.api.attClassify(file);
      d.state = 'ready';
      if (r.ok) {
        d.meta = r.meta;
        d.error = false;
        d.message = `Распознано (${r.by}). Проверьте поля и сохраните.`;
      } else {
        d.error = true;
        d.message = r.message;
      }
      renderDoc(d);
      refreshSaveAll();
    });
  }
}

$('drop').addEventListener('click', async () => addFiles(await window.api.attPickFiles()));
for (const ev of ['dragenter', 'dragover']) {
  $('drop').addEventListener(ev, (e) => { e.preventDefault(); $('drop').classList.add('over'); });
}
for (const ev of ['dragleave', 'drop']) {
  $('drop').addEventListener(ev, (e) => { e.preventDefault(); $('drop').classList.remove('over'); });
}
$('drop').addEventListener('drop', (e) => addFiles([...e.dataTransfer.files].map((f) => window.api.pathForFile(f))));
// чтобы случайно брошенный мимо файл не открылся в окне
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => e.preventDefault());

$('saveAll').addEventListener('click', async () => {
  for (const d of docs.filter((x) => x.state === 'ready')) await saveDoc(d);
});
$('openRoot').addEventListener('click', () => window.api.attOpenRoot());
$('chooseRoot').addEventListener('click', async () => {
  settings = await window.api.attChooseRoot();
  renderRoot();
  docs.forEach(renderDoc);
  refreshSaveAll();
});

window.api.attSettings().then((s) => {
  settings = s;
  renderRoot();
});
