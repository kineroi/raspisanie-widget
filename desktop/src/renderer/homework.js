'use strict';
/* global Logic */
const L = Logic;
const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const date = params.get('date');
const start = params.get('start');
const MAX_FILE_BYTES = 20 * 1024 * 1024;

let lesson = null;
let targets = [];
let added = []; // File[]
let busy = false;
let canSend = false;

function status(text, isError) {
  $('status').textContent = text || '';
  $('status').className = isError ? 'error' : 'muted';
}

function setBusy(b, text) {
  busy = b;
  $('expand').disabled = b;
  $('send').disabled = b || !canSend;
  if (text) status(text);
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function fileRow(name, action, cls, onClick) {
  const row = el('div', 'file');
  row.append(el('span', '', name));
  const b = el('button', cls, action);
  b.addEventListener('click', onClick);
  row.append(b);
  return row;
}

function renderExisting() {
  const box = $('existing');
  box.textContent = '';
  for (const d of targets) {
    for (const f of d.files || []) {
      box.append(fileRow(`📄 ${targets.length > 1 ? d.cls + ': ' : ''}${f.name} (уже в BilimClass)`, 'Удалить', 'danger',
        async () => {
          if (!confirm(`Удалить файл «${f.name}» из домашки ${d.cls} в BilimClass?`)) return;
          setBusy(true, 'Удаляю файл…');
          const r = await window.api.deleteFile(date, f.id);
          setBusy(false);
          if (r.ok) {
            d.files = d.files.filter((x) => x.id !== f.id);
            renderExisting();
            status('Файл удалён.');
          } else {
            status(r.error, true);
          }
        }));
    }
  }
}

function renderAdded() {
  const box = $('added');
  box.textContent = '';
  added.forEach((f, i) => {
    box.append(fileRow(`➕ ${f.name} (${(f.size / 1024 / 1024).toFixed(2)} МБ)`, '✕', '', () => {
      added.splice(i, 1);
      renderAdded();
    }));
  });
}

function addFiles(list) {
  for (const f of list) {
    if (f.size > MAX_FILE_BYTES) {
      status(`Файл «${f.name}» больше 20 МБ — не добавлен.`, true);
      continue;
    }
    if (!added.some((x) => x.name === f.name && x.size === f.size)) added.push(f);
  }
  renderAdded();
}

async function init() {
  const data = await window.api.getData();
  const day = L.makeSource(data.days)(L.parseIso(date));
  lesson = day.lessons.find((l) => l.start === start);
  if (!lesson) {
    document.body.textContent = 'Урок не найден. Обновите расписание.';
    return;
  }
  const d = L.parseIso(date);
  $('head').textContent = `${L.DAY_NAMES[L.dow(d)]}, ${L.dateText(d)} · ${L.range(lesson)} · ${lesson.title}`;
  document.title = `ДЗ · ${lesson.title} · ${L.dateText(d)}`;
  $('themes').textContent = lesson.details.filter((x) => x.theme)
    .map((x) => (lesson.details.length > 1 ? `${x.cls}: ` : 'Тема: ') + x.theme).join('\n');
  $('themes').style.whiteSpace = 'pre-line';

  targets = lesson.details.filter((x) => x.scheduleUuid).map((x) => ({ ...x, files: [...(x.files || [])] }));
  canSend = data.connected && targets.length > 0;
  if (!canSend) {
    $('warn').hidden = false;
    $('warn').textContent = data.connected
      ? 'Этого урока нет в BilimClass — отправить не получится.'
      : 'BilimClass не подключён — текст можно расписать, но отправить не получится. Подключите BilimClass в основном окне.';
  }
  $('send').disabled = !canSend;

  if (targets.length > 1) {
    $('classesBox').hidden = false;
    for (const t of targets) {
      const label = el('label');
      const cb = el('input');
      cb.type = 'checkbox';
      cb.checked = true;
      cb.dataset.uuid = t.scheduleUuid;
      cb.dataset.cls = t.cls;
      label.append(cb, ` ${t.cls}`);
      $('classes').append(label);
    }
  }
  $('noKey').hidden = !!data.aiKeyTail;
  $('aiLabel').hidden = !data.aiKeyTail;
  $('aiLabel').textContent = `Расписывает: ${data.aiLabel}`;
  const current = lesson.details.find((x) => x.homework);
  $('result').value = current ? current.homework : '';
  renderExisting();
  renderQuick(data.days, await window.api.homeworkHistory());
  $('note').focus();
}

/** Кнопки «как в прошлый раз» и частые задания: нажатие вставляет текст в поле для BilimClass. */
function renderQuick(days, history) {
  const s = L.homeworkSuggestions(days, history, lesson, date);
  const box = $('quick');
  box.textContent = '';
  const use = (text, what) => {
    $('result').value = text;
    status(`${what} — проверьте текст и отправьте.`);
    $('result').focus();
  };
  if (s.last) {
    const when = L.dateText(L.parseIso(s.last.date));
    const b = el('button', 'last', `↺ Как в прошлый раз (${s.last.cls}, ${when})`);
    b.title = s.last.text;
    b.addEventListener('click', () => use(s.last.text, `Вставлена домашка ${s.last.cls} от ${when}`));
    box.append(b);
  }
  for (const text of s.frequent) {
    if (s.last && text === s.last.text) continue;
    const b = el('button', '', text.split('\n')[0].slice(0, 60));
    b.title = text;
    b.addEventListener('click', () => use(text, 'Вставлено частое задание'));
    box.append(b);
  }
  $('quickBox').hidden = !box.children.length;
}

$('expand').addEventListener('click', async () => {
  const note = $('note').value.trim();
  if (!note) {
    status('Напишите кратко, что задать.', true);
    $('note').focus();
    return;
  }
  setBusy(true, 'Расписываю задание…');
  const theme = (lesson.details[0] && lesson.details[0].theme) || '';
  const r = await window.api.expand(note, lesson.title, theme);
  setBusy(false);
  $('result').value = r.text;
  status(r.message, !r.ok);
});

$('drop').addEventListener('click', () => $('picker').click());
$('picker').addEventListener('change', (e) => {
  addFiles(e.target.files);
  e.target.value = '';
});
for (const ev of ['dragenter', 'dragover']) {
  $('drop').addEventListener(ev, (e) => { e.preventDefault(); $('drop').classList.add('over'); });
}
for (const ev of ['dragleave', 'drop']) {
  $('drop').addEventListener(ev, (e) => { e.preventDefault(); $('drop').classList.remove('over'); });
}
$('drop').addEventListener('drop', (e) => addFiles(e.dataTransfer.files));
// чтобы случайно брошенный мимо файл не открылся в окне
window.addEventListener('dragover', (e) => e.preventDefault());
window.addEventListener('drop', (e) => e.preventDefault());

$('close').addEventListener('click', () => window.close());

$('send').addEventListener('click', async () => {
  if (busy) return;
  const body = $('result').value.trim();
  if (!body) {
    status('Текст домашки пустой.', true);
    $('result').focus();
    return;
  }
  let sel = targets.map((t) => ({ uuid: t.scheduleUuid, cls: t.cls }));
  if (targets.length > 1) {
    sel = [...document.querySelectorAll('#classes input:checked')].map((cb) => ({ uuid: cb.dataset.uuid, cls: cb.dataset.cls }));
  }
  if (!sel.length) {
    status('Отметьте хотя бы один класс.', true);
    return;
  }
  const who = sel.map((s) => s.cls).join(', ');
  const ask = `Отправить домашку в BilimClass для ${who}?\n\nЕё сразу увидят ученики и родители.` +
    (added.length ? `\nФайлов: ${added.length}` : '');
  if (!confirm(ask)) return;

  setBusy(true, 'Отправляю…');
  const files = [];
  for (const f of added) {
    files.push({ name: f.name, mime: f.type, data: new Uint8Array(await f.arrayBuffer()) });
  }
  const r = await window.api.sendHomework({ date, start, uuids: sel, body, files });
  setBusy(false);
  if (r.ok) {
    status('Домашка отправлена в BilimClass ✓');
    added = [];
    renderAdded();
    setTimeout(() => window.close(), 1200);
  } else {
    status(r.done && r.done.length ? `Отправлено: ${r.done.join(', ')}. Остальным — ошибка: ${r.error}` : r.error, true);
  }
});

window.addEventListener('beforeunload', (e) => {
  if (busy) e.returnValue = false;
});

init();
