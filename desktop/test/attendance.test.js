'use strict';
const test = require('node:test');
const assert = require('node:assert');
const T = require('../src/shared/attendance');

test('ФИО приводятся к нормальному виду', () => {
  assert.equal(T.fixName('ИВАНОВ ИВАН ИВАНОВИЧ'), 'Иванов Иван Иванович');
  assert.equal(T.fixName('Петров ПетрПетрович '), 'Петров Петр Петрович');
  // латинская «a» в русской фамилии
  assert.equal(T.fixName('Сидоровa Анна'), 'Сидорова Анна');
  assert.equal(T.fixName('мамедов али гусейн оглы'), 'Мамедов Али Гусейн оглы');
  assert.equal(T.fixName('Римская-корсакова Ольга'), 'Римская-Корсакова Ольга');
});

test('учебные дни: будни внутри четвертей, без праздников', () => {
  const j = T.empty();
  const nov = T.schoolDays(j, '2026-11');
  assert.equal(nov[0], '2026-11-02');
  assert.equal(nov.length, 21);
  const dec = T.schoolDays(j, '2026-12');
  assert.ok(!dec.includes('2026-12-16'), 'День Независимости');
  assert.equal(dec[dec.length - 1], '2026-12-25');
  assert.deepEqual(T.schoolDays(j, '2027-01').slice(0, 1), ['2027-01-11']);
  assert.ok(!T.schoolDays(j, '2027-03').includes('2027-03-23'), 'каникулы');
  assert.equal(T.schoolDays(j, '2027-05').slice(-1)[0], '2027-05-25');
  assert.deepEqual(T.months(), ['2026-11', '2026-12', '2027-01', '2027-02', '2027-03', '2027-04', '2027-05']);
  assert.equal(T.defaultMonth('2026-10-29'), '2026-11');
  assert.equal(T.defaultMonth('2027-01-20'), '2027-01');
  assert.equal(T.monthTitle('2026-11'), 'Ноябрь 2026');
});

test('выходной и учебный день вручную', () => {
  const j = T.empty();
  T.setSchoolDay(j, '2026-11-06', false);
  assert.ok(!T.schoolDays(j, '2026-11').includes('2026-11-06'));
  assert.deepEqual(j.offDays, ['2026-11-06']);
  T.setSchoolDay(j, '2026-11-06', true);
  assert.deepEqual(j.offDays, []);
  T.setSchoolDay(j, '2026-12-16', true);
  assert.ok(T.schoolDays(j, '2026-12').includes('2026-12-16'));
  assert.ok(T.monthColumns(j, '2026-12').some((c) => c.date === '2026-12-16' && c.school));
});

test('отметки и итоги месяца', () => {
  const j = T.empty();
  const c = T.addClass(j, '4 класс');
  const s = T.addStudent(c, 'Иванов Иван');
  T.setMark(j, '2026-11-02', s.id, 'sick');
  T.setMark(j, '2026-11-03', s.id, 'absent');
  T.setMark(j, '2026-11-04', s.id, 'other', '  соревнования ');
  assert.deepEqual(T.getMark(j, '2026-11-04', s.id), { r: 'other', note: 'соревнования' });
  assert.equal(T.markCode(T.getMark(j, '2026-11-03', s.id)), 'н');
  assert.deepEqual(T.monthStats(j, '2026-11', s.id), { total: 3, invalid: 1, by: { sick: 1, absent: 1, other: 1 } });
  T.setMark(j, '2026-11-02', s.id, '');
  assert.equal(T.getMark(j, '2026-11-02', s.id), null);
  assert.ok(!j.marks['2026-11-02']);
  T.removeStudent(j, c, s.id);
  assert.deepEqual(j.marks, {});
});

