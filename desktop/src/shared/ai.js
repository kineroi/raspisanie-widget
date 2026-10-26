/*
 * Нейросети для «Расписать»: какой сервис по ключу, как к нему обращаться, какую модель взять.
 * Три формата запросов покрывают почти всё: Google Gemini, Anthropic Claude и «как у OpenAI»
 * (OpenAI, Groq, OpenRouter, DeepSeek, Mistral, AnyModel и любой свой адрес).
 * Только данные и разбор ответов, без сети — сеть в main/ai.js. Тот же смысл, что Ai.java на телефоне.
 */
'use strict';

const L = require('./logic');

const PROVIDERS = {
  gemini: { name: 'Google Gemini', kind: 'gemini', base: 'https://generativelanguage.googleapis.com/v1beta' },
  anthropic: { name: 'Claude (Anthropic)', kind: 'anthropic', base: 'https://api.anthropic.com/v1' },
  groq: { name: 'Groq', kind: 'openai', base: 'https://api.groq.com/openai/v1' },
  openrouter: { name: 'OpenRouter', kind: 'openai', base: 'https://openrouter.ai/api/v1' },
  anymodel: { name: 'AnyModel', kind: 'openai', base: 'https://anymodel.org/v1' },
  openai: { name: 'OpenAI', kind: 'openai', base: 'https://api.openai.com/v1' },
  deepseek: { name: 'DeepSeek', kind: 'openai', base: 'https://api.deepseek.com/v1' },
  mistral: { name: 'Mistral', kind: 'openai', base: 'https://api.mistral.ai/v1' },
};

/** Кого проверять для ключа: по началу ключа, по порядку. */
function candidates(key) {
  const k = String(key || '').trim();
  if (k.startsWith('AIza')) return ['gemini'];
  if (k.startsWith('sk-ant-')) return ['anthropic'];
  if (k.startsWith('gsk_')) return ['groq'];
  if (k.startsWith('sk-or-')) return ['openrouter'];
  if (k.startsWith('sk-proj-') || k.startsWith('sk-svcacct-')) return ['openai'];
  // «sk-» без особого начала: так выглядят ключи AnyModel, OpenAI и DeepSeek
  if (k.startsWith('sk-')) return ['anymodel', 'openai', 'deepseek'];
  // незнакомое начало: у Google бывают ключи нового вида, поэтому сначала Gemini
  return ['gemini', 'mistral'];
}

