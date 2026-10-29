'use strict';
/* global Logic */
const L = Logic;
const $ = (id) => document.getElementById(id);

let data = null;
let weekOffset = 0;
let message = '';
let editingKey = false;
let aiMsg = '';
/** Сколько учеников в группе риска по всем классам (null — журнал ещё не загружен). */
let riskTotal = null;

const ICONS = {
  calendar: '<path d="M8 2V5M16 2V5M3.5 9.09H20.5"/><path d="M21 8.5V17C21 20 19.5 22 16 22H8C4.5 22 3 20 3 17V8.5C3 5.5 4.5 3.5 8 3.5H16C19.5 3.5 21 5.5 21 8.5Z"/>',
  pen: '<path d="M21.47 19V5C21.47 3 20.47 2 18.47 2H14.47C12.47 2 11.47 3 11.47 5V19C11.47 21 12.47 22 14.47 22H18.47C20.47 22 21.47 21 21.47 19Z"/><path d="M11.47 6H16.47M11.47 18H15.47M11.47 13.95L16.47 14M11.47 10H14.47"/><path d="M5.49 2C3.86 2 2.53 3.33 2.53 4.95V17.91C2.53 18.36 2.72 19.04 2.95 19.43L3.77 20.79C4.71 22.36 6.26 22.36 7.2 20.79L8.02 19.43C8.25 19.04 8.44 18.36 8.44 17.91V4.95C8.44 3.33 7.11 2 5.49 2Z"/><path d="M8.44 7H2.53"/>',
  teacher: '<path d="M10.05 2.53L4.03 6.46C2.1 7.72 2.1 10.54 4.03 11.8L10.05 15.73C11.13 16.44 12.91 16.44 13.99 15.73L19.98 11.8C21.9 10.54 21.9 7.73 19.98 6.47L13.99 2.54C12.91 1.82 11.13 1.82 10.05 2.53Z"/><path d="M5.63 13.08L5.62 17.77C5.62 19.04 6.6 20.4 7.8 20.8L10.99 21.86C11.54 22.04 12.45 22.04 13.01 21.86L16.2 20.8C17.4 20.4 18.38 19.04 18.38 17.77V13.13M21.4 15V9"/>',
  folder: '<path d="M21.5 11.15V16.57C21.5 19.72 20.17 21 16.92 21H7.08C3.83 21 2.5 19.72 2.5 16.57V7.43C2.5 4.28 3.83 3 7.08 3H9.6C10.94 3 11.3 3.37 11.84 4.07L13.06 5.7C13.42 6.18 13.63 6.3 14.27 6.3H16.92C20.17 6.3 21.5 7.58 21.5 10.73"/><path d="M8 13H16"/>',
  bell: '<path d="M12.02 2.91C8.71 2.91 6.02 5.6 6.02 8.91V11.8C6.02 12.41 5.76 13.34 5.45 13.86L4.3 15.77C3.59 16.95 4.08 18.26 5.38 18.7C9.69 20.14 14.34 20.14 18.65 18.7C19.86 18.3 20.39 16.87 19.73 15.77L18.58 13.86C18.28 13.34 18.02 12.41 18.02 11.8V8.91C18.02 5.61 15.32 2.91 12.02 2.91Z"/><path d="M15.02 19.06C15.02 20.71 13.67 22.06 12.02 22.06C11.2 22.06 10.44 21.72 9.9 21.18C9.36 20.64 9.02 19.88 9.02 19.06"/>',
  home: '<path d="M22 8.27V4.23C22 2.64 21.36 2 19.77 2H15.73C14.14 2 13.5 2.64 13.5 4.23V8.27C13.5 9.86 14.14 10.5 15.73 10.5H19.77C21.36 10.5 22 9.86 22 8.27Z"/><path d="M10.5 8.52V3.98C10.5 2.57 9.86 2 8.27 2H4.23C2.64 2 2 2.57 2 3.98V8.51C2 9.93 2.64 10.49 4.23 10.49H8.27C9.86 10.5 10.5 9.93 10.5 8.52Z"/><path d="M10.5 19.77V15.73C10.5 14.14 9.86 13.5 8.27 13.5H4.23C2.64 13.5 2 14.14 2 15.73V19.77C2 21.36 2.64 22 4.23 22H8.27C9.86 22 10.5 21.36 10.5 19.77Z"/><path d="M15 15.5H21M15 19.5H21"/>',
  setting: '<path d="M3 9.11V14.88C3 17 3 17 5 18.35L10.5 21.53C11.33 22.01 12.68 22.01 13.5 21.53L19 18.35C21 17 21 17 21 14.89V9.11C21 7 21 7 19 5.65L13.5 2.47C12.68 1.99 11.33 1.99 10.5 2.47L5 5.65C3 7 3 7 3 9.11Z"/><circle cx="12" cy="12" r="3"/>',
  moon: '<path d="M2.03 12.42C2.39 17.57 6.76 21.76 11.99 21.99C15.68 22.15 18.98 20.43 20.96 17.72C21.78 16.61 21.34 15.87 19.97 16.12C19.3 16.24 18.61 16.29 17.89 16.26C13 16.06 9 11.97 8.98 7.14C8.97 5.84 9.24 4.61 9.73 3.49C10.27 2.25 9.62 1.66 8.37 2.19C4.41 3.86 1.7 7.85 2.03 12.42Z"/>',
  sun: '<circle cx="12" cy="12" r="6.5"/><path stroke-width="2" d="M19.14 19.14L19.01 19.01M19.01 4.99L19.14 4.86M4.86 19.14L4.99 19.01M12 2.08V2M12 22V21.92M2.08 12H2M22 12H21.92M4.99 4.99L4.86 4.86"/>',
};

