'use strict';
// Справка СОР/СОЧ (или ПО) в Excel — в том же виде, что справки школы: шапка, таблица уровней,
// достигнутые цели и затруднения, ученики по уровням, пункты 2–4, дата и подпись.
const ExcelJS = require('exceljs');
const A = require('../shared/analysis');

const WIDTHS = [35.3, 10.4, 23.4, 10.4, 11.6, 11.6, 15.1, 19.9];
// примерно сколько символов помещается в строку объединённых ячеек
const CHARS = { full: 118, be: 44, fh: 38, bc: 27, df: 30, gh: 27 };
const THIN = { style: 'thin' };
const BOX = { top: THIN, left: THIN, bottom: THIN, right: THIN };
const FONT = { name: 'Calibri', size: 11 };

function lines(text, width) {
  return String(text || '').split('\n').reduce((n, part) => n + Math.max(1, Math.ceil(part.length / width)), 0);
}

/**
 * data: { analysis (A.build), quarter, school, teacher, subject, date, texts: {label: {achieved, difficult}},
 *         difficulties, reasons, plan }
 */
async function write(file, data) {
  const a = data.analysis;
  const wb = new ExcelJS.Workbook();
  const ws = wb.addWorksheet('Анализ', {
    pageSetup: { paperSize: 9, orientation: 'portrait', fitToPage: true, fitToWidth: 1, fitToHeight: 0,
      margins: { left: 0.4, right: 0.4, top: 0.5, bottom: 0.5, header: 0.3, footer: 0.3 } },
  });
  ws.columns = WIDTHS.map((width) => ({ width }));
  let r = 0;

  const cell = (addr, value, opt = {}) => {
    const c = ws.getCell(addr);
    c.value = value;
    c.font = { ...FONT, bold: !!opt.bold };
    c.alignment = { wrapText: true, vertical: opt.v || 'top', horizontal: opt.h || 'left' };
    if (opt.box) c.border = BOX;
    return c;
  };
  const merge = (range) => ws.mergeCells(range);
  const height = (row, n) => { ws.getRow(row).height = n <= 1 ? 18 : n * 17.5 + 8; };
  // рамка у всех ячеек объединения (иначе Excel рисует её только у первой)
  const boxRange = (row, from, to) => {
    for (let c = from; c <= to; c++) ws.getCell(row, c).border = BOX;
  };
  const line = (text, opt = {}) => {
    r += 1;
    merge(`A${r}:H${r}`);
    cell(`A${r}`, text, opt);
    if (opt.box) boxRange(r, 1, 8);
    height(r, lines(text, CHARS.full));
    return r;
  };

  line(data.school || '');
  line(A.title(a.mode, data.quarter, data.subject));
  line(`Класс: ${a.group}`);
  line(`Количество учащихся: ${a.students}`);
  line(`Педагог: ${data.teacher}`);
  line('Цель:');
  line(A.goal(a.mode));

  // ---------- Таблица уровней ----------
  const what = a.mode === 'po' ? 'промежуточного оценивания' : 'суммативного оценивания';
  line(a.mode === 'po' ? 'Анализ результатов ПО' : 'Анализ результатов СОР и СОЧ', { box: true, h: 'center' });
  const h1 = r + 1;
  r += 3;
  merge(`A${h1}:A${r}`); cell(`A${h1}`, a.mode === 'po' ? 'Вид промежуточного оценивания' : 'Вид суммативного оценивания', { box: true, h: 'center', v: 'middle' });
  merge(`B${h1}:B${r}`); cell(`B${h1}`, 'Ученик', { box: true, h: 'center', v: 'middle' });
  merge(`C${h1}:C${r}`); cell(`C${h1}`, 'Максимальный балл', { box: true, h: 'center', v: 'middle' });
  merge(`D${h1}:F${h1}`); cell(`D${h1}`, `Процентное содержание баллов ${what}`, { box: true, h: 'center' });
  merge(`G${h1}:G${r}`); cell(`G${h1}`, '% качества', { box: true, h: 'center', v: 'middle' });
  merge(`H${h1}:H${r}`); cell(`H${h1}`, '% успеваемости', { box: true, h: 'center', v: 'middle' });
  ['низкий', 'средний', 'высокий'].forEach((t, i) => cell(ws.getCell(h1 + 1, 4 + i).address, t, { box: true, h: 'center' }));
  ['0-39%', '40-84%', '85-100%'].forEach((t, i) => cell(ws.getCell(h1 + 2, 4 + i).address, t, { box: true, h: 'center' }));
  for (let row = h1; row <= r; row++) for (let c = 1; c <= 8; c++) ws.getCell(row, c).border = BOX;
  ws.getRow(h1).height = 48;
  r += 1;
  for (const c of [1, 2, 3, 7, 8]) cell(ws.getCell(r, c).address, '', { box: true });
  merge(`D${r}:F${r}`); cell(`D${r}`, 'Количество учеников', { box: true, h: 'center' }); boxRange(r, 4, 6);
  for (const it of a.items) {
    r += 1;
    [it.label, it.wrote, it.max, it.low, it.mid, it.high, `${it.quality}%`, `${it.success}%`]
      .forEach((v, i) => cell(ws.getCell(r, i + 1).address, v, { box: true, h: 'center' }));
  }

  // ---------- Достигнутые цели и затруднения ----------
  r += 1;
  r += 1;
  cell(`A${r}`, '', { box: true });
  merge(`B${r}:E${r}`); cell(`B${r}`, 'Достигнутые цели', { box: true, h: 'center' }); boxRange(r, 2, 5);
  merge(`F${r}:H${r}`); cell(`F${r}`, 'Цели, вызвавшие затруднения', { box: true, h: 'center' }); boxRange(r, 6, 8);
  for (const it of a.items) {
    r += 1;
    const t = data.texts[it.label] || {};
    cell(`A${r}`, it.label, { box: true, h: 'center', v: 'middle' });
    merge(`B${r}:E${r}`); cell(`B${r}`, t.achieved || '', { box: true }); boxRange(r, 2, 5);
    merge(`F${r}:H${r}`); cell(`F${r}`, t.difficult || '', { box: true }); boxRange(r, 6, 8);
    height(r, Math.max(lines(t.achieved, CHARS.be), lines(t.difficult, CHARS.fh)));
  }

  // ---------- Ученики по уровням ----------
  r += 1;
  line(`1. Анализ результатов ${a.mode === 'po' ? 'ПО' : 'СОР и СОЧ'} показал следующий уровень знаний у обучающихся:`, { box: true });
  r += 1;
  cell(`A${r}`, '', { box: true });
  merge(`B${r}:C${r}`); cell(`B${r}`, 'Низкий (Н): 0-39%', { box: true, h: 'center' }); boxRange(r, 2, 3);
  merge(`D${r}:F${r}`); cell(`D${r}`, 'Средний (С): 40-84%', { box: true, h: 'center' }); boxRange(r, 4, 6);
  merge(`G${r}:H${r}`); cell(`G${r}`, 'Высокий (В): 85-100%', { box: true, h: 'center' }); boxRange(r, 7, 8);
  for (const it of a.items) {
    r += 1;
    const low = it.names.low.join(', ');
    const mid = it.names.mid.join(', ');
    const high = it.names.high.join(', ');
    cell(`A${r}`, it.label, { box: true, h: 'center', v: 'middle' });
    merge(`B${r}:C${r}`); cell(`B${r}`, low, { box: true }); boxRange(r, 2, 3);
    merge(`D${r}:F${r}`); cell(`D${r}`, mid, { box: true }); boxRange(r, 4, 6);
    merge(`G${r}:H${r}`); cell(`G${r}`, high, { box: true }); boxRange(r, 7, 8);
    height(r, Math.max(lines(low, CHARS.bc), lines(mid, CHARS.df), lines(high, CHARS.gh)));
  }

  // ---------- Пункты 2–4 ----------
  r += 1;
  line('2. Перечень затруднений, которые возникли у обучающихся при выполнении заданий:');
  line(data.difficulties || '');
  line('3. Причины указанных выше затруднений у обучающихся при выполнении заданий:');
  line(data.reasons || A.REASONS);
  line(`4. Планируемая работа по итогам анализа результатов ${a.mode === 'po' ? 'ПО' : 'СОР и СОЧ'}`);
  line(data.plan || A.PLAN);
  line(`Дата: ${data.date}`);
  r += 1;
  line(`Педагог: ${data.teacher}`);

  await wb.xlsx.writeFile(file);
}

module.exports = { write };
