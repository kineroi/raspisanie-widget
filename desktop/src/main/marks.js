'use strict';
// Оценки за урок (ФО) и комментарии → журнал BilimClass. Запросы — те же, что делает сайт:
// урок и ученики — api …/journal/quarter, оценки — journal-service (свой пропуск chatToken, заголовок external: 1).
// Нейросети уходит только текст заметки учителя — без ФИО и без баллов.
const store = require('./store');
const bilim = require('./bilim');
const ai = require('./ai');
const journal = require('./journal');
const L = require('../shared/logic');
const M = require('../shared/marks');
const A = require('../shared/ai');

const journalHost = (eduYear) => (eduYear <= 2025
  ? 'https://journal-service-past.bilimclass.kz' : 'https://journal-service.bilimclass.kz');

async function getJson(url, opts) {
  return JSON.parse(await bilim.request('GET', url, opts));
}

/** Урок из сохранённого расписания (без классного часа — к нему оценок нет). */
function lessonOf(date, start) {
  const day = bilim.cachedDays()[String(date)];
  const lesson = day && day.lessons.find((l) => l.start === start);
  if (!lesson || lesson.title.startsWith('Кл. час')) return null;
  return lesson;
}

async function userInfo(token, school) {
  const info = await getJson(`${bilim.API}/api/v2/os/user-info`, { token, school });
  const user = info.data || info;
  if (!user.chatToken || !user.userId) throw new Error('Не удалось получить доступ к журналу BilimClass.');
  return { chat: user.chatToken, userId: user.userId };
}

/** Журнал класса (из расписания), период с этой датой, урок и оценки учеников. */
async function loadClass(token, school, user, date, det) {
  let list = store.get('journals', []);
  if (!list.some((j) => j.cls === det.cls)) list = journal.discoverJournals(store.readWeeks());
  const ji = list.find((j) => j.cls === det.cls);
  if (!ji) return { cls: det.cls, error: 'Журнал этого класса не найден. Откройте «Успеваемость», чтобы обновить список журналов.' };
  const eduYear = L.eduYear(L.parseIso(date));
  const H = { token, school };
  const periods = (await getJson(`${bilim.API}/api/v4/os/frontoffice/journal/period-list/${ji.uuid}?schoolId=${school}&eduYear=${eduYear}`, H)).data || [];
  const period = periods.find((p) => M.isoFromDmy(p.periodStart) <= date && date <= M.isoFromDmy(p.periodEnd));
  if (!period) return { cls: det.cls, error: 'Эта дата не входит ни в одну четверть журнала.' };
  const quarter = await getJson(`${bilim.API}/api/v4/os/frontoffice/journal/quarter/${ji.uuid}`
    + `?schoolId=${school}&eduYear=${eduYear}&educationProgramPeriodUuid=${period.uuid}`, H);
  const q = quarter.data || {};
  const subjectId = q.subjectId || ji.subjectId;
  const marks = await getJson(`${journalHost(eduYear)}/journal?schoolId=${school}&eduYear=${eduYear}`
    + `&userId=${user.userId}&groupId=${ji.groupId}&subjectId=${subjectId}`
    + `&dateFrom=${M.isoFromDmy(period.periodStart)}T00:00:00.000Z&dateTo=${M.isoFromDmy(period.periodEnd)}T00:00:00.000Z`,
  { token: user.chat, school, extra: { external: '1' } });
  const lesson = M.parseLesson(quarter, marks, det.scheduleUuid);
  if (!lesson.found) return { cls: det.cls, error: 'Этого урока нет в журнале класса.' };
  return { cls: det.cls, groupId: ji.groupId, subjectId, eduYear, ...lesson };
}

/** Ученики и оценки за урок по всем его классам. → { ok, title, classes } */
async function load(date, start) {
  const lesson = lessonOf(date, start);
  if (!lesson) return { ok: false, message: 'Урок не найден (или это классный час — оценок к нему нет). Обновите расписание.' };
  if (!store.get('refresh')) return { ok: false, message: 'BilimClass не подключён — войдите в основном окне.' };
  try {
    const classes = await bilim.withToken(async (token, school) => {
      const user = await userInfo(token, school);
      const out = [];
      for (const det of lesson.details.filter((d) => d.scheduleUuid)) out.push(await loadClass(token, school, user, date, det));
      return out;
    });
    return { ok: true, title: lesson.title, range: L.range(lesson), classes };
  } catch (e) {
    return { ok: false, message: e.code ? bilim.errorText(e) : (e.message || bilim.errorText(e)) };
  }
}

