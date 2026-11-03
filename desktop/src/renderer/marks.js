'use strict';
/* global Logic, Marks */
const L = Logic;
const M = Marks;
const $ = (id) => document.getElementById(id);
const params = new URLSearchParams(location.search);
const date = params.get('date');
const start = params.get('start');

/** Классы урока из журнала: { cls, scheduleUuid, groupId, subjectId, eduYear, markMax, completed, canManage, students } */
let classes = [];
/** Правки учителя: edits[cls][studentId] = { mark, comment, polished } */
const edits = {};
/** Итог отправки по ученикам: results[studentId] = { ok, message } */
let results = {};
let busy = false;
let hasKey = false;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function status(text, kind) {
  $('status').textContent = text || '';
  $('status').className = kind === 'error' ? 'bad' : kind === 'ok' ? 'ok' : 'muted';
}

function setBusy(b, text) {
  busy = b;
  for (const id of ['send', 'polish']) $(id).disabled = b;
  if (text) status(text);
  if (!b) refreshButtons();
}

function editOf(c, s) {
  const byCls = edits[c.cls] || (edits[c.cls] = {});
  return byCls[s.id] || (byCls[s.id] = { mark: s.mark == null ? '' : String(s.mark), comment: s.comment, polished: true });
}

/** Что уйдёт в журнал по классу. */
function pending(c) {
  return M.changes(c.students, edits[c.cls] || {}, c.markMax);
}

/** Комментарии, которые учитель изменил, но ещё не прогнал через нейросеть. */
function unpolished() {
  const out = [];
  for (const c of classes) {
    for (const s of c.students) {
      const e = (edits[c.cls] || {})[s.id];
      if (e && !e.polished && e.comment.trim() && e.comment.trim() !== s.comment) out.push(e);
    }
  }
  return out;
}

function tooLong() {
  return classes.some((c) => Object.values(edits[c.cls] || {}).some((e) => e.comment.trim().length > M.COMMENT_MAX));
}

function refreshButtons() {
  const n = classes.reduce((a, c) => a + (c.completed && c.canManage ? pending(c).length : 0), 0);
  $('send').disabled = busy || n === 0 || tooLong();
  $('send').textContent = n ? `Отправить в BilimClass: ${n}` : 'Отправить в BilimClass';
  $('polish').disabled = busy || !hasKey || unpolished().length === 0;
}

function studentRow(c, s, editable) {
  const e = editOf(c, s);
  const row = el('div', 'student');
  const name = el('div', 'name', s.name);
  if (s.mark != null || s.comment) {
    name.append(el('div', 'was', `в журнале: ${s.mark == null ? '—' : s.mark}${s.comment ? ' · есть комментарий' : ''}`));
  }
  row.append(name);

  const sel = el('select');
  const opts = [['', '—']];
  for (let i = c.markMax; i >= 0; i--) opts.push([String(i), String(i)]);
  for (const [v, t] of opts) {
    const o = el('option', '', t);
    o.value = v;
    sel.append(o);
  }
  sel.value = e.mark;
  sel.disabled = !editable;
  sel.title = `Балл ФО из ${c.markMax}`;
  sel.addEventListener('change', () => {
    e.mark = sel.value;
    refreshButtons();
  });
  row.append(sel);

  const box = el('div', 'comment');
  const ta = el('textarea');
  ta.placeholder = 'Комментарий кратко, например: «молодец, активно работал, но не сдал тетрадь»';
  ta.value = e.comment;
  ta.disabled = !editable;
  const count = el('div', 'count');
  const updateCount = () => {
    const n = ta.value.trim().length;
    count.textContent = `${n}/${M.COMMENT_MAX}${e.polished || !ta.value.trim() || ta.value.trim() === s.comment ? '' : ' · ещё не переведён'}`;
    count.classList.toggle('over', n > M.COMMENT_MAX);
  };
  ta.addEventListener('input', () => {
    e.comment = ta.value;
    e.polished = false;
    updateCount();
    refreshButtons();
  });
  updateCount();
  box.append(ta, count);
  row.append(box);

  const one = el('button', 'ai', '✨');
  one.title = 'Сделать этот комментарий на русском и английском';
  one.disabled = !editable || !hasKey;
  one.addEventListener('click', async () => {
    if (!e.comment.trim() || busy) return;
    setBusy(true, 'Нейросеть пишет комментарий…');
    const r = await window.api.marksComment(e.comment);
    setBusy(false);
    if (r.ok) {
      e.comment = r.text;
      e.polished = true;
      ta.value = r.text;
      updateCount();
      status('Комментарий готов — проверьте и при необходимости поправьте.');
    } else {
      status(r.message, 'error');
    }
    refreshButtons();
  });
  row.append(one);

  const res = results[s.id];
  if (res) row.append(el('div', `result ${res.ok ? 'ok' : 'bad'}`, res.ok ? 'Отправлено ✓' : res.message));
  return row;
}

