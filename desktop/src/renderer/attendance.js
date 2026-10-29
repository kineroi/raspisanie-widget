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
    c.name = name.value.trim() || c.name;
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
      s.name = T.fixName(input.value) || s.name;
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
  if (!journal.classes.length && !editing) {
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
    for (const s of c.students) {
      const st = T.monthStats(journal, month, s.id);
      total += st.total;
      invalid += st.invalid;
    }
    head.append(el('span', 'stat', `${c.students.length} уч. · пропусков за месяц: ${total}${invalid ? `, без уважительной причины: ${invalid}` : ''}`));
    card.append(head);
    if (c.students.length) card.append(classTable(c, cols));
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
  $('export').disabled = !journal.classes.length;
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

window.api.ajLoad().then((j) => {
  journal = T.normalize(j);
  month = T.defaultMonth(todayIso());
  render();
});
