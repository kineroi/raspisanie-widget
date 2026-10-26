'use strict';
// Запросы к BilimClass — те же, что делает сайт bilimclass.kz (и Android-версия).
const { net } = require('electron');
const store = require('./store');
const L = require('../shared/logic');

const API = 'https://api.bilimclass.kz';

class HttpError extends Error {
  constructor(code) {
    super(`HTTP ${code}`);
    this.code = code;
  }

  get isAuth() {
    return [401, 403, 419, 426].includes(this.code);
  }
}

async function request(method, url, { token, school, body, contentType } = {}) {
  const headers = { Accept: 'application/json', 'Accept-Language': 'ru', 'X-Localization': 'ru' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (school) headers['x-school-id'] = String(school);
  if (contentType) headers['Content-Type'] = contentType;
  let res;
  try {
    res = await net.fetch(url, { method, headers, body });
  } catch (e) {
    const err = new Error('Нет связи с BilimClass');
    err.network = true;
    throw err;
  }
  const text = await res.text();
  if (!res.ok) throw new HttpError(res.status);
  return text;
}

function secondsLeft(token) {
  try {
    const payload = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    return payload.exp ? payload.exp - Math.floor(Date.now() / 1000) : -1;
  } catch {
    return -1;
  }
}

async function fetchWeek(token, school, monday) {
  const url = `${API}/api/v4/os/frontoffice/dashboard/teacher?schoolId=${school}` +
    `&eduYear=${L.eduYear(monday)}&date=${L.isoDate(monday)}`;
  const json = JSON.parse(await request('GET', url, { token }));
  if (!json.data || !Array.isArray(json.data.days)) throw new Error('Неожиданный ответ BilimClass');
  return json;
}

async function renew(token, refresh) {
  let o;
  try {
    o = JSON.parse(await request('POST', `${API}/api/v2/os/refresh_token`, {
      token, body: JSON.stringify({ refreshToken: refresh }), contentType: 'application/json',
    }));
  } catch (e) {
    if (e instanceof HttpError && e.isAuth) store.set({ needLogin: true });
    throw e;
  }
  if (!o.access_token && o.data) o = o.data;
  if (!o.access_token) {
    store.set({ needLogin: true });
    throw new HttpError(401);
  }
  store.set({ token: o.access_token, refresh: o.refresh_token || refresh });
  return o.access_token;
}

// Все обращения к BilimClass по очереди, чтобы продление пропуска не запускалось дважды.
let chain = Promise.resolve();
function locked(fn) {
  const r = chain.then(fn, fn);
  chain = r.catch(() => {});
  return r;
}

/** Выполнить fn(token, school) с действующим пропуском; при устаревшем — продлить и повторить. */
function withToken(fn) {
  return locked(async () => {
    const refresh = store.get('refresh');
    const school = store.get('school');
    if (!refresh || !school) throw new HttpError(401);
    let token = store.get('token');
    const left = secondsLeft(token);
    if (!token || (left >= 0 && left < 300)) token = await renew(token, refresh);
    try {
      return await fn(token, school);
    } catch (e) {
      if (!(e instanceof HttpError) || !e.isAuth) throw e;
      token = await renew(token, store.get('refresh'));
      return fn(token, school);
    }
  });
}

/** Загрузить прошлую, текущую и следующую недели. Возвращает 'ok' | 'not_connected' | 'need_login' | 'no_network'. */
async function sync() {
  if (!store.get('refresh') || !store.get('school')) return 'not_connected';
  const monday = L.mondayOf(new Date());
  try {
    const weeks = await withToken(async (token, school) => {
      const out = [];
      for (const off of [-7, 0, 7]) out.push(await fetchWeek(token, school, L.addDays(monday, off)));
      return out;
    });
    L.parseWeeks(weeks); // проверка, что всё разбирается
    store.writeWeeks(weeks);
    store.set({ lastSync: Date.now(), needLogin: false });
    return 'ok';
  } catch (e) {
    if (e instanceof HttpError && e.isAuth) {
      store.set({ needLogin: true });
      return 'need_login';
    }
    return 'no_network';
  }
}

/** Дни из сохранённого расписания ({} если нет). */
function cachedDays() {
  const weeks = store.readWeeks();
  if (!weeks) return {};
  try {
    return L.parseWeeks(weeks);
  } catch {
    return {};
  }
}

/** Сохранить домашку урока (как кнопка «Сохранить» на сайте): текст заменяет прежний, файлы добавляются. */
async function saveHomework(token, school, eduYear, scheduleUuid, body, files) {
  const boundary = '----RaspisanieBoundary' + Date.now();
  const parts = [];
  const field = (name, value) => parts.push(Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`, 'utf8'));
  field('schoolId', school);
  field('eduYear', eduYear);
  field('scheduleUuid', scheduleUuid);
  field('body', body);
  field('_method', 'PUT');
  for (const f of files || []) {
    const name = String(f.name).replace(/["\r\n]/g, ' ');
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="files[]"; filename="${name}"\r\n` +
      `Content-Type: ${f.mime || 'application/octet-stream'}\r\n\r\n`, 'utf8'));
    parts.push(Buffer.from(f.data));
    parts.push(Buffer.from('\r\n'));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  await request('POST', `${API}/api/v4/os/frontoffice/simple-homework`, {
    token, school, body: Buffer.concat(parts), contentType: `multipart/form-data; boundary=${boundary}`,
  });
}

/** Удалить файл из домашки (так же, как сайт). */
async function deleteFile(token, school, eduYear, fileId) {
  await request('DELETE', `${API}/api/v4/os/frontoffice/simple-homework/delete-file`, {
    token, school, contentType: 'application/json',
    body: JSON.stringify({ schoolId: Number(school), eduYear, fileId }),
  });
}

function errorText(e) {
  if (e instanceof HttpError) {
    if (e.isAuth) return 'Вход в BilimClass устарел — войдите заново.';
    if (e.code === 413) return 'Файл слишком большой для BilimClass.';
    if (e.code === 422) return 'BilimClass не принял данные (проверьте текст и файлы).';
    return `BilimClass ответил ошибкой ${e.code}.`;
  }
  return 'Нет связи с BilimClass. Проверьте интернет.';
}

module.exports = { HttpError, sync, cachedDays, withToken, saveHomework, deleteFile, errorText };
