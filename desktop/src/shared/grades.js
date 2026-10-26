/*
 * Успеваемость: расчёт как в журнале BilimClass (формула 2025):
 *   %ФО = доля баллов ФО × 25, %СОР = доля баллов СОР × 25, %СОЧ = доля баллов СОЧ × 50,
 *   итог = округлить(%ФО + %СОР + %СОЧ);  < 40 → 2,  40–64 → 3,  65–84 → 4,  ≥ 85 → 5.
 * Пока СОЧ нет, программа показывает ПРОГНОЗ: те же веса, пересчитанные на имеющиеся части.
 * Работает и в Node (require), и в окнах программы (window.Grades).
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Grades = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const WEIGHTS = { fo: 25, sor: 25, soch: 50 };
  const RISK_MARGIN = 5; // «на грани» — до 5% над границей оценки

  function gradeFor(pct) {
    if (pct == null) return null;
    if (pct < 40) return 2;
    if (pct < 65) return 3;
    if (pct < 85) return 4;
    return 5;
  }
  const LOWER = { 3: 40, 4: 65, 5: 85 };

  const dmy = (s) => {
    // '03.09.26' или '01.09.2026' → 'YYYY-MM-DD'
    const [d, m, y] = String(s).split('.');
    return `${y.length === 2 ? '20' + y : y}-${m}-${d}`;
  };

  /**
   * Собрать журнал класса из трёх ответов BilimClass:
   *  quarter — /journal/quarter/{uuid}, marks — journal-service /journal, info — {cls, subject, groupId, uuid, period}.
   */
  function buildJournal(info, quarter, marks) {
    const q = quarter.data || quarter;
    const byStudent = (marks && (marks.data || marks)) || {};
    const sorSched = (q.sorSchedules || []).map((s) => ({ uuid: s.uuid, date: dmy(s.date), max: s.markMax }));
    const sochSched = (q.sochSchedules || []).map((s) => ({ uuid: s.uuid, date: dmy(s.date), max: s.markMax }));
    // ПО — промежуточное оценивание в 3–4 классах (вместо СОР и СОЧ)
    const poSched = (q.poSchedules || []).map((s) => ({ uuid: s.uuid, date: dmy(s.date), max: s.markMax }));
    const students = (q.rows || [])
      .filter((r) => r.studentStatus !== false && !r.departDate)
      .map((r) => {
        const cells = (byStudent[r.studentGroupUuid] && byStudent[r.studentGroupUuid].marks) || {};
        const fo = [];
        const sor = {};
        const soch = {};
        const po = {};
        for (const [sched, c] of Object.entries(cells)) {
          const f = c && c.formattedScore;
          if (f && typeof f.mark === 'number') fo.push({ mark: f.mark, max: f.assessmentMax || 10 });
          if (c && c.sor && typeof c.sor.mark === 'number') sor[sched] = { mark: c.sor.mark, max: c.sor.assessmentMax };
          if (c && c.soch && typeof c.soch.mark === 'number') soch[sched] = { mark: c.soch.mark, max: c.soch.assessmentMax };
          if (c && c.po && typeof c.po.mark === 'number') po[sched] = { mark: c.po.mark, max: c.po.assessmentMax };
        }
        return {
          id: r.studentGroupUuid,
          name: r.fullName || '',
          finalScore: r.finalScore ? String(r.finalScore) : '',
          siteGrade: r.recommendedScore ? Number(r.recommendedScore) : null,
          canFinal: r.canGetFinalScore !== false,
          fo, sor, soch, po,
        };
      });
    const fd = q.finalMarkDeadline || null;
    return {
      ...info,
      sor: sorSched,
      soch: sochSched,
      po: poSched,
      deadline: fd && fd.endDate ? { start: fd.startDate, end: fd.endDate } : null,
      students,
    };
  }

  const ratio = (items) => {
    const sum = items.reduce((a, x) => a + x.mark, 0);
    const max = items.reduce((a, x) => a + (x.max || 0), 0);
    return max > 0 ? sum / max : null;
  };

  /** Проценты и итог по ученику. */
  function studentStats(st) {
    const r = { fo: ratio(st.fo), sor: ratio(Object.values(st.sor)), soch: ratio(Object.values(st.soch)) };
    const parts = {};
    for (const k of ['fo', 'sor', 'soch']) parts[k] = r[k] == null ? null : r[k] * WEIGHTS[k];
    let total = null;
    let forecast = false;
    if (parts.fo != null && parts.sor != null && parts.soch != null) {
      total = Math.round(parts.fo + parts.sor + parts.soch);
    } else {
      // прогноз по имеющимся частям
      let w = 0;
      let s = 0;
      for (const k of ['fo', 'sor', 'soch']) if (r[k] != null) { w += WEIGHTS[k]; s += r[k] * WEIGHTS[k]; }
      if (w > 0) { total = Math.round((s / w) * 100); forecast = true; }
    }
    // Если BilimClass уже рассчитал рекомендуемую оценку — она главная (в классах без СОР/СОЧ
    // у BilimClass своё правило); собственный расчёт — только прогноз, пока её нет.
    const site = st.siteGrade || null;
    return { ratios: r, parts, total, grade: site || gradeFor(total), fromSite: !!site, forecast: !site && forecast };
  }

  /**
   * Кто в группе риска: 'two' — сейчас 2; 'edge2' — на грани двойки; 'edge3' — на грани тройки (из 4).
   * grade — оценка (BilimClass или расчётная), total — процент.
   */
  function riskOf(total, grade) {
    if (total == null && grade == null) return null;
    const g = grade || gradeFor(total);
    if (g === 2) return 'two';
    if (g === 3 && total != null && total < 40 + RISK_MARGIN) return 'edge2';
    if (g === 4 && total != null && total < 65 + RISK_MARGIN) return 'edge3';
    return null;
  }

  const RISK_TEXT = {
    two: 'сейчас выходит 2',
    edge2: 'на грани двойки',
    edge3: 'на грани тройки',
  };

  /**
   * Сколько баллов нужно на СОЧ для 3, 4 и 5 (если СОЧ в четверти есть и ещё не написан).
   * Возвращает {max, need: {3: n|null, 4: n|null, 5: n|null}} — null значит «недостижимо»; 0 — «уже обеспечено».
   * ФО и СОР берутся текущие; если их ещё нет — считаются как 0.
   */
  function needForSoch(st, journal) {
    if (!journal.soch.length || Object.keys(st.soch).length) return null;
    const max = journal.soch.reduce((a, s) => a + (s.max || 0), 0);
    if (!max) return null;
    const fo = ratio(st.fo) || 0;
    const sor = ratio(Object.values(st.sor)) || 0;
    const base = fo * WEIGHTS.fo + sor * WEIGHTS.sor;
    const need = {};
    for (const g of [3, 4, 5]) {
      need[g] = null;
      for (let s = 0; s <= max; s++) {
        if (Math.round(base + (s / max) * WEIGHTS.soch) >= LOWER[g]) { need[g] = s; break; }
      }
    }
    return { max, need };
  }

  /** Сводка по классу: ученики со статистикой, отсортированные от слабых к сильным. */
  function classSummary(journal) {
    const rows = journal.students.map((st) => {
      const stats = studentStats(st);
      return { st, stats, risk: riskOf(stats.total, stats.grade), soch: needForSoch(st, journal) };
    });
    rows.sort((a, b) => (a.stats.total == null ? 999 : a.stats.total) - (b.stats.total == null ? 999 : b.stats.total));
    return { rows, riskCount: rows.filter((r) => r.risk).length };
  }

  // ---------- Контроль конца четверти ----------

  const daysBetween = (a, b) => Math.round((Date.parse(b) - Date.parse(a)) / 86400000);

  /**
   * Что не сделано к концу четверти. today — 'YYYY-MM-DD'; lessons — уроки из расписания
   * [{date, cls, theme, homework, additional}] за четверть.
   * Возвращает null, если до конца четверти больше 7 дней (или срок итоговых прошёл).
   */
  function quarterCheck(journals, lessons, today) {
    const items = [];
    let end = null;
    let deadline = null;
    for (const j of journals) {
      if (!j.period) continue;
      const pEnd = j.period.end;
      const lastDay = j.deadline && j.deadline.end > pEnd ? j.deadline.end : pEnd;
      if (daysBetween(today, pEnd) > 7 || today > lastDay) continue;
      end = !end || pEnd < end ? pEnd : end;
      if (j.deadline) deadline = !deadline || j.deadline.end < deadline ? j.deadline.end : deadline;
      const noFinal = j.students.filter((s) => s.canFinal && !s.finalScore).length;
      if (noFinal) items.push({ cls: j.cls, kind: 'final', count: noFinal, text: `${j.cls}: не выставлено итоговых — ${noFinal}` });
      for (const [kind, list, key] of [['sor', j.sor, 'sor'], ['soch', j.soch, 'soch']]) {
        for (const s of list) {
          if (s.date > today) continue;
          const missing = j.students.filter((st) => !st[key][s.uuid]).length;
          if (missing) {
            items.push({ cls: j.cls, kind, count: missing,
              text: `${j.cls}: ${kind === 'sor' ? 'СОР' : 'СОЧ'} от ${s.date.slice(8)}.${s.date.slice(5, 7)} — без оценки ${missing}` });
          }
        }
      }
    }
    if (!end) return null;
    const periodStart = journals.reduce((a, j) => (j.period && (!a || j.period.start < a) ? j.period.start : a), null);
    const past = (lessons || []).filter((l) => !l.additional && l.date < today && (!periodStart || l.date >= periodStart));
    const noTheme = past.filter((l) => !l.theme).length;
    const noHw = past.filter((l) => !l.homework).length;
    if (noTheme) items.push({ kind: 'theme', count: noTheme, text: `Уроков без темы — ${noTheme}` });
    if (noHw) items.push({ kind: 'homework', count: noHw, text: `Уроков без ДЗ — ${noHw}` });
    return { end, deadline, daysLeft: daysBetween(today, end), items };
  }

  /** Выбрать четверть: текущую; если каникулы — последнюю прошедшую. periods: [{uuid, period, start, end}] в ISO. */
  function pickPeriod(periods, today) {
    const cur = periods.find((p) => p.start <= today && today <= p.end);
    if (cur) return cur;
    const past = periods.filter((p) => p.end < today).sort((a, b) => (a.end < b.end ? 1 : -1));
    return past[0] || null;
  }

  return {
    WEIGHTS, gradeFor, buildJournal, studentStats, riskOf, RISK_TEXT, needForSoch, classSummary, quarterCheck,
    pickPeriod, dmy,
  };
});
