/*
 * Общая логика «Моё расписание» для Windows (тот же смысл, что в Android-версии):
 *  - запасное расписание и данные BilimClass,
 *  - что показывать сейчас (урок / перемена / окно / выходной),
 *  - раскрытие сокращений домашки без ИИ,
 *  - запрос и ответ Gemini.
 * Работает и в Node (require), и в окнах программы (window.Logic).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Logic = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // ---------- Даты и время (локальные, без часовых поясов) ----------

  const DAY_NAMES = ['Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота', 'Воскресенье'];
  const IN_DAY = ['В понедельник', 'Во вторник', 'В среду', 'В четверг', 'В пятницу', 'В субботу', 'В воскресенье'];
  const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
    'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

  const pad = (n) => String(n).padStart(2, '0');
  /** 'YYYY-MM-DD' для объекта Date (по местному времени). */
  const isoDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  const parseIso = (s) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const addDays = (d, n) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n);
  /** 0 = понедельник … 6 = воскресенье */
  const dow = (d) => (d.getDay() + 6) % 7;
  const mondayOf = (d) => addDays(d, -dow(d));
  const dateText = (d) => `${d.getDate()} ${MONTHS[d.getMonth()]}`;
  /** минуты от полуночи для 'HH:MM' */
  const toMin = (hm) => { const [h, m] = hm.split(':').map(Number); return h * 60 + m; };
  const range = (l) => `${l.start}–${l.end}`;
  const eduYear = (d) => (d.getMonth() + 1 >= 8 ? d.getFullYear() : d.getFullYear() - 1);

  // ---------- Запасное расписание ----------

  const MAX_BREAK_MINUTES = 40;

  const TEMPLATE = [
    // Понедельник
    [['08:30', '09:00', 'Кл. час, 9 класс (каб. 6)'], ['09:00', '09:45', '7А + 8А'],
      ['09:50', '10:35', '5А + 6А'], ['10:40', '11:25', '9А'], ['12:45', '13:30', '4А']],
    // Вторник
    [['12:45', '13:30', '4А'], ['13:35', '14:20', '3А (каб. 4)']],
    // Среда
    [['09:00', '09:45', '7А + 8А'], ['09:50', '10:35', '5А + 6А']],
    // Четверг
    [['09:50', '10:35', '5А + 6А'], ['11:55', '12:40', '9А'], ['12:45', '13:30', '7А + 8А']],
    // Пятница
    [['09:00', '09:45', '9А'], ['12:45', '13:30', '3А (каб. 4)']],
    [], [],
  ];

  function templateDay(d) {
    return {
      holiday: false,
      fromBilim: false,
      lessons: TEMPLATE[dow(d)].map(([start, end, title]) => ({ start, end, title, details: [] })),
    };
  }

  /** Источник: дни из BilimClass ({'YYYY-MM-DD': day}), а где их нет — запасное расписание. */
  function makeSource(days) {
    return (d) => (days && days[isoDate(d)]) || templateDay(d);
  }

  // ---------- Разбор ответа BilimClass ----------

  const NO_HOMEWORK = 'не проставляется';
  const clean = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim();
  const compactClass = (label) => {
    const s = String(label || '').trim();
    return /^\d+\s+\S$/.test(s) ? s.replace(/\s+/g, '') : s;
  };
  const dmyToIso = (s) => { const [d, m, y] = s.split('.'); return `${y}-${m}-${d}`; };

  function parseItem(l) {
    const time = l && l.time;
    if (!time || !time.timeStart || !time.timeEnd) return null;
    const hw = l.homework || null;
    const body = hw ? clean(hw.body) : '';
    const files = (hw && Array.isArray(hw.files) ? hw.files : []).map((f) => ({
      id: f.id || 0,
      name: (f.name || '') + (f.extension || ''),
      link: f.link || '',
    }));
    return {
      start: time.timeStart,
      end: time.timeEnd,
      cls: compactClass(l.class && l.class.label),
      additional: !!(l.subject && l.subject.isAdditional),
      theme: clean(l.theme && l.theme.label),
      homework: body.toLowerCase() === NO_HOMEWORK ? '' : body,
      cabinet: (l.cabinet && l.cabinet.number) ? String(l.cabinet.number) : '',
      scheduleUuid: l.scheduleUuid || l.scheduleId || '',
      files,
    };
  }

  function toLesson(group) {
    const first = group[0];
    let title;
    if (first.additional) {
      title = (first.theme.startsWith('Кл') ? 'Кл. час, ' : 'Факультатив, ') + first.cls;
    } else {
      title = [...new Set(group.map((it) => it.cls))].join(' + ');
    }
    const cab = group.every((it) => it.cabinet === first.cabinet) ? first.cabinet : '';
    if (cab) title += ` (каб. ${cab})`;
    return {
      start: first.start,
      end: first.end,
      title,
      details: group.map((it) => ({
        cls: it.cls, theme: it.theme, homework: it.homework, scheduleUuid: it.scheduleUuid, files: it.files,
      })),
    };
  }

  /** Ответ за неделю → {'YYYY-MM-DD': {holiday, fromBilim, lessons}} */
  function parseWeek(resp) {
    const data = resp && resp.data;
    if (!data || !Array.isArray(data.days)) throw new Error('Неожиданный ответ BilimClass');
    const items = {};
    for (const row of data.rows || []) {
      if (!row) continue;
      for (const [key, arr] of Object.entries(row)) {
        if (!Array.isArray(arr)) continue;
        const iso = dmyToIso(key);
        for (const raw of arr) {
          const it = parseItem(raw);
          if (it) (items[iso] = items[iso] || []).push(it);
        }
      }
    }
    const result = {};
    for (const d of data.days) {
      const iso = dmyToIso(d.date);
      const list = items[iso] || [];
      const groups = new Map();
      for (const it of list) {
        const k = `${it.start}-${it.end}`;
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(it);
      }
      const lessons = [...groups.values()].map(toLesson).sort((a, b) => toMin(a.start) - toMin(b.start));
      result[iso] = { holiday: !!d.isHoliday, fromBilim: true, lessons };
    }
    return result;
  }

  /** Несколько недель сразу. */
  function parseWeeks(responses) {
    return Object.assign({}, ...responses.map(parseWeek));
  }

  // ---------- Что показывать сейчас ----------

  function homeworkSummary(lesson) {
    const hw = new Map();
    for (const d of lesson.details || []) if (d.homework) hw.set(d.cls, d.homework);
    if (hw.size === 0) return null;
    const values = [...hw.values()];
    if (values.every((v) => v === values[0])) return `ДЗ: ${values[0]}`;
    return 'ДЗ: ' + [...hw.entries()].map(([c, v]) => `${c} — ${v}`).join('; ');
  }

  const countLessons = (list) => list.filter((l) => !l.title.startsWith('Кл. час')).length;

  function nextSchoolDay(from, source) {
    for (let i = 1; i <= 21; i++) {
      const d = addDays(from, i);
      if (source(d).lessons.length) return d;
    }
    return null;
  }

  function whenText(today, d) {
    if (isoDate(d) === isoDate(addDays(today, 1))) return 'Завтра';
    if (d > addDays(today, 6)) return dateText(d);
    return IN_DAY[dow(d)];
  }

  /**
   * Состояние на момент now (Date).
   * rows: 'past' | 'current' | 'next' | 'normal';
   * countdownTo: Date или null; countdownLabel: 'до звонка' | 'через'.
   */
  function computeState(now, source) {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    const t = now.getHours() * 60 + now.getMinutes() + now.getSeconds() / 60;
    const s = {
      dayName: DAY_NAMES[dow(date)],
      dateText: dateText(date),
      status: '',
      countdownTo: null,
      countdownLabel: '',
      countdownAt: '',
      subText: null,
      listTitle: '',
      listDate: isoDate(date),
      lessons: [],
      rows: [],
    };
    const day = source(date);
    const today = day.lessons;
    const at = (hm) => new Date(date.getFullYear(), date.getMonth(), date.getDate(), ...hm.split(':').map(Number));

    if (!today.length) {
      const weekend = dow(date) >= 5;
      s.status = weekend ? 'Выходной 🎉' : day.holiday ? 'Праздничный день 🎉' : 'Сегодня уроков нет';
      const next = nextSchoolDay(date, source);
      if (next) {
        const nl = source(next).lessons;
        s.subText = `${whenText(date, next)}: с ${nl[0].start} · ${nl[0].title}`;
        s.listTitle = `${DAY_NAMES[dow(next)]}, ${dateText(next)} · уроков: ${countLessons(nl)}`;
        s.lessons = nl;
        s.listDate = isoDate(next);
        s.rows = nl.map(() => 'normal');
      }
      return s;
    }

    s.lessons = today;
    s.rows = today.map((l) => (t >= toMin(l.end) ? 'past' : 'normal'));
    s.listTitle = `Мои уроки сегодня · ${countLessons(today)}`;
    const current = today.findIndex((l) => t >= toMin(l.start) && t < toMin(l.end));
    const next = today.findIndex((l) => t < toMin(l.start));

    if (current >= 0) {
      const l = today[current];
      s.rows[current] = 'current';
      s.status = `Сейчас: ${l.title}`;
      s.countdownTo = at(l.end);
      s.countdownLabel = 'до звонка';
      s.countdownAt = l.end;
      s.subText = homeworkSummary(l);
    } else if (next >= 0) {
      const l = today[next];
      s.rows[next] = 'next';
      if (next === 0) {
        s.status = `Уроки с ${l.start} · ${l.title}`;
      } else {
        const gap = toMin(l.start) - toMin(today[next - 1].end);
        s.status = (gap < MAX_BREAK_MINUTES ? 'Перемена' : 'Окно') + ` · далее ${l.title}`;
      }
      s.countdownTo = at(l.start);
      s.countdownLabel = 'через';
      s.countdownAt = l.start;
    } else {
      s.status = 'Уроки на сегодня закончились';
      const nd = nextSchoolDay(date, source);
      if (nd) {
        const nl = source(nd).lessons;
        s.subText = `${whenText(date, nd)}: с ${nl[0].start} · ${nl[0].title}`;
      }
    }
    return s;
  }

  // ---------- Напоминания ----------

  const REMIND_BEFORE_MIN = 5;

  /**
   * Ближайшее напоминание после now: {at: Date, date: 'YYYY-MM-DD', lesson} или null.
   * Напоминание — за 5 минут до начала урока; уроки, до которых уже меньше 5 минут, пропускаются.
   */
  function nextReminder(now, source, minutesBefore = REMIND_BEFORE_MIN) {
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    for (let i = 0; i <= 21; i++) {
      const d = addDays(today, i);
      for (const l of source(d).lessons) {
        const [h, m] = l.start.split(':').map(Number);
        const at = new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m - minutesBefore);
        if (at > now) return { at, date: isoDate(d), lesson: l };
      }
    }
    return null;
  }

  /** Текст напоминания: заголовок и строка с подробностями. */
  function reminderText(lesson, minutesBefore = REMIND_BEFORE_MIN) {
    const themes = (lesson.details || []).filter((d) => d.theme)
      .map((d) => ((lesson.details.length > 1 ? `${d.cls}: ` : '') + d.theme));
    return {
      title: `Через ${minutesBefore} минут: ${lesson.title}`,
      body: [`${range(lesson)}`, ...themes].join(' · '),
    };
  }

  /** «12:34» или «1:20:05» */
  function formatLeft(ms) {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const sec = total % 60;
    return h > 0 ? `${h}:${pad(m)}:${pad(sec)}` : `${pad(m)}:${pad(sec)}`;
  }

  // ---------- Домашка без ИИ ----------

  const RULES = [
    ['(?:сл\\.?|слова|слов)\\s+выуч\\p{L}*|выуч\\p{L}*\\s+(?:сл\\.?|слова|слов)', 'выучить слова', 'learn the words'],
    ['правил\\p{L}*\\s+(?:повтор\\p{L}*|выуч\\p{L}*)|(?:повтор\\p{L}*|выуч\\p{L}*)\\s+правил\\p{L}*',
      'повторить правило', 'revise the rule'],
    ['текст\\p{L}*\\s+(?:прочит\\p{L}*|чит\\p{L}*)|(?:прочит\\p{L}*|читать)\\s+текст\\p{L}*', 'прочитать текст', 'read the text'],
    ['раб\\.?\\s*тетр\\p{L}*|р\\.\\s*т\\.?|рт', 'рабочая тетрадь', 'Workbook'],
    ['учебн\\p{L}*|уч\\.?', 'учебник', "Student's Book"],
    ['стр\\.?|с\\.', 'страница', 'page'],
    ['упр\\.?|у\\.', 'упражнение', 'exercise'],
    ['письм\\p{L}*|п\\.', 'письменно', 'in writing'],
    ['устн\\p{L}*', 'устно', 'orally'],
    ['выуч\\p{L}*|учить', 'выучить', 'learn'],
    ['повтор\\p{L}*', 'повторить', 'revise'],
    ['прочит\\p{L}*|читать|чит\\.?', 'прочитать', 'read'],
    ['перевод\\p{L}*|перев\\p{L}*', 'перевести', 'translate'],
    ['пересказ\\p{L}*', 'пересказ', 'retell'],
    ['сл\\.?|слова|слов', 'слова', 'the words'],
    ['текст\\p{L}*', 'текст', 'the text'],
    ['правил\\p{L}*', 'правило', 'the rule'],
    ['сочин\\p{L}*|эссе', 'сочинение', 'essay'],
    ['проект\\p{L}*', 'проект', 'project'],
    ['карточк\\p{L}*', 'карточка', 'card'],
    ['наизусть', 'наизусть', 'by heart'],
    ['тест\\p{L}*', 'тест', 'test'],
  ].map(([re, ru, en]) => ({ re: new RegExp(`(?<!\\p{L})(?:${re})(?!\\p{L})`, 'giu'), ru, en }));

  function replaceRules(s, lang) {
    const found = [];
    let out = s;
    for (const r of RULES) {
      out = out.replace(r.re, () => { found.push(r[lang]); return `\u0000${found.length - 1}\u0001`; });
    }
    out = out.replace(/\u0000(\d+)\u0001/g, (_, i) => found[Number(i)]);
    out = out.replace(/\s+/g, ' ').trim();
    out = out.replace(/(exercise|упражнение) (\d+[\d-]*) ?,? ?(письменно|устно|in writing|orally)/giu, '$1 $2 ($3)');
    out = out.replace(/(учебник|рабочая тетрадь|Student's Book|Workbook) (страница|page)/giu, '$1, $2');
    out = out.replace(/(страница|page) (\d+[\d-]*) (упражнение|exercise)/giu, '$1 $2, $3');
    return out;
  }

  function sentence(s) {
    let t = s.trim();
    if (!t) return t;
    t = t[0].toUpperCase() + t.slice(1);
    if (!/[.!?]$/.test(t)) t += '.';
    return t;
  }

  function expandRules(note) {
    const n = String(note || '').trim();
    if (!n) return '';
    return `Задание: ${sentence(replaceRules(n, 'ru'))}\nTask: ${sentence(replaceRules(n, 'en'))}`;
  }

  // ---------- Gemini ----------

  const GEMINI_MODELS = ['gemini-flash-lite-latest', 'gemini-flash-latest'];

  const GEMINI_SYSTEM =
    'Ты помогаешь учителю английского языка в казахстанской школе (ученики говорят по-русски, ' +
    'многие слабо знают английский). Учитель даёт краткую заметку о домашнем задании. ' +
    'Перепиши её в понятное задание для учеников и родителей.\n' +
    'Правила:\n' +
    '1. Используй ТОЛЬКО то, что есть в заметке. Ничего не добавляй от себя: никаких новых ' +
    'упражнений, страниц, слов, сроков и советов.\n' +
    '2. Номера страниц, упражнений и английские слова из заметки сохрани точно.\n' +
    '3. Ответ ровно из двух строк, без приветствий, пояснений и разметки:\n' +
    'Задание: <понятный текст на русском, 1–3 предложения>\n' +
    'Task: <тот же текст на простом английском, 1–3 предложения>\n' +
    '4. Сокращения раскрывай: стр — страница, упр — упражнение, р.т. — рабочая тетрадь, ' +
    'письм — письменно, сл — слова.';

  function geminiRequest(note, classes, theme) {
    let user = '';
    if (classes) user += `Класс: ${classes}\n`;
    if (theme) user += `Тема урока: ${theme}\n`;
    user += `Заметка учителя: ${String(note).trim()}`;
    return {
      systemInstruction: { parts: [{ text: GEMINI_SYSTEM }] },
      contents: [{ role: 'user', parts: [{ text: user }] }],
      generationConfig: { temperature: 0.2, maxOutputTokens: 2048 },
    };
  }

  function geminiParse(body) {
    const c = body && body.candidates && body.candidates[0];
    const parts = (c && c.content && c.content.parts) || [];
    const text = parts.filter((p) => !p.thought).map((p) => p.text || '').join('');
    return text.replace(/```/g, '').replace(/\*\*/g, '').trim().replace(/\n\s*\n+/g, '\n');
  }

  return {
    DAY_NAMES, MONTHS, isoDate, parseIso, addDays, dow, mondayOf, dateText, toMin, range, eduYear,
    templateDay, makeSource, compactClass, parseWeek, parseWeeks, homeworkSummary, computeState, formatLeft,
    expandRules, GEMINI_MODELS, geminiRequest, geminiParse, nextReminder, reminderText, REMIND_BEFORE_MIN,
  };
});
