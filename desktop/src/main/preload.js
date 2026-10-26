'use strict';
// Мост между окнами и программой: окна могут вызывать только эти команды.
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('api', {
  getData: () => ipcRenderer.invoke('get-data'),
  installUpdate: () => ipcRenderer.invoke('install-update'),
  checkUpdate: () => ipcRenderer.invoke('check-update'),
  sync: () => ipcRenderer.invoke('sync'),
  login: () => ipcRenderer.invoke('login'),
  logout: () => ipcRenderer.invoke('logout'),
  setAiKey: (key) => ipcRenderer.invoke('set-ai-key', key),
  setAutostart: (on) => ipcRenderer.invoke('set-autostart', on),
  setMiniOnTop: (on) => ipcRenderer.invoke('set-mini-on-top', on),
  setReminders: (on) => ipcRenderer.invoke('set-reminders', on),
  testReminder: () => ipcRenderer.invoke('test-reminder'),
  openMain: () => ipcRenderer.invoke('open-main'),
  openHomework: (date, start) => ipcRenderer.invoke('open-homework', date, start),
  hideMini: () => ipcRenderer.invoke('hide-mini'),
  showMini: () => ipcRenderer.invoke('show-mini'),
  resizeMini: (h) => ipcRenderer.invoke('resize-mini', h),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  expand: (note, classes, theme) => ipcRenderer.invoke('expand', note, classes, theme),
  sendHomework: (req) => ipcRenderer.invoke('send-homework', req),
  deleteFile: (date, fileId) => ipcRenderer.invoke('delete-file', date, fileId),
  onData: (cb) => ipcRenderer.on('data-changed', (_e, data) => cb(data)),
});
