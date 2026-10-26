'use strict';
// Автообновление: проверка выпусков на GitHub, загрузка в фоне, установка при перезапуске.
const { app, Notification } = require('electron');

const CHECK_EVERY_MS = 6 * 60 * 60 * 1000;

let state = { status: 'idle', version: '', percent: 0 }; // idle | checking | downloading | ready | error
let onChange = () => {};
let autoUpdater = null;

function set(patch) {
  state = { ...state, ...patch };
  onChange(state);
}

function start(changed) {
  onChange = changed;
  // В режиме разработки и самопроверки обновления не проверяем
  if (!app.isPackaged || process.env.RASPISANIE_SMOKE === '1') return;
  ({ autoUpdater } = require('electron-updater'));
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true; // если не перезапустить сразу — установится при выходе

  autoUpdater.on('checking-for-update', () => set({ status: 'checking' }));
  autoUpdater.on('update-not-available', () => set({ status: 'idle' }));
  autoUpdater.on('update-available', (info) => set({ status: 'downloading', version: info.version, percent: 0 }));
  autoUpdater.on('download-progress', (p) => set({ status: 'downloading', percent: Math.round(p.percent || 0) }));
  autoUpdater.on('update-downloaded', (info) => {
    set({ status: 'ready', version: info.version });
    if (Notification.isSupported()) {
      new Notification({
        title: 'Моё расписание: обновление готово',
        body: `Версия ${info.version}. Перезапустите программу (значок в трее → «Перезапустить и обновить»).`,
      }).show();
    }
  });
  autoUpdater.on('error', () => set({ status: state.status === 'ready' ? 'ready' : 'error' }));

  const check = () => autoUpdater.checkForUpdates().catch(() => set({ status: 'error' }));
  setTimeout(check, 30 * 1000);
  setInterval(check, CHECK_EVERY_MS);
}

function check() {
  if (autoUpdater) return autoUpdater.checkForUpdates().catch(() => set({ status: 'error' }));
  return Promise.resolve();
}

function installNow() {
  if (autoUpdater && state.status === 'ready') autoUpdater.quitAndInstall(true, true);
}

module.exports = { start, check, installNow, getState: () => state };
