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
  setAiKey: (key, base) => ipcRenderer.invoke('set-ai-key', key, base),
  setAiModel: (model) => ipcRenderer.invoke('set-ai-model', model),
  refreshAiModels: () => ipcRenderer.invoke('refresh-ai-models'),
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
  openGrades: () => ipcRenderer.invoke('open-grades'),
  gradesData: () => ipcRenderer.invoke('grades-data'),
  gradesRefresh: () => ipcRenderer.invoke('grades-refresh'),
  setGradeAlerts: (on) => ipcRenderer.invoke('set-grade-alerts', on),
  onGrades: (cb) => ipcRenderer.on('grades-changed', () => cb()),
});
