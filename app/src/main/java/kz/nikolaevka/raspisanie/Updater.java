package kz.nikolaevka.raspisanie;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Проверка обновлений: сравнивает номер установленной версии с последним выпуском на GitHub,
 * скачивает новый APK и открывает экран установки Android.
 */
final class Updater {

    private static final String LATEST =
            "https://api.github.com/repos/kineroi/raspisanie-widget/releases/latest";
    private static final String APK_NAME = "raspisanie-widget.apk";
    private static final long CHECK_EVERY_MS = 6L * 60 * 60 * 1000;

    private static final String PREFS = "updater";
    private static final String K_LAST_CHECK = "last_check";
    private static final String K_VERSION = "version";
    private static final String K_CODE = "code";
    private static final String K_URL = "url";
    private static final String K_DOWNLOAD = "download_id";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static long installedCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static String installedName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** Номер сборки из тега выпуска: «v3.0.12» → 12; «v5» → 5. */
    static long codeFromTag(String tag) {
        Matcher m = Pattern.compile("(\\d+)\\s*$").matcher(tag == null ? "" : tag.trim());
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    /** Есть ли уже найденное обновление новее установленной версии (без обращения к сети). */
    static boolean hasUpdate(Context c) {
        return prefs(c).getLong(K_CODE, 0) > installedCode(c) && !prefs(c).getString(K_URL, "").isEmpty();
    }

    static String updateVersion(Context c) {
        return prefs(c).getString(K_VERSION, "");
    }

    /**
     * Проверить GitHub (не чаще раза в 6 часов, если force=false). Вызывать НЕ из главного потока.
     * Возвращает true, если есть обновление.
     */
    static boolean check(Context c, boolean force) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        if (!force && now - p.getLong(K_LAST_CHECK, 0) < CHECK_EVERY_MS) return hasUpdate(c);
        try {
            JSONObject rel = new JSONObject(get(LATEST));
            String tag = rel.optString("tag_name", "");
            String url = "";
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (APK_NAME.equals(a.optString("name"))) url = a.optString("browser_download_url", "");
                }
            }
            p.edit().putLong(K_LAST_CHECK, now)
                    .putLong(K_CODE, codeFromTag(tag))
                    .putString(K_VERSION, tag.replaceFirst("^v", ""))
                    .putString(K_URL, url)
                    .apply();
        } catch (Exception e) {
            // нет интернета — проверим в следующий раз
        }
        return hasUpdate(c);
    }

    /** Начать загрузку нового APK; возвращает номер загрузки. */
    static long startDownload(Context c) {
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        File old = new File(dir, APK_NAME);
        if (old.exists()) //noinspection ResultOfMethodCallIgnored
            old.delete();
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(prefs(c).getString(K_URL, "")))
                .setTitle("Моё расписание — обновление")
                .setDescription("Версия " + updateVersion(c))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, APK_NAME);
        long id = dm.enqueue(req);
        prefs(c).edit().putLong(K_DOWNLOAD, id).apply();
        return id;
    }

    /** -1 ошибка, 0..100 процент, 101 — скачано. */
    static int progress(Context c, long id) {
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        try (Cursor cur = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cur == null || !cur.moveToFirst()) return -1;
            int status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 101;
            if (status == DownloadManager.STATUS_FAILED) return -1;
            long done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            return total > 0 ? (int) (done * 100 / total) : 0;
        }
    }

    /** Открыть экран установки Android для скачанного файла. */
    static boolean openInstaller(Context c, long id) {
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) return false;
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private Updater() {
    }
}
