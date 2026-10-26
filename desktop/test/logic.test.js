'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const L = require('../src/shared/logic.js');

function lesson(start, end, cls, additional, theme, hw, cab) {
  return {
    time: { timeStart: start, timeEnd: end },
    class: { label: cls },
    subject: { label: 'Шетел тілі', isAdditional: additional },
    theme: { label: theme },
    scheduleUuid: `uuid-${cls.replace(' ', '')}-${start}`,
    homework: {
      body: hw,
      files: cls === '3 А' ? [{ id: 77, name: 'card', extension: '.jpeg', link: 'https://x/y' }] : [],
    },
    cabinet: { number: cab },
    subgroup: null,
  };
}

const WEEK = {
  data: {
    days: ['02', '03', '04', '05', '06', '07', '08'].map((d) => ({ date: `${d}.11.2026`, isHoliday: d === '04' })),
    rows: [
      {
        '02.11.2026': [lesson('08:30', '09:00', '9 класс', true, 'Кл. час «Тестовая тема»\nЕщё строка', 'не проставляется', '6')],
        '03.11.2026': [lesson('13:35', '14:20', '3 А', false, 'Colours', 'p. 10', '4')],
      },
      {
        '02.11.2026': [lesson('09:00', '09:45', '7 А', false, 'Theme A', 'ex. 1', null),
          lesson('09:00', '09:45', '8 А', false, 'Theme B', 'ex. 2', null)],
      },
      {
        '02.11.2026': [lesson('12:45', '13:30', '4 А', false, 'Animals', '', null)],
        '03.11.2026': [lesson('12:45', '13:30', '4 А', false, 'Food', 'revision', null)],
      },
      {
        '02.11.2026': [lesson('09:50', '10:35', '5 А', false, 'Heroes', 'revision', null),
          lesson('09:50', '10:35', '6 А', false, 'Sport', 'revision', null)],
      },
    ],
  },
};

const EMPTY_WEEK = {
  data: {
    days: ['26.10', '27.10', '28.10', '29.10', '30.10', '31.10', '01.11']
      .map((d, i) => ({ date: `${d}.2026`, isHoliday: i === 0 })),
    rows: [],
  },
};

test('разбор недели и объединение сдвоенных уроков', () => {
  const days = L.parseWeek(WEEK);
  assert.equal(Object.keys(days).length, 7);
  const mon = days['2026-11-02'].lessons;
  assert.equal(mon.length, 4);
  assert.equal(mon[0].title, 'Кл. час, 9 класс (каб. 6)');
  assert.equal(L.homeworkSummary(mon[0]), null);
  assert.equal(mon[0].details[0].theme, 'Кл. час «Тестовая тема» Ещё строка');
  assert.equal(mon[1].title, '7А + 8А');
  assert.equal(L.homeworkSummary(mon[1]), 'ДЗ: 7А — ex. 1; 8А — ex. 2');
  assert.equal(mon[1].details[1].scheduleUuid, 'uuid-8А-09:00');
  assert.equal(mon[2].title, '5А + 6А');
  assert.equal(L.homeworkSummary(mon[2]), 'ДЗ: revision');
  const tue = days['2026-11-03'].lessons;
  assert.deepEqual(tue.map((l) => l.title), ['4А', '3А (каб. 4)']);
  assert.equal(tue[1].details[0].files[0].name, 'card.jpeg');
  assert.equal(days['2026-11-04'].holiday, true);
});

test('состояние виджета по данным BilimClass', () => {
  const src = L.makeSource(L.parseWeeks([EMPTY_WEEK, WEEK]));
  const s = L.computeState(new Date(2026, 10, 2, 9, 10), src);
  assert.equal(s.status, 'Сейчас: 7А + 8А');
  assert.equal(s.subText, 'ДЗ: 7А — ex. 1; 8А — ex. 2');
  assert.equal(s.rows[1], 'current');
  assert.equal(s.countdownLabel, 'до звонка');

  const h = L.computeState(new Date(2026, 9, 26, 10, 0), src);
  assert.equal(h.status, 'Праздничный день 🎉');
  assert.ok(h.subText.startsWith('2 ноября: с 08:30'), h.subText);
  assert.equal(h.listDate, '2026-11-02');

  assert.equal(L.computeState(new Date(2026, 9, 27, 10, 0), src).status, 'Сегодня уроков нет');
});

