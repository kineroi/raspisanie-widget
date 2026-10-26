'use strict';
// Расписывает краткую заметку в задание на русском и английском через Google Gemini.
const { net } = require('electron');
const L = require('../shared/logic');

class GeminiError extends Error {
  constructor(code, msg) {
    super(msg);
    this.code = code;
  }
}

async function expand(apiKey, note, classes, theme) {
  const payload = JSON.stringify(L.geminiRequest(note, classes, theme));
  let last = null;
  for (const model of L.GEMINI_MODELS) {
    let res;
    try {
      res = await net.fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
        body: payload,
      });
    } catch {
      throw new GeminiError(0, 'network');
    }
    if (res.ok) {
      const text = L.geminiParse(await res.json());
      if (text) return text;
      last = new GeminiError(0, 'empty');
      continue;
    }
    last = new GeminiError(res.status, `HTTP ${res.status}`);
    // 404 — модель недоступна, 429 — закончился лимит, 503 — перегружено: пробуем следующую
    if (![404, 429, 503].includes(res.status)) throw last;
  }
  throw last || new GeminiError(0, 'empty');
}

function explain(e) {
  const code = e && e.code;
  if ([400, 401, 403].includes(code)) return 'Ключ Gemini не подходит. Проверьте его в главном окне.';
  if (code === 429) return 'Дневной лимит Gemini исчерпан. Текст расписан без ИИ.';
  return 'Gemini недоступен (нет интернета?). Текст расписан без ИИ.';
}

module.exports = { expand, explain };
