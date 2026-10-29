'use strict';
// «Моё расписание» для Windows: мини-окно поверх всех окон, основное окно, окно домашки, трей.
const {
  app, BrowserWindow, Tray, Menu, dialog, ipcMain, nativeImage, nativeTheme, powerMonitor, screen, session, shell,
} = require('electron');
const path = require('path');
const store = require('./store');
const bilim = require('./bilim');
const ai = require('./ai');
const A = require('../shared/ai');
const updater = require('./updater');
const reminders = require('./reminders');
const journal = require('./journal');
const analysis = require('./analysis');
const attestation = require('./attestation');
const digest = require('./digest');
const L = require('../shared/logic');

const ASSETS = path.join(__dirname, '..', 'assets');
const RENDERER = path.join(__dirname, '..', 'renderer');
const PRELOAD = path.join(__dirname, 'preload.js');
const SYNC_EVERY_MS = 3 * 60 * 60 * 1000;
const MAX_FILE_BYTES = 20 * 1024 * 1024;
const BILIM_PARTITION = 'persist:bilim';

app.setAppUserModelId('kz.nikolaevka.raspisanie');

// Самопроверка (RASPISANIE_SMOKE=1): временный профиль, открыть все окна, собрать ошибки, выйти.
const SMOKE = process.env.RASPISANIE_SMOKE === '1';
if (SMOKE) app.setPath('userData', path.join(require('os').tmpdir(), `raspisanie-smoke-${process.pid}`));
const smokeErrors = [];

let mini = null;
let main = null;
let tray = null;
let loginWin = null;
const homeworkWins = new Map();
let syncing = false;
let quitting = false;

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showMain());
}

// ---------- Данные для окон ----------

function aiConfig() {
  return {
    key: store.get('aiKey'),
    provider: store.get('aiProvider', ''),
    base: store.get('aiBase', ''),
    model: store.get('aiModel', ''),
  };
}

/** Цвет окна до загрузки страницы — под тему Windows, чтобы не мигало. */
function windowBackground() {
  return nativeTheme.shouldUseDarkColors ? '#121212' : '#FFFFFF';
}

function snapshot() {
  const cfg = aiConfig();
  const key = cfg.key;
  const p = A.resolve(cfg);
  return {
    days: bilim.cachedDays(),
    connected: !!store.get('refresh'),
    needLogin: !!store.get('needLogin', false),
    lastSync: store.get('lastSync', 0),
    syncing,
    aiKeyTail: key ? key.slice(-4) : '',
    aiName: p ? p.name : '',
    aiLabel: p ? A.label(p, cfg.model || (p.kind === 'gemini' ? L.GEMINI_MODELS[0] : '')) : '',
    aiModel: cfg.model,
    aiModels: store.get('aiModels', []),
    aiBase: cfg.provider === 'custom' ? cfg.base : '',
    autostart: app.getLoginItemSettings().openAtLogin,
    miniOnTop: store.get('miniOnTop', true),
    version: app.getVersion(),
    remindersOn: store.get('reminders', true),
    gradeAlertsOn: store.get('gradeAlerts', true),
    update: updater.getState(),
  };
}

function broadcast() {
  reminders.reschedule();
  const data = snapshot();
  for (const w of BrowserWindow.getAllWindows()) {
    if (!w.isDestroyed() && w !== loginWin) w.webContents.send('data-changed', data);
  }
  updateTrayMenu();
}

async function runSync() {
  if (syncing) return 'busy';
  syncing = true;
  broadcast();
  let r;
  try {
    r = await bilim.sync();
  } finally {
    syncing = false;
  }
  broadcast();
  return r;
}

// ---------- Окна ----------

function webPrefs() {
  return { preload: PRELOAD, contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false };
}

