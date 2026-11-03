'use strict';
// Оценки за урок (ФО) с комментарием → журнал BilimClass. Без Electron, чтобы проверять тестами.
// Так же, как сайт bilimclass.kz: новая оценка — POST journal-service/mark, изменение — PUT по markUuid.
// Ставить можно только за урок, отмеченный проведённым, и если журнал разрешает учителю (canManageScores).
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Marks = factory();
}(typeof self !== 'undefined' ? self : this, () => {
  /** Столько символов BilimClass принимает в комментарии к оценке (поле на сайте, max-length 250). */
  const COMMENT_MAX = 250;

  /**
   * Подсказка нейросети: заметка учителя → комментарий на русском и английском. Имени и оценки в запросе нет.
   * max — сколько символов на обе версии; каждой даём половину: так модели соблюдают предел надёжнее.
   */
  function systemPrompt(max) {
    const each = Math.floor((max - 1) / 2);
    return 'Ты помогаешь учителю английского языка в школе Казахстана писать комментарий к оценке ученика '
      + 'в электронном журнале. Учитель даёт короткую заметку (может быть с сокращениями и опечатками). '
      + 'Сделай из неё вежливый понятный комментарий для ученика и родителей на русском языке и тот же смысл на английском.\n'
      + 'Правила: ничего не придумывай сверх заметки; не добавляй имён и обращений; не упоминай балл, если его нет в заметке; '
      + 'если заметка длинная — оставь главное, сократи второстепенное; '
      + `русская версия — не длиннее ${each} символов, английская — не длиннее ${each} символов (с пробелами); `
      + 'каждая версия — законченная фраза, без многоточия в конце.\n'
      + 'Верни ТОЛЬКО объект JSON без пояснений: {"ru": "…", "en": "…"}';
  }

  /** Обрезать по границе слова, чтобы влезло в n символов (с многоточием). */
  function cut(s, n) {
    const t = String(s || '').replace(/\s+/g, ' ').trim();
    if (t.length <= n) return t;
    if (n <= 1) return '';
    const room = t.slice(0, n - 1);
    const sp = room.lastIndexOf(' ');
    return (sp > n * 0.5 ? room.slice(0, sp) : room).replace(/[\s,.;:—-]+$/, '') + '…';
  }

  /**
   * Комментарий из двух строк: русская, затем английская — не длиннее max символов вместе.
   * Если не влезает, обе части укорачиваются пропорционально.
   */
  function fitComment(ru, en, max = COMMENT_MAX) {
    const a = String(ru || '').replace(/\s+/g, ' ').trim();
    const b = String(en || '').replace(/\s+/g, ' ').trim();
    if (!a || !b) return cut(a || b, max);
    if (a.length + 1 + b.length <= max) return `${a}\n${b}`;
    const room = max - 1;
    let na = Math.floor((room * a.length) / (a.length + b.length));
    na = Math.min(a.length, Math.max(1, na));
    const nb = room - na;
    return `${cut(a, na)}\n${cut(b, nb)}`;
  }

  /** «2026-11-02» → «02.11.26» (так даты уроков в журнале). */
  const journalDate = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(2, 4)}`;

  /** «02.11.2026» или «02.11.26» → «2026-11-02». */
  function isoFromDmy(s) {
    const [d, m, y] = String(s || '').split('.');
    if (!d || !m || !y) return '';
    return `${y.length === 2 ? '20' + y : y}-${m}-${d}`;
  }

  /** Допустимый балл: целое от 0 до max. */
  function validMark(v, max) {
    if (v === '' || v == null) return false;
    const n = Number(v);
    return Number.isInteger(n) && n >= 0 && n <= Number(max || 10);
  }

  /**
   * Урок в журнале класса: ответ «quarter» (уроки и ученики) + ответ journal-service (оценки) →
   * { found, scheduleUuid, markMax, completed, canBeCompleted, canManage, students: [{ id, name, mark, comment, markUuid }] }.
   */
  function parseLesson(quarter, marks, scheduleUuid) {
    const q = (quarter && (quarter.data || quarter)) || {};
    const col = (q.lessonSchedules || []).find((s) => s.uuid === scheduleUuid);
    if (!col) return { found: false, scheduleUuid, students: [] };
    const byStudent = (marks && (marks.data || marks)) || {};
    const students = (q.rows || [])
      .filter((r) => r.studentStatus !== false && !r.departDate)
      .map((r) => {
        const cell = byStudent[r.studentGroupUuid] && byStudent[r.studentGroupUuid].marks
          && byStudent[r.studentGroupUuid].marks[scheduleUuid];
        const f = (cell && cell.formattedScore) || {};
        return {
          id: r.studentGroupUuid,
          name: r.fullName || '',
          mark: typeof f.mark === 'number' ? f.mark : null,
          comment: f.comment || '',
          markUuid: f.markUuid || '',
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    return {
      found: true,
      scheduleUuid,
      markMax: Number(col.markMax || q.journalMaxScore || 10),
      completed: !!col.isCompletedLesson,
      canBeCompleted: col.canBeCompleted !== false,
      canManage: col.canManageScores !== false,
      students,
    };
  }

  /**
   * Что отправить: правки учителя { [id]: { mark, comment } } против того, что уже в журнале.
   * Новая оценка без балла не ставится; удалить оценку отсюда нельзя (только на сайте).
   * → [{ id, mark, comment, markUuid, kind: 'create' | 'update' }]
   */
  function changes(students, edits, max) {
    const out = [];
    for (const s of students) {
      const e = edits[s.id];
      if (!e) continue;
      const mark = e.mark === '' || e.mark == null ? null : Number(e.mark);
      const comment = String(e.comment == null ? s.comment : e.comment).trim();
      if (mark == null) {
        // без балла можно только поправить комментарий к уже стоящей оценке
        if (s.markUuid && s.mark != null && comment !== s.comment) {
          out.push({ id: s.id, mark: s.mark, comment, markUuid: s.markUuid, kind: 'update' });
        }
        continue;
      }
      if (!validMark(mark, max)) continue;
      if (mark === s.mark && comment === s.comment) continue;
      out.push({ id: s.id, mark, comment, markUuid: s.markUuid, kind: s.markUuid ? 'update' : 'create' });
    }
    return out;
  }

  /** Тело запроса «поставить оценку» (POST), как на сайте. */
  function createBody({ userId, eduYear, schoolId, groupId, subjectId, scheduleUuid, date, markMax }, ch) {
    return {
      userId,
      eduYear: Number(eduYear),
      schoolId: Number(schoolId),
      groupId: Number(groupId),
      subjectId: Number(subjectId),
      studentGroupUuid: ch.id,
      scheduleUuid,
      date: `${date}T00:00:00.000Z`,
      markType: 'formattedScore',
      mark: ch.mark,
      markMax: Number(markMax),
      comment: ch.comment,
    };
  }

  /** Тело запроса «изменить оценку» (PUT), как на сайте. */
  function updateBody({ userId, markMax }, ch) {
    return {
      userId,
      uuid: ch.markUuid,
      markType: 'formattedScore',
      mark: ch.mark,
      markMax: Number(markMax),
      comment: ch.comment,
    };
  }

  return {
    COMMENT_MAX, systemPrompt, cut, fitComment, journalDate, isoFromDmy, validMark, parseLesson, changes, createBody, updateBody,
  };
}));
