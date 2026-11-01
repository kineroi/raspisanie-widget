'use strict';
/* global Attendance */
const T = Attendance;
const $ = (id) => document.getElementById(id);

let journal = T.empty();
let month = '';
let editing = false;
let pop = null;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const dm = (iso) => `${iso.slice(8)}.${iso.slice(5, 7)}`;
const WEEKDAYS = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб'];

async function persist() {
  journal = T.normalize(await window.api.ajSave(journal));
}

function message(text, bad) {
  $('message').textContent = text || '';
  $('message').style.color = bad ? 'var(--red)' : 'var(--green)';
}

function codeBadge(reason) {
  const r = T.reasonById(reason);
  // заглавными: в шрифте Onest строчная «б» похожа на «6»
  return el('span', `code r-${r.id}`, r.code.toUpperCase());
}

function renderMonths() {
  const box = $('months');
  box.textContent = '';
  for (const m of T.months()) {
    const b = el('button', m === month ? 'active' : '', T.monthTitle(m));
    b.addEventListener('click', () => {
      month = m;
      render();
    });
    box.append(b);
  }
}

function renderAlerts() {
  const box = $('alerts');
  box.textContent = '';
  const list = T.alerts(journal, todayIso());
  if (!list.length) return;
  const card = el('div', 'card alerts');
  card.append(el('h2', '', 'Обратите внимание'));
  const ul = el('ul');
  for (const a of list) ul.append(el('li', '', T.alertText(a)));
  card.append(ul);
  box.append(card);
}

function renderLegend() {
  const box = $('legend');
  box.textContent = '';
  for (const r of T.REASONS) {
    const s = el('span');
    s.append(codeBadge(r.id), r.name);
    box.append(s);
  }
}

// ---------- Всплывающее меню ----------

function closePop() {
  if (pop) pop.remove();
  pop = null;
}

function openPop(anchor, build) {
  closePop();
  pop = el('div', 'pop');
  build(pop);
  document.body.append(pop);
  const r = anchor.getBoundingClientRect();
  const w = pop.offsetWidth;
  const h = pop.offsetHeight;
  pop.style.left = Math.max(8, Math.min(r.left, window.innerWidth - w - 8)) + 'px';
  pop.style.top = (r.bottom + h + 8 > window.innerHeight ? Math.max(8, r.top - h - 4) : r.bottom + 4) + 'px';
}

document.addEventListener('mousedown', (e) => {
  if (pop && !pop.contains(e.target)) closePop();
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') closePop();
});

function markMenu(td, date, student) {
  openPop(td, (p) => {
    const cur = T.getMark(journal, date, student.id);
    p.append(el('div', 'head', `${student.name} · ${WEEKDAYS[new Date(date + 'T00:00:00').getDay()]}, ${dm(date)}`));
    const set = async (reason, note) => {
      T.setMark(journal, date, student.id, reason, note);
      closePop();
      await persist();
      render();
    };
    const present = el('button', '', '✓ Был на уроках');
    present.addEventListener('click', () => set(''));
    p.append(present);
    for (const r of T.REASONS.filter((x) => x.id !== 'other')) {
      const b = el('button');
      b.append(codeBadge(r.id), r.name);
      b.addEventListener('click', () => set(r.id));
      p.append(b);
    }
    // «Другое» — своя запись
    const row = el('div', 'other');
    row.append(codeBadge('other'));
    const input = el('input');
    input.placeholder = 'Другое: впишите причину';
    input.value = cur && cur.r === 'other' ? cur.note || '' : '';
    const ok = el('button', 'primary', 'OK');
    const save = () => set('other', input.value);
    ok.addEventListener('click', save);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') save(); });
    row.append(input, ok);
    p.append(row);
  });
}

function dayMenu(th, col) {
  openPop(th, (p) => {
    p.append(el('div', 'head', `${WEEKDAYS[new Date(col.date + 'T00:00:00').getDay()]}, ${dm(col.date)}`));
    const b = el('button', '', col.school ? 'Сделать выходным (не учебный день)' : 'Сделать учебным днём');
    b.addEventListener('click', async () => {
      T.setSchoolDay(journal, col.date, !col.school);
      closePop();
      await persist();
      render();
    });
    p.append(b);
  });
}

// ---------- Таблицы классов ----------

