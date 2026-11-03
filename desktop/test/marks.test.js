'use strict';
const test = require('node:test');
const assert = require('node:assert');
const M = require('../src/shared/marks');

const QUARTER = {
  data: {
    journalMaxScore: '10',
    lessonSchedules: [
      { uuid: 'L1', date: '02.11.26', isCompletedLesson: true, canBeCompleted: true, markMax: 10, canManageScores: true, type: 'regular' },
      { uuid: 'L2', date: '04.11.26', isCompletedLesson: false, canBeCompleted: true, markMax: 10, canManageScores: true, type: 'regular' },
    ],
    rows: [
      { studentGroupUuid: 'S2', fullName: 'Петров Пётр' },
      { studentGroupUuid: 'S1', fullName: 'Иванова Анна' },
      { studentGroupUuid: 'S3', fullName: 'Выбывший Ученик', departDate: '01.10.2026' },
    ],
  },
};

const MARKS = {
  data: {
    S1: { studentGroupUuid: 'S1', marks: { L1: { attendance: '', formattedScore: { mark: 7, comment: 'Хорошо', markUuid: 'M1', assessmentMax: 10, type: 'formattedScore' } } } },
    S2: { studentGroupUuid: 'S2', marks: {} },
  },
};

test('урок в журнале: ученики по алфавиту, выбывшие скрыты, уже стоящие оценки', () => {
  const l = M.parseLesson(QUARTER, MARKS, 'L1');
  assert.equal(l.found, true);
  assert.equal(l.completed, true);
  assert.equal(l.canManage, true);
  assert.equal(l.markMax, 10);
  assert.deepEqual(l.students, [
    { id: 'S1', name: 'Иванова Анна', mark: 7, comment: 'Хорошо', markUuid: 'M1', attendance: '' },
    { id: 'S2', name: 'Петров Пётр', mark: null, comment: '', markUuid: '', attendance: '' },
  ]);
  assert.equal(M.parseLesson(QUARTER, MARKS, 'L2').completed, false);
  assert.equal(M.parseLesson(QUARTER, MARKS, 'нет').found, false);
});

test('что отправить: новая оценка, изменение, только комментарий, без изменений', () => {
  const l = M.parseLesson(QUARTER, MARKS, 'L1');
  assert.deepEqual(M.changes(l.students, {
    S1: { mark: '7', comment: 'Хорошо' },
    S2: { mark: '9', comment: 'Молодец' },
  }, 10), [{ id: 'S2', mark: 9, comment: 'Молодец', markUuid: '', kind: 'create' }]);
  assert.deepEqual(M.changes(l.students, { S1: { mark: '8', comment: 'Хорошо' } }, 10),
    [{ id: 'S1', mark: 8, comment: 'Хорошо', markUuid: 'M1', kind: 'update' }]);
  // балл стёрт, а комментарий изменён — меняем только комментарий у стоящей оценки
  assert.deepEqual(M.changes(l.students, { S1: { mark: '', comment: 'Отлично' } }, 10),
    [{ id: 'S1', mark: 7, comment: 'Отлично', markUuid: 'M1', kind: 'update' }]);
  // новая оценка без балла и балл вне шкалы не отправляются
  assert.deepEqual(M.changes(l.students, { S2: { mark: '', comment: 'Молодец' } }, 10), []);
  assert.deepEqual(M.changes(l.students, { S2: { mark: '11', comment: '' } }, 10), []);
});

test('тела запросов — как у сайта BilimClass', () => {
  const ctx = { userId: 97, eduYear: 2026, schoolId: '1007', groupId: 11, subjectId: 22, scheduleUuid: 'L1', date: '2026-11-02', markMax: 10 };
  assert.deepEqual(M.createBody(ctx, { id: 'S2', mark: 9, comment: 'Молодец' }), {
    userId: 97, eduYear: 2026, schoolId: 1007, groupId: 11, subjectId: 22, studentGroupUuid: 'S2', scheduleUuid: 'L1',
    date: '2026-11-02T00:00:00.000Z', markType: 'formattedScore', mark: 9, markMax: 10, comment: 'Молодец',
  });
  assert.deepEqual(M.updateBody(ctx, { id: 'S1', mark: 8, comment: '', markUuid: 'M1' }),
    { userId: 97, uuid: 'M1', markType: 'formattedScore', mark: 8, markMax: 10, comment: '' });
});

test('комментарий на двух языках не длиннее 250 символов', () => {
  assert.equal(M.fitComment('Молодец, хорошо работал.', 'Well done, good work.'), 'Молодец, хорошо работал.\nWell done, good work.');
  const ru = 'Очень длинное предложение '.repeat(12).trim();
  const en = 'A very long sentence '.repeat(12).trim();
  const c = M.fitComment(ru, en);
  assert.ok(c.length <= 250, String(c.length));
  const [a, b] = c.split('\n');
  assert.ok(a.endsWith('…') && b.endsWith('…'));
  assert.ok(a.startsWith('Очень') && b.startsWith('A very'));
  assert.equal(M.fitComment('', 'Only English'), 'Only English');
  assert.ok(M.systemPrompt(249).includes('не длиннее 124 символов'));
});

test('даты и баллы', () => {
  assert.equal(M.journalDate('2026-11-02'), '02.11.26');
  assert.equal(M.isoFromDmy('02.11.2026'), '2026-11-02');
  assert.equal(M.isoFromDmy('27.12.26'), '2026-12-27');
  assert.ok(M.validMark('0', 10) && M.validMark(10, 10));
  assert.ok(!M.validMark('', 10) && !M.validMark('7.5', 10) && !M.validMark(11, 10));
});