function svg(name) {
  const ns = 'http://www.w3.org/2000/svg';
  const s = document.createElementNS(ns, 'svg');
  s.setAttribute('viewBox', '0 0 24 24');
  s.setAttribute('fill', 'none');
  s.setAttribute('stroke', 'currentColor');
  s.setAttribute('stroke-width', '1.5');
  s.setAttribute('stroke-linecap', 'round');
  s.setAttribute('stroke-linejoin', 'round');
  // пути — постоянные строки из ICONS, не данные пользователя
  s.innerHTML = ICONS[name];
  return s;
}

/** 1 урок, 2 урока, 5 уроков */
function plural(n, one, few, many) {
  const a = n % 100;
  const b = n % 10;
  if (a >= 11 && a <= 14) return many;
  if (b === 1) return one;
  if (b >= 2 && b <= 4) return few;
  return many;
}

// ---------- Тема: как в Windows или выбранная вручную ----------

const darkQuery = window.matchMedia('(prefers-color-scheme: dark)');
/** 'light' | 'dark' — выбрано вручную; null — как в Windows. */
let manualTheme = null;
try { manualTheme = localStorage.getItem('theme'); } catch (e) { /* хранилище недоступно */ }

function applyTheme() {
  if (manualTheme) document.documentElement.dataset.theme = manualTheme;
  else delete document.documentElement.dataset.theme;
  const dark = manualTheme ? manualTheme === 'dark' : darkQuery.matches;
  const btn = $('themeBtn');
  btn.textContent = '';
  btn.append(svg(dark ? 'sun' : 'moon'));
  btn.title = dark ? 'Светлая тема' : 'Тёмная тема';
}

$('themeBtn').addEventListener('click', () => {
  const dark = manualTheme ? manualTheme === 'dark' : darkQuery.matches;
  // выбрали ту же тему, что в Windows, — снова следуем за системой
  manualTheme = !dark === darkQuery.matches ? null : (dark ? 'light' : 'dark');
  try {
    if (manualTheme) localStorage.setItem('theme', manualTheme);
    else localStorage.removeItem('theme');
  } catch (e) { /* без сохранения — до перезапуска */ }
  applyTheme();
});
darkQuery.addEventListener('change', applyTheme);
applyTheme();

// ---------- Разделы: главная, неделя, настройки ----------

/** Неделя — та же страница, что главная, но только с расписанием на всю ширину. */
function showView(name) {
  $('viewHome').hidden = name === 'settings';
  $('viewHome').classList.toggle('week-only', name === 'week');
  $('viewSettings').hidden = name !== 'settings';
  $('navHome').classList.toggle('active', name === 'home');
  $('navWeek').classList.toggle('active', name === 'week');
  $('navSettings').classList.toggle('active', name === 'settings');
  $('scroll').scrollTop = 0;
}

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function button(label, cls, onClick) {
  const b = el('button', cls, label);
  b.addEventListener('click', onClick);
  return b;
}

function when(ms) {
  const t = new Date(ms);
  const hm = `${String(t.getHours()).padStart(2, '0')}:${String(t.getMinutes()).padStart(2, '0')}`;
  return L.isoDate(t) === L.isoDate(new Date()) ? `сегодня в ${hm}` : `${L.dateText(t)} в ${hm}`;
}

