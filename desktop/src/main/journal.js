'use strict';
// Успеваемость: загрузка журналов из BilimClass (только чтение) и расчёт группы риска / СОЧ / конца четверти.
const store = require('./store');
const bilim = require('./bilim');
const L = require('../shared/logic');
const G = require('../shared/grades');

const REFRESH_EVERY_MS = 6 * 60 * 60 * 1000;
let loading = null;

const journalHost = (eduYear) => (eduYear <= 2025
  ? 'https://journal-service-past.bilimclass.kz' : 'https://journal-service.bilimclass.kz');

/** Мои журналы — из уроков в расписании (класс + предмет), без классного часа. */
function discoverJournals(weeks) {
  const map = new Map();
  for (const w of weeks || []) {
    for (const row of (w.data && w.data.rows) || []) {
      for (const arr of Object.values(row || {})) {
        for (const l of arr || []) {
          const subj = l.subject || {};
          const uuid = l.educationProgramSubjectUuid || subj.uuid;
          const groupId = l.class && l.class.groupId;
          if (!uuid || !groupId || subj.isAdditional) continue;
          map.set(uuid, { uuid, groupId, subjectId: subj.subjectId, cls: L.compactClass(l.class.label) });
        }
      }
    }
  }
  // сохраняем найденные ранее (на каникулах в расписании уроков может не быть)
  for (const j of store.get('journals', [])) if (!map.has(j.uuid)) map.set(j.uuid, j);
  const list = [...map.values()].sort((a, b) => a.cls.localeCompare(b.cls, 'ru', { numeric: true }));
  store.set({ journals: list });
  return list;
}

async function getJson(url, opts) {
  return JSON.parse(await bilim.request('GET', url, opts));
}

/** Загрузить уроки (темы и ДЗ) за четверть — для контроля конца четверти. */
async function quarterLessons(token, school, start, end) {
  const lessons = [];
  for (let d = L.mondayOf(L.parseIso(start)); L.isoDate(d) <= end; d = L.addDays(d, 7)) {
    const days = L.parseWeek(await bilim.fetchWeek(token, school, d));
    for (const [date, day] of Object.entries(days)) {
      if (date < start || date > end) continue;
      for (const l of day.lessons) {
        for (const det of l.details) {
          lessons.push({ date, cls: det.cls, theme: det.theme, homework: det.homework, additional: l.title.startsWith('Кл. час') || l.title.startsWith('Факультатив') });
        }
      }
    }
  }
  return lessons;
}

/** Обновить успеваемость из BilimClass. Возвращает 'ok' | 'not_connected' | 'need_login' | 'no_network' | 'no_journals'. */
function refresh() {
  if (loading) return loading;
  loading = (async () => {
    if (!store.get('refresh')) return 'not_connected';
    const journalsInfo = discoverJournals(store.readWeeks());
    if (!journalsInfo.length) return 'no_journals';
    const today = L.isoDate(new Date());
    try {
      const result = await bilim.withToken(async (token, school) => {
        const H = { token, school };
        const info = await getJson(`${bilim.API}/api/v2/os/user-info`, H);
        const user = info.data || info;
        const chat = user.chatToken;
        const userId = user.userId;
        if (!chat || !userId) throw new Error('no chat token');
        const eduYear = L.eduYear(new Date());
        const journals = [];
        for (const ji of journalsInfo) {
          const periodsResp = await getJson(`${bilim.API}/api/v4/os/frontoffice/journal/period-list/${ji.uuid}?schoolId=${school}&eduYear=${eduYear}`, H);
          const periods = (periodsResp.data || []).map((p) => ({
            uuid: p.uuid, n: p.period, start: G.dmy(p.periodStart), end: G.dmy(p.periodEnd),
          }));
          const period = G.pickPeriod(periods, today);
          if (!period) continue;
          const quarter = await getJson(`${bilim.API}/api/v4/os/frontoffice/journal/quarter/${ji.uuid}` +
            `?schoolId=${school}&eduYear=${eduYear}&educationProgramPeriodUuid=${period.uuid}`, H);
          const q = quarter.data || {};
          const marks = await getJson(`${journalHost(eduYear)}/journal?schoolId=${school}&eduYear=${eduYear}` +
            `&userId=${userId}&groupId=${ji.groupId}&subjectId=${q.subjectId || ji.subjectId}` +
            `&dateFrom=${period.start}T00:00:00.000Z&dateTo=${period.end}T00:00:00.000Z`,
          { token: chat, school, extra: { external: '1' } });
          journals.push(G.buildJournal({
            uuid: ji.uuid, groupId: ji.groupId,
            cls: q.group ? L.compactClass(String(q.group).replace(/\s+(РУС|ҚАЗ|КАЗ)$/i, '')) : ji.cls,
            subject: q.subjectName || '',
            period,
          }, quarter, marks));
        }
        // уроки за четверть нужны только в последнюю неделю четверти
        let lessons = [];
        const soon = journals.find((j) => j.period && Date.parse(j.period.end) - Date.parse(today) <= 7 * 86400000 && j.period.end >= today);
        if (soon) lessons = await quarterLessons(token, school, soon.period.start, soon.period.end);
        return { journals, lessons };
      });
      store.writeGrades({ updatedAt: Date.now(), ...result });
      store.set({ gradesAt: Date.now() });
      return 'ok';
    } catch (e) {
      if (e instanceof bilim.HttpError && e.isAuth) return 'need_login';
      return 'no_network';
    }
  })().finally(() => {
    loading = null;
  });
  return loading;
}

function refreshIfStale() {
  if (Date.now() - store.get('gradesAt', 0) > REFRESH_EVERY_MS) return refresh();
  return Promise.resolve('fresh');
}

/** Данные для окна «Успеваемость». */
function snapshot() {
  const g = store.readGrades();
  if (!g) return { updatedAt: 0, classes: [], check: null };
  const today = L.isoDate(new Date());
  return {
    updatedAt: g.updatedAt,
    classes: g.journals.map((j) => ({ journal: j, summary: G.classSummary(j) })),
    check: G.quarterCheck(g.journals, g.lessons, today),
  };
}

module.exports = { refresh, refreshIfStale, snapshot, discoverJournals };
