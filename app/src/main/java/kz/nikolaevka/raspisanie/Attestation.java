package kz.nikolaevka.raspisanie;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Аттестация: разделы папки, распознанный нейросетью документ → папка и имя файла,
 * отправка на Google Диск через скрипт Google пользователя (assets/attestation.gs).
 * Тот же смысл, что desktop/src/shared/attestation.js в программе для Windows.
 */
final class Attestation {

    static final class Section {
        final String id, folder, name, word;
        /** Есть ли внутри папки по уровням. */
        final boolean levels;

        Section(String id, String folder, String name, String word, boolean levels) {
            this.id = id;
            this.folder = folder;
            this.name = name;
            this.word = word;
            this.levels = levels;
        }
    }

    static final class Level {
        final String id, folder, name;

        Level(String id, String folder, String name) {
            this.id = id;
            this.folder = folder;
            this.name = name;
        }
    }

    static final Section[] SECTIONS = {
            new Section("courses", "1. Курсы повышения квалификации", "Курсы повышения квалификации", "Сертификат", false),
            new Section("awards", "2. Грамоты и благодарности", "Грамоты и благодарности", "Грамота", true),
            new Section("contests", "3. Конкурсы педагога", "Конкурсы педагога", "Диплом", true),
            new Section("students", "4. Достижения учеников", "Достижения учеников", "Диплом ученика", true),
            new Section("lessons", "5. Открытые уроки и выступления", "Открытые уроки и выступления", "Сертификат", false),
            new Section("publications", "6. Публикации и методические разработки", "Публикации и методические разработки", "Публикация", false),
            new Section("ozp", "7. ОЗП и категория", "ОЗП и категория", "Документ", false),
            new Section("other", "8. Другое", "Другое", "Документ", false),
    };

    /** Республиканский и международный уровни лежат в одной папке. */
    static final Level[] LEVELS = {
            new Level("school", "Школьный", "Школьный"),
            new Level("district", "Районный", "Районный"),
            new Level("region", "Областной", "Областной"),
            new Level("national", "Республиканский и международный", "Республиканский"),
            new Level("international", "Республиканский и международный", "Международный"),
    };

    static final String ROOT_FOLDER = "Аттестация";
    /** Больше нейросеть в одном запросе не примет. */
    static final long MAX_FILE_BYTES = 15L * 1024 * 1024;

    static final String SYSTEM =
            "Ты помогаешь учителю школы в Казахстане собрать документы для аттестации педагога. "
                    + "Тебе дают один документ (грамоту, благодарственное письмо, сертификат, диплом, приказ и т.п.) — на русском, казахском или английском.\n"
                    + "Верни ТОЛЬКО объект JSON без пояснений:\n"
                    + "{\"section\": один из: \"courses\" (курсы повышения квалификации, обучение, семинары с сертификатом об обучении), "
                    + "\"awards\" (грамоты, благодарности, благодарственные письма самому педагогу), "
                    + "\"contests\" (дипломы и сертификаты педагога за участие или победу в конкурсах, олимпиадах для учителей), "
                    + "\"students\" (достижения учеников: дипломы учеников, а также документы педагогу за подготовку победителя или призёра), "
                    + "\"lessons\" (открытые уроки, мастер-классы, выступления на семинарах и конференциях), "
                    + "\"publications\" (публикации, статьи, методические разработки, свидетельства о публикации), "
                    + "\"ozp\" (оценка знаний педагогов, сертификаты ОЗП/НКТ, приказы о присвоении категории), "
                    + "\"other\" (всё остальное),\n"
                    + " \"level\": \"school\", \"district\", \"region\", \"national\", \"international\" или \"\" — уровень мероприятия или того, кто выдал "
                    + "(школа — school; район, районный отдел образования — district; область, управление образования области — region; "
                    + "министерство, республиканский — national; международный — international),\n"
                    + " \"date\": \"дата выдачи в формате ГГГГ-ММ-ДД или пусто, если её нет\",\n"
                    + " \"kind\": \"вид документа по-русски одним-тремя словами: Грамота, Благодарственное письмо, Сертификат, Диплом I степени и т.п.\",\n"
                    + " \"title\": \"за что или какое мероприятие, по-русски, коротко — до 8 слов, без кавычек\",\n"
                    + " \"issuer\": \"кто выдал, коротко\"}\n"
                    + "Если полной даты в документе нет, оставь \"date\" пустым. Ничего не придумывай.";

