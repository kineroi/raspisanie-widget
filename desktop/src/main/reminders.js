'use strict';
// Напоминание за 5 минут до урока — уведомление Windows.
const { Notification } = require('electron');
const L = require('../shared/logic');

const MAX_WAIT_MS = 30 * 60 * 1000; // не ждать дольше 30 минут подряд: пересчитаем (сон, смена времени)

let timer = null;
let getDays = () => ({});
let isEnabled = () => false; // до start() не планируем
let onClick = () => {};
let lastShown = '';

/** Пересчитать ближайшее напоминание (после обновления расписания, выхода из сна, смены настройки). */
function reschedule() {
  if (timer) clearTimeout(timer);
  timer = null;
  if (!isEnabled()) return null;
  const next = L.nextReminder(new Date(), L.makeSource(getDays()));
  if (!next) return null;
  const wait = next.at - Date.now();
  timer = setTimeout(() => {
    if (wait <= MAX_WAIT_MS) fire(next);
    reschedule();
  }, Math.max(1000, Math.min(wait, MAX_WAIT_MS)));
  return next;
}

function fire(next) {
  const key = `${next.date} ${next.lesson.start}`;
  if (key === lastShown) return; // не повторять одно и то же
  // Если компьютер «проспал» момент напоминания и урок уже начался — не показываем
  const [h, m] = next.lesson.start.split(':').map(Number);
  const start = L.parseIso(next.date);
  start.setHours(h, m, 0, 0);
  if (Date.now() >= start.getTime()) return;
  lastShown = key;
  show(next.lesson, next.date);
}

function show(lesson, date) {
  if (!Notification.isSupported()) return;
  const t = L.reminderText(lesson);
  const n = new Notification({ title: t.title, body: t.body, silent: false });
  n.on('click', () => onClick(date, lesson.start));
  n.show();
}

function start(opts) {
  getDays = opts.getDays;
  isEnabled = opts.isEnabled;
  onClick = opts.onClick;
  reschedule();
}

module.exports = { start, reschedule, show };
