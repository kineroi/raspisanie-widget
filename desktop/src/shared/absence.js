'use strict';
// Посещаемость из нашего журнала → отметки отсутствия в журнале BilimClass. Без Electron, чтобы проверять тестами.
// Наш журнал — по дням для всех классов школы; в BilimClass отсутствие ставится на урок в журнале учителя.
// Поэтому на выбранный день берём уроки учителя и для каждого урока отмечаем отсутствовавших учеников этого класса.
// Как сайт: POST journal-service/attendance/bulk; ставить можно только за проведённый урок.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Absence = factory();
}(typeof self !== 'undefined' ? self : this, () => {
  /** Наши причины → коды BilimClass. */
  const CODE = {
    sick: 'missing_by_sick',
    free: 'missing_due',
    family: 'missing_due',
    other: 'missing_due',
    absent: 'missing_without_reason',
  };
  const PRESENT = 'was_in_class';
  const LABEL = {
    missing_by_sick: 'по болезни',
    missing_due: 'по уважительной причине',
    missing_without_reason: 'без уважительной причины',
    late: 'опоздал',
    was_in_class: 'был на уроке',
  };
  const MISSING = ['missing_by_sick', 'missing_due', 'missing_without_reason'];

  const norm = (s) => String(s || '').toLowerCase().replace(/ё/g, 'е').replace(/[^а-яa-z\s-]/g, ' ').replace(/\s+/g, ' ').trim();

  /** Фамилия и имя — так сверяем ученика в двух журналах (отчества бывают записаны по-разному). */
  function nameKey(name) {
    return norm(name).split(' ').slice(0, 2).join(' ');
  }

  /** Номер и буква класса: «4 класс» → {n: '4', letter: ''}, «4А» → {n: '4', letter: 'а'}. */
  function classKey(name) {
    const m = /(\d{1,2})\s*[«"-]?\s*([А-Яа-яЁё])?(?![а-яё])/.exec(String(name || ''));
    if (!m) return null;
    const letter = m[2] ? norm(m[2]) : '';
    return { n: m[1], letter: letter === 'к' ? '' : letter };
  }

  /** Тот же ли класс: номер совпадает, буква — если указана в обоих. */
  function sameClass(ours, bilim) {
    const a = classKey(ours);
    const b = classKey(bilim);
    if (!a || !b || a.n !== b.n) return false;
    return !a.letter || !b.letter || a.letter === b.letter;
  }

  /**
   * Что поставить на урок одного класса.
   * lesson — урок в журнале BilimClass: { cls, completed, canBeCompleted, canManage, students: [{ id, name, attendance }] };
   * absent — отсутствовавшие в этот день ученики нашего класса: [{ name, reason }];
   * corrected — ученики, которым в нашем журнале отсутствие сняли («был на уроках»): [name].
   * → { send: [{ id, name, code }], unmatched: [name], blocked: '' | причина, почему урок пропущен }
   */
  function plan(lesson, absent, corrected) {
    const out = { send: [], unmatched: [], blocked: '' };
    if (!lesson.canManage) out.blocked = 'журнал не разрешает отмечать этот урок';
    else if (!lesson.completed) out.blocked = lesson.canBeCompleted ? 'урок не отмечен проведённым' : 'урок ещё не прошёл';
    const byKey = new Map(lesson.students.map((s) => [nameKey(s.name), s]));
    for (const a of absent) {
      const s = byKey.get(nameKey(a.name));
      if (!s) {
        out.unmatched.push(a.name);
        continue;
      }
      const code = CODE[a.reason] || 'missing_due';
      if (s.attendance !== code) out.send.push({ id: s.id, name: s.name, code });
    }
    // в нашем журнале отсутствие сняли — снимаем и в BilimClass (опоздания и чужие отметки не трогаем)
    for (const name of corrected) {
      const s = byKey.get(nameKey(name));
      if (s && MISSING.includes(s.attendance)) out.send.push({ id: s.id, name: s.name, code: PRESENT });
    }
    if (out.blocked) out.send = [];
    return out;
  }

  /** Тело запроса, как на сайте: одна отметка на ученика и урок. */
  function bulkBody({ userId, eduYear, schoolId, groupId, subjectId, scheduleUuid, date }, items) {
    return {
      userId,
      attendances: items.map((it) => ({
        userId,
        eduYear: Number(eduYear),
        schoolId: Number(schoolId),
        groupId: Number(groupId),
        subjectId: Number(subjectId),
        studentGroupUuid: it.id,
        scheduleUuid,
        date: `${date}T00:00:00.000Z`,
        attendance: it.code,
      })),
    };
  }

  return { CODE, PRESENT, LABEL, MISSING, nameKey, classKey, sameClass, plan, bulkBody };
}));
