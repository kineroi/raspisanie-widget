'use strict';
/* global Grades, Logic */
const G = Grades;
const L = Logic;
const $ = (id) => document.getElementById(id);
let snap = null;
let busy = false;

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

const fmtDate = (iso) => `${iso.slice(8)}.${iso.slice(5, 7)}`;
const pct = (v) => (v == null ? '—' : `${(Math.round(v * 10) / 10).toString().replace('.', ',')}%`);

function when(ms) {
  if (!ms) return 'ещё не загружалось';
  const t = new Date(ms);
  const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
  return L.isoDate(t) === L.isoDate(new Date()) ? `обновлено сегодня в ${hm}` : `обновлено ${L.dateText(t)} в ${hm}`;
}

function sochText(n) {
  if (!n) return '';
  const part = [];
  for (const g of [3, 4, 5]) {
    const v = n.need[g];
    part.push(`${g} — ${v == null ? 'нельзя' : v === 0 ? 'уже есть' : `от ${v}`}`);
  }
  return `На СОЧ (из ${n.max}): ${part.join(', ')}`;
}

function renderCheck(c) {
  const box = $('check');
  box.textContent = '';
  if (!c) return;
  const card = el('div', 'card alert');
  const h = el('h2', '', c.daysLeft >= 0 ? `Конец четверти через ${c.daysLeft} дн. (${fmtDate(c.end)})` : `Четверть закончилась ${fmtDate(c.end)}`);
  if (c.deadline) h.append(el('span', 'muted', `итоговые — до ${fmtDate(c.deadline)}`));
  card.append(h);
  if (!c.items.length) {
    card.append(el('div', '', 'Всё в порядке: итоговые, СОР/СОЧ, темы и ДЗ заполнены ✓'));
  } else {
    const ul = el('ul');
    for (const it of c.items) ul.append(el('li', '', it.text));
    card.append(ul);
  }
  box.append(card);
}

function renderClasses() {
  const box = $('classes');
  box.textContent = '';
  if (!snap || !snap.classes.length) {
    box.append(el('div', 'card muted', snap && snap.updatedAt ? 'Нет журналов.' : 'Нажмите «Обновить», чтобы загрузить журналы из BilimClass.'));
    return;
  }
  const only = $('onlyRisk').checked;
  for (const { journal: j, summary } of snap.classes) {
    const card = el('div', 'card');
    const h = el('h2', '', `${j.cls}${j.subject ? ' · ' + j.subject : ''}`);
    if (j.period) h.append(el('span', 'muted', `${j.period.n} четверть, ${fmtDate(j.period.start)}–${fmtDate(j.period.end)}`));
    if (summary.riskCount) h.append(el('span', 'badge', `группа риска: ${summary.riskCount}`));
    card.append(h);
    const rows = summary.rows.filter((r) => !only || r.risk);
    if (!rows.length) {
      card.append(el('div', 'muted', only ? 'Никого в группе риска ✓' : 'Нет учеников.'));
      box.append(card);
      continue;
    }
    const table = el('table');
    const head = el('tr');
    for (const t of ['Ученик', '%ФО', '%СОР', '%СОЧ', 'Итог', 'Оценка', '']) head.append(el('th', '', t));
    table.append(head);
    for (const r of rows) {
      const tr = el('tr');
      tr.append(el('td', '', r.st.name));
      tr.append(el('td', 'num', pct(r.stats.parts.fo)));
      tr.append(el('td', 'num', pct(r.stats.parts.sor)));
      tr.append(el('td', 'num', pct(r.stats.parts.soch)));
      tr.append(el('td', 'num', r.stats.total == null ? '—' : `${r.stats.total}%${r.stats.forecast ? ' (прогноз)' : ''}`));
      const g = el('td');
      if (r.stats.grade) g.append(el('span', `grade g${r.stats.grade}`, String(r.stats.grade)));
      if (r.stats.fromSite) g.title = 'Рекомендуемая оценка BilimClass';
      tr.append(g);
      const info = el('td');
      if (r.risk) info.append(el('div', 'risk', '⚠ ' + G.RISK_TEXT[r.risk]));
      if (r.soch) info.append(el('div', 'soch small', sochText(r.soch)));
      tr.append(info);
      table.append(tr);
    }
    card.append(table);
    box.append(card);
  }
}

function render() {
  $('status').textContent = busy ? 'Загружаю журналы из BilimClass…' : when(snap && snap.updatedAt);
  $('refresh').disabled = busy;
  renderCheck(snap && snap.check);
  renderClasses();
}

async function load() {
  snap = await window.api.gradesData();
  render();
}

$('refresh').addEventListener('click', async () => {
  busy = true;
  render();
  const r = await window.api.gradesRefresh();
  busy = false;
  await load();
  if (r === 'need_login') $('status').textContent = 'Вход в BilimClass устарел — войдите заново в основном окне.';
  else if (r === 'no_network') $('status').textContent = 'Не удалось загрузить: нет связи с BilimClass. Показано сохранённое.';
  else if (r === 'not_connected') $('status').textContent = 'BilimClass не подключён — войдите в основном окне.';
  else if (r === 'no_journals') $('status').textContent = 'Не нашёл ваших журналов: обновите расписание в основном окне.';
});
$('onlyRisk').addEventListener('change', renderClasses);
window.api.onGrades(() => load());

load().then(() => {
  if (!snap.updatedAt || Date.now() - snap.updatedAt > 60 * 60 * 1000) $('refresh').click();
});
