package kz.nikolaevka.raspisanie;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;

/** Запросы к BilimClass (те же, что делает сайт bilimclass.kz). */
final class BilimApi {

    static final String API = "https://api.bilimclass.kz";

    /** Ошибка ответа сервера (с кодом HTTP). */
    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super("HTTP " + code);
            this.code = code;
        }

        /** Вход устарел или недействителен. */
        boolean isAuth() {
            return code == 401 || code == 403 || code == 419 || code == 426;
        }
    }

    /** Новый пропуск после продления входа. */
    static final class Tokens {
        final String access;
        final String refresh;

        Tokens(String access, String refresh) {
            this.access = access;
            this.refresh = refresh;
        }
    }

    /** Расписание с темами и ДЗ на неделю, в которую входит monday. */
    static String fetchWeek(String token, String schoolId, LocalDate monday) throws IOException {
        int eduYear = eduYear(monday);
        String url = API + "/api/v4/os/frontoffice/dashboard/teacher?schoolId=" + schoolId
                + "&eduYear=" + eduYear + "&date=" + monday;
        String body = request("GET", url, token, null);
        try {
            // Проверяем, что ответ похож на расписание.
            new JSONObject(body).getJSONObject("data").getJSONArray("days");
        } catch (JSONException e) {
            throw new IOException("Неожиданный ответ BilimClass");
        }
        return body;
    }

    /** Продлить вход по refresh-токену. */
    static Tokens refresh(String oldToken, String refreshToken) throws IOException {
        JSONObject req = new JSONObject();
        try {
            req.put("refreshToken", refreshToken);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        String body = request("POST", API + "/api/v2/os/refresh_token", oldToken, req.toString());
        try {
            JSONObject o = new JSONObject(body);
            if (!o.has("access_token") && o.optJSONObject("data") != null) o = o.getJSONObject("data");
            String access = o.optString("access_token", "");
            String refresh = o.optString("refresh_token", "");
            if (access.isEmpty()) throw new HttpError(401);
            return new Tokens(access, refresh.isEmpty() ? refreshToken : refresh);
        } catch (JSONException e) {
            throw new HttpError(401);
        }
    }

    /** Через сколько секунд истекает токен (по полю exp внутри него); -1, если не удалось узнать. */
    static long secondsLeft(String token, long nowEpochSec) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return -1;
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long exp = new JSONObject(json).optLong("exp", 0);
            return exp == 0 ? -1 : exp - nowEpochSec;
        } catch (RuntimeException | JSONException e) {
            return -1;
        }
    }

    /** Файл для отправки вместе с домашкой. */
    static final class Upload {
        final String name;
        final String mime;
        final byte[] data;

        Upload(String name, String mime, byte[] data) {
            this.name = name;
            this.mime = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
            this.data = data;
        }
    }

    static int eduYear(LocalDate date) {
        return date.getMonthValue() >= 8 ? date.getYear() : date.getYear() - 1;
    }

    /**
     * Сохранить домашнее задание урока (как кнопка «Сохранить» на сайте).
     * Текст заменяет прежний, файлы добавляются к уже прикреплённым.
     */
    static void saveHomework(String token, String schoolId, int eduYear, String scheduleUuid,
                             String body, java.util.List<Upload> files) throws IOException {
        String boundary = "----RaspisanieBoundary" + System.nanoTime();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        formField(out, boundary, "schoolId", schoolId);
        formField(out, boundary, "eduYear", String.valueOf(eduYear));
        formField(out, boundary, "scheduleUuid", scheduleUuid);
        formField(out, boundary, "body", body);
        formField(out, boundary, "_method", "PUT");
        if (files != null) {
            for (Upload f : files) {
                String head = "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files[]\"; filename=\""
                        + f.name.replace("\"", "'").replace("\r", " ").replace("\n", " ") + "\"\r\n"
                        + "Content-Type: " + f.mime + "\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(f.data);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpURLConnection c = open("POST", API + "/api/v4/os/frontoffice/simple-homework", token, schoolId);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setFixedLengthStreamingMode(out.size());
            try (OutputStream os = c.getOutputStream()) {
                out.writeTo(os);
            }
            finish(c);
        } finally {
            c.disconnect();
        }
    }

    /** Удалить файл, прикреплённый к домашке. */
    static void deleteFile(String token, String schoolId, int eduYear, long fileId) throws IOException {
        // Сайт отправляет DELETE с JSON; на всякий случай используем принятый у сервера
        // способ «POST + _method=DELETE», который работает с любым HTTP-клиентом.
        String form = "schoolId=" + enc(schoolId) + "&eduYear=" + eduYear + "&fileId=" + fileId + "&_method=DELETE";
        byte[] bytes = form.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = open("POST", API + "/api/v4/os/frontoffice/simple-homework/delete-file", token, schoolId);
        try {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            finish(c);
        } finally {
            c.disconnect();
        }
    }

    private static void formField(ByteArrayOutputStream out, String boundary, String name, String value)
            throws IOException {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        out.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static HttpURLConnection open(String method, String url, String token, String schoolId)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Localization", "ru");
        if (schoolId != null) c.setRequestProperty("x-school-id", schoolId);
        if (token != null && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        return c;
    }

    private static String finish(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new HttpError(code);
        try (InputStream in = c.getInputStream()) {
            return readAll(in);
        }
    }

    /** GET с дополнительными заголовками (для сервиса журнала нужен свой пропуск и «external: 1»). */
    static JSONObject getJson(String url, String token, String schoolId, java.util.Map<String, String> extra)
            throws IOException {
        HttpURLConnection c = open("GET", url, token, schoolId);
        try {
            if (extra != null) for (java.util.Map.Entry<String, String> e : extra.entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            String body = finish(c);
            try {
                return new JSONObject(body);
            } catch (JSONException e) {
                throw new IOException("Неожиданный ответ BilimClass");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String request(String method, String url, String token, String jsonBody) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Accept-Language", "ru");
            if (token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (jsonBody != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpError(code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private BilimApi() {
    }
}