function createMini() {
  const pos = store.get('miniPos', null);
  const area = screen.getPrimaryDisplay().workArea;
  const width = 320;
  mini = new BrowserWindow({
    width,
    height: 380,
    x: pos ? pos.x : area.x + area.width - width - 16,
    y: pos ? pos.y : area.y + 16,
    frame: false,
    transparent: true,
    resizable: false,
    maximizable: false,
    minimizable: false,
    fullscreenable: false,
    skipTaskbar: true,
    alwaysOnTop: store.get('miniOnTop', true),
    show: false,
    title: 'Моё расписание',
    icon: path.join(ASSETS, 'icon.png'),
    webPreferences: webPrefs(),
  });
  if (store.get('miniOnTop', true)) mini.setAlwaysOnTop(true, 'floating');
  mini.loadFile(path.join(RENDERER, 'mini.html'));
  mini.once('ready-to-show', () => {
    keepOnScreen(mini);
    if (!store.get('miniHidden', false)) mini.showInactive();
  });
  mini.on('moved', () => {
    const [x, y] = mini.getPosition();
    store.set({ miniPos: { x, y } });
  });
  mini.on('close', (e) => {
    if (!quitting) {
      e.preventDefault();
      hideMini();
    }
  });
}

/** Если мини-окно оказалось за пределами экранов (сменился монитор) — вернуть в угол. */
function keepOnScreen(win) {
  const b = win.getBounds();
  const visible = screen.getAllDisplays().some((d) => {
    const a = d.workArea;
    return b.x + 40 > a.x && b.x < a.x + a.width - 40 && b.y + 20 > a.y && b.y < a.y + a.height - 40;
  });
  if (!visible) {
    const a = screen.getPrimaryDisplay().workArea;
    win.setPosition(a.x + a.width - b.width - 16, a.y + 16);
  }
}

function showMini() {
  if (!mini) createMini();
  store.set({ miniHidden: false });
  mini.showInactive();
  updateTrayMenu();
}

function hideMini() {
  store.set({ miniHidden: true });
  if (mini) mini.hide();
  updateTrayMenu();
}

function showMain() {
  if (main && !main.isDestroyed()) {
    if (main.isMinimized()) main.restore();
    main.show();
    main.focus();
    return;
  }
  main = new BrowserWindow({
    width: 1240,
    height: 820,
    minWidth: 620,
    minHeight: 500,
    title: 'Моё расписание',
    backgroundColor: windowBackground(),
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: webPrefs(),
  });
  main.removeMenu();
  main.loadFile(path.join(RENDERER, 'main.html'));
  main.on('closed', () => {
    main = null;
  });
  // Обновить, если давно не обновлялось
  if (Date.now() - store.get('lastSync', 0) > 10 * 60 * 1000) runSync();
}

function openHomework(date, start) {
  const key = `${date} ${start}`;
  const existing = homeworkWins.get(key);
  if (existing && !existing.isDestroyed()) {
    existing.show();
    existing.focus();
    return;
  }
  const w = new BrowserWindow({
    width: 640,
    height: 780,
    minWidth: 480,
    minHeight: 500,
    title: 'Домашнее задание',
    backgroundColor: windowBackground(),
    icon: path.join(ASSETS, 'icon.png'),
    alwaysOnTop: true, // чтобы мини-окно его не закрывало
    autoHideMenuBar: true,
    webPreferences: webPrefs(),
  });
  w.removeMenu();
  w.loadFile(path.join(RENDERER, 'homework.html'), { query: { date, start } });
  homeworkWins.set(key, w);
  w.on('closed', () => homeworkWins.delete(key));
}

let gradesWin = null;
let analysisWin = null;

let attestationWin = null;

function openAttestation() {
  if (attestationWin && !attestationWin.isDestroyed()) {
    attestationWin.show();
    attestationWin.focus();
    return;
  }
  attestationWin = new BrowserWindow({
    width: 980,
    height: 820,
    minWidth: 620,
    minHeight: 500,
    title: 'Аттестация',
    backgroundColor: windowBackground(),
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: webPrefs(),
  });
  attestationWin.removeMenu();
  attestationWin.loadFile(path.join(RENDERER, 'attestation.html'));
  attestationWin.on('closed', () => {
    attestationWin = null;
  });
}

