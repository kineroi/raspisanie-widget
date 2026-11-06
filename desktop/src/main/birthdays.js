'use strict';
// Дни рождения учеников: хранятся в журнале посещаемости (на компьютере и в файле на Google Диске учителя).
// Уведомление — в день рождения с 8 утра: если компьютер включили позже, покажем при запуске.
const { Notification } = require('electron');
const store = require('./store');
const attendance = require('./attendance');
const T = require('../shared/attendance');

const NOTIFY_HOUR = 8;
const SOON_DAYS = 7;

function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const list = () => T.upcomingBirthdays(attendance.load(), todayIso());

/** Для главного окна: кто в ближайшую неделю и сколько всего в списке. */
function snapshot() {
  const all = list();
  return {
    count: all.length,
    soon: all.filter((b) => b.daysLeft <= SOON_DAYS).map((b) => ({ text: T.birthdayText(b), daysLeft: b.daysLeft, next: b.next })),
  };
}

/** Добавить из вставленной таблицы. → { added, known, bad } */
function importText(text) {
  const r = T.parseBirthdays(text);
  const j = attendance.load();
  const added = T.addBirthdays(j, r.items);
  attendance.save(j);
  return { ...added, bad: r.bad };
}

function remove(id) {
  const j = attendance.load();
  T.removeBirthday(j, String(id));
  attendance.save(j);
}

/** Показать сегодняшние дни рождения один раз в день. */
function check(onClick) {
  if (!store.get('birthdayAlerts', true) || new Date().getHours() < NOTIFY_HOUR) return;
  const today = todayIso();
  const now = list().filter((b) => b.daysLeft === 0);
  if (!now.length || store.get('birthdayShown', '') === today || !Notification.isSupported()) return;
  store.set({ birthdayShown: today });
  const n = new Notification({
    title: now.length > 1 ? `Сегодня дни рождения: ${now.length}` : 'Сегодня день рождения',
    body: now.map(T.birthdayText).join('\n'),
  });
  n.on('click', onClick);
  n.show();
}

module.exports = { list, snapshot, importText, remove, check };
