'use strict';
// Нейросеть для «Расписать»: проверка ключа (какой это сервис), список моделей и сам запрос.
const { net } = require('electron');
const A = require('../shared/ai');

class AiError extends Error {
  constructor(code, msg) {
    super(msg);
    this.code = code;
  }
}

async function call(req, method, body) {
  let res;
  try {
    res = await net.fetch(req.url, {
      method,
      headers: body ? { 'Content-Type': 'application/json', ...req.headers } : req.headers,
      body: body ? JSON.stringify(body) : undefined,
    });
  } catch {
    throw new AiError(0, 'network');
  }
  if (!res.ok) throw new AiError(res.status, `HTTP ${res.status}`);
  try {
    return await res.json();
  } catch {
    throw new AiError(0, 'bad-json');
  }
}

async function listModels(p, key) {
  return A.parseModels(p, await call(A.modelsRequest(p, key), 'GET'));
}

/**
 * Какой сервис принимает ключ. С адресом — только он; иначе по началу ключа,
 * и для «sk-…» по очереди AnyModel, OpenAI, DeepSeek. Запрос списка моделей бесплатный.
 */
async function detect(key, base) {
  const ids = base ? ['custom'] : A.candidates(key);
  let network = false;
  const tried = [];
  for (const id of ids) {
    const p = A.provider(id, base);
    try {
      const check = A.keyCheckRequest(p, key);
      if (check) await call(check, 'GET');
      const models = await listModels(p, key);
      return { provider: id, base: p.base, models, model: A.defaultModel(models), name: p.name };
    } catch (e) {
      if (e.code === 0) network = true;
      tried.push(`${p.name} — ${e.code ? `ответ ${e.code}` : 'нет связи'}`);
    }
  }
  const err = new AiError(network ? 0 : 401, network ? 'network' : 'rejected');
  err.tried = tried;
  throw err;
}

async function expand(cfg, note, classes, theme) {
  const p = A.resolve(cfg);
  let last = null;
  for (const model of A.modelsToTry(p, cfg.model)) {
    try {
      const req = A.chatRequest(p, cfg.key, model, note, classes, theme);
      const text = A.parseChat(p, await call(req, 'POST', req.body));
      if (text) return text;
      last = new AiError(0, 'empty');
    } catch (e) {
      last = e;
      // 404 — модель недоступна, 429 — закончился лимит, 503 — перегружено: пробуем следующую
      if (![404, 429, 503].includes(e.code)) throw e;
    }
  }
  throw last || new AiError(0, 'empty');
}

function explain(e, p) {
  const code = e && e.code;
  const name = p.name;
  // Gemini на неверный ключ отвечает 400
  if ([401, 403].includes(code) || (code === 400 && p.kind === 'gemini')) return `Ключ ${name} не подходит. Проверьте его в главном окне.`;
  if (code === 402) return `На балансе ${name} закончились деньги. Текст расписан без ИИ.`;
  if (code === 429) return `Лимит ${name} исчерпан. Текст расписан без ИИ.`;
  if (code === 400 || code === 404) return `${name} не принял запрос. Попробуйте выбрать другую модель в главном окне.`;
  return `${name} недоступен (нет интернета?). Текст расписан без ИИ.`;
}

function explainDetect(e) {
  const tried = e && e.tried && e.tried.length ? ` Проверено: ${e.tried.join('; ')}.` : '';
  if (e && e.code === 0) return `Не удалось проверить ключ: нет связи с сервисом. Проверьте интернет и попробуйте ещё раз.${tried}`;
  return `Ключ не принят. Проверьте, что он скопирован целиком.${tried}`;
}

module.exports = { detect, listModels, expand, explain, explainDetect };