function openAnalysis() {
  if (analysisWin && !analysisWin.isDestroyed()) {
    analysisWin.show();
    analysisWin.focus();
    return;
  }
  analysisWin = new BrowserWindow({
    width: 1060,
    height: 860,
    minWidth: 680,
    minHeight: 500,
    title: 'Справка СОР/СОЧ',
    backgroundColor: windowBackground(),
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: webPrefs(),
  });
  analysisWin.removeMenu();
  analysisWin.loadFile(path.join(RENDERER, 'analysis.html'));
  analysisWin.on('closed', () => {
    analysisWin = null;
  });
}

function openGrades() {
  if (gradesWin && !gradesWin.isDestroyed()) {
    gradesWin.show();
    gradesWin.focus();
    return;
  }
  gradesWin = new BrowserWindow({
    width: 1040,
    height: 820,
    minWidth: 640,
    minHeight: 480,
    title: 'Успеваемость',
    backgroundColor: windowBackground(),
    icon: path.join(ASSETS, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: webPrefs(),
  });
  gradesWin.removeMenu();
  gradesWin.loadFile(path.join(RENDERER, 'grades.html'));
  gradesWin.on('closed', () => {
    gradesWin = null;
  });
}

/** Вход: настоящая страница BilimClass; пароль вводится на сайте, программа берёт только пропуск. */
function openLogin() {
  if (loginWin && !loginWin.isDestroyed()) {
    loginWin.focus();
    return Promise.resolve(false);
  }
  return new Promise((resolve) => {
    let done = false;
    loginWin = new BrowserWindow({
      width: 1000,
      height: 800,
      title: 'Вход в BilimClass — после входа окно закроется само',
      icon: path.join(ASSETS, 'icon.png'),
      autoHideMenuBar: true,
      webPreferences: { partition: BILIM_PARTITION, contextIsolation: true, nodeIntegration: false, sandbox: true },
    });
    loginWin.removeMenu();
    loginWin.on('page-title-updated', (e) => e.preventDefault());
    // ссылки «открыть в новом окне» — в обычном браузере
    loginWin.webContents.setWindowOpenHandler(({ url }) => {
      shell.openExternal(url);
      return { action: 'deny' };
    });
    loginWin.loadURL('https://www.bilimclass.kz/login');

    const ses = session.fromPartition(BILIM_PARTITION);
    const timer = setInterval(async () => {
      if (done || !loginWin || loginWin.isDestroyed()) return;
      try {
        const token = await loginWin.webContents.executeJavaScript(
          "(function(){try{return localStorage.getItem('token')||''}catch(e){return ''}})()", true);
        const cookies = await ses.cookies.get({ url: 'https://www.bilimclass.kz' });
        const val = (n) => {
          const c = cookies.find((x) => x.name === n);
          return c ? decodeURIComponent(c.value) : '';
        };
        const refresh = val('r_t');
        const school = val('schoolId');
        if (token && refresh && school) {
          done = true;
          clearInterval(timer);
          store.set({ token, refresh, school, needLogin: false });
          loginWin.close();
          resolve(true);
          runSync();
        }
      } catch {
        // страница ещё грузится
      }
    }, 1500);

    loginWin.on('closed', () => {
      clearInterval(timer);
      loginWin = null;
      if (!done) resolve(false);
      broadcast();
    });
  });
}

async function logout() {
  store.clearBilim();
  if (gradesWin && !gradesWin.isDestroyed()) gradesWin.close();
  await session.fromPartition(BILIM_PARTITION).clearStorageData();
  broadcast();
}

// ---------- Трей ----------

function updateTrayMenu() {
  if (!tray) return;
  const miniVisible = !!(mini && mini.isVisible());
  const upd = updater.getState();
  tray.setContextMenu(Menu.buildFromTemplate([
    ...(upd.status === 'ready' ? [
      { label: `Перезапустить и обновить (версия ${upd.version})`, click: installUpdate },
      { type: 'separator' },
    ] : []),
    { label: miniVisible ? 'Скрыть мини-окно' : 'Показать мини-окно', click: () => (miniVisible ? hideMini() : showMini()) },
    { label: 'Открыть расписание', click: showMain },
    { label: 'Успеваемость', click: openGrades, enabled: !!store.get('refresh') },
    { label: syncing ? 'Обновляю…' : 'Обновить из BilimClass', enabled: !syncing && !!store.get('refresh'), click: runSync },
    { type: 'separator' },
    {
      label: 'Мини-окно поверх всех окон', type: 'checkbox', checked: store.get('miniOnTop', true),
      click: (item) => setMiniOnTop(item.checked),
    },
    {
      label: 'Напоминать за 5 минут до урока', type: 'checkbox', checked: store.get('reminders', true),
      click: (item) => setReminders(item.checked),
    },
    {
      label: 'Запускать вместе с Windows', type: 'checkbox', checked: app.getLoginItemSettings().openAtLogin,
      click: (item) => setAutostart(item.checked),
    },
    { type: 'separator' },
    { label: 'Выход', click: () => { quitting = true; app.quit(); } },
  ]));
}

function installUpdate() {
  quitting = true;
  updater.installNow();
}

function setReminders(on) {
  store.set({ reminders: on });
  broadcast();
}

function setMiniOnTop(on) {
  store.set({ miniOnTop: on });
  if (mini) mini.setAlwaysOnTop(on, 'floating');
  broadcast();
}

function setAutostart(on) {
  app.setLoginItemSettings({ openAtLogin: on, args: ['--autostart'] });
  broadcast();
}

// ---------- Команды из окон ----------

ipcMain.handle('get-data', () => snapshot());
ipcMain.handle('install-update', () => installUpdate());
ipcMain.handle('check-update', () => updater.check());
ipcMain.handle('sync', () => runSync());
ipcMain.handle('login', () => openLogin());
ipcMain.handle('logout', () => logout());
// Ключ проверяется сразу: какой сервис его принял, какие модели доступны
ipcMain.handle('set-ai-key', async (_e, key, base) => {
  const k = String(key || '').trim();
  if (!k) {
    store.set({ aiKey: null, aiProvider: null, aiBase: null, aiModel: null, aiModels: null });
    broadcast();
    return { ok: true };
  }
  if (String(base || '').trim() && !A.isValidBase(base)) return { ok: false, message: 'Во втором поле должен быть адрес сервиса (https://…). Для Gemini и других сервисов из списка оставьте его пустым.' };
  const b = A.normalizeBase(base);
  try {
    const r = await ai.detect(k, b);
    store.set({ aiKey: k, aiProvider: r.provider, aiBase: r.provider === 'custom' ? r.base : null, aiModel: r.model || null, aiModels: r.models });
    broadcast();
    return { ok: true, name: r.name, model: r.model };
  } catch (e) {
    return { ok: false, message: ai.explainDetect(e) };
  }
});
ipcMain.handle('set-ai-model', (_e, model) => {
  store.set({ aiModel: String(model || '') || null });
  broadcast();
});
ipcMain.handle('refresh-ai-models', async () => {
  const cfg = aiConfig();
  const p = A.resolve(cfg);
  if (!p) return { ok: false };
  try {
    const models = await ai.listModels(p, cfg.key);
    store.set({ aiModels: models, aiProvider: p.id });
    broadcast();
    return { ok: true };
  } catch (e) {
    return { ok: false, message: ai.explainDetect(e) };
  }
});
ipcMain.handle('set-autostart', (_e, on) => setAutostart(!!on));
ipcMain.handle('set-mini-on-top', (_e, on) => setMiniOnTop(!!on));
ipcMain.handle('set-reminders', (_e, on) => setReminders(!!on));
ipcMain.handle('set-grade-alerts', (_e, on) => {
  store.set({ gradeAlerts: !!on });
  broadcast();
});
ipcMain.handle('open-grades', () => openGrades());
ipcMain.handle('open-analysis', () => openAnalysis());
ipcMain.handle('open-attestation', () => openAttestation());
ipcMain.handle('att-settings', () => attestation.settings());
ipcMain.handle('att-choose-root', async (e) => {
  const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), {
    title: 'Папка «Аттестация» (лучше на Google Диске)',
    defaultPath: attestation.root() || undefined,
    properties: ['openDirectory', 'createDirectory'],
  });
  return r.canceled ? attestation.settings() : attestation.setRoot(r.filePaths[0]);
});
ipcMain.handle('att-reset-root', () => attestation.setRoot(''));
ipcMain.handle('att-pick-files', async (e) => {
  const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), {
    title: 'Грамоты, сертификаты, дипломы',
    properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Документы', extensions: ['pdf', 'jpg', 'jpeg', 'png', 'webp', 'docx'] }],
  });
  return r.canceled ? [] : r.filePaths;
});
ipcMain.handle('att-classify', (_e, file) => attestation.classify(String(file)));
ipcMain.handle('att-save', (_e, file, meta) => attestation.save(String(file), meta || {}));
ipcMain.handle('att-show', (_e, file) => {
  const f = String(file || '');
  if (f) shell.showItemInFolder(f);
});
ipcMain.handle('att-open-root', () => {
  const r = attestation.root();
  if (!r) return false;
  require('fs').mkdirSync(r, { recursive: true });
  shell.openPath(r);
  return true;
});
ipcMain.handle('an-load', (_e, quarter) => analysis.load(Number(quarter) || 1));
ipcMain.handle('an-settings', () => analysis.settings());
ipcMain.handle('an-save-settings', (_e, s) => analysis.saveSettings(s || {}));
ipcMain.handle('an-texts', (_e, cls, quarter) => analysis.getTexts(String(cls), Number(quarter)));
ipcMain.handle('an-set-texts', (_e, cls, quarter, texts) => analysis.setTexts(String(cls), Number(quarter), texts || {}));
ipcMain.handle('an-pick-files', async (e) => {
  const r = await dialog.showOpenDialog(BrowserWindow.fromWebContents(e.sender), {
    title: 'Работа СОР/СОЧ/ПО (задания, цели, критерии)',
    properties: ['openFile', 'multiSelections'],
    filters: [{ name: 'Работы', extensions: ['docx', 'pdf', 'jpg', 'jpeg', 'png', 'webp', 'doc'] }],
  });
  return r.canceled ? [] : r.filePaths;
});
ipcMain.handle('an-prepare', (_e, cls, quarter, label, paths) =>
  analysis.prepare(String(cls), Number(quarter), String(label), (paths || []).map(String)));