async function doSync() {
  message = '';
  const r = await window.api.sync();
  if (r === 'no_network') message = 'Не удалось обновить: нет связи с BilimClass. Показано сохранённое.';
  if (r === 'need_login') message = 'Вход в BilimClass устарел — войдите заново.';
  render();
}

function renderBilim() {
  let status;
  if (!data.connected) {
    status = 'Не подключён. Подключите, чтобы расписание, темы и домашка подтягивались автоматически.';
  } else if (data.needLogin) {
    status = 'Вход устарел — нажмите «Войти снова».';
  } else if (data.syncing) {
    status = 'Обновляю…';
  } else {
    status = data.lastSync ? `Обновлено ${when(data.lastSync)}` : 'Подключён.';
  }
  $('bilimStatus').textContent = status;
  $('bilimStatus').className = data.needLogin ? 'error' : 'muted';
  $('bilimMsg').textContent = message;

  // Короткая карточка на главной
  $('bcStatus').textContent = message || (!data.connected ? 'Не подключён'
    : data.needLogin ? 'Вход устарел' : data.syncing ? 'Обновляю…'
      : data.lastSync ? `Обновлено ${when(data.lastSync)}` : 'Подключён');
  $('bcStatus').className = message || data.needLogin ? 'error' : '';
  const act = $('bcAction');
  act.textContent = '';
  if (!data.connected || data.needLogin) {
    act.append(button(data.connected ? 'Войти снова' : 'Войти', 'primary', () => window.api.login()));
  } else {
    const r = button('Обновить', '', doSync);
    r.disabled = data.syncing;
    act.append(r);
  }

  const box = $('bilimButtons');
  box.textContent = '';
  if (!data.connected || data.needLogin) {
    box.append(button(data.connected ? 'Войти снова' : 'Войти в BilimClass', 'primary', () => window.api.login()));
  } else {
    const r = button('Обновить', 'primary', doSync);
    r.disabled = data.syncing;
    box.append(r, button('Выйти', '', async () => {
      if (!confirm('Выйти из BilimClass на этом компьютере?')) return;
      await window.api.logout();
    }));
  }
}

function renderAi() {
  const box = $('aiView');
  box.textContent = '';
  if (data.aiKeyTail && !editingKey) {
    const st = el('div', 'ai-ok');
    st.append(el('span', 'dot'), ` Подключено: ${data.aiName}`);
    box.append(st, el('div', 'muted small', `Ключ …${data.aiKeyTail}${data.aiBase ? ` · ${data.aiBase}` : ''}`));
    const models = data.aiModels || [];
    if (models.length) {
      const sel = el('select');
      for (const m of models) {
        const o = el('option', '', m);
        o.value = m;
        sel.append(o);
      }
      if (data.aiModel && !models.includes(data.aiModel)) {
        const o = el('option', '', data.aiModel);
        o.value = data.aiModel;
        sel.prepend(o);
      }
      sel.value = data.aiModel || models[0];
      sel.addEventListener('change', () => window.api.setAiModel(sel.value));
      const lab = el('label', 'small muted', 'Модель:');
      lab.append(sel);
      box.append(lab);
    }
    if (aiMsg) box.append(el('div', 'error small', aiMsg));
    const row = el('div', 'row');
    row.append(button('Изменить ключ', '', () => { editingKey = true; aiMsg = ''; renderAi(); }));
    row.append(button('Обновить список моделей', '', async (e) => {
      e.target.disabled = true;
      const r = await window.api.refreshAiModels();
      aiMsg = r.ok ? '' : r.message || '';
      renderAi();
    }));
    box.append(row);
    return;
  }
  box.append(el('div', 'muted small',
    'Нужен, чтобы ИИ расписывал домашку на русском и английском. Подходят ключи Google Gemini, Claude, Groq, xAI Grok, '
    + 'OpenRouter, OpenAI, DeepSeek, Mistral, AnyModel. Сервис определяется сам. Ключ хранится только на этом компьютере (в зашифрованном виде).'));
  const link = el('a', 'small', 'Бесплатный ключ: aistudio.google.com → Get API key');
  link.href = '#';
  link.addEventListener('click', (e) => { e.preventDefault(); window.api.openExternal('https://aistudio.google.com/apikey'); });
  box.append(link);
  const input = el('input');
  input.type = 'password';
  input.placeholder = 'Вставьте ключ (AIza…, sk-…, gsk_…, xai-…)';
  input.style.marginTop = '8px';
  const base = el('input');
  base.type = 'text';
  base.placeholder = 'https://…/v1 (необязательно)';
  base.style.marginTop = '2px';
  const baseLab = el('div', 'small muted', 'Адрес сервиса — только если вашего сервиса нет в списке выше. Обычно оставьте пустым:');
  baseLab.style.marginTop = '8px';
  base.value = data.aiBase || '';
  const msg = el('div', 'small', aiMsg);
  msg.className = aiMsg ? 'error small' : 'small muted';
  const row = el('div', 'row');
  const save = button('Проверить и сохранить', 'primary', async () => {
    if (!input.value.trim()) { input.focus(); return; }
    save.disabled = true;
    msg.className = 'small muted';
    msg.textContent = 'Проверяю ключ…';
    const r = await window.api.setAiKey(input.value.trim(), base.value.trim());
    save.disabled = false;
    if (r.ok) {
      editingKey = false;
      aiMsg = '';
      renderAi();
    } else {
      aiMsg = r.message;
      msg.className = 'error small';
      msg.textContent = r.message;
    }
  });
  row.append(save);
  if (data.aiKeyTail) {
    row.append(button('Удалить ключ', 'danger', async () => { editingKey = false; aiMsg = ''; await window.api.setAiKey(''); }));
    row.append(button('Отмена', '', () => { editingKey = false; aiMsg = ''; renderAi(); }));
  }
  box.append(input, baseLab, base, msg, row);
}