test('запасное расписание: перемена, окно, конец дня, выходной', () => {
  const src = L.makeSource({});
  assert.equal(L.computeState(new Date(2026, 9, 19, 9, 47), src).status, 'Перемена · далее 5А + 6А');
  assert.equal(L.computeState(new Date(2026, 9, 19, 11, 30), src).status, 'Окно · далее 4А');
  const end = L.computeState(new Date(2026, 9, 23, 15, 0), src);
  assert.equal(end.status, 'Уроки на сегодня закончились');
  assert.ok(end.subText.startsWith('В понедельник: с 08:30'), end.subText);
  assert.equal(L.computeState(new Date(2026, 9, 24, 12, 0), src).status, 'Выходной 🎉');
});

test('раскрытие сокращений без ИИ', () => {
  assert.equal(L.expandRules('стр 45 упр 3 письм, сл выуч'),
    'Задание: Страница 45, упражнение 3 (письменно), выучить слова.\n' +
    'Task: Page 45, exercise 3 (in writing), learn the words.');
  assert.equal(L.expandRules('р.т. стр. 12 упр 1-2'),
    'Задание: Рабочая тетрадь, страница 12, упражнение 1-2.\nTask: Workbook, page 12, exercise 1-2.');
  assert.equal(L.expandRules('Учебник с. 50 у. 4 устно'),
    "Задание: Учебник, страница 50, упражнение 4 (устно).\nTask: Student's Book, page 50, exercise 4 (orally).");
  assert.equal(L.expandRules('  '), '');
});

test('Gemini: запрос и ответ', () => {
  const req = L.geminiRequest('стр 45', '9А', 'Heroes');
  const user = req.contents[0].parts[0].text;
  assert.ok(user.includes('Класс: 9А') && user.includes('Тема урока: Heroes') && user.includes('Заметка учителя: стр 45'));
  const resp = { candidates: [{ content: { parts: [
    { text: 'думаю...', thought: true },
    { text: '**Задание:** Страница 45.\n\nTask: Page 45.' }] } }] };
  assert.equal(L.geminiParse(resp), 'Задание: Страница 45.\nTask: Page 45.');
  assert.equal(L.geminiParse({ candidates: [] }), '');
});

test('время и даты', () => {
  assert.equal(L.formatLeft(754000), '12:34');
  assert.equal(L.formatLeft(4805000), '1:20:05');
  assert.equal(L.isoDate(L.mondayOf(new Date(2026, 9, 28))), '2026-10-26');
  assert.equal(L.eduYear(new Date(2027, 2, 1)), 2026);
  assert.equal(L.compactClass('7 А'), '7А');
  assert.equal(L.compactClass('9 класс'), '9 класс');
});

test('напоминание за 5 минут до урока', () => {
  const src = L.makeSource(L.parseWeeks([EMPTY_WEEK, WEEK]));
  // Утро понедельника 2 ноября: первое — кл. час 08:30 → напоминание в 08:25
  let r = L.nextReminder(new Date(2026, 10, 2, 7, 0), src);
  assert.equal(r.date, '2026-11-02');
  assert.equal(r.lesson.start, '08:30');
  assert.equal(r.at.getHours() * 60 + r.at.getMinutes(), 8 * 60 + 25);
  // В 08:56 до урока 09:00 меньше 5 минут — следующее напоминание к 09:50 (в 09:45)
  r = L.nextReminder(new Date(2026, 10, 2, 8, 56), src);
  assert.equal(r.lesson.start, '09:50');
  // Каникулы (неделя 26.10 без уроков) — напоминание переносится на 2 ноября
  r = L.nextReminder(new Date(2026, 9, 26, 12, 0), src);
  assert.equal(r.date, '2026-11-02');
  // После последнего урока понедельника — вторник 12:45 → 12:40
  r = L.nextReminder(new Date(2026, 10, 2, 14, 0), src);
  assert.equal(r.date, '2026-11-03');
  assert.equal(r.lesson.start, '12:45');

  const t = L.reminderText(src(new Date(2026, 10, 2)).lessons[1]);
  assert.equal(t.title, 'Через 5 минут: 7А + 8А');
  assert.equal(t.body, '09:00–09:45 · 7А: Theme A · 8А: Theme B');
});
