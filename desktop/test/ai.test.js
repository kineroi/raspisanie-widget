'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const A = require('../src/shared/ai.js');

test('Нейросеть: сервис по началу ключа', () => {
  assert.deepEqual(A.candidates('AIzaSyXXXX'), ['gemini']);
  assert.deepEqual(A.candidates('sk-ant-api03-XXXX'), ['anthropic']);
  assert.deepEqual(A.candidates('gsk_XXXX'), ['groq']);
  assert.deepEqual(A.candidates('sk-or-v1-XXXX'), ['openrouter']);
  assert.deepEqual(A.candidates('sk-proj-XXXX'), ['openai']);
  assert.deepEqual(A.candidates(' sk-dc9dXXXX '), ['anymodel', 'openai', 'deepseek']);
  assert.deepEqual(A.candidates('abcdef123456'), ['mistral']);
});

test('Нейросеть: свой адрес и старый ключ без записанного сервиса', () => {
  assert.equal(A.normalizeBase('anymodel.org/v1/'), 'https://anymodel.org/v1');
  assert.equal(A.normalizeBase('https://x.example/v1/chat/completions'), 'https://x.example/v1');
  assert.equal(A.normalizeBase('http://x.example/v1'), 'https://x.example/v1');
  const custom = A.provider('custom', 'https://llm.example.com/v1/');
  assert.deepEqual(custom, { id: 'custom', name: 'llm.example.com', kind: 'openai', base: 'https://llm.example.com/v1' });
  assert.equal(A.resolve({ key: 'AIzaOLD' }).id, 'gemini');
  assert.equal(A.resolve({ key: 'sk-x', provider: 'anymodel' }).base, 'https://anymodel.org/v1');
  assert.equal(A.resolve({ key: '' }), null);
});

test('Нейросеть: список моделей и модель по умолчанию', () => {
  const gem = A.provider('gemini');
  const gModels = A.parseModels(gem, { models: [
    { name: 'models/gemini-flash-latest', supportedGenerationMethods: ['generateContent'] },
    { name: 'models/gemini-flash-lite-latest', supportedGenerationMethods: ['generateContent'] },
    { name: 'models/text-embedding-004', supportedGenerationMethods: ['embedContent'] },
  ] });
  assert.deepEqual(gModels, ['gemini-flash-latest', 'gemini-flash-lite-latest']);
  assert.equal(A.defaultModel(gModels), 'gemini-flash-lite-latest');

  const any = A.provider('anymodel');
  const oModels = A.parseModels(any, { data: [
    { id: 'gpt-5' }, { id: 'claude-opus-4' }, { id: 'claude-haiku-4-5' }, { id: 'text-embedding-3-small' }, { id: 'whisper-1' },
  ] });
  assert.deepEqual(oModels, ['claude-haiku-4-5', 'claude-opus-4', 'gpt-5']);
  assert.equal(A.defaultModel(oModels), 'claude-haiku-4-5');
  assert.equal(A.defaultModel(['some-model']), 'some-model');
  assert.equal(A.defaultModel([]), '');
});

test('Нейросеть: запросы и ответы в трёх форматах', () => {
  const any = A.provider('anymodel');
  const o = A.chatRequest(any, 'sk-1', 'gpt-5', 'стр 45', '9А', 'Heroes');
  assert.equal(o.url, 'https://anymodel.org/v1/chat/completions');
  assert.equal(o.headers.Authorization, 'Bearer sk-1');
  assert.equal(o.body.model, 'gpt-5');
  assert.equal(o.body.messages[0].role, 'system');
  assert.match(o.body.messages[1].content, /Класс: 9А\nТема урока: Heroes\nЗаметка учителя: стр 45/);
  assert.equal(A.parseChat(any, { choices: [{ message: { content: '<think>…</think>**Задание:** Страница 45.\n\nTask: Page 45.' } }] }),
    'Задание: Страница 45.\nTask: Page 45.');

  const ant = A.provider('anthropic');
  const c = A.chatRequest(ant, 'sk-ant-1', 'claude-haiku-4-5', 'стр 45', '', '');
  assert.equal(c.url, 'https://api.anthropic.com/v1/messages');
  assert.equal(c.headers['x-api-key'], 'sk-ant-1');
  assert.equal(c.headers['anthropic-version'], '2023-06-01');
  assert.equal(c.body.messages[0].content, 'Заметка учителя: стр 45');
  assert.equal(A.parseChat(ant, { content: [{ type: 'text', text: 'Задание: А.\nTask: A.' }] }), 'Задание: А.\nTask: A.');

  const gem = A.provider('gemini');
  const g = A.chatRequest(gem, 'AIza1', 'gemini-flash-latest', 'стр 45', '9А', '');
  assert.equal(g.url, 'https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent');
  assert.equal(A.parseChat(gem, { candidates: [{ content: { parts: [{ text: 'Задание: Б.' }] } }] }), 'Задание: Б.');
  assert.deepEqual(A.modelsToTry(gem, 'gemini-flash-latest'), ['gemini-flash-latest', 'gemini-flash-lite-latest']);
  assert.deepEqual(A.modelsToTry(any, 'gpt-5'), ['gpt-5']);

  assert.equal(A.label(any, 'gpt-5'), 'AnyModel · gpt-5');
  assert.equal(A.keyCheckRequest(A.provider('openrouter'), 'sk-or-1').url, 'https://openrouter.ai/api/v1/key');
  assert.equal(A.keyCheckRequest(any, 'sk-1'), null);
});
