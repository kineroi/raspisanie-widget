'use strict';
// Уведомления об успеваемости: по понедельникам — группа риска, в конце четверти — что не заполнено.
const { Notification } = require('electron');
const store = require('./store');
const journal = require('./journal');
const L = require('../shared/logic');

const CHECK_EVERY_MS = 15 * 60 * 1000;
const RISK_TIME = 7 * 60 + 45; // понедельник 07:45
const QUARTER_TIME = 16 * 60; // каждый день 16:00 в последнюю неделю четверти

let onClick = () => {};
let isEnabled = () => true;

function notify(title, body) {
  if (!Notification.isSupported()) return;
  const n = new Notification({ title, body });
  n.on('click', () => onClick());
  n.show();
}

/** Текст сводки группы риска: «4А — 2, 7А — 1» или null. */
function riskText(snap) {
  const parts = snap.classes.filter((c) => c.summary.riskCount).map((c) => `${c.journal.cls} — ${c.summary.riskCount}`);
  return parts.length ? parts.join(', ') : null;
}

function quarterText(check) {
  if (!check || !check.items.length) return null;
  const head = check.daysLeft > 0 ? `До конца четверти ${check.daysLeft} дн.` : 'Четверть закончилась';
  const list = check.items.slice(0, 4).map((i) => i.text).join('; ');
  return { title: `${head}: есть незаполненное`, body: list + (check.items.length > 4 ? ` и ещё ${check.items.length - 4}` : '') };
}

async function tick() {
  if (!isEnabled() || !store.get('refresh')) return;
  const now = new Date();
  const mins = now.getHours() * 60 + now.getMinutes();
  const today = L.isoDate(now);

  const weekKey = L.isoDate(L.mondayOf(now));
  const riskDue = L.dow(now) === 0 && mins >= RISK_TIME && store.get('riskWeek', '') !== weekKey;
  const quarterDue = mins >= QUARTER_TIME && store.get('quarterDay', '') !== today;
  if (!riskDue && !quarterDue) return;

  await journal.refreshIfStale();
  const snap = journal.snapshot();
  if (riskDue) {
    store.set({ riskWeek: weekKey });
    const t = riskText(snap);
    if (t) notify('Группа риска на этой неделе', `${t}. Нажмите, чтобы посмотреть.`);
  }
  if (quarterDue) {
    const q = quarterText(snap.check);
    if (snap.check) store.set({ quarterDay: today }); // проверяем только в последнюю неделю четверти
    if (q) notify(q.title, q.body);
  }
}

function start(opts) {
  onClick = opts.onClick;
  isEnabled = opts.isEnabled;
  setTimeout(() => tick().catch(() => {}), 60 * 1000);
  setInterval(() => tick().catch(() => {}), CHECK_EVERY_MS);
}

module.exports = { start, riskText, quarterText };
