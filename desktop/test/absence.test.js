'use strict';
const test = require('node:test');
const assert = require('node:assert');
const AB = require('../src/shared/absence');

const LESSON = {
  cls: '4А',
  completed: true,
  canBeCompleted: true,
  canManage: true,
  students: [
    { id: 'S1', name: 'ИВАНОВА АННА ПЕТРОВНА', attendance: '' },
    { id: 'S2', name: 'Котов Илья Сергеевич', attendance: 'missing_by_sick' },
    { id: 'S3', name: 'Лис Кирилл', attendance: 'late' },
    { id: 'S4', name: 'Сидоров Пётр', attendance: 'missing_due' },
  ],
};

test('классы и ФИО сверяются без учёта регистра, буквы «ё» и отчества', () => {
  assert.ok(AB.sameClass('4 класс', '4А'));
  assert.ok(AB.sameClass('4 А класс', '4А'));
  assert.ok(!AB.sameClass('4 Б класс', '4А'));
  assert.ok(!AB.sameClass('5 класс', '4А'));
  assert.equal(AB.nameKey('ИВАНОВА АННА ПЕТРОВНА'), AB.nameKey('Иванова Анна'));
  assert.equal(AB.nameKey('Сидоров Петр Иванович'), AB.nameKey('Сидоров Пётр'));
});

test('план урока: новые отсутствия, уже совпадающие, снятые, незнакомые', () => {
  const p = AB.plan(LESSON, [
    { name: 'Иванова Анна Петровна', reason: 'absent' },
    { name: 'Котов Илья', reason: 'sick' }, // уже стоит «по болезни»
    { name: 'Петров Неизвестный', reason: 'family' },
  ], ['Сидоров Пётр', 'Лис Кирилл']);
  assert.deepEqual(p.send, [
    { id: 'S1', name: 'ИВАНОВА АННА ПЕТРОВНА', code: 'missing_without_reason' },
    // отсутствие сняли в нашем журнале — «был»; опоздание (Лис) не трогаем
    { id: 'S4', name: 'Сидоров Пётр', code: 'was_in_class' },
  ]);
  assert.deepEqual(p.unmatched, ['Петров Неизвестный']);
  assert.equal(p.blocked, '');
});

test('план: урок не проведён или нет прав — ничего не отправляем', () => {
  assert.equal(AB.plan({ ...LESSON, completed: false }, [{ name: 'Лис Кирилл', reason: 'sick' }], []).blocked, 'урок не отмечен проведённым');
  assert.equal(AB.plan({ ...LESSON, completed: false, canBeCompleted: false }, [], []).blocked, 'урок ещё не прошёл');
  const p = AB.plan({ ...LESSON, canManage: false }, [{ name: 'Лис Кирилл', reason: 'sick' }], []);
  assert.deepEqual(p.send, []);
  assert.ok(p.blocked);
});

test('коды причин и тело запроса — как у сайта', () => {
  assert.equal(AB.CODE.sick, 'missing_by_sick');
  assert.equal(AB.CODE.absent, 'missing_without_reason');
  assert.equal(AB.CODE.free, 'missing_due');
  const body = AB.bulkBody({ userId: 97, eduYear: 2026, schoolId: '1007', groupId: 11, subjectId: 22, scheduleUuid: 'L1', date: '2026-11-02' },
    [{ id: 'S1', code: 'missing_by_sick' }]);
  assert.deepEqual(body, {
    userId: 97,
    attendances: [{
      userId: 97, eduYear: 2026, schoolId: 1007, groupId: 11, subjectId: 22, studentGroupUuid: 'S1', scheduleUuid: 'L1',
      date: '2026-11-02T00:00:00.000Z', attendance: 'missing_by_sick',
    }],
  });
});
