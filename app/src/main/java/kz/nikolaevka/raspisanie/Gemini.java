package kz.nikolaevka.raspisanie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Расписывает краткую заметку учителя в понятное домашнее задание (RU + EN) через Google Gemini. */
final class Gemini {

    /** Модели по порядку: сначала самая лёгкая (больше бесплатных запросов в день). */
    static final String[] MODELS = {"gemini-flash-lite-latest", "gemini-flash-latest"};

    private static final String URL_TMPL =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    static final class Failure extends IOException {
        final int code;

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

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

    /** Расписать заметку. Вызывать НЕ из главного потока. */
    static String expand(String apiKey, String note, String classes, String theme) throws IOException {
        String payload;
        try {
            payload = buildRequest(note, classes, theme);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        Failure last = null;
        for (String model : MODELS) {
            try {
                String text = parseResponse(post(String.format(URL_TMPL, model), apiKey, payload));
                if (!text.isEmpty()) return text;
                last = new Failure(0, "Пустой ответ");
            } catch (Failure f) {
                last = f;
                // 404 — модель недоступна, 429 — закончился лимит этой модели: пробуем следующую
                if (f.code != 404 && f.code != 429 && f.code != 503) throw f;
            } catch (JSONException e) {
                last = new Failure(0, "Непонятный ответ");
            }
        }
        throw last != null ? last : new Failure(0, "Нет ответа");
    }

    static String explain(IOException e) {
        if (e instanceof Failure) {
            int code = ((Failure) e).code;
            if (code == 400 || code == 401 || code == 403) return "Ключ Gemini не подходит. Проверьте его в настройках.";
            if (code == 429) return "Дневной лимит Gemini исчерпан. Текст расписан без ИИ.";
        }
        return "Gemini недоступен (нет интернета?). Текст расписан без ИИ.";
    }

    private static String post(String url, String apiKey, String payload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(40000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", apiKey);
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Failure(code, "HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private Gemini() {
    }
}
