'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../src/shared/analysis.js');

// выдуманный класс: баллы как в 4 классе за 1 четверть (ПО из 16)
function poJournal() {
  const scores1 = [16, 8, 7, 16, 16, 11, 12, 10];
  const scores2 = [12, 8, 9, 15, 13, 9, 12, 11];
  return {
    cls: '4А', group: '4 А РУС', subject: 'Иностранный язык', teacher: 'Иван Петрович Сидоров',
    sor: [], soch: [],
    po: [{ uuid: 'p2', date: '2026-10-19', max: 16 }, { uuid: 'p1', date: '2026-09-21', max: 16 }],
    students: scores1.map((s, i) => ({
      name: `Ученик${i + 1} Имя${i + 1} Отчество`,
      sor: {}, soch: {},
      po: { p1: { mark: s, max: 16 }, p2: { mark: scores2[i], max: 16 } },
    })),
  };
}

test('Справка ПО: уровни, качество, успеваемость, ученики', () => {
  const a = A.build(poJournal());
  assert.equal(a.mode, 'po');
  assert.equal(a.grade, 4);
  assert.equal(a.teacher, 'Сидоров Иван Петрович');
  assert.deepEqual(a.items.map((i) => i.label), ['ПО 1', 'ПО 2']);
  const [po1, po2] = a.items;
  assert.equal(po1.date, '2026-09-21'); // по дате, а не по порядку в журнале
  assert.deepEqual([po1.wrote, po1.max, po1.low, po1.mid, po1.high, po1.quality, po1.success], [8, 16, 0, 5, 3, 63, 100]);
  assert.deepEqual([po2.low, po2.mid, po2.high, po2.quality, po2.success], [0, 7, 1, 63, 100]);
  assert.deepEqual(po1.names.high, ['Ученик1 Имя1', 'Ученик4 Имя4', 'Ученик5 Имя5']);
  assert.equal(A.fileName(a, 'Иностранный язык'), 'Анализ ПО 4 класс (Иностранный язык).xlsx');
  assert.match(A.title(a.mode, 1, 'Иностранный язык'), /промежуточного оценивания \(ПО\) за 1 четверть/);
  assert.match(A.goal('po'), /при проведении промежуточного оценивания за четверть/);
});

test('Справка СОР и СОЧ: низкий уровень, не писавшие, подписи', () => {
  const j = {
    cls: '5А', group: '5 А РУС', subject: 'Иностранный язык', teacher: '',
    po: [],
    sor: [{ uuid: 's1', date: '2026-09-23', max: 13 }, { uuid: 's2', date: '2026-10-07', max: 13 }],
    soch: [{ uuid: 'c1', date: '2026-10-21', max: 20 }],
    students: [
      { name: 'А Б', sor: { s1: { mark: 4, max: 13 }, s2: { mark: 6, max: 13 } }, soch: { c1: { mark: 12, max: 20 } }, po: {} },
      { name: 'В Г', sor: { s1: { mark: 9, max: 13 } }, soch: { c1: { mark: 17, max: 20 } }, po: {} },
    ],
  };
  const a = A.build(j);
  assert.equal(a.mode, 'sor');
  assert.deepEqual(a.items.map((i) => i.label), ['СОР 1', 'СОР 2', 'СОЧ']);
  const [s1, s2, soch] = a.items;
  assert.deepEqual([s1.wrote, s1.low, s1.mid, s1.quality, s1.success], [2, 1, 1, 50, 50]); // 4/13 = 31 %, 9/13 = 69 %
  assert.equal(s2.wrote, 1); // второй ученик СОР 2 не писал
  assert.deepEqual([soch.mid, soch.high, soch.quality], [1, 1, 50]); // 60 % и 85 %
  assert.equal(A.fileName(a, 'Иностранный язык'), 'Анализ СОР и СОЧ 5 класс (Иностранный язык).xlsx');
  assert.equal(A.difficultiesText(a.items, {
    'СОР 1': { unit: 'Home and away', difficulties: 'ошибки в вопросах.' },
    'СОЧ': { difficulties: 'орфография.' },
  }), 'СОР 1 «Home and away»: ошибки в вопросах.\nСОЧ: орфография.');
  assert.match(A.statsForAi(s1), /Писали 2 уч\., максимум 13 баллов\. Низкий уровень: 1/);
});

test('Справка: Ф.И.О. без заглавных и школа по умолчанию', () => {
  assert.equal(A.shortName('ЕПИШКИН КОНСТАНТИН Иванович'), 'Епишкин Константин');
  assert.equal(A.shortName('Петров Николай'), 'Петров Николай');
  assert.equal(A.shortName('ИВАНОВА-ПЕТРОВА Анна'), 'Иванова-Петрова Анна');
  assert.match(A.SCHOOL, /^КГУ «Николаевская основная средняя школа отдела образования района Беимбета Майлина»$/);
});
