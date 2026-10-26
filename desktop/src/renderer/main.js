'use strict';
/* global Logic */
const L = Logic;
const $ = (id) => document.getElementById(id);

let data = null;
let weekOffset = 0;
let message = '';
let editingKey = false;

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

function renderGemini() {
  const box = $('geminiView');
  box.textContent = '';
  if (data.aiKeyTail && !editingKey) {
    box.append(el('div', 'muted', `Подключён (ключ …${data.aiKeyTail}).`));
    const row = el('div', 'row');
    row.append(button('Изменить ключ', '', () => { editingKey = true; renderGemini(); }));
    box.append(row);
    return;
  }
  box.append(el('div', 'muted small',
    'Нужен, чтобы ИИ расписывал домашку на русском и английском. Ключ хранится только на этом компьютере (в зашифрованном виде).'));
  const link = el('a', 'small', 'Получить ключ: aistudio.google.com → Get API key');
  link.href = '#';
  link.addEventListener('click', (e) => { e.preventDefault(); window.api.openExternal('https://aistudio.google.com/apikey'); });
  box.append(link);
  const input = el('input');
  input.type = 'password';
  input.placeholder = 'Вставьте ключ (AIza…)';
  input.style.marginTop = '8px';
  const row = el('div', 'row');
  row.append(button('Сохранить ключ', 'primary', async () => {
    if (!input.value.trim()) { input.focus(); return; }
    editingKey = false;
    await window.api.setAiKey(input.value.trim());
  }));
  if (data.aiKeyTail) {
    row.append(button('Удалить ключ', 'danger', async () => { editingKey = false; await window.api.setAiKey(''); }));
    row.append(button('Отмена', '', () => { editingKey = false; renderGemini(); }));
  }
  box.append(input, row);
}

function renderWeek() {
  const weeks = $('weeks');
  weeks.textContent = '';
  ['Прошлая неделя', 'Эта неделя', 'Следующая неделя'].forEach((label, i) => {
    const off = i - 1;
    weeks.append(button(label, off === weekOffset ? 'sel' : '', () => { weekOffset = off; renderWeek(); }));
  });

  const today = new Date();
  const monday = L.addDays(L.mondayOf(today), weekOffset * 7);
  $('range').textContent = `${L.dateText(monday)} — ${L.dateText(L.addDays(monday, 6))}`;

  const source = L.makeSource(data.days);
  const now = L.computeState(today, source);
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
      const block = el('div', 'lesson ' + kind);
      const mark = kind === 'current' ? '▶ ' : kind === 'past' ? '✓ ' : kind === 'next' ? '→ ' : '';
      const head = el('div', 'head', `${mark}${L.range(l)}   ${l.title}`);
      head.append(el('span', 'edit', '✏️'));
      block.append(head);
      const many = l.details.length > 1;
      for (const det of l.details) {
        const lines = [];
        if (det.theme) lines.push((many ? `${det.cls}: ` : '') + det.theme);
        if (det.homework) lines.push((many ? '   ' : '') + `ДЗ: ${det.homework}`);
        if (det.files && det.files.length) lines.push((many ? '   ' : '') + `📎 файлов: ${det.files.length}`);
        if (lines.length) block.append(el('div', 'det', lines.join('\n')));
      }
      if (day.fromBilim && !l.title.startsWith('Кл. час') && !L.homeworkSummary(l)) {
        block.append(el('div', 'nohw', 'ДЗ не указано — нажмите, чтобы задать'));
      }
      block.addEventListener('click', () => window.api.openHomework(L.isoDate(d), l.start));
      card.append(block);
    });
    box.append(card);
  }
  if (!shown) box.append(el('div', 'empty', 'На этой неделе уроков нет.'));
  $('templateNote').textContent = data.connected ? ''
    : 'Пока BilimClass не подключён, показано расписание, записанное в программе (без тем и домашних заданий).';
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
  if (!(a && a.tagName === 'INPUT' && $('geminiView').contains(a))) renderGemini();
  $('onTop').checked = data.miniOnTop;
  $('reminders').checked = data.remindersOn;
  $('autostart').checked = data.autostart;
  renderWeek();
}

$('onTop').addEventListener('change', (e) => window.api.setMiniOnTop(e.target.checked));
$('reminders').addEventListener('change', (e) => window.api.setReminders(e.target.checked));
$('testReminder').addEventListener('click', async () => {
  if (!(await window.api.testReminder())) alert('Ближайших уроков не найдено.');
});
$('autostart').addEventListener('change', (e) => window.api.setAutostart(e.target.checked));
$('showMini').addEventListener('click', () => window.api.showMini());
$('checkUpdate').addEventListener('click', () => window.api.checkUpdate());

window.api.onData((d) => { data = d; render(); });
window.api.getData().then((d) => { data = d; render(); });
// Отметки «сейчас идёт» обновляются раз в минуту
setInterval(() => { if (data) renderWeek(); }, 60000);
