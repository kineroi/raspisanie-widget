'use strict';
/* global Logic */
const L = Logic;
let data = { days: {}, needLogin: false };
let lastSignature = '';
let lastHeight = 0;

const $ = (id) => document.getElementById(id);
const pad = (n) => String(n).padStart(2, '0');

function render() {
  const now = new Date();
  $('clock').textContent = `${pad(now.getHours())}:${pad(now.getMinutes())}`;
  const s = L.computeState(now, L.makeSource(data.days));

  if (s.countdownTo) {
    $('count').textContent = `${s.countdownLabel} ${L.formatLeft(s.countdownTo - now)} (в ${s.countdownAt})`;
    $('count').hidden = false;
  } else {
    $('count').hidden = true;
  }

  let sub = s.subText;
  if (!sub && data.needLogin) sub = 'Войдите в BilimClass заново (откройте расписание)';

  // Остальное перерисовываем только при изменениях
  const signature = JSON.stringify([s.dayName, s.dateText, s.status, sub, s.listTitle, s.listDate, s.rows,
    s.lessons.map((l) => [l.start, l.end, l.title])]);
  if (signature === lastSignature) return;
  lastSignature = signature;

  $('day').textContent = s.dayName;
  $('date').textContent = s.dateText;
  $('status').textContent = s.status;
  $('sub').textContent = sub || '';
  $('sub').hidden = !sub;
  $('listTitle').textContent = s.listTitle;

  const list = $('list');
  list.textContent = '';
  s.lessons.forEach((l, i) => {
    const row = document.createElement('div');
    const kind = s.rows[i];
    row.className = `lesson ${kind}`;
    const mark = kind === 'current' ? '▶ ' : kind === 'past' ? '✓ ' : '';
    const t = document.createElement('span');
    t.className = 't';
    t.textContent = L.range(l);
    const n = document.createElement('span');
    n.className = 'n';
    n.textContent = mark + l.title;
    row.title = [l.title, L.homeworkSummary(l) || ''].filter(Boolean).join('\n');
    row.append(t, n);
    row.addEventListener('click', () => window.api.openHomework(s.listDate, l.start));
    list.appendChild(row);
  });

  const h = Math.ceil(document.getElementById('w').getBoundingClientRect().height) + 14;
  if (h !== lastHeight) {
    lastHeight = h;
    window.api.resizeMini(h);
  }
}

$('open').addEventListener('click', () => window.api.openMain());
$('hide').addEventListener('click', () => window.api.hideMini());

window.api.onData((d) => {
  data = d;
  lastSignature = '';
  render();
});

window.api.getData().then((d) => {
  data = d;
  render();
  setInterval(render, 1000);
});