ipcMain.handle('an-save', (_e, cls, quarter, texts) => analysis.save(String(cls), Number(quarter), texts || {}));
ipcMain.handle('an-open', (_e, file, folder) => {
  const f = String(file || '');
  if (!f) return;
  if (folder) shell.showItemInFolder(f);
  else shell.openPath(f);
});
ipcMain.handle('grades-data', () => journal.snapshot());
ipcMain.handle('grades-refresh', async () => {
  const r = await journal.refresh();
  for (const w of BrowserWindow.getAllWindows()) if (!w.isDestroyed() && w !== loginWin) w.webContents.send('grades-changed');
  return r;
});
ipcMain.handle('test-reminder', () => {
  const days = bilim.cachedDays();
  const next = L.nextReminder(new Date(), L.makeSource(days));
  if (next) reminders.show(next.lesson, next.date);
  return !!next;
});
ipcMain.handle('open-main', () => showMain());
ipcMain.handle('open-homework', (_e, date, start) => openHomework(String(date), String(start)));
ipcMain.handle('hide-mini', () => hideMini());
ipcMain.handle('show-mini', () => showMini());
ipcMain.handle('resize-mini', (_e, height) => {
  if (!mini) return;
  const h = Math.max(140, Math.min(700, Math.round(Number(height) || 380)));
  const [w] = mini.getSize();
  // окно без изменения размера мышью; программно — разрешаем на мгновение
  mini.setResizable(true);
  mini.setSize(w, h);
  mini.setResizable(false);
});
ipcMain.handle('open-external', (_e, url) => {
  if (/^https:\/\//.test(String(url))) shell.openExternal(String(url));
});

ipcMain.handle('expand', async (_e, note, classes, theme) => {
  const cfg = aiConfig();
  if (!cfg.key) return { text: L.expandRules(note), message: 'Расписано по правилам (без ИИ). Проверьте текст.', ok: true };
  const p = A.resolve(cfg);
  try {
    return { text: await ai.expand(cfg, note, classes, theme), message: `Готово (${p.name}). Проверьте текст перед отправкой.`, ok: true };
  } catch (e) {
    return { text: L.expandRules(note), message: ai.explain(e, p), ok: false };
  }
});

ipcMain.handle('send-homework', async (_e, req) => {
  const { date, start, uuids, body, files } = req || {};
  const year = L.eduYear(L.parseIso(String(date)));
  const uploads = [];
  for (const f of files || []) {
    const data = Buffer.from(f.data);
    if (data.length > MAX_FILE_BYTES) return { ok: false, error: `Файл «${f.name}» больше 20 МБ`, done: [] };
    uploads.push({ name: String(f.name || 'file'), mime: String(f.mime || ''), data });
  }
  const done = [];
  try {
    for (const u of uuids || []) {
      await bilim.withToken((token, school) => bilim.saveHomework(token, school, year, u.uuid, String(body), uploads));
      done.push(u.cls);
    }
  } catch (e) {
    rememberHomework(String(body), done, String(date), String(start || ''));
    runSync();
    return { ok: false, error: bilim.errorText(e), done };
  }
  rememberHomework(String(body), done, String(date), String(start || ''));
  await runSync();
  return { ok: true, done };
});

/** Запомнить отправленную домашку — для кнопок «как в прошлый раз» и «частые задания». */
function rememberHomework(text, classes, date, start) {
  let history = store.get('hwHistory', []);
  for (const cls of classes) history = L.addHomeworkHistory(history, { text, cls, date, start });
  if (classes.length) store.set({ hwHistory: history });
}

ipcMain.handle('hw-history', () => store.get('hwHistory', []));

ipcMain.handle('delete-file', async (_e, date, fileId) => {
  const year = L.eduYear(L.parseIso(String(date)));
  try {
    await bilim.withToken((token, school) => bilim.deleteFile(token, school, year, Number(fileId)));
    await runSync();
    return { ok: true };
  } catch (e) {
    return { ok: false, error: bilim.errorText(e) };
  }
});

// ---------- Запуск ----------

app.whenReady().then(() => {
  if (!gotLock) return;
  const icon = nativeImage.createFromPath(path.join(ASSETS, 'tray.png'));
  tray = new Tray(icon);
  tray.setToolTip('Моё расписание');
  tray.on('click', () => (mini && mini.isVisible() ? hideMini() : showMini()));
  tray.on('double-click', showMain);

  if (SMOKE) startSmoke();
  // При первом запуске — включить автозапуск и открыть основное окно
  if (!SMOKE && !store.get('firstRunDone', false)) {
    store.set({ firstRunDone: true });
    setAutostart(true);
  }
  createMini();
  updateTrayMenu();

  const fromAutostart = process.argv.includes('--autostart');
  if (!fromAutostart || !store.get('refresh')) showMain();

  runSync();
  setInterval(runSync, SYNC_EVERY_MS);
  updater.start(() => broadcast());
  digest.start({
    isEnabled: () => !SMOKE && store.get('gradeAlerts', true),
    onClick: () => openGrades(),
  });
  reminders.start({
    getDays: () => bilim.cachedDays(),
    isEnabled: () => !SMOKE && store.get('reminders', true),
    onClick: (date, start) => openHomework(date, start),
  });
  powerMonitor.on('resume', () => {
    reminders.reschedule();
    setTimeout(runSync, 15000);
  });
});

/** Выдуманные журналы для окна справок: 4 класс (ПО) и 5 класс (СОР и СОЧ). */
function smokeAnalysis() {
  const st = (i, po, sor, soch) => ({ name: `Ученик${i} Тест`, po, sor, soch });
  analysis.useSample([
    { cls: '4А', group: '4 А РУС', subject: 'Иностранный язык', teacher: 'Имя Отчество Фамилия', sor: [], soch: [],
      po: [{ uuid: 'p1', date: '2026-09-21', max: 16 }, { uuid: 'p2', date: '2026-10-19', max: 16 }],
      students: [16, 8, 11].map((m, i) => st(i, { p1: { mark: m, max: 16 }, p2: { mark: 12, max: 16 } }, {}, {})) },
    { cls: '5А', group: '5 А РУС', subject: 'Иностранный язык', teacher: 'Имя Отчество Фамилия', po: [],
      sor: [{ uuid: 's1', date: '2026-09-23', max: 13 }], soch: [{ uuid: 'c1', date: '2026-10-21', max: 20 }],
      students: [4, 12].map((m, i) => st(i, {}, { s1: { mark: m, max: 13 } }, { c1: { mark: 15, max: 20 } })) },
  ], app.getPath('userData'));
}

/** Выдуманные данные успеваемости для самопроверки окна. */
function smokeGrades() {
  const G = require('../shared/grades');
  const today = new Date();
  const iso = (d) => L.isoDate(d);
  const end = iso(L.addDays(today, 4));
  const fo = (m) => ({ formattedScore: { mark: m, assessmentMax: 10 }, sor: [], soch: [] });
  const quarter = { data: {
    sorSchedules: [{ uuid: 's1', date: '01.10.26', markMax: 10 }],
    sochSchedules: [{ uuid: 'c1', date: '30.12.26', markMax: 24 }],
    finalMarkDeadline: { startDate: end, endDate: end },
    rows: ['А', 'Б', 'В'].map((x, i) => ({ studentGroupUuid: 'u' + i, fullName: `Ученик ${x}`, finalScore: '', canGetFinalScore: true })),
  } };
  const marks = { data: {
    u0: { marks: { a: fo(3), b: fo(4), s1: { formattedScore: [], sor: { mark: 3, assessmentMax: 10 }, soch: [] } } },
    u1: { marks: { a: fo(7), b: fo(7), s1: { formattedScore: [], sor: { mark: 7, assessmentMax: 10 }, soch: [] } } },
    u2: { marks: { a: fo(9), b: fo(10), s1: { formattedScore: [], sor: { mark: 9, assessmentMax: 10 }, soch: [] } } },
  } };
  const j = G.buildJournal({ cls: '7А', subject: 'Иностранный язык', period: { n: 2, start: iso(L.addDays(today, -50)), end } }, quarter, marks);
  store.writeGrades({ updatedAt: Date.now(), journals: [j], lessons: [{ date: iso(L.addDays(today, -1)), cls: '7А', theme: '', homework: '' }] });
}

function startSmoke() {
  smokeGrades();
  smokeAnalysis();
  app.on('browser-window-created', (_e, w) => {
    w.webContents.on('console-message', (event) => {
      if (event.level === 'error') smokeErrors.push(`console: ${event.message}`);
    });
    w.webContents.on('preload-error', (_ev, p, err) => smokeErrors.push(`preload: ${err && err.message}`));
    w.webContents.on('render-process-gone', (_ev, d) => smokeErrors.push(`renderer gone: ${d.reason}`));
    w.webContents.on('did-fail-load', (_ev, code, desc, url) => smokeErrors.push(`load failed ${code} ${desc} ${url}`));
  });
  process.on('uncaughtException', (err) => smokeErrors.push(`main: ${err.stack || err}`));
  setTimeout(() => openHomework('2026-10-19', '09:00'), 4000);
  setTimeout(() => openGrades(), 4500);
  setTimeout(() => openAnalysis(), 4700);
  setTimeout(async () => {
    try {
      if (!analysisWin) { smokeErrors.push('analysis window not opened'); return; }
      const r = await analysis.save('4А', 1, { items: { 'ПО 1': { achieved: 'Listening (4.1.3.1)', difficult: 'Writing (4.4.3.1)' } }, difficulties: 'ПО 1: ошибки.' });
      if (!r.ok || !require('fs').existsSync(r.file)) smokeErrors.push(`analysis save: ${r.message || 'no file'}`);
      const cards = await analysisWin.webContents.executeJavaScript("document.querySelectorAll('#classes .card').length", true);
      if (cards !== 2) smokeErrors.push(`analysis window: ${cards} cards`);
    } catch (err) {
      smokeErrors.push(`analysis check: ${err.stack || err}`);
    }
  }, 6500);
  // Проверка напоминаний: расчёт и показ уведомления не должны падать
  setTimeout(() => {
    try {
      const next = L.nextReminder(new Date(), L.makeSource(bilim.cachedDays()));
      if (!next) smokeErrors.push('reminder: nothing found in template');
      else reminders.show(next.lesson, next.date);
    } catch (err) {
      smokeErrors.push(`reminder: ${err.stack || err}`);
    }
  }, 5000);
  // Проверка окна домашки: написать заметку, нажать «Расписать», прочитать результат
  setTimeout(async () => {
    const hw = [...homeworkWins.values()][0];
    if (!hw) { smokeErrors.push('homework window not opened'); return; }
    try {
      await hw.webContents.executeJavaScript(
        "document.getElementById('note').value='стр 45 упр 3 письм, сл выуч'; document.getElementById('expand').click(); 1", true);
      await new Promise((r) => setTimeout(r, 1500));
      const result = await hw.webContents.executeJavaScript("document.getElementById('result').value", true);
      if (!result.startsWith('Задание: Страница 45')) smokeErrors.push(`expand result: ${result}`);
    } catch (err) {
      smokeErrors.push(`homework check: ${err.message}`);
    }
  }, 6000);
  setTimeout(async () => {
    const shots = process.env.RASPISANIE_SMOKE_SHOTS;
    if (shots) {
      for (const [i, w] of BrowserWindow.getAllWindows().entries()) {
        try {
          const img = await w.webContents.capturePage();
          require('fs').writeFileSync(path.join(shots, `win${i}.png`), img.toPNG());
        } catch (err) {
          smokeErrors.push(`shot: ${err.message}`);
        }
      }
    }
    const report = [];
    for (const w of BrowserWindow.getAllWindows()) {
      try {
        const text = await w.webContents.executeJavaScript('document.body.innerText', true);
        report.push({ title: w.getTitle(), size: w.getSize(), visible: w.isVisible(), chars: text.length, sample: text.slice(0, 160) });
      } catch (err) {
        smokeErrors.push(`read ${w.getTitle()}: ${err.message}`);
      }
    }
    // eslint-disable-next-line no-console
    const json = JSON.stringify({ errors: smokeErrors, windows: report }, null, 1);
    console.log('SMOKE_REPORT ' + json);
    if (process.env.RASPISANIE_SMOKE_REPORT) require('fs').writeFileSync(process.env.RASPISANIE_SMOKE_REPORT, json, 'utf8');
    quitting = true;
    app.exit(smokeErrors.length ? 1 : 0);
  }, 9000);
}

app.on('window-all-closed', () => {
  // программа продолжает работать в трее — ничего не делаем
});

app.on('before-quit', () => {
  quitting = true;
});
