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
        int eduYear = monday.getMonthValue() >= 8 ? monday.getYear() : monday.getYear() - 1;
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