/** Урок без домашки, которую можно задать (классный час не считается). */
function missingHomework(day, l) {
  return day.fromBilim && !l.title.startsWith('Кл. час') && !L.homeworkSummary(l);
}

function renderHeader(today, source) {
  const h = today.getHours();
  $('greet').textContent = h < 5 ? 'Доброй ночи' : h < 12 ? 'Доброе утро' : h < 18 ? 'Добрый день' : 'Добрый вечер';
  const day = source(today);
  const n = day.lessons.length;
  const missing = day.lessons.filter((l) => missingHomework(day, l)).length;
  let sub = `${L.DAY_NAMES[L.dow(today)]}, ${L.dateText(today)}`;
  if (n) sub += ` · ${n} ${plural(n, 'урок', 'урока', 'уроков')}`;
  if (missing) sub += `, без ДЗ: ${missing}`;
  $('todaySub').textContent = sub;
}

function tile(cls, icon, value, label, onClick) {
  const b = el('button', 'tile ' + cls);
  const text = el('span');
  text.append(el('b', '', value), el('small', '', label));
  b.append(svg(icon), text);
  b.title = `${value} — ${label}`;
  b.addEventListener('click', onClick);
  return b;
}

function renderTiles(today, source) {
  const box = $('tiles');
  box.textContent = '';
  const day = source(today);
  const n = day.lessons.length;
  box.append(tile('blue', 'calendar', n ? `${n} ${plural(n, 'урок', 'урока', 'уроков')}` : 'Нет уроков',
    'сегодня', () => { weekOffset = 0; renderWeek(); scrollToToday(); }));

  const missing = day.lessons.filter((l) => missingHomework(day, l));
  box.append(tile('red', 'pen', missing.length ? `${missing.length} без ДЗ` : 'ДЗ задано',
    missing.length ? 'задать домашку' : 'на сегодня всё',
    () => (missing.length ? window.api.openHomework(L.isoDate(today), missing[0].start) : scrollToToday())));

  const grades = tile('orange', 'teacher',
    riskTotal ? `${riskTotal} ${plural(riskTotal, 'ученик', 'ученика', 'учеников')}` : 'Успеваемость',
    riskTotal ? 'в группе риска' : riskTotal === 0 ? 'группа риска пуста' : 'журнал и оценки',
    () => window.api.openGrades());
  grades.disabled = !data.connected;
  box.append(grades);

  const an = tile('green', 'folder', 'СОР/СОЧ', 'справка в Excel', () => window.api.openAnalysis());
  an.disabled = !data.connected;
  box.append(an);
}

function renderAttendance() {
  const box = $('attendance');
  box.textContent = '';
  const list = data.attendanceAlerts || [];
  if (!data.attendanceReady) {
    box.append(el('div', 'muted small', 'Журнал посещаемости пока пуст: загрузите списки учеников из Word.'));
  } else if (!list.length) {
    box.append(el('div', 'ok', 'Долгих болезней и пропусков без уважительной причины нет ✓'));
  } else {
    box.append(el('div', 'warn', 'Обратите внимание'));
    const ul = el('ul');
    for (const a of list.slice(0, 5)) ul.append(el('li', '', a.text));
    if (list.length > 5) ul.append(el('li', 'muted', `и ещё ${list.length - 5}…`));
    box.append(ul);
  }
  box.append(button('Открыть журнал', 'primary', () => window.api.openAttendance()));
}

