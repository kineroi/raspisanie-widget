'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const T = require('../src/shared/attestation.js');

test('разбор ответа нейросети', () => {
  assert.deepEqual(T.parseClassification({
    section: 'awards', level: 'district', date: '2026-10-15', kind: 'Грамота', title: '«Лучший урок»', issuer: 'Районный отдел образования',
  }), { section: 'awards', level: 'district', date: '2026-10-15', kind: 'Грамота', title: 'Лучший урок', issuer: 'Районный отдел образования' });

  // Неизвестный раздел и уровень, кривая дата, нет вида — безопасные значения
  assert.deepEqual(T.parseClassification({ section: 'medals', level: 'city', date: '15.10.2026', title: '' }),
    { section: 'other', level: '', date: '', kind: 'Документ', title: '', issuer: '' });
  assert.equal(T.parseClassification({ section: 'courses' }).kind, 'Сертификат');
});

test('папка и имя файла', () => {
  assert.deepEqual(T.target({ section: 'awards', level: 'district', date: '2026-10-15', kind: 'Грамота', title: 'Лучший урок' }, '.PDF'),
    { parts: ['2. Грамоты и благодарности', 'Районный'], name: '2026-10-15 Грамота — Лучший урок.pdf' });
  // Республиканский и международный — одна папка
  assert.deepEqual(T.target({ section: 'students', level: 'international', date: '', kind: 'Диплом', title: '' }, '.jpg').parts,
    ['4. Достижения учеников', 'Республиканский и международный']);
  // Уровень не распознан, а раздел с уровнями
  assert.equal(T.target({ section: 'contests', level: '', kind: 'Диплом', title: 'x' }, '.pdf').parts[1], 'Уровень не указан');
  // Разделы без уровней и запрещённые в Windows символы
  assert.deepEqual(T.target({ section: 'courses', level: 'region', date: '2026-01-10', kind: 'Сертификат', title: 'Курс: "ИКТ" / 72 ч?' }, '.png'),
    { parts: ['1. Курсы повышения квалификации'], name: '2026-01-10 Сертификат — Курс ИКТ 72 ч.png' });
  assert.equal(T.target({ section: 'other', kind: '', title: '' }, '.pdf').name, 'Без даты Документ.pdf');
});

test('занятые имена файлов', () => {
  const taken = new Set(['a.pdf', 'a (2).pdf']);
  assert.equal(T.uniqueName('b.pdf', (n) => taken.has(n)), 'b.pdf');
  assert.equal(T.uniqueName('a.pdf', (n) => taken.has(n)), 'a (3).pdf');
});