function classTable(c, cols) {
  const today = todayIso();
  const wrap = el('div', 'scroll-x');
  const table = el('table');
  const thead = el('thead');
  const hr = el('tr');
  hr.append(el('th', '', '№'), el('th', '', 'ФИО'));
  for (const col of cols) {
    const monday = new Date(col.date + 'T00:00:00').getDay() === 1;
    const th = el('th', `day${col.school ? '' : ' off'}${col.date === today ? ' today' : ''}${monday ? ' monday' : ''}`, String(col.day));
    th.title = col.school ? 'Учебный день' : 'Выходной';
    th.addEventListener('click', () => dayMenu(th, col));
    hr.append(th);
  }
  hr.append(el('th', '', 'Всего'), el('th', '', 'Без ув.'));
  thead.append(hr);
  const tbody = el('tbody');
  T.sortedStudents(c).forEach((s, i) => {
    const tr = el('tr');
    tr.append(el('td', 'num', String(i + 1)), el('td', 'name', s.name));
    for (const col of cols) {
      const monday = new Date(col.date + 'T00:00:00').getDay() === 1;
      const td = el('td', `${col.school ? 'mark' : 'off'}${monday ? ' monday' : ''}`);
      if (col.school) {
        const m = T.getMark(journal, col.date, s.id);
        if (m) {
          td.append(codeBadge(m.r));
          if (m.note) td.title = m.note;
        }
        td.addEventListener('click', () => markMenu(td, col.date, s));
      }
      tr.append(td);
    }
    const st = T.monthStats(journal, month, s.id);
    tr.append(el('td', 'total', st.total ? String(st.total) : ''), el('td', 'total bad', st.invalid ? String(st.invalid) : ''));
    tbody.append(tr);
  });
  table.append(thead, tbody);
  wrap.append(table);
  return wrap;
}

function editCard(c) {
  const card = el('div', 'card');
  const head = el('div', 'edit-row');
  const name = el('input');
  name.value = c.name;
  name.addEventListener('change', async () => {
    T.rename(c, name.value.trim() || c.name);
    await persist();
    render();
  });
  const del = el('button', '', 'Удалить класс');
  del.addEventListener('click', async () => {
    if (!confirm(`Удалить «${c.name}» вместе с отметками?`)) return;
    T.removeClass(journal, c.id);
    await persist();
    render();
  });
  head.append(name, del);
  card.append(head);
  for (const s of T.sortedStudents(c)) {
    const row = el('div', 'edit-row');
    const input = el('input');
    input.value = s.name;
    input.addEventListener('change', async () => {
      T.rename(s, T.fixName(input.value) || s.name);
      await persist();
      render();
    });
    const rm = el('button', '', 'Удалить');
    rm.addEventListener('click', async () => {
      if (!confirm(`Удалить ${s.name} из журнала вместе с отметками?`)) return;
      T.removeStudent(journal, c, s.id);
      await persist();
      render();
    });
    row.append(input, rm);
    card.append(row);
  }
  const add = el('div', 'edit-row');
  const input = el('input');
  input.placeholder = 'Фамилия Имя Отчество нового ученика';
  const b = el('button', 'primary', 'Добавить');
  const go = async () => {
    if (!input.value.trim()) return;
    T.addStudent(c, input.value);
    await persist();
    render();
  };
  b.addEventListener('click', go);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') go(); });
  add.append(input, b);
  card.append(add);
  return card;
}

function renderClasses() {
  const box = $('classes');
  box.textContent = '';
  if (!T.sortedClasses(journal).length && !editing) {
    const card = el('div', 'card empty');
    card.append(el('h2', '', 'Журнал пока пуст'));
    card.append(el('p', 'muted', 'Загрузите списки учащихся из документа Word (таблицы «№ | ФИО» с заголовком «Список учащихся N класса») или добавьте классы вручную.'));
    const b = el('button', 'primary', 'Загрузить списки из Word');
    b.addEventListener('click', importWord);
    const m = el('button', '', 'Добавить вручную');
    m.addEventListener('click', () => { editing = true; render(); });
    const row = el('div', 'row');
    row.style.justifyContent = 'center';
    row.append(b, m);
    card.append(row);
    box.append(card);
    return;
  }
  if (editing) {
    for (const c of T.sortedClasses(journal)) box.append(editCard(c));
    const add = el('div', 'card');
    const row = el('div', 'edit-row');
    const input = el('input');
    input.placeholder = 'Название класса, например «5 класс»';
    const b = el('button', 'primary', 'Добавить класс');
    b.addEventListener('click', async () => {
      if (!input.value.trim()) return;
      T.addClass(journal, input.value);
      await persist();
      render();
    });
    row.append(input, b);
    add.append(row);
    box.append(add);
    return;
  }
  const cols = T.monthColumns(journal, month);
  for (const c of T.sortedClasses(journal)) {
    const card = el('div', 'card');
    const head = el('div', 'class-head');
    head.append(el('h2', '', c.name));
    let total = 0;
    let invalid = 0;
    const students = T.sortedStudents(c);
    for (const s of students) {
      const st = T.monthStats(journal, month, s.id);
      total += st.total;
      invalid += st.invalid;
    }
    head.append(el('span', 'stat', `${students.length} уч. · пропусков за месяц: ${total}${invalid ? `, без уважительной причины: ${invalid}` : ''}`));
    card.append(head);
    if (students.length) card.append(classTable(c, cols));
    else card.append(el('div', 'muted small', 'Нет учеников — нажмите «Изменить списки».'));
    box.append(card);
  }
}