/** Свой адрес сервиса: только https, без хвостовых «/» и без «/chat/completions». */
function normalizeBase(url) {
  let u = String(url || '').trim();
  if (!u) return '';
  if (!/^https:\/\//i.test(u)) u = 'https://' + u.replace(/^http:\/\//i, '');
  u = u.replace(/\/+$/, '').replace(/\/chat\/completions$/, '').replace(/\/models$/, '');
  return u;
}

/** Похоже ли на адрес сайта (а не, например, «Gemini API Key»). */
function isValidBase(url) {
  return /^https:\/\/[a-z0-9.-]+\.[a-z]{2,}(:\d+)?(\/\S*)?$/i.test(normalizeBase(url));
}

/** Описание подключения: { id, name, kind, base }. id = 'custom' для своего адреса. */
function provider(id, base) {
  if (id === 'custom') {
    const b = normalizeBase(base);
    let host = b;
    try {
      host = new URL(b).hostname;
    } catch {
      // оставим как есть
    }
    return { id: 'custom', name: host || 'Свой сервис', kind: 'openai', base: b };
  }
  const p = PROVIDERS[id];
  return p ? { id, ...p } : null;
}

/** Старые версии хранили только ключ Gemini: если сервис не записан, определяем по ключу. */
function resolve(cfg) {
  if (!cfg || !cfg.key) return null;
  const id = cfg.provider || candidates(cfg.key)[0];
  return provider(id, cfg.base);
}

// ---------- Список моделей ----------

function modelsRequest(p, key) {
  if (p.kind === 'gemini') return { url: `${p.base}/models?pageSize=1000`, headers: { 'x-goog-api-key': key } };
  if (p.kind === 'anthropic') {
    return { url: `${p.base}/models?limit=1000`, headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01' } };
  }
  return { url: `${p.base}/models`, headers: { Authorization: `Bearer ${key}` } };
}

/** Отдельная проверка ключа там, где список моделей открыт всем (OpenRouter). */
function keyCheckRequest(p, key) {
  if (p.id !== 'openrouter') return null;
  return { url: `${p.base}/key`, headers: { Authorization: `Bearer ${key}` } };
}

// не для текста: картинки, звук, поиск по смыслу и т. п.
const NOT_CHAT = /embed|whisper|tts|dall-e|image|audio|moderation|realtime|transcribe|speech|rerank|vision-preview|aqa|guard|sora|veo|imagen|lyria|ocr/i;

function parseModels(p, body) {
  let ids = [];
  if (p.kind === 'gemini') {
    ids = ((body && body.models) || [])
      .filter((m) => !m.supportedGenerationMethods || m.supportedGenerationMethods.includes('generateContent'))
      .map((m) => String(m.name || '').replace(/^models\//, ''));
  } else {
    const list = Array.isArray(body) ? body : (body && body.data) || [];
    ids = list.map((m) => String((m && m.id) || ''));
  }
  ids = ids.filter((id) => id && !NOT_CHAT.test(id));
  return [...new Set(ids)].sort((a, b) => a.localeCompare(b));
}

// Быстрые и дешёвые модели — для короткого текста домашки этого хватает с запасом
const PREFERRED = [
  /^gemini-flash-lite-latest$/, /^gemini-flash-latest$/, /gemini-[\d.]+-flash-lite$/, /gemini-[\d.]+-flash$/,
  /haiku/, /^llama-3\.3-70b-versatile$/, /^deepseek-chat$/, /gpt-[\w.]*mini$/, /mistral-small-latest/,
  /gemini.*flash/, /gpt-[\w.]*mini/, /:free$/, /claude/, /gpt/, /deepseek/, /llama/, /gemini/,
];

function defaultModel(models) {
  for (const re of PREFERRED) {
    const m = models.find((id) => re.test(id));
    if (m) return m;
  }
  return models[0] || '';
}

// ---------- «Расписать» ----------

function userText(note, classes, theme) {
  let user = '';
  if (classes) user += `Класс: ${classes}\n`;
  if (theme) user += `Тема урока: ${theme}\n`;
  return user + `Заметка учителя: ${String(note).trim()}`;
}

function chatRequest(p, key, model, note, classes, theme) {
  const system = L.GEMINI_SYSTEM;
  const user = userText(note, classes, theme);
  if (p.kind === 'gemini') {
    return {
      url: `${p.base}/models/${model}:generateContent`,
      headers: { 'x-goog-api-key': key },
      body: L.geminiRequest(note, classes, theme),
    };
  }
  if (p.kind === 'anthropic') {
    return {
      url: `${p.base}/messages`,
      headers: { 'x-api-key': key, 'anthropic-version': '2023-06-01' },
      body: { model, system, messages: [{ role: 'user', content: user }], max_tokens: 1024, temperature: 0.2 },
    };
  }
  return {
    url: `${p.base}/chat/completions`,
    headers: { Authorization: `Bearer ${key}` },
    body: {
      model,
      messages: [{ role: 'system', content: system }, { role: 'user', content: user }],
      temperature: 0.2,
      max_tokens: 1024,
    },
  };
}

function cleanup(s) {
  return String(s || '')
    .replace(/<think>[\s\S]*?<\/think>/g, '') // «размышления» некоторых моделей
    .replace(/```/g, '').replace(/\*\*/g, '').trim().replace(/\n\s*\n+/g, '\n');
}

function parseChat(p, body) {
  if (p.kind === 'gemini') return L.geminiParse(body);
  if (p.kind === 'anthropic') {
    const parts = (body && body.content) || [];
    return cleanup(parts.filter((c) => c.type === 'text').map((c) => c.text || '').join(''));
  }
  const c = body && body.choices && body.choices[0];
  const content = c && c.message && c.message.content;
  if (Array.isArray(content)) return cleanup(content.map((x) => (x && x.text) || '').join(''));
  return cleanup(content);
}

/** Какие модели пробовать по очереди (у Gemini — запасная, если кончился дневной лимит). */
function modelsToTry(p, model) {
  if (p.kind !== 'gemini') return [model];
  const list = model ? [model] : [];
  for (const m of L.GEMINI_MODELS) if (!list.includes(m)) list.push(m);
  return list;
}

function label(p, model) {
  if (!p) return '';
  return model ? `${p.name} · ${model}` : p.name;
}

module.exports = {
  PROVIDERS, candidates, normalizeBase, isValidBase, provider, resolve, modelsRequest, keyCheckRequest, parseModels, defaultModel,
  chatRequest, parseChat, modelsToTry, label,
};