test('предупреждения: долго болеет и пропуски без уважительной причины', () => {
  const j = T.empty();
  const c = T.addClass(j, '5 класс');
  const a = T.addStudent(c, 'Болеющий Ученик');
  const b = T.addStudent(c, 'Прогульщик Ученик');
  const d = T.addStudent(c, 'Хороший Ученик');
  for (const day of ['2026-11-05', '2026-11-06', '2026-11-09', '2026-11-10']) T.setMark(j, day, a.id, 'sick');
  T.setMark(j, '2026-11-03', b.id, 'absent');
  T.setMark(j, '2026-11-09', b.id, 'absent');
  T.setMark(j, '2026-11-10', d.id, 'sick');

  // 11 ноября ещё не отмечали — болеет 4 учебных дня по 10-е включительно
  const list = T.alerts(j, '2026-11-11');
  assert.equal(list.length, 2);
  assert.equal(list[0].kind, 'sick');
  assert.equal(list[0].days, 4);
  assert.equal(list[0].since, '2026-11-05');
  assert.equal(T.alertText(list[0]), 'Болеющий Ученик (5 класс) болеет 4 учебных дня подряд — с 05.11');
  assert.equal(list[1].kind, 'absent');
  assert.equal(T.alertText(list[1]), 'Прогульщик Ученик (5 класс): 2 пропуска без уважительной причины за 30 дней, последний 09.11');

  // пришёл 11-го — болезнь закончилась
  T.setMark(j, '2026-11-11', a.id, '');
  assert.equal(T.alerts(j, '2026-11-12').filter((x) => x.kind === 'sick').length, 0);
  // через месяц старые прогулы уже не считаются
  assert.equal(T.alerts(j, '2026-12-15').length, 0);
});

test('список из шаблона Word', () => {
  const p = (t) => `<w:p><w:r><w:t xml:space="preserve">${t}</w:t></w:r></w:p>`;
  const tc = (t) => `<w:tc><w:tcPr/>${p(t)}</w:tc>`;
  const tr = (...cells) => `<w:tr w:rsidR="1">${cells.map(tc).join('')}</w:tr>`;
  const xml = '<w:body>'
    + p('Список учащихся 9 класса СЕНТЯБРЬ')
    + `<w:tbl>${tr('№', 'ФИО', '1', '2')}${tr('1', 'ПЕТРОВ ПЁТР', '', '')}${tr('2', 'Сидоров Сидор<', '', '')}</w:tbl>`
    + p('Список учащихся 2 «Б» класса')
    + `<w:tbl>${tr('№', 'ФИО')}${tr('1', 'Иванова Анна')}</w:tbl>`
    + '</w:body>';
  const parsed = T.parseTemplate(xml.replace('Сидор<', 'Сидор&lt;'));
  assert.deepEqual(parsed, [
    { name: '9 класс', students: ['Петров Пётр', 'Сидоров Сидор<'] },
    { name: '2 Б класс', students: ['Иванова Анна'] },
  ]);
  const j = T.empty();
  assert.deepEqual(T.importClasses(j, parsed), { classes: 2, students: 3 });
  assert.deepEqual(T.importClasses(j, parsed), { classes: 0, students: 0 });
  assert.deepEqual(T.sortedClasses(j).map((c) => c.name), ['2 Б класс', '9 класс']);
});

test('выгрузка в Word: таблица класса, коды, итоги, примечания', () => {
  const j = T.empty();
  const c = T.addClass(j, '4 класс');
  const s = T.addStudent(c, 'Иванов Иван Иванович');
  T.setMark(j, '2026-11-02', s.id, 'absent');
  T.setMark(j, '2026-11-03', s.id, 'other', 'олимпиада <район>');
  const files = T.docxFiles(j, '2026-11');
  assert.deepEqual(Object.keys(files).sort(), ['[Content_Types].xml', '_rels/.rels', 'word/document.xml']);
  const xml = files['word/document.xml'];
  assert.ok(xml.includes('Список учащихся 4 класса НОЯБРЬ 2026'));
  assert.ok(xml.includes('w:orient="landscape"'));
  assert.ok(xml.includes('>Иванов Иван Иванович<'));
  assert.ok(xml.includes('w:fill="F2DCDB"'), 'прогул выделен');
  assert.ok(xml.includes('Иванов И.И. (4 класс), 03.11: олимпиада &lt;район&gt;'));
  // 21 учебный день + №, ФИО, Всего, Без ув. пр.
  const header = xml.split('<w:tr>')[1];
  assert.equal((header.match(/<w:tc>/g) || []).length, 25);
  assert.equal(T.fileName('2026-11'), 'Посещаемость — Ноябрь 2026.docx');
});