function render() {
  closePop();
  renderMonths();
  renderAlerts();
  renderLegend();
  renderClasses();
  $('edit').textContent = editing ? 'Готово' : 'Изменить списки';
  $('edit').classList.toggle('primary', editing);
  $('export').disabled = !T.sortedClasses(journal).length;
}

async function importWord() {
  const r = await window.api.ajImport();
  if (r.canceled) return;
  if (!r.ok) {
    message(r.message, true);
    return;
  }
  journal = T.normalize(r.journal);
  message(r.classes || r.students
    ? `Загружено: классов — ${r.classes}, учеников — ${r.students}. Проверьте ФИО в «Изменить списки».`
    : 'Все ученики из документа уже есть в журнале.');
  render();
}

// ---------- Ответ из WhatsApp ----------
// Учитель вставляет ответы классных руководителей, программа находит учеников и причины,
// учитель проверяет галочки и нажимает «Отметить». Разбор — здесь же, без нейросети.

/** null — панель закрыта; иначе { date, text, rows, present, unknown, parsed }. */
let reply = null;

function openReply() {
  const t = todayIso();
  reply = { date: t, text: '', rows: [], present: [], unknown: [], parsed: false };
  renderReply();
  $('replyText').focus();
}

function parseReplyText() {
  const r = T.parseReply(journal, reply.text);
  reply.rows = [];
  for (const it of r.items) {
    if (it.sid) {
      reply.rows.push({ sid: it.sid, name: it.name, cls: it.cls, reason: it.reason, note: it.note, line: it.line, on: true });
    } else {
      // непонятно, кто именно: показываем всех подходящих без галочек — учитель отметит нужных
      for (const o of it.options) {
        reply.rows.push({ sid: o.sid, name: o.name, cls: o.cls, reason: it.reason, note: it.note, line: it.line, on: false, group: it.name });
      }
    }
  }
  reply.present = r.present;
  reply.unknown = r.unknown;
  reply.parsed = true;
  renderReply();
}

async function applyReply() {
  const rows = reply.rows.filter((x) => x.on);
  for (const x of rows) T.setMark(journal, reply.date, x.sid, x.reason, x.reason === 'other' ? x.note : '');
  await persist();
  month = T.defaultMonth(reply.date);
  const n = rows.length;
  reply = null;
  renderReply();
  render();
  message(`Отмечено отсутствующих: ${n}.`);
}

function reasonSelect(row, onChange) {
  const s = el('select');
  for (const [v, text] of [['', '— причина не указана —'], ...T.REASONS.map((r) => [r.id, r.name])]) {
    const o = el('option', '', text);
    o.value = v;
    s.append(o);
  }
  s.value = row.reason;
  s.addEventListener('change', () => {
    row.reason = s.value;
    onChange();
  });
  return s;
}

