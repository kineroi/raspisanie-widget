'use strict';
// «Моё расписание» для Windows: мини-окно поверх всех окон, основное окно, окно домашки, трей.
const {
  app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, powerMonitor, screen, session, shell,
} = require('electron');
const path = require('path');
const store = require('./store');
const bilim = require('./bilim');
const gemini = require('./gemini');
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

function snapshot() {
  const key = store.get('aiKey');
  return {
    days: bilim.cachedDays(),
    connected: !!store.get('refresh'),
    needLogin: !!store.get('needLogin', false),
    lastSync: store.get('lastSync', 0),
    syncing,
    aiKeyTail: key ? key.slice(-4) : '',
    autostart: app.getLoginItemSettings().openAtLogin,
    miniOnTop: store.get('miniOnTop', true),
  };
}

function broadcast() {
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
    width: 980,
    height: 820,
    minWidth: 620,
    minHeight: 500,
    title: 'Моё расписание',
    backgroundColor: '#F3E5F5',
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
    backgroundColor: '#FFFFFF',
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
  await session.fromPartition(BILIM_PARTITION).clearStorageData();
  broadcast();
}

// ---------- Трей ----------

function updateTrayMenu() {
  if (!tray) return;
  const miniVisible = !!(mini && mini.isVisible());
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: miniVisible ? 'Скрыть мини-окно' : 'Показать мини-окно', click: () => (miniVisible ? hideMini() : showMini()) },
    { label: 'Открыть расписание', click: showMain },
    { label: syncing ? 'Обновляю…' : 'Обновить из BilimClass', enabled: !syncing && !!store.get('refresh'), click: runSync },
    { type: 'separator' },
    {
      label: 'Мини-окно поверх всех окон', type: 'checkbox', checked: store.get('miniOnTop', true),
      click: (item) => setMiniOnTop(item.checked),
    },
    {
      label: 'Запускать вместе с Windows', type: 'checkbox', checked: app.getLoginItemSettings().openAtLogin,
      click: (item) => setAutostart(item.checked),
    },
    { type: 'separator' },
    { label: 'Выход', click: () => { quitting = true; app.quit(); } },
  ]));
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
ipcMain.handle('sync', () => runSync());
ipcMain.handle('login', () => openLogin());
ipcMain.handle('logout', () => logout());
ipcMain.handle('set-ai-key', (_e, key) => {
  store.set({ aiKey: String(key || '').trim() || null });
  broadcast();
});
ipcMain.handle('set-autostart', (_e, on) => setAutostart(!!on));
ipcMain.handle('set-mini-on-top', (_e, on) => setMiniOnTop(!!on));
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
  const key = store.get('aiKey');
  if (!key) return { text: L.expandRules(note), message: 'Расписано по правилам (без ИИ). Проверьте текст.', ok: true };
  try {
    return { text: await gemini.expand(key, note, classes, theme), message: 'Готово. Проверьте текст перед отправкой.', ok: true };
  } catch (e) {
    return { text: L.expandRules(note), message: gemini.explain(e), ok: false };
  }
});

ipcMain.handle('send-homework', async (_e, req) => {
  const { date, uuids, body, files } = req || {};
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
    runSync();
    return { ok: false, error: bilim.errorText(e), done };
  }
  await runSync();
  return { ok: true, done };
});

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
  powerMonitor.on('resume', () => setTimeout(runSync, 15000));
});

function startSmoke() {
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