function stat(color, icon, value, name, sub) {
  const b = el('button', 'stat');
  const ic = el('div', 'ic');
  ic.style.background = `var(--${color})`;
  ic.append(svg(icon));
  b.append(ic, el('b', '', value), el('span', '', name), el('small', '', sub));
  b.addEventListener('click', () => showView('settings'));
  return b;
}

function renderStats() {
  const box = $('stats');
  box.textContent = '';
  const u = data.update || { status: 'idle' };
  const upd = u.status === 'ready' ? `готово обновление ${u.version}`
    : u.status === 'downloading' ? `загружается ${u.version}…`
      : u.status === 'checking' ? 'проверяю обновления…' : 'нажмите, чтобы проверить';
  box.append(
    stat('orange', 'bell', data.remindersOn ? 'Включены' : 'Выключены', 'Напоминания', 'за 5 минут до урока'),
    stat('blue', 'teacher', data.aiKeyTail ? data.aiName : 'Нет ключа', 'Нейросеть',
      data.aiKeyTail ? `ключ …${data.aiKeyTail} · ДЗ на RU и EN` : 'нужна, чтобы расписывать ДЗ'),
    stat('green', 'home', data.miniOnTop ? 'Поверх окон' : 'Обычное', 'Мини-окно',
      data.autostart ? 'запуск вместе с Windows' : 'без автозапуска'),
    stat('primary', 'setting', data.version || '—', 'Версия', upd),
  );
}