function renderReply() {
  const box = $('reply');
  box.textContent = '';
  if (!reply) return;
  const card = el('div', 'card reply');
  card.append(el('h2', '', 'Ответ из WhatsApp'));
  card.append(el('div', 'muted small', 'Скопируйте в WhatsApp ответы классных руководителей (можно сразу несколько сообщений) '
    + 'и вставьте сюда. Программа найдёт учеников и причины — проверьте и нажмите «Отметить».'));
  const ta = el('textarea');
  ta.id = 'replyText';
  ta.placeholder = 'Например:\n4 кл: Иванов, Петрова болеют\n6 класс — все на месте';
  ta.value = reply.text;
  ta.addEventListener('input', () => { reply.text = ta.value; });
  card.append(ta);

  const bar = el('div', 'bar');
  bar.append(el('span', 'muted small', 'День:'));
  const date = el('input');
  date.type = 'date';
  date.value = reply.date;
  date.addEventListener('change', () => {
    reply.date = date.value || reply.date;
    renderReply();
  });
  bar.append(date);
  if (!T.isSchoolDay(journal, reply.date)) {
    const w = el('span', 'small', 'это не учебный день — проверьте дату');
    w.style.color = 'var(--orange)';
    bar.append(w);
  }
  const parse = el('button', reply.parsed ? '' : 'primary', reply.parsed ? 'Разобрать заново' : 'Разобрать');
  parse.addEventListener('click', parseReplyText);
  const cancel = el('button', '', 'Закрыть');
  cancel.addEventListener('click', () => { reply = null; renderReply(); });
  bar.append(el('div', 'grow'), parse, cancel);
  card.append(bar);

  if (reply.parsed) {
    if (!reply.rows.length) card.append(el('div', 'small muted', 'Отсутствующих в тексте не нашлось.'));
    let lastGroup = '';
    for (const row of reply.rows) {
      if (row.group && row.group !== lastGroup) card.append(el('div', 'group', `Непонятно, кто именно («${row.line}») — отметьте нужных:`));
      lastGroup = row.group || '';
      const r = el('div', 'row-item');
      const label = el('label');
      const cb = el('input');
      cb.type = 'checkbox';
      cb.checked = row.on;
      cb.addEventListener('change', () => { row.on = cb.checked; renderReply(); });
      label.append(cb, `${row.name} · ${row.cls}`);
      r.append(label);
      const sel = reasonSelect(row, renderReply);
      if (row.on && !row.reason) sel.classList.add('need');
      r.append(sel);
      if (row.reason === 'other') {
        const note = el('input');
        note.type = 'text';
        note.placeholder = 'причина';
        note.value = row.note;
        note.addEventListener('input', () => { row.note = note.value; });
        r.append(note);
      }
      if (!row.group) r.append(el('div', 'src', `«${row.line}»`));
      card.append(r);
    }
    if (reply.present.length) card.append(el('div', 'small', `Все на месте: ${reply.present.join(', ')}.`));
    if (reply.unknown.length) {
      const u = el('div', 'small', `Нет в журнале: ${reply.unknown.join(', ')} — проверьте, может быть, опечатка.`);
      u.style.color = 'var(--orange)';
      card.append(u);
    }
    const chosen = reply.rows.filter((x) => x.on);
    const missing = chosen.filter((x) => !x.reason).length;
    const go = el('button', 'primary', missing ? `Выберите причину (${missing})` : `Отметить: ${chosen.length}`);
    go.disabled = !chosen.length || missing > 0;
    go.addEventListener('click', applyReply);
    const row = el('div', 'bar');
    row.append(go);
    card.append(row);
  }
  box.append(card);
}

$('paste').addEventListener('click', openReply);
$('import').addEventListener('click', importWord);
$('edit').addEventListener('click', () => {
  editing = !editing;
  render();
});
$('export').addEventListener('click', async () => {
  const r = await window.api.ajExport(month, T.fileName(month));
  if (r.canceled) return;
  message(r.ok ? `Сохранено: ${r.file}` : r.message, !r.ok);
});

function renderSync(s) {
  const box = $('sync');
  if (!s || s.state === 'never') {
    box.textContent = 'Синхронизация с телефоном…';
    box.style.color = 'var(--muted)';
    return;
  }
  const at = new Date(s.at);
  const hm = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  box.textContent = s.state === 'ok' ? `Синхронизировано с телефоном через Google Диск · ${hm}` : s.message;
  box.style.color = s.state === 'ok' ? 'var(--green)' : 'var(--red)';
}

$('syncNow').addEventListener('click', async () => {
  $('syncNow').disabled = true;
  const r = await window.api.ajSyncNow();
  $('syncNow').disabled = false;
  journal = T.normalize(r.journal);
  render();
  renderSync(r.status);
});

// пришли отметки с телефона — перерисовать, но не мешать, пока открыто меню или правятся списки
window.api.onAjSynced((r) => {
  renderSync(r.status);
  if (r.journal && !pop && !editing) {
    journal = T.normalize(r.journal);
    render();
  }
});

window.api.ajLoad().then((r) => {
  journal = T.normalize(r.journal);
  month = T.defaultMonth(todayIso());
  render();
  renderSync(r.status);
});
