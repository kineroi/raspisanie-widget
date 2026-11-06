'use strict';
const $ = (id) => document.getElementById(id);

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

const dm = (iso) => `${iso.slice(8)}.${iso.slice(5, 7)}`;

function when(b) {
  if (b.daysLeft === 0) return 'сегодня';
  if (b.daysLeft === 1) return `завтра, ${dm(b.next)}`;
  return `${dm(b.next)}, через ${b.daysLeft} дн.`;
}

async function render() {
  const box = $('list');
  box.textContent = '';
  const list = await window.api.birthdaysList();
  if (!list.length) box.append(el('div', 'muted small', 'Список пока пуст.'));
  for (const b of list) {
    const row = el('div', `person${b.daysLeft === 0 ? ' today' : ''}`);
    row.append(el('div', 'name', `${b.name} · исполнится ${b.age}`));
    row.append(el('div', 'when', when(b)));
    const del = el('button', '', '✕');
    del.title = 'Убрать из списка';
    del.addEventListener('click', async () => {
      if (!confirm(`Убрать ${b.name} из списка?`)) return;
      await window.api.birthdaysRemove(b.id);
      render();
    });
    row.append(del);
    box.append(row);
  }
}

$('add').addEventListener('click', async () => {
  const r = await window.api.birthdaysImport($('text').value);
  const parts = [`Добавлено: ${r.added}.`];
  if (r.known) parts.push(`Уже были в списке: ${r.known}.`);
  if (r.bad.length) parts.push(`Не распознаны строки (нужны ФИО и дата ДД.ММ.ГГГГ):\n${r.bad.join('\n')}`);
  $('message').textContent = parts.join(' ');
  $('message').style.color = r.bad.length ? 'var(--orange)' : 'var(--green)';
  if (!r.bad.length) $('text').value = '';
  render();
});

window.api.onData(render);
render();
