'use strict';
// Журнал посещаемости: учебные дни, отметки, предупреждения, выгрузка в Word. Без Electron, чтобы проверять тестами.
// ФИО учеников не отправляются ни в нейросеть, ни в BilimClass — только в файл на Google Диске учителя
// для синхронизации компьютера и телефона. Тот же смысл, что Attendance.java на телефоне.
//
// Синхронизация: у каждой отметки, ученика, класса и поправки дня есть время изменения t (мс).
// Журналы двух устройств объединяются по элементам (merge): побеждает более позднее изменение,
// удаление — это отметка «удалён» со своим временем, поэтому ничего не теряется и не воскресает.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Attendance = factory();
}(typeof self !== 'undefined' ? self : this, () => {
  /** valid — уважительная ли причина. */
  const REASONS = [
    { id: 'sick', code: 'б', name: 'Болел', valid: true },
    { id: 'free', code: 'осв', name: 'Освобождение', valid: true },
    { id: 'family', code: 'с/о', name: 'По семейным обстоятельствам', valid: true },
    { id: 'absent', code: 'н', name: 'Без уважительной причины', valid: false },
    { id: 'other', code: 'др', name: 'Другое', valid: true },
  ];

  /** Учебные четверти 2026/2027 года (с ноября). */
  const QUARTERS = [
    { n: 2, start: '2026-11-02', end: '2026-12-27' },
    { n: 3, start: '2027-01-11', end: '2027-03-19' },
    { n: 4, start: '2027-03-29', end: '2027-05-25' },
  ];

  /** Праздники Казахстана в учебные дни (и переносы с выходных). Любой день можно переключить вручную. */
  const HOLIDAYS = ['2026-12-16', '2027-03-08', '2027-05-01', '2027-05-03', '2027-05-07', '2027-05-09', '2027-05-10'];

  const FIRST_MONTH = '2026-11';
  const LAST_MONTH = '2027-05';
  const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];

  /** Сколько учебных дней подряд болеет, чтобы предупредить. */
  const SICK_DAYS = 3;
  /** Сколько пропусков без уважительной причины за 30 дней, чтобы предупредить. */
  const ABSENT_COUNT = 2;

  const reasonById = (id) => REASONS.find((r) => r.id === id) || null;

  /** Часы (в тестах подменяются). */
  let clock = () => Date.now();
  const setClock = (fn) => { clock = fn; };

  function empty() {
    return { classes: [], marks: {}, days: {}, birthdays: [] };
  }

  const isObj = (o) => !!o && typeof o === 'object' && !Array.isArray(o);

  /**
   * Сохранённый журнал → журнал с нужными полями. Старый вид (offDays/onDays, без времени изменений)
   * переводится в новый с временем 0 — любое новое изменение его перекроет.
   */
  function normalize(j) {
    const o = isObj(j) ? j : {};
    const days = {};
    if (isObj(o.days)) for (const [d, e] of Object.entries(o.days)) if (isObj(e)) days[d] = { school: e.school, t: e.t || 0 };
    for (const d of Array.isArray(o.offDays) ? o.offDays : []) if (!days[d]) days[d] = { school: false, t: 0 };
    for (const d of Array.isArray(o.onDays) ? o.onDays : []) if (!days[d]) days[d] = { school: true, t: 0 };
    const marks = {};
    if (isObj(o.marks)) {
      for (const [d, day] of Object.entries(o.marks)) {
        if (!isObj(day)) continue;
        marks[d] = {};
        for (const [sid, m] of Object.entries(day)) if (isObj(m)) marks[d][sid] = { ...m, r: m.r || '', t: m.t || 0 };
      }
    }
    const classes = (Array.isArray(o.classes) ? o.classes : []).filter(isObj).map((c) => ({
      ...c,
      t: c.t || 0,
      students: (Array.isArray(c.students) ? c.students : []).filter(isObj).map((x) => ({ ...x, t: x.t || 0 })),
    }));
    const birthdays = (Array.isArray(o.birthdays) ? o.birthdays : []).filter(isObj).map((b) => ({ ...b, t: b.t || 0 }));
    return { classes, marks, days, birthdays };
  }

  const live = (x) => !x.deleted && !x.mergedInto;

  // ---------- Даты ----------

  function addDays(iso, n) {
    const d = new Date(iso + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
  }

  const weekday = (iso) => new Date(iso + 'T00:00:00Z').getUTCDay();
  const quarterOf = (iso) => QUARTERS.find((q) => iso >= q.start && iso <= q.end) || null;

  /** Учебный ли день без ручных поправок: будни внутри четверти, не праздник. */
  function defaultSchoolDay(iso) {
    const w = weekday(iso);
    return w >= 1 && w <= 5 && !!quarterOf(iso) && !HOLIDAYS.includes(iso);
  }

  function isSchoolDay(j, iso) {
    const e = j.days[iso];
    return e && typeof e.school === 'boolean' ? e.school : defaultSchoolDay(iso);
  }

  /** Сделать день учебным или выходным (например, перенос праздника или карантин). */
  function setSchoolDay(j, iso, on) {
    j.days[iso] = { school: on === defaultSchoolDay(iso) ? null : !!on, t: clock() };
  }

  function months() {
    const out = [];
    let [y, m] = FIRST_MONTH.split('-').map(Number);
    for (;;) {
      const s = `${y}-${String(m).padStart(2, '0')}`;
      out.push(s);
      if (s === LAST_MONTH) return out;
      m++;
      if (m > 12) { m = 1; y++; }
    }
  }

  /** «Ноябрь 2026». */
  function monthTitle(month) {
    const [y, m] = month.split('-').map(Number);
    const n = MONTHS[m - 1];
    return `${n[0].toUpperCase()}${n.slice(1)} ${y}`;
  }

  /** Месяц журнала для сегодняшней даты (до ноября — ноябрь, после мая — май). */
  function defaultMonth(todayIso) {
    const m = todayIso.slice(0, 7);
    if (m < FIRST_MONTH) return FIRST_MONTH;
    if (m > LAST_MONTH) return LAST_MONTH;
    return m;
  }

  function daysOfMonth(month) {
    const out = [];
    for (let d = `${month}-01`; d.startsWith(month); d = addDays(d, 1)) out.push(d);
    return out;
  }

  /** Столбцы месяца: будни внутри четвертей и добавленные вручную дни; school — учебный ли. */
  function monthColumns(j, month) {
    return daysOfMonth(month)
      .filter((d) => (j.days[d] && j.days[d].school === true) || (quarterOf(d) && weekday(d) >= 1 && weekday(d) <= 5))
      .map((d) => ({ date: d, day: Number(d.slice(8)), school: isSchoolDay(j, d) }));
  }

  const schoolDays = (j, month) => monthColumns(j, month).filter((c) => c.school).map((c) => c.date);

  // ---------- Отметки ----------

  function getMark(j, iso, sid) {
    const day = j.marks[iso];
    const m = day && day[sid];
    return m && reasonById(m.r) ? m : null;
  }

  /** reason = '' — был на уроках (запись остаётся с временем, чтобы при синхронизации отметка не вернулась). */
  function setMark(j, iso, sid, reason, note) {
    const day = j.marks[iso] || (j.marks[iso] = {});
    const m = { r: reason && reasonById(reason) ? reason : '', t: clock() };
    const text = String(note || '').trim();
    if (m.r === 'other' && text) m.note = text;
    day[sid] = m;
  }

  /** Код в клетке журнала: «б», «н»… Для «Другое» — «др». */
  const markCode = (m) => (m && reasonById(m.r) ? reasonById(m.r).code : '');

  /** Пропуски ученика за месяц: всего, без уважительной причины, по причинам. */
  function monthStats(j, month, sid) {
    const s = { total: 0, invalid: 0, by: {} };
    for (const d of schoolDays(j, month)) {
      const m = getMark(j, d, sid);
      if (!m) continue;
      s.total++;
      if (!reasonById(m.r).valid) s.invalid++;
      s.by[m.r] = (s.by[m.r] || 0) + 1;
    }
    return s;
  }

  // ---------- Классы и ученики ----------

  const newId = () => Date.now().toString(36) + Math.random().toString(36).slice(2, 8);

  // латинские буквы, похожие на русские (часто попадают в списки при наборе)
  const LATIN = {
    a: 'а', e: 'е', o: 'о', p: 'р', c: 'с', x: 'х', y: 'у',
    A: 'А', B: 'В', E: 'Е', K: 'К', M: 'М', H: 'Н', O: 'О', P: 'Р', C: 'С', T: 'Т', X: 'Х',
  };
  const LOWER_WORDS = ['оглы', 'улы', 'кызы', 'қызы', 'гызы'];

  function capitalize(w) {
    const low = w.toLowerCase();
    if (LOWER_WORDS.includes(low)) return low;
    return low.split('-').map((p) => (p ? p[0].toUpperCase() + p.slice(1) : p)).join('-');
  }

  /** ФИО как положено: «ИВАНОВ ИВАН» и «Иванов ИванИванович» → «Иванов Иван Иванович». */
  function fixName(s) {
    return String(s || '')
      .replace(/([а-яё])([А-ЯЁ])/g, '$1 $2')
      .split(/\s+/)
      .filter(Boolean)
      .map((w) => (/[А-Яа-яЁё]/.test(w) ? w.replace(/[A-Za-z]/g, (ch) => LATIN[ch] || ch) : w))
      .map(capitalize)
      .join(' ');
  }

  /** «4 класс» → «4 класса» (для заголовка таблицы). */
  function classGenitive(name) {
    const n = String(name || '').trim();
    return /класс$/i.test(n) ? n + 'а' : n;
  }

  /** Классы по порядку номера: 1, 2 … 11, потом остальные. */
  function sortedClasses(j) {
    const num = (c) => {
      const m = /^(\d+)/.exec(c.name);
      return m ? Number(m[1]) : 99;
    };
    return j.classes.filter(live).sort((a, b) => num(a) - num(b) || a.name.localeCompare(b.name, 'ru'));
  }

  function sortedStudents(cls) {
    return cls.students.filter(live).sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  }

  function addClass(j, name) {
    const c = { id: newId(), name: String(name || '').trim() || 'Новый класс', t: clock(), students: [] };
    j.classes.push(c);
    return c;
  }

  function addStudent(cls, name) {
    const s = { id: newId(), name: fixName(name), t: clock() };
    cls.students.push(s);
    return s;
  }

  /** Переименовать ученика или класс. */
  function rename(item, name) {
    item.name = name;
    item.t = clock();
  }

  /** Удалить ученика (его отметки больше нигде не показываются). */
  function removeStudent(j, cls, sid) {
    const s = cls.students.find((x) => x.id === sid);
    if (!s) return;
    s.deleted = true;
    s.t = clock();
  }

  function removeClass(j, cid) {
    const c = j.classes.find((x) => x.id === cid);
    if (!c) return;
    c.deleted = true;
    c.t = clock();
  }

  // ---------- Синхронизация ----------

  /** Подпись элемента: чем решать, если время изменения совпало до миллисекунды. */
  const sig = (x) => [x.r, x.note, x.name, x.deleted, x.mergedInto, x.school].map((v) => (v == null ? '' : String(v))).join('|');

  /** Из двух версий одного элемента — более позднюю. */
  function newer(a, b) {
    if (!a) return b;
    if (!b) return a;
    if ((b.t || 0) !== (a.t || 0)) return (b.t || 0) > (a.t || 0) ? b : a;
    return sig(b) > sig(a) ? b : a;
  }

  /** Объединить два журнала (порядок не важен: merge(a, b) и merge(b, a) дают одно и то же). */
  function merge(a, b) {
    const x = normalize(JSON.parse(JSON.stringify(a || {})));
    const y = normalize(JSON.parse(JSON.stringify(b || {})));
    const classes = new Map();
    for (const c of x.classes.concat(y.classes)) {
      const cur = classes.get(c.id);
      const students = new Map(cur ? cur.students.map((s) => [s.id, s]) : []);
      for (const s of c.students) students.set(s.id, newer(students.get(s.id), s));
      const head = newer(cur, c);
      classes.set(c.id, { ...head, students: [...students.values()] });
    }
    const marks = {};
    for (const src of [x.marks, y.marks]) {
      for (const [d, day] of Object.entries(src)) {
        const out = marks[d] || (marks[d] = {});
        for (const [sid, m] of Object.entries(day)) out[sid] = newer(out[sid], m);
      }
    }
    const days = {};
    for (const src of [x.days, y.days]) for (const [d, e] of Object.entries(src)) days[d] = newer(days[d], e);
    const birthdays = new Map();
    for (const b of x.birthdays.concat(y.birthdays)) birthdays.set(b.id, newer(birthdays.get(b.id), b));
    const j = {
      classes: [...classes.values()].sort((p, q) => (p.id < q.id ? -1 : 1)),
      marks,
      days,
      birthdays: [...birthdays.values()].sort((p, q) => (p.id < q.id ? -1 : 1)),
    };
    for (const c of j.classes) c.students.sort((p, q) => (p.id < q.id ? -1 : 1));
    dedupe(j);
    return j;
  }

  /**
   * Одинаковые классы и ученики (списки загружали из Word и на компьютере, и на телефоне) сливаются в один:
   * остаётся тот, у кого меньше id, у другого — mergedInto. Отметки переносятся.
   */
  function dedupe(j) {
    const lower = (s) => String(s || '').trim().toLowerCase();
    const groups = new Map();
    for (const c of j.classes.filter(live)) {
      const k = lower(c.name);
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(c);
    }
    for (const list of groups.values()) {
      list.sort((p, q) => (p.id < q.id ? -1 : 1));
      for (const c of list.slice(1)) {
        c.mergedInto = list[0].id;
        c.t = clock();
      }
    }
    const byId = new Map(j.classes.map((c) => [c.id, c]));
    const target = (c) => {
      let t = c;
      for (let i = 0; t && t.mergedInto && i < 20; i++) t = byId.get(t.mergedInto);
      return t && live(t) ? t : null;
    };
    for (const c of j.classes) {
      if (!c.mergedInto) continue;
      const to = target(c);
      if (!to) continue;
      for (const s of c.students) {
        const i = to.students.findIndex((x) => x.id === s.id);
        if (i < 0) to.students.push(s);
        else to.students[i] = newer(to.students[i], s);
      }
      c.students = [];
    }
    const moved = new Map();
    for (const c of j.classes.filter(live)) {
      const names = new Map();
      for (const s of c.students.filter(live).sort((p, q) => (p.id < q.id ? -1 : 1))) {
        const k = lower(s.name);
        if (names.has(k)) {
          s.mergedInto = names.get(k);
          s.t = clock();
        } else {
          names.set(k, s.id);
        }
      }
      for (const s of c.students) if (s.mergedInto) moved.set(s.id, s.mergedInto);
    }
    for (const day of Object.values(j.marks)) {
      for (const [sid, to] of moved) {
        if (!day[sid]) continue;
        day[to] = newer(day[to], day[sid]);
        delete day[sid];
      }
    }
  }

  /** Одинаковы ли журналы по содержанию (порядок ключей не важен). */
  function same(a, b) {
    const canon = (v) => {
      if (Array.isArray(v)) return '[' + v.map(canon).sort().join(',') + ']';
      if (isObj(v)) return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + canon(v[k])).join(',') + '}';
      return JSON.stringify(v === undefined ? null : v);
    };
    return canon(normalize(a)) === canon(normalize(b));
  }

  // ---------- Список из Word ----------

  function xmlText(s) {
    return String(s)
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&');
  }

  const runsText = (xml) => xmlText((xml.match(/<w:t(?:\s[^>]*)?>[^<]*<\/w:t>/g) || [])
    .map((t) => t.replace(/<[^>]+>/g, '')).join(''));

  /**
   * word/document.xml шаблона → [{ name: '9 класс', students: ['Иванов Иван Иванович', …] }].
   * Над каждой таблицей — строка «Список учащихся 9 класса …», в таблице: №, ФИО, дни.
   */
  function parseTemplate(xml) {
    const out = [];
    let heading = '';
    const blocks = String(xml).match(/<w:tbl>[\s\S]*?<\/w:tbl>|<w:p[ >][\s\S]*?<\/w:p>/g) || [];
    for (const b of blocks) {
      if (!b.startsWith('<w:tbl>')) {
        const t = runsText(b).trim();
        if (t) heading = t;
        continue;
      }
      const students = [];
      for (const row of b.match(/<w:tr[ >][\s\S]*?<\/w:tr>/g) || []) {
        const cells = (row.match(/<w:tc>[\s\S]*?<\/w:tc>/g) || []).map((c) => runsText(c).trim());
        if (cells.length >= 2 && /^\d+\.?$/.test(cells[0]) && /[А-Яа-яЁёA-Za-z]/.test(cells[1])) students.push(fixName(cells[1]));
      }
      if (!students.length) continue;
      const m = /(\d{1,2})\s*[«"]?([А-Яа-яЁё])?[»"]?\s*класс/i.exec(heading);
      const name = m ? `${m[1]}${m[2] ? ' ' + m[2].toUpperCase() : ''} класс` : (heading || `Класс ${out.length + 1}`);
      out.push({ name, students });
    }
    return out;
  }

  /** Добавить классы и учеников из шаблона; уже записанных не дублируем. */
  function importClasses(j, parsed) {
    let classes = 0;
    let students = 0;
    for (const p of parsed) {
      let c = j.classes.find((x) => live(x) && x.name.toLowerCase() === p.name.toLowerCase());
      if (!c) {
        c = addClass(j, p.name);
        classes++;
      }
      for (const name of p.students) {
        if (c.students.some((s) => live(s) && s.name.toLowerCase() === name.toLowerCase())) continue;
        addStudent(c, name);
        students++;
      }
    }
    return { classes, students };
  }

  // ---------- Предупреждения ----------

  const dm = (iso) => `${iso.slice(8)}.${iso.slice(5, 7)}`;

  /**
   * Кто долго болеет (SICK_DAYS учебных дней подряд по сегодняшний) и кто пропускает
   * без уважительной причины (ABSENT_COUNT раз за 30 дней).
   */
  function alerts(j, todayIso) {
    const days = [];
    for (let d = QUARTERS[0].start; d <= todayIso && d <= QUARTERS[QUARTERS.length - 1].end; d = addDays(d, 1)) {
      if (isSchoolDay(j, d)) days.push(d);
    }
    const from = addDays(todayIso, -30);
    const sick = [];
    const absent = [];
    for (const c of sortedClasses(j)) {
      for (const s of sortedStudents(c)) {
        let i = days.length - 1;
        // сегодня ещё не отмечали — считаем по вчерашний день
        if (i >= 0 && days[i] === todayIso && !getMark(j, todayIso, s.id)) i--;
        let run = 0;
        let since = '';
        while (i >= 0) {
          const m = getMark(j, days[i], s.id);
          if (!m || m.r !== 'sick') break;
          run++;
          since = days[i];
          i--;
        }
        if (run >= SICK_DAYS) sick.push({ kind: 'sick', cls: c.name, student: s.name, sid: s.id, days: run, since });

        const miss = days.filter((d) => d > from && (getMark(j, d, s.id) || {}).r === 'absent');
        if (miss.length >= ABSENT_COUNT) {
          absent.push({ kind: 'absent', cls: c.name, student: s.name, sid: s.id, count: miss.length, last: miss[miss.length - 1] });
        }
      }
    }
    sick.sort((a, b) => b.days - a.days);
    absent.sort((a, b) => b.count - a.count);
    return sick.concat(absent);
  }

  function plural(n, one, few, many) {
    const a = n % 10;
    const b = n % 100;
    if (a === 1 && b !== 11) return one;
    if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return few;
    return many;
  }

  function alertText(a) {
    if (a.kind === 'sick') {
      return `${a.student} (${a.cls}) болеет ${a.days} ${plural(a.days, 'учебный день', 'учебных дня', 'учебных дней')} подряд — с ${dm(a.since)}`;
    }
    return `${a.student} (${a.cls}): ${a.count} ${plural(a.count, 'пропуск', 'пропуска', 'пропусков')} без уважительной причины `
      + `за 30 дней, последний ${dm(a.last)}`;
  }

  // ---------- Дни рождения ----------
  // Список вставляется из таблицы (ФИО и дата), синхронизируется вместе с журналом. id зависит только от ФИО и даты,
  // поэтому один и тот же список, вставленный на компьютере и на телефоне, не задваивается.

  const birthdayId = (name, date) => `${String(name).toLowerCase().replace(/ё/g, 'е')}|${date}`;

  /** Настоящая ли дата «ГГГГ-ММ-ДД» (31.02 — нет). */
  function validDate(iso) {
    const d = new Date(`${iso}T00:00:00Z`);
    return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === iso;
  }

  /** Текст из Excel или Word: в строке ФИО и дата ДД.ММ.ГГГГ → { items: [{ name, date }], bad: [строки без даты] }. */
  function parseBirthdays(text) {
    const items = [];
    const bad = [];
    for (const raw of String(text || '').split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      const m = /(\d{1,2})[./-](\d{1,2})[./-](\d{4})/.exec(line);
      const name = fixName(line.replace(/\d{1,2}[./-]\d{1,2}[./-]\d{4}/, ' ').replace(/^\s*\d+[.)]?\s+/, ''));
      const date = m ? `${m[3]}-${m[2].padStart(2, '0')}-${m[1].padStart(2, '0')}` : '';
      if (name && date && validDate(date)) items.push({ name, date });
      else bad.push(line);
    }
    return { items, bad };
  }

  /** Добавить в журнал; удалённого раньше вернуть. → { added, known } */
  function addBirthdays(j, items) {
    let added = 0;
    let known = 0;
    for (const it of items) {
      const id = birthdayId(it.name, it.date);
      const cur = j.birthdays.find((b) => b.id === id);
      if (cur && !cur.deleted) {
        known++;
      } else if (cur) {
        cur.deleted = false;
        cur.t = clock();
        added++;
      } else {
        j.birthdays.push({ id, name: it.name, date: it.date, t: clock() });
        added++;
      }
    }
    return { added, known };
  }

  function removeBirthday(j, id) {
    const b = j.birthdays.find((x) => x.id === id);
    if (b) {
      b.deleted = true;
      b.t = clock();
    }
  }

  /** Ближайшая дата праздника не раньше сегодня. 29 февраля в невисокосный год — 28-го. */
  function nextBirthday(birth, todayIso) {
    const on = (y) => {
      const iso = `${y}${birth.slice(4)}`;
      return validDate(iso) ? iso : `${y}-02-28`;
    };
    const y = Number(todayIso.slice(0, 4));
    return on(y) >= todayIso ? on(y) : on(y + 1);
  }

  /** Все дни рождения по порядку от сегодня: [{ id, name, date, next, age, daysLeft }]. */
  function upcomingBirthdays(j, todayIso) {
    const at = (iso) => Date.parse(`${iso}T00:00:00Z`);
    return j.birthdays.filter((b) => !b.deleted).map((b) => {
      const next = nextBirthday(b.date, todayIso);
      return {
        id: b.id,
        name: b.name,
        date: b.date,
        next,
        age: Number(next.slice(0, 4)) - Number(b.date.slice(0, 4)),
        daysLeft: Math.round((at(next) - at(todayIso)) / 86400000),
      };
    }).sort((a, b) => a.daysLeft - b.daysLeft || a.name.localeCompare(b.name, 'ru'));
  }

  /** «Иванов Иван, 13 лет». */
  function birthdayText(b) {
    return `${b.name.split(' ').slice(0, 2).join(' ')}, ${b.age} ${plural(b.age, 'год', 'года', 'лет')}`;
  }

  // ---------- Ответ из WhatsApp → отметки ----------
  // Классные руководители отвечают в группе как попало: «4 кл: Иванов, Петрова болеют», «Котов Илья — соревнования».
  // Разбор на устройстве, без нейросети: ищем учеников журнала по фамилии и имени (с падежами),
  // причину — по словам рядом. Учитель проверяет результат перед сохранением.

  /** Слова-признаки причин. Порядок важен: «без уважительной» раньше «уважительной». */
  const REASON_WORDS = [
    ['absent', /без\s+уваж|неуваж|прогул|не\s+знаю\s+почему|причина\s+неизвестн|без\s+причин/],
    ['family', /семейн|с\/о(?![а-я])|по\s+сем/],
    ['free', /освобожд|освоб|(^|[^а-я])осв([^а-я]|$)/],
    ['sick', /(^|[^а-я])бол(е|ё)?(ет|ют|ен|ьн|еть|ел|ела|ели|еют)|болезн|заболе|орви|грипп|температур|простуд|больничн|у\s+врача|в\s+больниц/],
    ['other', /соревнован|олимпиад|конкурс|санатор|сборы|выступлен|экскурси|лагер/],
  ];
  const ALL_PRESENT = /вс[её]\s+(на\s+месте|присутству|пришли|в\s+школе)|нет\s+отсутствующ|отсутствующих\s+нет|все\s+есть/;
  /** Слова с большой буквы, которые не имена (чтобы не жаловаться «не нашёл»). */
  const NOT_NAMES = new Set(['здравствуйте', 'добрый', 'доброе', 'день', 'утро', 'класс', 'кл', 'болеет', 'болеют', 'болен',
    'больна', 'отсутствуют', 'отсутствует', 'отсутствующих', 'нет', 'все', 'всё', 'на', 'месте', 'без', 'причины',
    'по', 'семейным', 'обстоятельствам', 'освобождение', 'спасибо', 'итого', 'из', 'не', 'пришли', 'пришёл',
    'пришел', 'пришла', 'сегодня', 'вчера', 'завтра', 'ученики', 'ученик', 'ученица', 'уважительной', 'причине',
    'соревнования', 'олимпиада', 'справка', 'орви', 'другое', 'пожалуйста', 'напишите', 'хорошо', 'ок', 'да', 'температура']);

  const norm = (w) => String(w || '').toLowerCase().replace(/ё/g, 'е');

  /** Одно и то же слово в другом падеже: «Иванов» — «Иванова», «Ивановой»; «Никита» — «Никиты». 2 — точно, 1 — похоже. */
  function sameWord(t, w) {
    if (t === w) return 2;
    if (t.length < 3 || w.length < 3) return 0;
    let i = 0;
    while (i < t.length && i < w.length && t[i] === w[i]) i++;
    const need = Math.max(3, Math.max(t.length, w.length) - 3);
    return i >= need && i >= Math.min(t.length, w.length) - 2 && Math.abs(t.length - w.length) <= 3 ? 1 : 0;
  }

  /** «4 кл», «4 класс», «4А:», «в 4-м классе» → номер (и буква) класса; '' — не указан. */
  function classMention(line) {
    const m = /(?:^|[^\d])(\d{1,2})\s*(?:-?(?:й|м|го|ом))?\s*[«"]?([А-ЯЁ])?[»"]?\s*(?:кл(?:асс[а-я]*)?(?![а-я]))/i.exec(line)
      || /^\s*(\d{1,2})\s*[«"]?([А-ЯЁ])?[»"]?\s*[:\-–—)]/i.exec(line)
      // «4а Иванов, Петров» — номер с буквой в начале строки
      || /^\s*(\d{1,2})\s*[«"]?([А-ЯЁ])[»"]?(?![а-яё])/i.exec(line);
    if (!m) return '';
    const letter = m[2] && !/^[кК]$/.test(m[2]) ? m[2].toUpperCase() : '';
    return m[1] + letter;
  }

  /** Классы журнала, о которых говорит упоминание; без упоминания — все. */
  function classesFor(j, mention) {
    if (!mention) return sortedClasses(j);
    const n = /^\d+/.exec(mention)[0];
    const letter = norm(mention.slice(n.length));
    const all = sortedClasses(j).filter((c) => (/^(\d+)/.exec(c.name) || [])[1] === n);
    const exact = letter ? all.filter((c) => new RegExp(`^\\d+\\s*${letter}(?![а-я])`).test(norm(c.name))) : [];
    return exact.length ? exact : all;
  }

  function reasonOf(line) {
    const t = norm(line);
    for (const [id, re] of REASON_WORDS) if (re.test(t)) return id;
    return '';
  }

  /** Ученики, которых называет кусок текста (несколько — если не понять, кто именно). */
  function findStudents(part, classes, classKnown) {
    const tokens = (part.match(/[А-Яа-яЁё]+(?:-[А-Яа-яЁё]+)?/g) || []).map(norm);
    const scored = [];
    for (const c of classes) {
      for (const s of sortedStudents(c)) {
        const [sur, first] = norm(s.name).split(' ');
        let sScore = 0;
        let fScore = 0;
        for (const t of tokens) {
          const sw = sameWord(t, sur);
          sScore = Math.max(sScore, sw === 2 ? 4 : sw ? 3 : 0);
          if (first) fScore = Math.max(fScore, sameWord(t, first) ? 2 : t.length === 1 && t === first[0] ? 1 : 0);
        }
        // только по имени — если класс назван и такое имя в нём одно
        const score = sScore ? sScore + fScore : (classKnown && fScore === 2 ? 2 : 0);
        if (score) scored.push({ s, c, score, bySurname: sScore > 0 });
      }
    }
    if (!scored.length) return [];
    const best = Math.max(...scored.map((x) => x.score));
    const top = scored.filter((x) => x.score === best);
    if (!top[0].bySurname && top.length > 1) return [];
    return top;
  }

  /**
   * Текст ответа (можно сразу несколько сообщений, скопированных из WhatsApp) →
   * { items: [{ sid, name, cls, reason, note, line, options? }], present: ['4 класс'], unknown: ['Сидоров'] }.
   * reason '' — причина не указана (учитель выберет сам); options — несколько учеников, подходящих под написанное.
   */
  function parseReply(j, text) {
    const items = [];
    const present = [];
    const unknown = [];
    let mentioned = '';
    let forward = '';
    let pending = [];
    const lines = String(text || '')
      // заголовок скопированного сообщения: «[01.11.26, 09:05] Анна Петровна: …»
      .replace(/^\s*\[[^\]\n]{4,40}\]\s*[^:\n]{1,60}:[ \t]?/gm, '')
      .split(/\n+/)
      .map((l) => l.trim())
      .filter(Boolean);
    for (const line of lines) {
      const mention = classMention(line);
      if (mention) {
        mentioned = mention;
        forward = '';
        pending = [];
      }
      const classes = classesFor(j, mentioned);
      const reason = reasonOf(line);
      if (mentioned && ALL_PRESENT.test(norm(line))) {
        for (const c of classes) if (!present.includes(c.name)) present.push(c.name);
      }
      const found = [];
      const missed = [];
      // имена разделены запятыми, «и», тире, точкой с запятой
      const text1 = line.replace(/(?:^|[^\d])\d{1,2}\s*[«"]?[А-ЯЁ]?[»"]?\s*кл(?:асс[а-я]*)?\.?/gi, ' ')
        .replace(/^\s*\d{1,2}\s*[«"]?[А-ЯЁ]?[»"]?(?![а-яё])/i, ' ');
      for (const part of text1.split(/[,;:()]|\s+и\s+|\s+[—–-]\s+/)) {
        const top = findStudents(part, classes, !!mentioned);
        if (top.length) {
          found.push(top);
        } else {
          for (const w of part.match(/[А-ЯЁ][а-яё]{2,}/g) || []) {
            if (!NOT_NAMES.has(norm(w)) && !reasonOf(w)) missed.push(w);
          }
        }
      }
      const lineItems = [];
      for (const top of found) {
        const one = top.length === 1 ? top[0] : null;
        if (one && items.some((x) => x.sid === one.s.id)) continue;
        const it = {
          sid: one ? one.s.id : '',
          name: one ? one.s.name : top.map((x) => x.s.name).join(' или '),
          cls: one ? one.c.name : '',
          reason: reason || forward,
          note: '',
          line,
        };
        if (!one) it.options = top.map((x) => ({ sid: x.s.id, name: x.s.name, cls: x.c.name }));
        items.push(it);
        lineItems.push(it);
      }
      if (lineItems.length) {
        if (!reason && !forward) pending = pending.concat(lineItems);
        unknown.push(...missed);
      } else if (missed.length) {
        // похоже на ученика, которого нет в журнале («Сидоров болеет») — покажем учителю
        unknown.push(...missed);
      } else if (reason) {
        // строка только с причиной: относится к названным выше без причины и к тем, кто ниже
        for (const it of pending) it.reason = reason;
        pending = [];
        forward = reason;
      }
    }
    for (const it of items) {
      if (it.reason === 'other') it.note = it.line.length > 80 ? it.line.slice(0, 80) + '…' : it.line;
    }
    return { items, present, unknown: [...new Set(unknown)] };
  }

  // ---------- Выгрузка в Word ----------

  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

  const FONT = '<w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman" w:cs="Times New Roman"/>';

  function run(text, size, bold) {
    return `<w:r><w:rPr>${FONT}${bold ? '<w:b/>' : ''}<w:sz w:val="${size}"/><w:szCs w:val="${size}"/></w:rPr>`
      + `<w:t xml:space="preserve">${esc(text)}</w:t></w:r>`;
  }

  function para(text, { size = 24, bold = false, align = 'left', before = 0, after = 0 } = {}) {
    return `<w:p><w:pPr><w:spacing w:before="${before}" w:after="${after}"/><w:jc w:val="${align}"/></w:pPr>`
      + `${text === '' ? '' : run(text, size, bold)}</w:p>`;
  }

  function cell(text, width, { size = 20, bold = false, align = 'center', shade = '' } = {}) {
    return `<w:tc><w:tcPr><w:tcW w:w="${width}" w:type="dxa"/>${shade ? `<w:shd w:val="clear" w:color="auto" w:fill="${shade}"/>` : ''}`
      + `<w:vAlign w:val="center"/></w:tcPr>${para(text, { size, bold, align })}</w:tc>`;
  }

  // A4 альбомная, поля 2 см слева и справа
  const PAGE_W = 16838;
  const PAGE_H = 11906;
  const MARGIN_X = 1134;
  const MARGIN_Y = 567;

  function shortName(name) {
    const p = String(name).split(' ');
    return p.length > 1 ? `${p[0]} ${p.slice(1).filter((x) => !LOWER_WORDS.includes(x)).map((x) => x[0] + '.').join('')}` : name;
  }

  /** word/document.xml: таблица на каждый класс, как в шаблоне, плюс итоги и обозначения. */
  function documentXml(j, month) {
    const days = schoolDays(j, month);
    const title = MONTHS[Number(month.slice(5)) - 1].toUpperCase();
    const year = month.slice(0, 4);
    const avail = PAGE_W - 2 * MARGIN_X;
    const wNum = 450;
    const wTotal = 700;
    const wDay = days.length ? Math.min(560, Math.floor((avail - wNum - 2600 - 2 * wTotal) / days.length)) : 0;
    const wName = avail - wNum - 2 * wTotal - wDay * days.length;
    const notes = [];
    let body = '';
    for (const c of sortedClasses(j)) {
      if (!sortedStudents(c).length) continue;
      body += para(`Список учащихся ${classGenitive(c.name)} ${title} ${year}`, { size: 26, bold: true, align: 'center', before: 120, after: 80 });
      const grid = [wNum, wName, ...days.map(() => wDay), wTotal, wTotal];
      body += '<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/><w:tblLayout w:type="fixed"/><w:tblBorders>'
        + ['top', 'left', 'bottom', 'right', 'insideH', 'insideV'].map((b) => `<w:${b} w:val="single" w:sz="4" w:space="0" w:color="000000"/>`).join('')
        + '</w:tblBorders><w:tblCellMar><w:left w:w="40" w:type="dxa"/><w:right w:w="40" w:type="dxa"/></w:tblCellMar></w:tblPr>'
        + `<w:tblGrid>${grid.map((w) => `<w:gridCol w:w="${w}"/>`).join('')}</w:tblGrid>`;
      body += '<w:tr><w:trPr><w:tblHeader/></w:trPr>' + cell('№', wNum, { bold: true }) + cell('ФИО', wName, { bold: true })
        + days.map((d) => cell(String(Number(d.slice(8))), wDay, { bold: true, size: 18 })).join('')
        + cell('Всего', wTotal, { bold: true, size: 16 }) + cell('Без ув. пр.', wTotal, { bold: true, size: 16 }) + '</w:tr>';
      sortedStudents(c).forEach((s, i) => {
        const st = monthStats(j, month, s.id);
        body += '<w:tr>' + cell(String(i + 1), wNum) + cell(s.name, wName, { align: 'left', size: 22 })
          + days.map((d) => {
            const m = getMark(j, d, s.id);
            if (m && m.r === 'other' && m.note) notes.push(`${shortName(s.name)} (${c.name}), ${dm(d)}: ${m.note}`);
            return cell(markCode(m), wDay, { size: 18, shade: m && !reasonById(m.r).valid ? 'F2DCDB' : '' });
          }).join('')
          + cell(st.total ? String(st.total) : '', wTotal) + cell(st.invalid ? String(st.invalid) : '', wTotal) + '</w:tr>';
      });
      body += '</w:tbl>';
    }
    if (!body) body = para('В журнале пока нет учеников.');
    body += para('Обозначения: ' + REASONS.map((r) => `${r.code} — ${r.name.toLowerCase()}`).join('; ') + '.', { size: 20, before: 200 });
    if (notes.length) {
      body += para('Примечания:', { size: 20, bold: true, before: 80 });
      for (const n of notes) body += para(n, { size: 20 });
    }
    return '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      + '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
      + body
      + `<w:sectPr><w:pgSz w:w="${PAGE_W}" w:h="${PAGE_H}" w:orient="landscape"/>`
      + `<w:pgMar w:top="${MARGIN_Y}" w:right="${MARGIN_X}" w:bottom="${MARGIN_Y}" w:left="${MARGIN_X}" w:header="0" w:footer="0" w:gutter="0"/></w:sectPr>`
      + '</w:body></w:document>';
  }

  /** Все файлы документа Word: путь → текст. Упаковать в zip — дело программы. */
  function docxFiles(j, month) {
    return {
      '[Content_Types].xml': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        + '<Default Extension="xml" ContentType="application/xml"/>'
        + '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        + '</Types>',
      '_rels/.rels': '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        + '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        + '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        + '</Relationships>',
      'word/document.xml': documentXml(j, month),
    };
  }

  /** «Посещаемость — Ноябрь 2026.docx». */
  const fileName = (month) => `Посещаемость — ${monthTitle(month)}.docx`;

  return {
    REASONS, QUARTERS, HOLIDAYS, SICK_DAYS, ABSENT_COUNT,
    setClock, reasonById, empty, normalize, merge, same, rename, addDays, isSchoolDay, setSchoolDay, months, monthTitle, defaultMonth, monthColumns, schoolDays,
    getMark, setMark, markCode, monthStats, fixName, classGenitive, sortedClasses, sortedStudents, addClass, addStudent,
    removeStudent, removeClass, parseTemplate, importClasses, alerts, alertText, documentXml, docxFiles, fileName, plural,
    parseReply, parseBirthdays, addBirthdays, removeBirthday, nextBirthday, upcomingBirthdays, birthdayText,
  };
}));
