'use strict';
// Тема окна: как в Windows или выбранная вручную в главном окне (localStorage 'theme').
// Подключается в <head>, чтобы окно сразу открывалось в нужных цветах.
(function () {
  function apply() {
    let t = null;
    try { t = localStorage.getItem('theme'); } catch (e) { /* хранилище недоступно */ }
    if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t;
    else delete document.documentElement.dataset.theme;
  }
  apply();
  // переключили тему в главном окне — перекрашиваемся сразу
  window.addEventListener('storage', (e) => { if (e.key === 'theme') apply(); });
}());
