'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const G = require('../src/shared/grades.js');

// Выдуманный класс из трёх учеников той же структуры, что ответы BilimClass
const quarter = {
  data: {
    sorSchedules: [{ uuid: 'sor1', date: '01.12.26', markMax: 10 }, { uuid: 'sor2', date: '10.12.26', markMax: 10 }],
    sochSchedules: [{ uuid: 'soch1', date: '20.12.26', markMax: 24 }],
    finalMarkDeadline: { startDate: '2026-12-21', endDate: '2026-12-25' },
    rows: [
      { studentGroupUuid: 'a', fullName: 'Ученик А', finalScore: '', canGetFinalScore: true, studentStatus: true, departDate: null },
      { studentGroupUuid: 'b', fullName: 'Ученик Б', finalScore: '4', canGetFinalScore: true, studentStatus: true, departDate: null },
      { studentGroupUuid: 'c', fullName: 'Ученик В', finalScore: '', canGetFinalScore: true, studentStatus: true, departDate: null },
      { studentGroupUuid: 'x', fullName: 'Выбыл', finalScore: '', canGetFinalScore: true, studentStatus: true, departDate: '10.11.2026' },
    ],
  },
};
const fo = (m) => ({ formattedScore: { mark: m, assessmentMax: 10, type: 'mark' }, sor: [], soch: [] });
const marks = {
  data: {
    a: { marks: { l1: fo(7), l2: fo(7), l3: fo(8), l4: fo(6),
      sor1: { formattedScore: [], sor: { mark: 8, assessmentMax: 10 }, soch: [] },
      sor2: { formattedScore: [], sor: { mark: 7, assessmentMax: 10 }, soch: [] } } },
    b: { marks: { l1: fo(7), l2: fo(7), l3: fo(8), l4: fo(6),
      sor1: { formattedScore: [], sor: { mark: 8, assessmentMax: 10 }, soch: [] },
      sor2: { formattedScore: [], sor: { mark: 7, assessmentMax: 10 }, soch: [] },
      soch1: { formattedScore: [], sor: [], soch: { mark: 17, assessmentMax: 24 } } } },
    c: { marks: { l1: fo(4), l2: fo(3), l3: { formattedScore: { mark: null }, sor: [], soch: [] },
      sor1: { formattedScore: [], sor: { mark: 3, assessmentMax: 10 }, soch: [] } } },
  },
};
const info = { cls: '7А', subject: 'Иностранный язык', period: { n: 2, start: '2026-11-02', end: '2026-12-27' } };

test('журнал: выбывшие не учитываются, оценки разложены по ФО/СОР/СОЧ', () => {
  const j = G.buildJournal(info, quarter, marks);
  assert.equal(j.students.length, 3);
  assert.equal(j.students[0].fo.length, 4);
  assert.deepEqual(Object.keys(j.students[1].soch), ['soch1']);
  assert.equal(j.students[2].fo.length, 2); // пустая оценка пропущена
  assert.equal(j.soch[0].date, '2026-12-20');
});

test('расчёт как в BilimClass: 72% → 4; без СОЧ — прогноз', () => {
  const j = G.buildJournal(info, quarter, marks);
  const b = G.studentStats(j.students[1]);
  assert.equal(b.total, 72);
  assert.equal(b.grade, 4);
  assert.equal(b.forecast, false);
  const a = G.studentStats(j.students[0]);
  assert.equal(a.forecast, true);
  assert.equal(a.total, 73); // (17.5 + 18.75) / 50
  const c = G.studentStats(j.students[2]);
  assert.equal(c.total, 33); // ФО 0.35 и СОР 0.3 поровну
  assert.equal(c.grade, 2);
});

test('группа риска', () => {
  assert.equal(G.riskOf(38), 'two');
  assert.equal(G.riskOf(42), 'edge2');
  assert.equal(G.riskOf(45), null);
  assert.equal(G.riskOf(67), 'edge3');
  assert.equal(G.riskOf(72), null);
  // оценка BilimClass главнее процента
  assert.equal(G.riskOf(69, 3), null);
  assert.equal(G.riskOf(50, 2), 'two');
  const sum = G.classSummary(G.buildJournal(info, quarter, marks));
  assert.equal(sum.rows[0].st.name, 'Ученик В'); // сначала самые слабые
  assert.equal(sum.riskCount, 1);
});

test('рекомендуемая оценка BilimClass главнее расчёта', () => {
  const q2 = JSON.parse(JSON.stringify(quarter));
  q2.data.rows[0].recommendedScore = '3';
  const j = G.buildJournal(info, q2, marks);
  const a = G.studentStats(j.students[0]);
  assert.equal(a.total, 73);
  assert.equal(a.grade, 3);
  assert.equal(a.fromSite, true);
  assert.equal(a.forecast, false);
});

test('сколько нужно на СОЧ', () => {
  const j = G.buildJournal(info, quarter, marks);
  const n = G.needForSoch(j.students[0], j); // ФО 0.7, СОР 0.75 → база 36.25
  assert.deepEqual(n, { max: 24, need: { 3: 2, 4: 14, 5: 24 } });
  assert.equal(G.needForSoch(j.students[1], j), null); // СОЧ уже написан
  const weak = G.needForSoch(j.students[2], j); // база 8.75 + 7.5 = 16.25
  assert.equal(weak.need[5], null); // 5 недостижима
  assert.equal(weak.need[3], 12);
});

test('контроль конца четверти', () => {
  const j = G.buildJournal(info, quarter, marks);
  assert.equal(G.quarterCheck([j], [], '2026-12-10'), null); // ещё 17 дней
  const lessons = [
    { date: '2026-12-21', cls: '7А', theme: 'X', homework: '' },
    { date: '2026-12-21', cls: '7А', theme: '', homework: 'ex 1' },
    { date: '2026-12-21', cls: '9 класс', theme: '', homework: '', additional: true },
    { date: '2026-10-20', cls: '7А', theme: '', homework: '' }, // прошлая четверть — не считается
  ];
  const r = G.quarterCheck([j], lessons, '2026-12-22');
  assert.equal(r.daysLeft, 5);
  assert.equal(r.deadline, '2026-12-25');
  const texts = r.items.map((i) => i.text);
  assert.ok(texts.includes('7А: не выставлено итоговых — 2'), texts.join(' | '));
  assert.ok(texts.includes('7А: СОР от 10.12 — без оценки 1'), texts.join(' | '));
  assert.ok(texts.includes('7А: СОЧ от 20.12 — без оценки 2'), texts.join(' | '));
  assert.ok(texts.includes('Уроков без темы — 1'));
  assert.ok(texts.includes('Уроков без ДЗ — 1'));
  assert.equal(G.quarterCheck([j], lessons, '2026-12-28'), null); // срок прошёл
});

test('выбор четверти: на каникулах — последняя прошедшая', () => {
  const ps = [
    { uuid: 'q1', period: 1, start: '2026-09-01', end: '2026-10-25' },
    { uuid: 'q2', period: 2, start: '2026-11-02', end: '2026-12-27' },
  ];
  assert.equal(G.pickPeriod(ps, '2026-10-26').uuid, 'q1');
  assert.equal(G.pickPeriod(ps, '2026-11-05').uuid, 'q2');
  assert.equal(G.pickPeriod(ps, '2026-08-20'), null);
});