    /** Поля документа (что распознала нейросеть или поправил учитель). */
    static final class Meta {
        String section = "other", level = "", date = "", kind = "", title = "", issuer = "";
    }

    static Section sectionById(String id) {
        for (Section s : SECTIONS) if (s.id.equals(id)) return s;
        return SECTIONS[SECTIONS.length - 1];
    }

    static Level levelById(String id) {
        for (Level l : LEVELS) if (l.id.equals(id)) return l;
        return null;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[«»\"]", "").replaceAll("\\s+", " ").trim();
    }

    private static final Pattern ISO_DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    /** Ответ нейросети → поля документа (неизвестное заменяется на безопасное). */
    static Meta parseClassification(JSONObject o) {
        Meta m = new Meta();
        if (o == null) o = new JSONObject();
        m.section = sectionById(o.optString("section", "")).id;
        String level = o.optString("level", "");
        m.level = levelById(level) != null ? level : "";
        String date = o.optString("date", "");
        m.date = ISO_DATE.matcher(date).matches() ? date : "";
        String kind = clean(o.optString("kind", ""));
        m.kind = kind.isEmpty() ? sectionById(m.section).word : kind;
        m.title = clean(o.optString("title", ""));
        m.issuer = clean(o.optString("issuer", ""));
        return m;
    }

    /** Символы, которые нельзя использовать в именах файлов. */
    private static String safe(String s) {
        return (s == null ? "" : s).replaceAll("[<>:\"/\\\\|?*\\x00-\\x1f]", " ").replaceAll("\\s+", " ")
                .replaceAll("[ .]+$", "").trim();
    }

    static final class Target {
        /** Папки внутри «Аттестации». */
        final List<String> parts;
        final String name;

        Target(List<String> parts, String name) {
            this.parts = parts;
            this.name = name;
        }

        String path() {
            StringBuilder sb = new StringBuilder(ROOT_FOLDER);
            for (String p : parts) sb.append(" / ").append(p);
            return sb.append(" / ").append(name).toString();
        }
    }

    /**
     * Папка и имя файла. Пример: [«2. Грамоты и благодарности», «Районный»],
     * «2026-10-15 Грамота — Лучший урок.pdf». ext — с точкой.
     */
    static Target target(Meta meta, String ext) {
        Section s = sectionById(meta.section);
        List<String> parts = new ArrayList<>();
        parts.add(s.folder);
        if (s.levels) {
            Level l = levelById(meta.level);
            parts.add(l != null ? l.folder : "Уровень не указан");
        }
        String kind = safe(meta.kind);
        if (kind.isEmpty()) kind = s.word;
        String title = safe(meta.title);
        String name = (meta.date.isEmpty() ? "Без даты" : meta.date) + " " + kind + (title.isEmpty() ? "" : " — " + title);
        if (name.length() > 120) name = name.substring(0, 120).trim();
        return new Target(parts, name + (ext == null ? "" : ext.toLowerCase()));
    }

