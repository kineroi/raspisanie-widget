'use strict';
// Журнал посещаемости: учебные дни, отметки, предупреждения, выгрузка в Word. Без Electron, чтобы проверять тестами.
// ФИО учеников хранятся только на устройстве и никуда не отправляются — ни в нейросеть, ни в BilimClass.
// Тот же смысл, что Attendance.java на телефоне.
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

  function empty() {
    return { classes: [], marks: {}, offDays: [], onDays: [] };
  }

  /** Сохранённый журнал → журнал с нужными полями. */
  function normalize(j) {
    const o = j && typeof j === 'object' ? j : {};
    return {
      classes: Array.isArray(o.classes) ? o.classes : [],
      marks: o.marks && typeof o.marks === 'object' ? o.marks : {},
      offDays: Array.isArray(o.offDays) ? o.offDays : [],
      onDays: Array.isArray(o.onDays) ? o.onDays : [],
    };
  }

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
    if (j.onDays.includes(iso)) return true;
    if (j.offDays.includes(iso)) return false;
    return defaultSchoolDay(iso);
  }

  /** Сделать день учебным или выходным (например, перенос праздника или карантин). */
  function setSchoolDay(j, iso, on) {
    j.onDays = j.onDays.filter((d) => d !== iso);
    j.offDays = j.offDays.filter((d) => d !== iso);
    if (on !== defaultSchoolDay(iso)) (on ? j.onDays : j.offDays).push(iso);
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
      .filter((d) => j.onDays.includes(d) || (quarterOf(d) && weekday(d) >= 1 && weekday(d) <= 5))
      .map((d) => ({ date: d, day: Number(d.slice(8)), school: isSchoolDay(j, d) }));
  }

  const schoolDays = (j, month) => monthColumns(j, month).filter((c) => c.school).map((c) => c.date);

  // ---------- Отметки ----------

  function getMark(j, iso, sid) {
    const day = j.marks[iso];
    return (day && day[sid]) || null;
  }

  /** reason = '' — был на уроках. Для «Другое» — своя запись. */
  function setMark(j, iso, sid, reason, note) {
    const day = j.marks[iso] || (j.marks[iso] = {});
    if (!reason || !reasonById(reason)) {
      delete day[sid];
    } else {
      const m = { r: reason };
      const t = String(note || '').trim();
      if (reason === 'other' && t) m.note = t;
      day[sid] = m;
    }
    if (!Object.keys(day).length) delete j.marks[iso];
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
    return j.classes.slice().sort((a, b) => num(a) - num(b) || a.name.localeCompare(b.name, 'ru'));
  }

  function sortedStudents(cls) {
    return cls.students.slice().sort((a, b) => a.name.localeCompare(b.name, 'ru'));
  }

  function addClass(j, name) {
    const c = { id: newId(), name: String(name || '').trim() || 'Новый класс', students: [] };
    j.classes.push(c);
    return c;
  }

  function addStudent(cls, name) {
    const s = { id: newId(), name: fixName(name) };
    cls.students.push(s);
    return s;
  }

  /** Удалить ученика вместе с его отметками. */
  function removeStudent(j, cls, sid) {
    cls.students = cls.students.filter((s) => s.id !== sid);
    for (const d of Object.keys(j.marks)) {
      delete j.marks[d][sid];
      if (!Object.keys(j.marks[d]).length) delete j.marks[d];
    }
  }

  function removeClass(j, cid) {
    const c = j.classes.find((x) => x.id === cid);
    if (!c) return;
    for (const s of c.students.slice()) removeStudent(j, c, s.id);
    j.classes = j.classes.filter((x) => x.id !== cid);
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
      let c = j.classes.find((x) => x.name.toLowerCase() === p.name.toLowerCase());
      if (!c) {
        c = addClass(j, p.name);
        classes++;
      }
      for (const name of p.students) {
        if (c.students.some((s) => s.name.toLowerCase() === name.toLowerCase())) continue;
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
      if (!c.students.length) continue;
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
    reasonById, empty, normalize, addDays, isSchoolDay, setSchoolDay, months, monthTitle, defaultMonth, monthColumns, schoolDays,
    getMark, setMark, markCode, monthStats, fixName, classGenitive, sortedClasses, sortedStudents, addClass, addStudent,
    removeStudent, removeClass, parseTemplate, importClasses, alerts, alertText, documentXml, docxFiles, fileName, plural,
  };
}));
