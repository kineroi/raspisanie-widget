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
  T.setSchoolDay(j, '2026-11-06', true);
  assert.ok(T.schoolDays(j, '2026-11').includes('2026-11-06'));
  assert.equal(j.days['2026-11-06'].school, null, 'снова как обычно');
  // старый вид журнала
  const old = T.normalize({ classes: [], marks: {}, offDays: ['2026-11-09'], onDays: ['2026-11-07'] });
  assert.ok(!T.isSchoolDay(old, '2026-11-09'));
  assert.ok(T.isSchoolDay(old, '2026-11-07'));
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
  assert.equal(T.getMark(j, '2026-11-04', s.id).note, 'соревнования');
  assert.equal(T.markCode(T.getMark(j, '2026-11-03', s.id)), 'н');
  assert.deepEqual(T.monthStats(j, '2026-11', s.id), { total: 3, invalid: 1, by: { sick: 1, absent: 1, other: 1 } });
  T.setMark(j, '2026-11-02', s.id, '');
  assert.equal(T.getMark(j, '2026-11-02', s.id), null);
  T.removeStudent(j, c, s.id);
  assert.equal(T.sortedStudents(c).length, 0);
});

test('синхронизация: объединение журналов компьютера и телефона', () => {
  let now = 1000;
  T.setClock(() => now);
  try {
    // общий журнал, который уже был на обоих устройствах
    const base = T.empty();
    const c = T.addClass(base, '4 класс');
    const a = T.addStudent(c, 'Иванов Иван');
    const b = T.addStudent(c, 'Петров Пётр');
    const pc = T.merge(base, {});
    const phone = T.merge(base, {});

    now = 2000;
    T.setMark(pc, '2026-11-02', a.id, 'sick');
    T.setMark(phone, '2026-11-02', b.id, 'absent');
    T.setMark(phone, '2026-11-03', a.id, 'free');
    now = 3000;
    T.setMark(pc, '2026-11-03', a.id, ''); // на компьютере позже исправили: был
    T.removeStudent(phone, phone.classes[0], b.id);
    T.setSchoolDay(pc, '2026-11-06', false);

    const m1 = T.merge(pc, phone);
    const m2 = T.merge(phone, pc);
    assert.ok(T.same(m1, m2), 'порядок не важен');
    assert.equal(T.getMark(m1, '2026-11-02', a.id).r, 'sick');
    assert.equal(T.getMark(m1, '2026-11-03', a.id), null, 'побеждает более позднее');
    assert.deepEqual(T.sortedStudents(T.sortedClasses(m1)[0]).map((s) => s.name), ['Иванов Иван'], 'удалённый не воскресает');
    assert.ok(!T.isSchoolDay(m1, '2026-11-06'));
    assert.ok(T.same(T.merge(m1, m1), m1), 'повторное объединение ничего не меняет');
  } finally {
    T.setClock(() => Date.now());
  }
});

