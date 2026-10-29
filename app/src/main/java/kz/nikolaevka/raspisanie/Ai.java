package kz.nikolaevka.raspisanie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Нейросети для «Расписать»: какой сервис по ключу, список моделей и сам запрос.
 * Три формата покрывают почти всё: Google Gemini, Anthropic Claude и «как у OpenAI»
 * (OpenAI, Groq, xAI Grok, OpenRouter, DeepSeek, Mistral, AnyModel и любой свой адрес).
 * Тот же смысл, что desktop/src/shared/ai.js в программе для Windows.
 */
final class Ai {

    static final String GEMINI = "gemini", ANTHROPIC = "anthropic", OPENAI = "openai", CUSTOM = "custom";

    static final class Provider {
        final String id, name, kind, base;

        Provider(String id, String name, String kind, String base) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.base = base;
        }
    }

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    static {
        add("gemini", "Google Gemini", GEMINI, "https://generativelanguage.googleapis.com/v1beta");
        add("anthropic", "Claude (Anthropic)", ANTHROPIC, "https://api.anthropic.com/v1");
        add("groq", "Groq", OPENAI, "https://api.groq.com/openai/v1");
        add("xai", "xAI Grok", OPENAI, "https://api.x.ai/v1");
        add("openrouter", "OpenRouter", OPENAI, "https://openrouter.ai/api/v1");
        add("anymodel", "AnyModel", OPENAI, "https://anymodel.org/v1");
        add("openai", "OpenAI", OPENAI, "https://api.openai.com/v1");
        add("deepseek", "DeepSeek", OPENAI, "https://api.deepseek.com/v1");
        add("mistral", "Mistral", OPENAI, "https://api.mistral.ai/v1");
    }

    private static void add(String id, String name, String kind, String base) {
        PROVIDERS.put(id, new Provider(id, name, kind, base));
    }

    static final class Failure extends IOException {
        final int code;
        /** Для проверки ключа: какие сервисы спрашивали и что они ответили. */
        String tried = "";

        Failure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Кого проверять для ключа: по началу ключа, по порядку. */
    static List<String> candidates(String key) {
        String k = key == null ? "" : key.trim();
        if (k.startsWith("AIza")) return Collections.singletonList("gemini");
        if (k.startsWith("sk-ant-")) return Collections.singletonList("anthropic");
        if (k.startsWith("gsk_")) return Collections.singletonList("groq");
        // Grok от xAI — не путать с Groq
        if (k.startsWith("xai-")) return Collections.singletonList("xai");
        if (k.startsWith("sk-or-")) return Collections.singletonList("openrouter");
        if (k.startsWith("sk-proj-") || k.startsWith("sk-svcacct-")) return Collections.singletonList("openai");
        // «sk-» без особого начала: так выглядят ключи AnyModel, OpenAI и DeepSeek
        if (k.startsWith("sk-")) return Arrays.asList("anymodel", "openai", "deepseek");
        // незнакомое начало: у Google бывают ключи нового вида, поэтому сначала Gemini
        return Arrays.asList("gemini", "mistral");
    }

    /** Свой адрес сервиса: только https, без хвостовых «/» и без «/chat/completions». */
    static String normalizeBase(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return "";
        if (!u.toLowerCase().startsWith("https://")) u = "https://" + u.replaceFirst("(?i)^http://", "");
        u = u.replaceAll("/+$", "").replaceAll("/chat/completions$", "").replaceAll("/models$", "");
        return u;
    }

    private static final Pattern VALID_BASE =
            Pattern.compile("^https://[a-z0-9.-]+\\.[a-z]{2,}(:\\d+)?(/\\S*)?$", Pattern.CASE_INSENSITIVE);

    /** Похоже ли на адрес сайта (а не, например, «Gemini API Key»). */
    static boolean isValidBase(String url) {
        return VALID_BASE.matcher(normalizeBase(url)).matches();
    }

    static Provider provider(String id, String base) {
        if (CUSTOM.equals(id)) {
            String b = normalizeBase(base);
            String host = b;
            try {
                String h = URI.create(b).getHost();
                if (h != null) host = h;
            } catch (IllegalArgumentException ignored) {
                // оставим как есть
            }
            return new Provider(CUSTOM, host.isEmpty() ? "Свой сервис" : host, OPENAI, b);
        }
        return PROVIDERS.get(id);
    }

    /** Старые версии хранили только ключ Gemini: если сервис не записан, определяем по ключу. */
    static Provider resolve(String key, String providerId, String base) {
        if (key == null || key.isEmpty()) return null;
        String id = providerId == null || providerId.isEmpty() ? candidates(key).get(0) : providerId;
        return provider(id, base);
    }

    // ---------- Запросы ----------

    static final class Request {
        final String url;
        final Map<String, String> headers = new LinkedHashMap<>();
        final String body;
        /** Сколько ждать ответа: с файлами нейросеть думает дольше. */
        int timeout = 40000;

        Request(String url, String body) {
            this.url = url;
            this.body = body;
        }
    }

    static Request modelsRequest(Provider p, String key) {
        Request r;
        if (GEMINI.equals(p.kind)) {
            r = new Request(p.base + "/models?pageSize=1000", null);
            r.headers.put("x-goog-api-key", key);
        } else if (ANTHROPIC.equals(p.kind)) {
            r = new Request(p.base + "/models?limit=1000", null);
            r.headers.put("x-api-key", key);
            r.headers.put("anthropic-version", "2023-06-01");
        } else {
            r = new Request(p.base + "/models", null);
            r.headers.put("Authorization", "Bearer " + key);
        }
        return r;
    }

    /** Отдельная проверка ключа там, где список моделей открыт всем (OpenRouter). */
    static Request keyCheckRequest(Provider p, String key) {
        if (!"openrouter".equals(p.id)) return null;
        Request r = new Request(p.base + "/key", null);
        r.headers.put("Authorization", "Bearer " + key);
        return r;
    }

    // не для текста: картинки, звук, поиск по смыслу и т. п.
    private static final Pattern NOT_CHAT = Pattern.compile(
            "embed|whisper|tts|dall-e|image|audio|moderation|realtime|transcribe|speech|rerank"
                    + "|vision-preview|aqa|guard|sora|veo|imagen|lyria|ocr", Pattern.CASE_INSENSITIVE);

    static List<String> parseModels(Provider p, String body) throws JSONException {
        List<String> ids = new ArrayList<>();
        if (GEMINI.equals(p.kind)) {
            JSONArray models = new JSONObject(body).optJSONArray("models");
            for (int i = 0; models != null && i < models.length(); i++) {
                JSONObject m = models.getJSONObject(i);
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                if (methods != null && !methods.toString().contains("\"generateContent\"")) continue;
                ids.add(m.optString("name", "").replaceFirst("^models/", ""));
            }
        } else {
            String t = body.trim();
            JSONArray list = t.startsWith("[") ? new JSONArray(t) : new JSONObject(t).optJSONArray("data");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject m = list.optJSONObject(i);
                if (m != null) ids.add(m.optString("id", ""));
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String id : ids) if (!id.isEmpty() && !NOT_CHAT.matcher(id).find()) out.add(id);
        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        return sorted;
    }

    // Быстрые и дешёвые модели — для короткого текста домашки этого хватает с запасом
    private static final String[] PREFERRED = {
            "^gemini-flash-lite-latest$", "^gemini-flash-latest$", "gemini-[\\d.]+-flash-lite$", "gemini-[\\d.]+-flash$",
            "haiku", "^llama-3\\.3-70b-versatile$", "^deepseek-chat$", "gpt-[\\w.]*mini$", "mistral-small-latest",
            "^grok-\\d[\\w.-]*-fast", "^grok-\\d[\\w.-]*mini",
            "gemini.*flash", "gpt-[\\w.]*mini", ":free$", "claude", "gpt", "deepseek", "llama", "gemini", "^grok",
    };

    static String defaultModel(List<String> models) {
        for (String re : PREFERRED) {
            Pattern p = Pattern.compile(re);
            for (String m : models) if (p.matcher(m).find()) return m;
        }
        return models.isEmpty() ? "" : models.get(0);
    }

    static Request chatRequest(Provider p, String key, String model, String note, String classes, String theme)
            throws JSONException {
        Request r;
        if (GEMINI.equals(p.kind)) {
            r = new Request(p.base + "/models/" + model + ":generateContent", Gemini.buildRequest(note, classes, theme));
            r.headers.put("x-goog-api-key", key);
            return r;
        }
        String user = Gemini.userPrompt(note, classes, theme);
        if (ANTHROPIC.equals(p.kind)) {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("system", Gemini.systemPrompt())
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)))
                    .put("max_tokens", 1024)
                    .put("temperature", 0.2);
            r = new Request(p.base + "/messages", body.toString());
            r.headers.put("x-api-key", key);
            r.headers.put("anthropic-version", "2023-06-01");
            return r;
        }
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", Gemini.systemPrompt()))
                        .put(new JSONObject().put("role", "user").put("content", user)))
                .put("temperature", 0.2)
                .put("max_tokens", 1024);
        r = new Request(p.base + "/chat/completions", body.toString());
        r.headers.put("Authorization", "Bearer " + key);
        return r;
    }

    static String parseChat(Provider p, String body) throws JSONException {
        if (GEMINI.equals(p.kind)) return Gemini.parseResponse(body);
        JSONObject o = new JSONObject(body);
        StringBuilder sb = new StringBuilder();
        if (ANTHROPIC.equals(p.kind)) {
            JSONArray parts = o.optJSONArray("content");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject c = parts.optJSONObject(i);
                if (c != null && "text".equals(c.optString("type"))) sb.append(c.optString("text", ""));
            }
        } else {
            JSONArray choices = o.optJSONArray("choices");
            JSONObject msg = choices == null || choices.length() == 0 ? null
                    : choices.getJSONObject(0).optJSONObject("message");
            Object content = msg == null ? null : msg.opt("content");
            if (content instanceof JSONArray) {
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null) sb.append(c.optString("text", ""));
                }
            } else if (content instanceof String) {
                sb.append((String) content);
            }
        }
        // «размышления» некоторых моделей
        return Gemini.cleanup(sb.toString().replaceAll("(?s)<think>.*?</think>", ""));
    }

    /** Файл для нейросети: тип (application/pdf, image/jpeg…) и содержимое в base64. */
    static final class FilePart {
        final String mime, data;

        FilePart(String mime, String data) {
            this.mime = mime;
            this.data = data;
        }
    }

    /** Сервисы «как у OpenAI» PDF не принимают — только картинки. */
    static final int PDF_UNSUPPORTED = -2;

    /** Запрос с файлами и своей подсказкой, ответ — объект JSON (как fileRequest в desktop/src/shared/ai.js). */
    static Request fileRequest(Provider p, String key, String model, String system, String text, List<FilePart> files)
            throws JSONException, Failure {
        int maxTokens = 8192;
        Request r;
        if (GEMINI.equals(p.kind)) {
            JSONArray parts = new JSONArray();
            for (FilePart f : files) {
                parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", f.mime).put("data", f.data)));
            }
            parts.put(new JSONObject().put("text", text));
            JSONObject body = new JSONObject()
                    .put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                    .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", parts)))
                    .put("generationConfig", new JSONObject().put("temperature", 0.2).put("maxOutputTokens", maxTokens)
                            .put("responseMimeType", "application/json"));
            r = new Request(p.base + "/models/" + model + ":generateContent", body.toString());
            r.headers.put("x-goog-api-key", key);
        } else if (ANTHROPIC.equals(p.kind)) {
            JSONArray content = new JSONArray();
            for (FilePart f : files) {
                String type = "application/pdf".equals(f.mime) ? "document" : "image";
                content.put(new JSONObject().put("type", type).put("source", new JSONObject()
                        .put("type", "base64").put("media_type", f.mime).put("data", f.data)));
            }
            content.put(new JSONObject().put("type", "text").put("text", text));
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("system", system)
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", content)))
                    .put("max_tokens", maxTokens)
                    .put("temperature", 0.2);
            r = new Request(p.base + "/messages", body.toString());
            r.headers.put("x-api-key", key);
            r.headers.put("anthropic-version", "2023-06-01");
        } else {
            for (FilePart f : files) if ("application/pdf".equals(f.mime)) throw new Failure(PDF_UNSUPPORTED, "pdf");
            Object user = text;
            if (!files.isEmpty()) {
                JSONArray content = new JSONArray();
                for (FilePart f : files) {
                    content.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", "data:" + f.mime + ";base64," + f.data)));
                }
                content.put(new JSONObject().put("type", "text").put("text", text));
                user = content;
            }
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", user)))
                    .put("temperature", 0.2)
                    .put("max_tokens", maxTokens);
            r = new Request(p.base + "/chat/completions", body.toString());
            r.headers.put("Authorization", "Bearer " + key);
        }
        r.timeout = 120000;
        return r;
    }

    /** Текст ответа без чистки разметки (для JSON). */
    static String rawText(Provider p, String body) throws JSONException {
        JSONObject o = new JSONObject(body);
        StringBuilder sb = new StringBuilder();
        if (GEMINI.equals(p.kind)) {
            JSONArray cands = o.optJSONArray("candidates");
            JSONObject c = cands == null || cands.length() == 0 ? null : cands.optJSONObject(0);
            JSONObject content = c == null ? null : c.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject x = parts.optJSONObject(i);
                if (x != null && !x.optBoolean("thought")) sb.append(x.optString("text", ""));
            }
            return sb.toString();
        }
        if (ANTHROPIC.equals(p.kind)) {
            JSONArray parts = o.optJSONArray("content");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject c = parts.optJSONObject(i);
                if (c != null && "text".equals(c.optString("type"))) sb.append(c.optString("text", ""));
            }
            return sb.toString();
        }
        JSONArray choices = o.optJSONArray("choices");
        JSONObject msg = choices == null || choices.length() == 0 ? null : choices.getJSONObject(0).optJSONObject("message");
        Object content = msg == null ? null : msg.opt("content");
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null) sb.append(c.optString("text", ""));
            }
        } else if (content instanceof String) {
            sb.append((String) content);
        }
        return sb.toString();
    }

    /** Достать объект JSON из ответа (модели иногда оборачивают его в текст или ```json). */
    static JSONObject parseJson(String text) {
        String t = text == null ? "" : text;
        int from = t.indexOf('{'), to = t.lastIndexOf('}');
        if (from < 0 || to <= from) return null;
        try {
            return new JSONObject(t.substring(from, to + 1));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Какие модели пробовать по очереди (у Gemini — запасная, если кончился дневной лимит). */
    static List<String> modelsToTry(Provider p, String model) {
        List<String> list = new ArrayList<>();
        if (model != null && !model.isEmpty()) list.add(model);
        if (GEMINI.equals(p.kind)) for (String m : Gemini.MODELS) if (!list.contains(m)) list.add(m);
        return list;
    }

    static String label(Provider p, String model) {
        if (p == null) return "";
        String m = model == null || model.isEmpty() ? (GEMINI.equals(p.kind) ? Gemini.MODELS[0] : "") : model;
        return m.isEmpty() ? p.name : p.name + " · " + m;
    }

    // ---------- Сеть (вызывать НЕ из главного потока) ----------

    static final class Detected {
        final String providerId, base, model, name;
        final List<String> models;

        Detected(String providerId, String base, List<String> models, String model, String name) {
            this.providerId = providerId;
            this.base = base;
            this.models = models;
            this.model = model;
            this.name = name;
        }
    }

    static List<String> listModels(Provider p, String key) throws IOException {
        try {
            return parseModels(p, http(modelsRequest(p, key)));
        } catch (JSONException e) {
            throw new Failure(0, "Непонятный ответ");
        }
    }

    /**
     * Какой сервис принимает ключ. С адресом — только он; иначе по началу ключа,
     * и для «sk-…» по очереди AnyModel, OpenAI, DeepSeek. Запрос списка моделей бесплатный.
     */
    static Detected detect(String key, String base) throws IOException {
        if (base != null && !base.trim().isEmpty() && !isValidBase(base)) throw new Failure(-1, "bad-base");
        String b = normalizeBase(base);
        List<String> ids = b.isEmpty() ? candidates(key) : Collections.singletonList(CUSTOM);
        boolean network = false;
        StringBuilder tried = new StringBuilder();
        for (String id : ids) {
            Provider p = provider(id, b);
            int code = 0;
            try {
                Request check = keyCheckRequest(p, key);
                if (check != null) http(check);
                List<String> models = listModels(p, key);
                return new Detected(id, p.base, models, defaultModel(models), p.name);
            } catch (Failure f) {
                code = f.code;
            } catch (IOException e) {
                code = 0;
            }
            if (code == 0) network = true;
            if (tried.length() > 0) tried.append("; ");
            tried.append(p.name).append(" — ").append(code == 0 ? "нет связи" : "ответ " + code);
        }
        Failure f = new Failure(network ? 0 : 401, network ? "network" : "rejected");
        f.tried = tried.toString();
        throw f;
    }

    static String expand(Provider p, String key, String model, String note, String classes, String theme)
            throws IOException {
        Failure last = null;
        for (String m : modelsToTry(p, model)) {
            try {
                String text = parseChat(p, http(chatRequest(p, key, m, note, classes, theme)));
                if (!text.isEmpty()) return text;
                last = new Failure(0, "Пустой ответ");
            } catch (Failure f) {
                last = f;
                // 404 — модель недоступна, 429 — закончился лимит, 503 — перегружено: пробуем следующую
                if (f.code != 404 && f.code != 429 && f.code != 503) throw f;
            } catch (JSONException e) {
                last = new Failure(0, "Непонятный ответ");
            }
        }
        throw last != null ? last : new Failure(0, "Нет ответа");
    }

    /** Запрос с файлами, ответ — объект JSON; у Gemini при лимите пробуем запасные модели. */
    static JSONObject completeJson(Provider p, String key, String model, String system, String text,
                                   List<FilePart> files) throws IOException {
        Failure last = null;
        for (String m : modelsToTry(p, model)) {
            try {
                JSONObject o = parseJson(rawText(p, http(fileRequest(p, key, m, system, text, files))));
                if (o != null) return o;
                last = new Failure(0, "Непонятный ответ");
            } catch (Failure f) {
                last = f;
                if (f.code != 404 && f.code != 429 && f.code != 503) throw f;
            } catch (JSONException e) {
                last = new Failure(0, "Непонятный ответ");
            }
        }
        throw last != null ? last : new Failure(0, "Нет ответа");
    }

    static String explain(IOException e, Provider p) {
        String name = p.name;
        if (e instanceof Failure) {
            int code = ((Failure) e).code;
            // Gemini на неверный ключ отвечает 400
            if (code == 401 || code == 403 || (code == 400 && GEMINI.equals(p.kind))) return "Ключ " + name + " не подходит. Проверьте его на главном экране.";
            if (code == 402) return "На балансе " + name + " закончились деньги. Текст расписан без ИИ.";
            if (code == 429) return "Лимит " + name + " исчерпан. Текст расписан без ИИ.";
            if (code == 400 || code == 404)
                return name + " не принял запрос. Попробуйте выбрать другую модель на главном экране.";
        }
        return name + " недоступен (нет интернета?). Текст расписан без ИИ.";
    }

    static String explainDetect(IOException e) {
        if (e instanceof Failure && ((Failure) e).code == -1)
            return "Во втором поле должен быть адрес сервиса (https://…). Для Gemini и других сервисов из списка оставьте его пустым.";
        String tried = e instanceof Failure && !((Failure) e).tried.isEmpty()
                ? " Проверено: " + ((Failure) e).tried + "." : "";
        if (!(e instanceof Failure) || ((Failure) e).code == 0)
            return "Не удалось проверить ключ: нет связи с сервисом. Проверьте интернет и попробуйте ещё раз." + tried;
        return "Ключ не принят. Проверьте, что он скопирован целиком." + tried;
    }

    private static String http(Request r) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(r.url).openConnection();
        try {
            c.setRequestMethod(r.body == null ? "GET" : "POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(r.timeout);
            for (Map.Entry<String, String> h : r.headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (r.body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = r.body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(bytes);
                }
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
        } catch (Failure f) {
            throw f;
        } catch (IOException e) {
            throw new Failure(0, "network");
        } finally {
            c.disconnect();
        }
    }

    private Ai() {
    }
}