function classCard(c) {
  const card = el('div', 'card');
  card.append(el('h2', '', c.cls));
  if (c.error) {
    card.append(el('div', 'lesson-state bad', c.error));
    return card;
  }
  const state = el('div', 'lesson-state');
  if (!c.canManage) {
    state.append(el('span', 'bad', 'Журнал не разрешает вам ставить оценки за этот урок.'));
  } else if (c.completed) {
    state.append(el('span', 'ok', `Урок проведён ✓ · баллы от 0 до ${c.markMax}`));
  } else if (!c.canBeCompleted) {
    state.append(el('span', 'warn', 'Урок ещё не прошёл — оценки можно будет поставить после урока.'));
  } else {
    state.append(el('span', 'warn', 'Урок не отмечен проведённым — пока оценки поставить нельзя.'));
    {
      const b = el('button', '', 'Отметить урок проведённым');
      b.addEventListener('click', async () => {
        if (!confirm(`Отметить урок ${c.cls} проведённым в BilimClass?\n\nТак же, как кнопка «Урок проведён» в журнале на сайте.`)) return;
        setBusy(true, 'Отмечаю урок…');
        const r = await window.api.marksConduct(date, c.scheduleUuid);
        setBusy(false);
        if (r.ok) await load('Урок отмечен проведённым ✓');
        else status(r.message, 'error');
      });
      state.append(b);
    }
  }
  card.append(state);
  const editable = c.completed && c.canManage;
  if (!c.students.length) card.append(el('div', 'small muted', 'В журнале нет учеников.'));
  for (const s of c.students) card.append(studentRow(c, s, editable));
  return card;
}

function render() {
  const box = $('classes');
  box.textContent = '';
  for (const c of classes) box.append(classCard(c));
  refreshButtons();
}

async function load(message) {
  setBusy(true, 'Загружаю журнал из BilimClass…');
  const r = await window.api.marksLoad(date, start);
  setBusy(false);
  if (!r.ok) {
    classes = [];
    render();
    status(r.message, 'error');
    return;
  }
  const d = L.parseIso(date);
  $('head').textContent = `${L.DAY_NAMES[L.dow(d)]}, ${L.dateText(d)} · ${r.range} · ${r.title}`;
  document.title = `Оценки · ${r.title} · ${L.dateText(d)}`;
  classes = r.classes;
  for (const k of Object.keys(edits)) delete edits[k];
  render();
  status(message || '', message ? 'ok' : '');
}

/** Прогнать через нейросеть все изменённые комментарии. */
async function polishAll() {
  const list = unpolished();
  if (!list.length) return true;
  setBusy(true, `Нейросеть пишет комментарии: ${list.length}…`);
  let failed = '';
  for (const e of list) {
    const r = await window.api.marksComment(e.comment);
    if (r.ok) {
      e.comment = r.text;
      e.polished = true;
    } else {
      failed = r.message;
      break;
    }
  }
  setBusy(false);
  render();
  if (failed) {
    status(failed, 'error');
    return false;
  }
  status('Комментарии готовы — проверьте их и нажмите «Отправить».');
  return true;
}

$('polish').addEventListener('click', polishAll);
$('close').addEventListener('click', () => window.close());

$('send').addEventListener('click', async () => {
  if (busy) return;
  // сначала — комментарии на двух языках, и учитель их видит до отправки
  if (hasKey && unpolished().length) {
    await polishAll();
    return;
  }
  if (tooLong()) {
    status(`Комментарий длиннее ${M.COMMENT_MAX} символов — сократите его.`, 'error');
    return;
  }
  const plan = classes.filter((c) => c.completed && c.canManage).map((c) => ({ c, list: pending(c) })).filter((x) => x.list.length);
  if (!plan.length) return;
  const lines = [];
  for (const { c, list } of plan) {
    for (const ch of list) {
      const s = c.students.find((x) => x.id === ch.id);
      lines.push(`${c.cls}: ${s.name} — ${ch.mark}${ch.comment ? ' + комментарий' : ''}${ch.kind === 'update' ? ' (изменение)' : ''}`);
    }
  }
  if (!confirm(`Отправить в журнал BilimClass?\n\n${lines.join('\n')}\n\nОценки сразу увидят ученики и родители.`)) return;
  setBusy(true, 'Отправляю оценки…');
  results = {};
  let ok = 0;
  let bad = 0;
  for (const { c, list } of plan) {
    const ctx = { cls: c.cls, scheduleUuid: c.scheduleUuid, groupId: c.groupId, subjectId: c.subjectId, eduYear: c.eduYear, markMax: c.markMax };
    const r = await window.api.marksSave(date, ctx, list);
    if (!r.results.length && r.message) {
      setBusy(false);
      status(r.message, 'error');
      return;
    }
    for (const x of r.results) {
      results[x.id] = x;
      if (x.ok) ok++;
      else bad++;
    }
  }
  setBusy(false);
  // перечитать журнал: видно, что оценки действительно встали
  const keep = results;
  await load();
  results = keep;
  render();
  status(bad ? `Отправлено: ${ok}, с ошибкой: ${bad} — см. у учеников.` : `Оценки отправлены в BilimClass ✓ (${ok})`, bad ? 'error' : 'ok');
});

window.addEventListener('beforeunload', (e) => {
  if (busy) e.returnValue = false;
});

window.api.getData().then((data) => {
  hasKey = !!data.aiKeyTail;
  load();
});