/**
 * Отправить оценки: cls — { cls, scheduleUuid, groupId, subjectId, eduYear, markMax }, list — из M.changes.
 * Каждая оценка отдельно; ошибки не останавливают остальные. → { ok, results: [{ id, ok, message }] }
 */
async function save(date, cls, list) {
  // если пропуск устареет посреди отправки, withToken повторит всё заново — уже отправленные не дублируем
  const sent = new Set();
  try {
    return await bilim.withToken(async (token, school) => {
      const user = await userInfo(token, school);
      const host = journalHost(cls.eduYear);
      const ctx = { ...cls, userId: user.userId, schoolId: school, date };
      const results = list.filter((ch) => sent.has(ch.id)).map((ch) => ({ id: ch.id, ok: true }));
      for (const ch of list) {
        if (sent.has(ch.id)) continue;
        const create = ch.kind === 'create' || !ch.markUuid;
        try {
          await bilim.request(create ? 'POST' : 'PUT', `${host}/mark`, {
            token: user.chat, school, contentType: 'application/json', extra: { external: '1' },
            body: JSON.stringify(create ? M.createBody(ctx, ch) : M.updateBody(ctx, ch)),
          });
          sent.add(ch.id);
          results.push({ id: ch.id, ok: true });
        } catch (e) {
          if (e instanceof bilim.HttpError && e.isExpired) throw e;
          results.push({ id: ch.id, ok: false, message: bilim.errorText(e) });
        }
      }
      return { ok: results.every((r) => r.ok), results };
    });
  } catch (e) {
    return { ok: false, message: bilim.errorText(e), results: [] };
  }
}

/** Отметить урок проведённым (как кнопка в журнале сайта) — без этого оценки не ставятся. */
async function conduct(date, scheduleUuid) {
  try {
    await bilim.withToken((token, school) => bilim.request('POST', `${bilim.API}/api/v4/os/frontoffice/lesson/complete`, {
      token, school, contentType: 'application/json',
      body: JSON.stringify({ schoolId: Number(school), eduYear: L.eduYear(L.parseIso(date)), scheduleUuid }),
    }));
    return { ok: true };
  } catch (e) {
    return { ok: false, message: bilim.errorText(e) };
  }
}

function aiConfig() {
  return { key: store.get('aiKey'), provider: store.get('aiProvider', ''), base: store.get('aiBase', ''), model: store.get('aiModel', '') };
}

/** Заметка учителя → комментарий на русском и английском, вместе не длиннее 250 символов. */
async function polishComment(note) {
  const cfg = aiConfig();
  if (!cfg.key) return { ok: false, message: 'Нужен ключ нейросети (Настройки → Нейросеть).' };
  const p = A.resolve(cfg);
  const ask = async (max) => {
    const out = await ai.completeJson(cfg, { system: M.systemPrompt(max), text: `Заметка учителя: ${String(note).trim()}`, maxTokens: 2048 });
    return { ru: String((out && out.ru) || ''), en: String((out && out.en) || '') };
  };
  try {
    let r = await ask(M.COMMENT_MAX - 10);
    if (!r.ru && !r.en) return { ok: false, message: `${p.name} не вернул комментарий. Попробуйте ещё раз.` };
    // не влезло — просим ещё короче, и только потом обрезаем сами
    if (r.ru.length + 1 + r.en.length > M.COMMENT_MAX) r = await ask(Math.floor(M.COMMENT_MAX * 0.6));
    return { ok: true, text: M.fitComment(r.ru, r.en) };
  } catch (e) {
    return { ok: false, message: ai.explain(e, p).replace(' Текст расписан без ИИ.', ' Комментарий не изменён.') };
  }
}

module.exports = { load, save, conduct, polishComment };
