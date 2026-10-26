package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** То же, что desktop/test/ai.test.js: сервис по ключу, модели, запросы и ответы. */
public class AiTest {

    @Test
    public void providerByKeyPrefix() {
        assertEquals(Collections.singletonList("gemini"), Ai.candidates("AIzaSyXXXX"));
        assertEquals(Collections.singletonList("anthropic"), Ai.candidates("sk-ant-api03-XXXX"));
        assertEquals(Collections.singletonList("groq"), Ai.candidates("gsk_XXXX"));
        assertEquals(Collections.singletonList("openrouter"), Ai.candidates("sk-or-v1-XXXX"));
        assertEquals(Collections.singletonList("openai"), Ai.candidates("sk-proj-XXXX"));
        assertEquals(Arrays.asList("anymodel", "openai", "deepseek"), Ai.candidates(" sk-dc9dXXXX "));
        assertEquals(Collections.singletonList("mistral"), Ai.candidates("abcdef123456"));
    }

    @Test
    public void customBaseAndOldKey() {
        assertEquals("https://anymodel.org/v1", Ai.normalizeBase("anymodel.org/v1/"));
        assertEquals("https://x.example/v1", Ai.normalizeBase("https://x.example/v1/chat/completions"));
        assertEquals("https://x.example/v1", Ai.normalizeBase("http://x.example/v1"));
        Ai.Provider custom = Ai.provider(Ai.CUSTOM, "https://llm.example.com/v1/");
        assertEquals("llm.example.com", custom.name);
        assertEquals("https://llm.example.com/v1", custom.base);
        assertEquals(Ai.OPENAI, custom.kind);
        assertEquals("gemini", Ai.resolve("AIzaOLD", "", "").id);
        assertEquals("https://anymodel.org/v1", Ai.resolve("sk-x", "anymodel", "").base);
        assertNull(Ai.resolve("", "", ""));
    }

    @Test
    public void modelsAndDefault() throws Exception {
        Ai.Provider gem = Ai.provider("gemini", "");
        List<String> g = Ai.parseModels(gem, "{\"models\":["
                + "{\"name\":\"models/gemini-flash-latest\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/gemini-flash-lite-latest\",\"supportedGenerationMethods\":[\"generateContent\"]},"
                + "{\"name\":\"models/text-embedding-004\",\"supportedGenerationMethods\":[\"embedContent\"]}]}");
        assertEquals(Arrays.asList("gemini-flash-latest", "gemini-flash-lite-latest"), g);
        assertEquals("gemini-flash-lite-latest", Ai.defaultModel(g));

        Ai.Provider any = Ai.provider("anymodel", "");
        List<String> o = Ai.parseModels(any, "{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"claude-opus-4\"},"
                + "{\"id\":\"claude-haiku-4-5\"},{\"id\":\"text-embedding-3-small\"},{\"id\":\"whisper-1\"}]}");
        assertEquals(Arrays.asList("claude-haiku-4-5", "claude-opus-4", "gpt-5"), o);
        assertEquals("claude-haiku-4-5", Ai.defaultModel(o));
        assertEquals("some-model", Ai.defaultModel(Collections.singletonList("some-model")));
        assertEquals("", Ai.defaultModel(Collections.emptyList()));
    }

    @Test
    public void requestsAndResponses() throws Exception {
        Ai.Provider any = Ai.provider("anymodel", "");
        Ai.Request o = Ai.chatRequest(any, "sk-1", "gpt-5", "стр 45", "9А", "Heroes");
        assertEquals("https://anymodel.org/v1/chat/completions", o.url);
        assertEquals("Bearer sk-1", o.headers.get("Authorization"));
        JSONObject ob = new JSONObject(o.body);
        assertEquals("gpt-5", ob.getString("model"));
        assertEquals("system", ob.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertTrue(ob.getJSONArray("messages").getJSONObject(1).getString("content")
                .contains("Класс: 9А\nТема урока: Heroes\nЗаметка учителя: стр 45"));
        assertEquals("Задание: Страница 45.\nTask: Page 45.", Ai.parseChat(any,
                "{\"choices\":[{\"message\":{\"content\":\"<think>…</think>**Задание:** Страница 45.\\n\\nTask: Page 45.\"}}]}"));

        Ai.Provider ant = Ai.provider("anthropic", "");
        Ai.Request c = Ai.chatRequest(ant, "sk-ant-1", "claude-haiku-4-5", "стр 45", "", "");
        assertEquals("https://api.anthropic.com/v1/messages", c.url);
        assertEquals("sk-ant-1", c.headers.get("x-api-key"));
        assertEquals("2023-06-01", c.headers.get("anthropic-version"));
        assertEquals("Заметка учителя: стр 45", new JSONObject(c.body).getJSONArray("messages")
                .getJSONObject(0).getString("content"));
        assertEquals("Задание: А.\nTask: A.", Ai.parseChat(ant,
                "{\"content\":[{\"type\":\"text\",\"text\":\"Задание: А.\\nTask: A.\"}]}"));

        Ai.Provider gem = Ai.provider("gemini", "");
        Ai.Request g = Ai.chatRequest(gem, "AIza1", "gemini-flash-latest", "стр 45", "9А", "");
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent", g.url);
        assertEquals("Задание: Б.", Ai.parseChat(gem, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Задание: Б.\"}]}}]}"));
        assertEquals(Arrays.asList("gemini-flash-latest", "gemini-flash-lite-latest"), Ai.modelsToTry(gem, "gemini-flash-latest"));
        assertEquals(Collections.singletonList("gpt-5"), Ai.modelsToTry(any, "gpt-5"));

        assertEquals("AnyModel · gpt-5", Ai.label(any, "gpt-5"));
        assertEquals("https://openrouter.ai/api/v1/key", Ai.keyCheckRequest(Ai.provider("openrouter", ""), "sk-or-1").url);
        assertNull(Ai.keyCheckRequest(any, "sk-1"));
        assertEquals("Google Gemini · gemini-flash-lite-latest", Ai.label(gem, ""));
    }
}