/** Открыть раздел «Неделя» на сегодняшнем дне. */
function scrollToToday() {
  showView('week');
  const t = document.querySelector('.day.today');
  (t || $('weekCard')).scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderWeek() {
  const today = new Date();
  const monday = L.addDays(L.mondayOf(today), weekOffset * 7);
  $('range').textContent = `${L.dateText(monday)} — ${L.dateText(L.addDays(monday, 6))}`;
  $('prevWeek').disabled = weekOffset <= -1;
  $('nextWeek').disabled = weekOffset >= 1;

  const source = L.makeSource(data.days);
  const now = L.computeState(today, source);
  renderHeader(today, source);
  renderTiles(today, source);

  const box = $('daysBox');
  box.textContent = '';
  let shown = 0;
  for (let i = 0; i < 7; i++) {
    const d = L.addDays(monday, i);
    const day = source(d);
    if (i >= 5 && !day.lessons.length) continue;
    shown++;
    const isToday = L.isoDate(d) === L.isoDate(today);
    const card = el('div', 'day' + (isToday ? ' today' : ''));
    card.append(el('h3', '', `${L.DAY_NAMES[i]}, ${L.dateText(d)}${isToday ? ' · сегодня' : ''}`));
    if (!day.lessons.length) {
      card.append(el('div', 'empty', day.holiday ? 'Праздничный день — уроков нет' : 'Уроков нет'));
    }
    day.lessons.forEach((l, idx) => {
      const kind = isToday && now.listDate === L.isoDate(d) ? now.rows[idx] : 'normal';
      const row = el('div', 'lesson ' + kind);
      row.title = 'Задать или изменить домашнее задание';
      row.append(el('div', 'time', L.range(l)));
      const body = el('div', 'body');
      body.append(el('div', 'name', l.title));
      const many = l.details.length > 1;
      for (const det of l.details) {
        const lines = [];
        if (det.theme) lines.push((many ? `${det.cls}: ` : '') + det.theme);
        if (det.homework) lines.push((many ? `${det.cls} — ` : '') + `ДЗ: ${det.homework}`);
        if (det.files && det.files.length) lines.push(`📎 файлов: ${det.files.length}`);
        if (lines.length) body.append(el('div', 'det', lines.join('\n')));
      }
      if (missingHomework(day, l)) body.append(el('div', 'nohw', 'ДЗ не задано — нажмите, чтобы задать'));
      row.append(body);
      if (kind === 'current' && now.countdownTo) {
        const min = Math.max(1, Math.ceil((now.countdownTo - today) / 60000));
        row.append(el('span', 'chip now', `идёт · ещё ${min} мин`));
      } else if (kind === 'next') {
        row.append(el('span', 'chip next', 'далее'));
      }
      row.addEventListener('click', () => window.api.openHomework(L.isoDate(d), l.start));
      card.append(row);
    });
    box.append(card);
  }
  if (!shown) box.append(el('div', 'empty', 'На этой неделе уроков нет.'));
  $('templateNote').textContent = data.connected ? ''
    : 'Пока BilimClass не подключён, показано расписание, записанное в программе (без тем и домашних заданий).';
}

async function loadRisk() {
  if (!data || !data.connected) { riskTotal = null; return; }
  try {
    const g = await window.api.gradesData();
    riskTotal = g.classes.length ? g.classes.reduce((s, c) => s + (c.summary.riskCount || 0), 0) : null;
  } catch (e) {
    riskTotal = null;
  }
  if (data) renderWeek();
}

function renderUpdate() {
  const u = data.update || { status: 'idle' };
  const banner = $('updateBanner');
  banner.textContent = '';
  banner.hidden = true;
  if (u.status === 'ready') {
    banner.hidden = false;
    banner.append(el('span', '', `Готово обновление программы (версия ${u.version}).`));
    banner.append(button('Перезапустить и обновить', '', () => window.api.installUpdate()));
  } else if (u.status === 'downloading') {
    banner.hidden = false;
    banner.append(el('span', '', `Загружается обновление ${u.version}… ${u.percent || 0}%`));
  }
  $('version').textContent = data.version ? `Версия ${data.version}` : '';
  $('checkUpdate').disabled = u.status === 'checking' || u.status === 'downloading';
  $('checkUpdate').textContent = u.status === 'checking' ? 'Проверяю…' : 'Проверить обновления';
}

function render() {
  if (!data) return;
  renderUpdate();
  renderBilim();
  // не перерисовывать поле ключа, пока в нём печатают
  const a = document.activeElement;
  if (!(a && ['INPUT', 'SELECT', 'BUTTON'].includes(a.tagName) && $('aiView').contains(a))) renderAi();
  $('onTop').checked = data.miniOnTop;
  $('reminders').checked = data.remindersOn;
  $('gradeAlerts').checked = data.gradeAlertsOn;
  $('openGrades').disabled = !data.connected;
  $('openAnalysis').disabled = !data.connected;
  $('autostart').checked = data.autostart;
  renderStats();
  renderAttendance();
  renderWeek();
}

$('navHome').addEventListener('click', () => showView('home'));
$('navSettings').addEventListener('click', () => showView('settings'));
$('navWeek').addEventListener('click', () => showView('week'));
$('prevWeek').addEventListener('click', () => { weekOffset = Math.max(-1, weekOffset - 1); renderWeek(); });
$('nextWeek').addEventListener('click', () => { weekOffset = Math.min(1, weekOffset + 1); renderWeek(); });
$('showMini2').addEventListener('click', () => window.api.showMini());
$('credits').textContent = 'Оформление по шаблону «Educational Platform – Student Dashboard» (Maharram Hasanli, CC BY 4.0), '
  + 'иконки Vuesax, шрифт Onest (SIL OFL 1.1).';
$('onTop').addEventListener('change', (e) => window.api.setMiniOnTop(e.target.checked));
$('reminders').addEventListener('change', (e) => window.api.setReminders(e.target.checked));
$('gradeAlerts').addEventListener('change', (e) => window.api.setGradeAlerts(e.target.checked));
$('openGrades').addEventListener('click', () => window.api.openGrades());
$('openAnalysis').addEventListener('click', () => window.api.openAnalysis());
$('openAttestation').addEventListener('click', () => window.api.openAttestation());
$('openAttendance').addEventListener('click', () => window.api.openAttendance());
$('testReminder').addEventListener('click', async () => {
  if (!(await window.api.testReminder())) alert('Ближайших уроков не найдено.');
});
$('autostart').addEventListener('change', (e) => window.api.setAutostart(e.target.checked));
$('showMini').addEventListener('click', () => window.api.showMini());
$('checkUpdate').addEventListener('click', () => window.api.checkUpdate());

window.api.onData((d) => {
  const wasConnected = data && data.connected;
  data = d;
  render();
  if (d.connected && !wasConnected) loadRisk();
});
window.api.getData().then((d) => { data = d; render(); loadRisk(); });
window.api.onGrades(() => loadRisk());
// Отметки «сейчас идёт» обновляются раз в минуту
setInterval(() => { if (data) renderWeek(); }, 60000);
