/*
 * Справка «Анализ по итогам СОР и СОЧ» (5–9 классы) и «… промежуточного оценивания» (3–4 классы)
 * по данным журнала BilimClass: уровни, % качества, % успеваемости, ученики по уровням.
 * Уровни: низкий 0–39 %, средний 40–84 %, высокий 85–100 %.
 * Качество — доля учеников с результатом от 65 % (оценки «4» и «5»), успеваемость — от 40 %.
 * Работает и в Node (require), и в окнах программы (window.Analysis).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Analysis = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const GOAL =
    'Анализ результатов {a} (определить уровень целей обучения, направленный на развитие устной речи и ' +
    'фонематического слуха, письма, чтения, исходя из этого при проведении {b} за четверть ' +
    'проверялись все навыки: Listening, Speaking, Reading, Writing)';

  const REASONS = 'Невнимательность, следствие нерегулярного выполнения домашнего задания отдельными учащимися, ' +
    'пробелы в знаниях по причине пропусков занятий.';

  const PLAN = 'Для полноценного развития всех мыслительных навыков определить для всех уч-ся:\n' +
    '• Провести работу над ошибками, повторив темы, в которых дети затруднялись при выполнении задания.\n' +
    '• Продолжить работу отслеживать прогресс в обучении и корректировать индивидуальную траекторию развития ученика.\n' +
    '• Мотивировать учащихся на устранение имеющихся пробелов в усвоении учебной программы.\n' +
    '• Вести контроль за усвоением полученных знаний на уроке в ходе формативного оценивания.\n' +
    '• Усилить контроль выполнения домашнего задания.';

  const NO_DIFFICULTIES = 'Затруднений не выявлено';

  const SCHOOL = 'КГУ «Николаевская основная средняя школа отдела образования района Беимбета Майлина»';

  const level = (pct) => (pct < 40 ? 'low' : pct < 85 ? 'mid' : 'high');
  const percent = (part, all) => (all ? Math.floor(0.5 + (part / all) * 100) : 0);

  /** «РАСКИН» → «Раскин», «Иванова-ПЕТРОВА» → «Иванова-Петрова»; обычное написание не трогаем. */
  function fixCase(word) {
    return word.split('-').map((w) => (w.length > 1 && w === w.toUpperCase() && w !== w.toLowerCase()
      ? w[0] + w.slice(1).toLowerCase() : w)).join('-');
  }

  /** «ТУЙГАНОВ Сергей Петрович» → «Туйганов Сергей» (как в справках). */
  function shortName(full) {
    return String(full || '').trim().split(/\s+/).slice(0, 2).map(fixCase).join(' ');
  }

  /** «Марат Сандибекович Туйганов» (так пишет BilimClass) → «Туйганов Марат Сандибекович». */
  function teacherName(name) {
    const w = String(name || '').trim().split(/\s+/).filter(Boolean);
    return w.length === 3 ? `${w[2]} ${w[0]} ${w[1]}` : w.join(' ');
  }

  /** Итоги одной работы. */
  function assessment(label, sched, students, kind) {
    const results = [];
    for (const st of students) {
      const m = st[kind] && st[kind][sched.uuid];
      if (!m) continue;
      const max = m.max || sched.max;
      if (!max) continue;
      const pct = (m.mark / max) * 100;
      results.push({ name: shortName(st.name), mark: m.mark, max, pct, level: level(pct) });
    }
    const by = { low: [], mid: [], high: [] };
    for (const r of results) by[r.level].push(r.name);
    return {
      key: sched.uuid,
      label,
      date: sched.date,
      max: sched.max,
      wrote: results.length,
      low: by.low.length,
      mid: by.mid.length,
      high: by.high.length,
      quality: percent(results.filter((r) => r.pct >= 65).length, results.length),
      success: percent(results.filter((r) => r.pct >= 40).length, results.length),
      names: by,
    };
  }

  const byDate = (a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : 0);

  /**
   * Справка по журналу класса (из Grades.buildJournal).
   * mode 'po' — 3–4 классы (ПО 1, ПО 2…), 'sor' — СОР 1…, СОЧ.
   */
  function build(journal) {
    const students = journal.students || [];
    const po = [...(journal.po || [])].sort(byDate);
    const sor = [...(journal.sor || [])].sort(byDate);
    const soch = [...(journal.soch || [])].sort(byDate);
    const mode = po.length && !sor.length && !soch.length ? 'po' : 'sor';
    const items = [];
    if (mode === 'po') {
      po.forEach((s, i) => items.push(assessment(`ПО ${i + 1}`, s, students, 'po')));
    } else {
      sor.forEach((s, i) => items.push(assessment(`СОР ${i + 1}`, s, students, 'sor')));
      soch.forEach((s, i) => items.push(assessment(soch.length > 1 ? `СОЧ ${i + 1}` : 'СОЧ', s, students, 'soch')));
    }
    return {
      cls: journal.cls,
      group: journal.group || journal.cls,
      grade: parseInt(journal.cls, 10) || null,
      subject: journal.subject || '',
      teacher: teacherName(journal.teacher),
      period: journal.period,
      students: students.length,
      mode,
      items,
    };
  }

  function title(mode, quarter, subject) {
    const what = mode === 'po' ? 'промежуточного оценивания (ПО)' : 'СОР и СОЧ';
    return `Анализ по итогам проведения ${what} за ${quarter} четверть по предмету ${subject}`;
  }

  function goal(mode) {
    return mode === 'po'
      ? GOAL.replace('{a}', 'промежуточного оценивания').replace('{b}', 'промежуточного оценивания')
      : GOAL.replace('{a}', 'СОР и СОЧ').replace('{b}', 'суммативного оценивания');
  }

  function fileName(a, subject) {
    const what = a.mode === 'po' ? 'Анализ ПО' : 'Анализ СОР и СОЧ';
    return `${what} ${a.grade || a.cls} класс (${subject}).xlsx`.replace(/[\\/:*?"<>|]/g, '');
  }

  /** Пункт 2 из затруднений по каждой работе. */
  function difficultiesText(items, texts) {
    const lines = [];
    for (const it of items) {
      const t = texts[it.label] || {};
      const d = String(t.difficulties || '').trim();
      if (!d) continue;
      const unit = t.unit ? ` «${t.unit}»` : '';
      lines.push(`${it.label}${unit}: ${d}`);
    }
    return lines.join('\n');
  }

  /** Краткие итоги работы для нейросети — без фамилий. */
  function statsForAi(it) {
    return `Писали ${it.wrote} уч., максимум ${it.max} баллов. Низкий уровень: ${it.low}, средний: ${it.mid}, ` +
      `высокий: ${it.high}. Качество ${it.quality}%, успеваемость ${it.success}%.`;
  }

  return {
    SCHOOL, REASONS, PLAN, NO_DIFFICULTIES, shortName, teacherName, build, title, goal, fileName, difficultiesText, statsForAi,
  };
});