test('синхронизация: списки, загруженные на обоих устройствах, не задваиваются', () => {
  const tpl = [{ name: '4 класс', students: ['Иванов Иван', 'Петров Пётр'] }];
  const pc = T.empty();
  T.importClasses(pc, tpl);
  const phone = T.empty();
  T.importClasses(phone, tpl);
  T.importClasses(phone, [{ name: '5 класс', students: ['Сидоров Сидор'] }]);
  const ivanPhone = T.sortedStudents(phone.classes[0]).find((s) => s.name === 'Иванов Иван');
  T.setMark(phone, '2026-11-02', ivanPhone.id, 'sick');

  const m = T.merge(pc, phone);
  assert.deepEqual(T.sortedClasses(m).map((c) => c.name), ['4 класс', '5 класс']);
  const four = T.sortedClasses(m)[0];
  assert.deepEqual(T.sortedStudents(four).map((s) => s.name), ['Иванов Иван', 'Петров Пётр']);
  const ivan = T.sortedStudents(four)[0];
  assert.equal(T.getMark(m, '2026-11-02', ivan.id).r, 'sick', 'отметка перенесена на оставшегося');
  // и с другой стороны получится то же самое
  const back = T.merge(phone, m);
  assert.ok(T.same(T.merge(m, back), back));
  assert.equal(T.sortedStudents(T.sortedClasses(back)[0]).length, 2);
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

/** Выдуманная школа с теми же трудностями, что в настоящей: одна фамилия в разных классах, брат и сестра. */
function school() {
  const j = T.empty();
  const add = (cls, names) => {
    const c = T.addClass(j, cls);
    for (const n of names) T.addStudent(c, n);
  };
  add('3 класс', ['Котов Николай Петрович', 'Котова Алиса Денисовна']);
  add('4 класс', ['Котов Александр Петрович', 'Лис Кирилл Сергеевич', 'Иванова Анна Петровна']);
  add('5 класс', ['Котов Сергей Денисович']);
  add('6 класс', ['Лис Евгений Сергеевич']);
  return j;
}

const pick = (r) => r.items.map((x) => [x.name.split(' ').slice(0, 2).join(' '), x.cls, x.reason]);

test('ответ из WhatsApp: класс, несколько учеников, причина', () => {
  const r = T.parseReply(school(), '4 кл: Котов, Иванова болеют');
  assert.deepEqual(pick(r), [['Котов Александр', '4 класс', 'sick'], ['Иванова Анна', '4 класс', 'sick']]);
  assert.deepEqual(r.unknown, []);
});

test('ответ из WhatsApp: одинаковые фамилии различаются по имени, падежи', () => {
  const j = school();
  assert.deepEqual(pick(T.parseReply(j, 'Котов Николай болеет')), [['Котов Николай', '3 класс', 'sick']]);
  assert.deepEqual(pick(T.parseReply(j, 'Котова Алиса — соревнования')), [['Котова Алиса', '3 класс', 'other']]);
  assert.equal(T.parseReply(j, 'Котова Алиса — соревнования').items[0].note, 'Котова Алиса — соревнования');
  // «у Котова Сергея температура»
  assert.deepEqual(pick(T.parseReply(j, 'у Котова Сергея температура')), [['Котов Сергей', '5 класс', 'sick']]);
  // только фамилия и класс не назван — непонятно, кто именно
  const amb = T.parseReply(j, 'Котов отсутствует');
  assert.equal(amb.items.length, 1);
  assert.equal(amb.items[0].sid, '');
  assert.equal(amb.items[0].reason, '');
  assert.deepEqual(amb.items[0].options.map((o) => o.cls), ['3 класс', '4 класс', '5 класс']);
});

test('ответ из WhatsApp: несколько сообщений, причина отдельной строкой, все на месте, незнакомые', () => {
  const text = '[01.11.26, 09:05] Мария Ивановна: 6 класс\nЛис\nпо семейным\n'
    + '[01.11.26, 09:07] Ольга Петровна: 3 класс все на месте\n'
    + '[01.11.26, 09:10] Нина Сергеевна: 5 кл: Сергей без уважительной причины\n'
    + 'Сидоров болеет';
  const r = T.parseReply(school(), text);
  assert.deepEqual(pick(r), [['Лис Евгений', '6 класс', 'family'], ['Котов Сергей', '5 класс', 'absent']]);
  assert.deepEqual(r.present, ['3 класс']);
  assert.deepEqual(r.unknown, ['Сидоров']);
});

test('ответ из WhatsApp: класс с буквой без слова «класс»', () => {
  const r = T.parseReply(school(), '4а Иванова, Лис - освобождение');
  assert.deepEqual(pick(r), [['Иванова Анна', '4 класс', 'free'], ['Лис Кирилл', '4 класс', 'free']]);
});

test('ответ из WhatsApp: причина перед списком', () => {
  const r = T.parseReply(school(), '4 класс\nБолеют:\nЛис Кирилл\nИванова');
  assert.deepEqual(pick(r), [['Лис Кирилл', '4 класс', 'sick'], ['Иванова Анна', '4 класс', 'sick']]);
});

test('дни рождения: разбор таблицы, ФИО, плохие строки', () => {
  const r = T.parseBirthdays('СИДОРОВ\tПЁТР\tИВАНОВИЧ\t29.04.2013\r\n\nКотова Анна 5.1.2014\nБез даты Ученик\nЛис Кирилл 31.02.2013');
  assert.deepEqual(r.items, [
    { name: 'Сидоров Пётр Иванович', date: '2013-04-29' },
    { name: 'Котова Анна', date: '2014-01-05' },
  ]);
  assert.deepEqual(r.bad, ['Без даты Ученик', 'Лис Кирилл 31.02.2013']);
});

test('дни рождения: порядок от сегодня, возраст, 29 февраля', () => {
  const j = T.empty();
  T.addBirthdays(j, [
    { name: 'Сидоров Пётр', date: '2013-11-06' },
    { name: 'Котова Анна', date: '2014-01-05' },
    { name: 'Лис Кирилл', date: '2012-02-29' },
    { name: 'Прошлый Ученик', date: '2013-11-01' },
  ]);
  const up = T.upcomingBirthdays(j, '2026-11-06');
  assert.deepEqual(up.map((b) => [b.name, b.next, b.age, b.daysLeft]), [
    ['Сидоров Пётр', '2026-11-06', 13, 0],
    ['Котова Анна', '2027-01-05', 13, 60],
    ['Лис Кирилл', '2027-02-28', 15, 114],
    ['Прошлый Ученик', '2027-11-01', 14, 360],
  ]);
  assert.equal(T.birthdayText(up[0]), 'Сидоров Пётр, 13 лет');
  assert.equal(T.nextBirthday('2012-02-29', '2028-01-01'), '2028-02-29');
});

test('дни рождения: один список на двух устройствах не задваивается, удаление синхронизируется', () => {
  let now = 1000;
  T.setClock(() => now);
  try {
    const list = [{ name: 'Сидоров Пётр', date: '2013-04-29' }];
    const pc = T.empty();
    const phone = T.empty();
    assert.deepEqual(T.addBirthdays(pc, list), { added: 1, known: 0 });
    assert.deepEqual(T.addBirthdays(pc, list), { added: 0, known: 1 });
    now = 2000;
    T.addBirthdays(phone, list);
    const m = T.merge(pc, phone);
    assert.equal(T.upcomingBirthdays(m, '2026-11-06').length, 1);
    now = 3000;
    T.removeBirthday(pc, pc.birthdays[0].id);
    const m2 = T.merge(pc, m);
    assert.equal(T.upcomingBirthdays(m2, '2026-11-06').length, 0);
    assert.ok(T.same(T.merge(m2, phone), T.merge(phone, m2)));
  } finally {
    T.setClock(() => Date.now());
  }
});
