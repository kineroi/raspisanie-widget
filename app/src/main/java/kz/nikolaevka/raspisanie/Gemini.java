package kz.nikolaevka.raspisanie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


/** Подсказка для нейросети (общая для всех сервисов) и формат запроса/ответа Google Gemini. Сеть — в {@link Ai}. */
final class Gemini {

    /** Модели по порядку: сначала самая лёгкая (больше бесплатных запросов в день). */
    static final String[] MODELS = {"gemini-flash-lite-latest", "gemini-flash-latest"};

    static String systemPrompt() {
        return "Ты помогаешь учителю английского языка в казахстанской школе (ученики говорят по-русски, "
                + "многие слабо знают английский). Учитель даёт краткую заметку о домашнем задании. "
                + "Перепиши её в понятное задание для учеников и родителей.\n"
                + "Правила:\n"
                + "1. Используй ТОЛЬКО то, что есть в заметке. Ничего не добавляй от себя: никаких новых "
                + "упражнений, страниц, слов, сроков и советов.\n"
                + "2. Номера страниц, упражнений и английские слова из заметки сохрани точно.\n"
                + "3. Ответ ровно из двух строк, без приветствий, пояснений и разметки:\n"
                + "Задание: <понятный текст на русском, 1–3 предложения>\n"
                + "Task: <тот же текст на простом английском, 1–3 предложения>\n"
                + "4. Сокращения раскрывай: стр — страница, упр — упражнение, р.т. — рабочая тетрадь, "
                + "письм — письменно, сл — слова.";
    }

    static String userPrompt(String note, String classes, String theme) {
        StringBuilder sb = new StringBuilder();
        if (classes != null && !classes.isEmpty()) sb.append("Класс: ").append(classes).append("\n");
        if (theme != null && !theme.isEmpty()) sb.append("Тема урока: ").append(theme).append("\n");
        sb.append("Заметка учителя: ").append(note.trim());
        return sb.toString();
    }

    /** Запрос в формате Gemini API. */
    static String buildRequest(String note, String classes, String theme) throws JSONException {
        JSONObject req = new JSONObject();
        req.put("systemInstruction", new JSONObject()
                .put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt()))));
        req.put("contents", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("parts", new JSONArray().put(new JSONObject().put("text", userPrompt(note, classes, theme))))));
        req.put("generationConfig", new JSONObject()
                .put("temperature", 0.2)
                // запас на «размышления» у новых моделей; сам ответ короткий
                .put("maxOutputTokens", 2048));
        return req.toString();
    }

    /** Достать текст из ответа Gemini. */
    static String parseResponse(String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        JSONArray cands = o.optJSONArray("candidates");
        if (cands == null || cands.length() == 0) return "";
        JSONObject content = cands.getJSONObject(0).optJSONObject("content");
        if (content == null) return "";
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && !p.optBoolean("thought", false)) sb.append(p.optString("text", ""));
        }
        return cleanup(sb.toString());
    }

    /** Убрать возможную разметку (**, ```), лишние пустые строки. */
    static String cleanup(String s) {
        String t = s.replace("```", "").replace("**", "").trim();
        t = t.replaceAll("\\n\\s*\\n+", "\n");
        return t;
    }

    private Gemini() {
    }
}