    /** Расширение файла с точкой («.pdf») по имени, а если его нет — по типу. */
    static String extension(String name, String mime) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) return name.substring(dot).toLowerCase();
        if (mime == null) return "";
        switch (mime) {
            case "application/pdf": return ".pdf";
            case "image/jpeg": return ".jpg";
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case DOCX: return ".docx";
            default: return "";
        }
    }

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Тип для нейросети: картинки и PDF уходят как есть, docx — текстом, остальное не распознаём. */
    static String aiMime(String ext) {
        switch (ext) {
            case ".pdf": return "application/pdf";
            case ".jpg":
            case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".webp": return "image/webp";
            default: return null;
        }
    }

    /** Текст из docx: абзацы из word/document.xml без разметки. */
    static String docxText(InputStream in) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(e.getName())) continue;
                String xml = new String(readAll(zip, MAX_FILE_BYTES), StandardCharsets.UTF_8);
                return xmlText(xml);
            }
        }
        return "";
    }

    static String xmlText(String xml) {
        String t = xml.replaceAll("</w:p>", "\n").replaceAll("<w:tab/>", " ").replaceAll("<[^>]+>", "");
        t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    static byte[] readAll(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) throw new IOException("too-big");
        }
        return out.toByteArray();
    }

    // ---------- Google Диск через скрипт пользователя ----------

    private static final String PREFS = "attestation", K_URL = "script_url", K_SECRET = "secret";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String scriptUrl(Context c) {
        return prefs(c).getString(K_URL, "");
    }

    /** Пароль, который скрипт запоминает при первом подключении и потом сверяет. */
    static String secret(Context c) {
        String s = prefs(c).getString(K_SECRET, "");
        if (s.isEmpty()) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            s = sb.toString();
            prefs(c).edit().putString(K_SECRET, s).apply();
        }
        return s;
    }

    static void setScriptUrl(Context c, String url) {
        prefs(c).edit().putString(K_URL, url == null ? "" : url.trim()).apply();
    }

    private static final Pattern SCRIPT_URL =
            Pattern.compile("^https://script\\.google\\.com/(a/macros/[^/\\s]+|macros)/s/[\\w-]+/exec$");

    /** Ссылка веб-приложения из «Развернуть»: https://script.google.com/macros/s/…/exec. */
    static boolean isScriptUrl(String url) {
        return url != null && SCRIPT_URL.matcher(url.trim()).matches();
    }

    /** Ошибка скрипта, понятная учителю. */
    static final class DriveError extends IOException {
        DriveError(String message) {
            super(message);
        }
    }

    static JSONObject pingRequest(String secret) throws JSONException {
        return new JSONObject().put("action", "ping").put("secret", secret);
    }

    static JSONObject saveRequest(String secret, Target t, String mime, String base64) throws JSONException {
        JSONArray parts = new JSONArray();
        for (String p : t.parts) parts.put(p);
        return new JSONObject().put("action", "save").put("secret", secret).put("root", ROOT_FOLDER)
                .put("parts", parts).put("name", t.name).put("mime", mime).put("data", base64);
    }

    /** Ответ скрипта → объект; ошибки — понятным текстом. */
    static JSONObject parseResponse(String body) throws DriveError {
        JSONObject o;
        try {
            o = new JSONObject(body == null ? "" : body.trim());
        } catch (JSONException e) {
            // вместо ответа скрипта пришла страница входа Google: доступ не «Все»
            throw new DriveError("Скрипт не ответил. Проверьте, что при развёртывании выбран доступ «Все» "
                    + "и ссылка заканчивается на /exec.");
        }
        if (o.optBoolean("ok")) return o;
        String err = o.optString("error", "");
        if ("secret".equals(err)) {
            throw new DriveError("Скрипт уже подключён к другому телефону. В скрипте откройте «Настройки проекта» → "
                    + "«Свойства скрипта», удалите SECRET и подключите снова.");
        }
        throw new DriveError("Google Диск не принял файл: " + (err.isEmpty() ? "неизвестная ошибка" : err));
    }

    /** Запрос к скрипту (НЕ из главного потока). Google отвечает переадресацией — идём по ней сами. */
    static JSONObject call(String url, JSONObject request) throws IOException {
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        String body;
        try {
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String location = c.getHeaderField("Location");
            if (code >= 300 && code < 400 && location != null) {
                body = get(location);
            } else if (code == 401 || code == 403) {
                throw new DriveError("Нет доступа к скрипту. При развёртывании выберите «Запуск от имени: я» и доступ «Все».");
            } else if (code == 404) {
                throw new DriveError("Скрипт не найден. Проверьте ссылку: она должна заканчиваться на /exec.");
            } else if (code < 200 || code >= 300) {
                throw new DriveError("Google ответил ошибкой " + code + ". Попробуйте ещё раз.");
            } else {
                try (InputStream in = c.getInputStream()) {
                    body = new String(readAll(in, 1 << 20), StandardCharsets.UTF_8);
                }
            }
        } catch (DriveError e) {
            throw e;
        } catch (IOException e) {
            throw new DriveError("Нет связи с Google. Проверьте интернет и попробуйте ещё раз.");
        } finally {
            c.disconnect();
        }
        return parseResponse(body);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new DriveError("Google ответил ошибкой " + code + ". Попробуйте ещё раз.");
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in, 1 << 20), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private Attestation() {
    }
}
