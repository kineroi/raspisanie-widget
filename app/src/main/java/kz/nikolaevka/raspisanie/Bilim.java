package kz.nikolaevka.raspisanie;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Хранение входа в BilimClass и синхронизация расписания.
 * Всё хранится только в памяти приложения на телефоне.
 */
final class Bilim {

    enum Result { OK, NOT_CONNECTED, NEED_LOGIN, NO_NETWORK }

    private static final String PREFS = "bilim";
    private static final String K_TOKEN = "token";
    private static final String K_REFRESH = "refresh";
    private static final String K_SCHOOL = "school";
    private static final String K_LAST_SYNC = "last_sync";
    private static final String K_NEED_LOGIN = "need_login";
    private static final String CACHE_FILE = "weeks.json";
    private static final int JOB_ID = 42;
    private static final long JOB_PERIOD_MS = 3L * 60 * 60 * 1000;

    private static final Object LOCK = new Object();

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isConnected(Context c) {
        return !prefs(c).getString(K_REFRESH, "").isEmpty();
    }

    static boolean needLogin(Context c) {
        return prefs(c).getBoolean(K_NEED_LOGIN, false);
    }

    static long lastSync(Context c) {
        return prefs(c).getLong(K_LAST_SYNC, 0);
    }

    /** Сохранить вход, полученный со страницы BilimClass. */
    static void saveLogin(Context c, String token, String refresh, String schoolId) {
        prefs(c).edit()
                .putString(K_TOKEN, token)
                .putString(K_REFRESH, refresh)
                .putString(K_SCHOOL, schoolId)
                .putBoolean(K_NEED_LOGIN, false)
                .apply();
        schedule(c);
    }

    /** Выйти: удалить вход и сохранённое расписание. */
    static void logout(Context c) {
        // нейросеть — не часть входа в BilimClass, её настройки сохраняем
        SharedPreferences p = prefs(c);
        Map<String, String> ai = new HashMap<>();
        for (String k : AI_KEYS) ai.put(k, p.getString(k, ""));
        SharedPreferences.Editor e = p.edit().clear();
        for (Map.Entry<String, String> kv : ai.entrySet()) if (!kv.getValue().isEmpty()) e.putString(kv.getKey(), kv.getValue());
        e.apply();
        Journals.clear(c);
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), CACHE_FILE).delete();
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js != null) js.cancel(JOB_ID);
    }

    /** Данные о днях из сохранённого расписания BilimClass (пусто, если нет). */
    static Map<LocalDate, Schedule.Day> cachedDays(Context c) {
        File f = new File(c.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return Collections.emptyMap();
        try {
            return BilimData.parseCache(readFile(f));
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    /** Сохранённые ответы BilimClass по неделям (как пришли), или пустой массив. */
    static JSONArray cachedWeeksRaw(Context c) {
        File f = new File(c.getFilesDir(), CACHE_FILE);
        if (!f.exists()) return new JSONArray();
        try {
            return new JSONArray(readFile(f));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    static String readText(File f) throws IOException {
        return readFile(f);
    }

    static void writeText(File f, String s) throws IOException {
        writeFile(f, s);
    }

    /** Источник расписания для виджета и приложения. */
    static Schedule.Source source(Context c) {
        Map<LocalDate, Schedule.Day> days = cachedDays(c);
        return days.isEmpty() ? Schedule.TEMPLATE : BilimData.source(days);
    }

    /** Загрузить прошлую, текущую и следующую неделю. Вызывать НЕ из главного потока. */
    static Result sync(Context context) {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String refresh = p.getString(K_REFRESH, "");
            String school = p.getString(K_SCHOOL, "");
            if (refresh.isEmpty() || school.isEmpty()) return Result.NOT_CONNECTED;
            String token = p.getString(K_TOKEN, "");

            LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            LocalDate[] weeks = {monday.minusWeeks(1), monday, monday.plusWeeks(1)};

            try {
                long left = BilimApi.secondsLeft(token, System.currentTimeMillis() / 1000);
                if (token.isEmpty() || (left >= 0 && left < 300)) {
                    token = renew(c, token, refresh);
                }
                JSONArray all = new JSONArray();
                for (int i = 0; i < weeks.length; i++) {
                    String body;
                    try {
                        body = BilimApi.fetchWeek(token, school, weeks[i]);
                    } catch (BilimApi.HttpError e) {
                        if (!e.isAuth()) throw e;
                        token = renew(c, token, p.getString(K_REFRESH, refresh));
                        body = BilimApi.fetchWeek(token, school, weeks[i]);
                    }
                    all.put(new JSONObject(body));
                }
                writeFile(new File(c.getFilesDir(), CACHE_FILE), all.toString());
                p.edit().putLong(K_LAST_SYNC, System.currentTimeMillis())
                        .putBoolean(K_NEED_LOGIN, false).apply();
                ScheduleWidget.updateAll(c);
                return Result.OK;
            } catch (BilimApi.HttpError e) {
                if (e.isAuth()) {
                    p.edit().putBoolean(K_NEED_LOGIN, true).apply();
                    ScheduleWidget.updateAll(c);
                    return Result.NEED_LOGIN;
                }
                return Result.NO_NETWORK;
            } catch (Exception e) {
                return Result.NO_NETWORK;
            }
        }
    }

    // ---------- Нейросеть: ключ, сервис, модель (хранится только на телефоне) ----------

    private static final String K_AI_KEY = "ai_key", K_AI_PROVIDER = "ai_provider", K_AI_BASE = "ai_base",
            K_AI_MODEL = "ai_model", K_AI_MODELS = "ai_models";
    private static final String[] AI_KEYS = {K_AI_KEY, K_AI_PROVIDER, K_AI_BASE, K_AI_MODEL, K_AI_MODELS};

    static String aiKey(Context c) {
        return prefs(c).getString(K_AI_KEY, "");
    }

    static String aiModel(Context c) {
        return prefs(c).getString(K_AI_MODEL, "");
    }

    static String aiBase(Context c) {
        return prefs(c).getString(K_AI_BASE, "");
    }

    static List<String> aiModels(Context c) {
        String s = prefs(c).getString(K_AI_MODELS, "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split("\n")));
    }

    /** Подключённый сервис (или null, если ключа нет). */
    static Ai.Provider aiProvider(Context c) {
        SharedPreferences p = prefs(c);
        return Ai.resolve(p.getString(K_AI_KEY, ""), p.getString(K_AI_PROVIDER, ""), p.getString(K_AI_BASE, ""));
    }

    /** Сохранить проверенный ключ вместе с сервисом и моделями. */
    static void setAi(Context c, String key, Ai.Detected d) {
        prefs(c).edit()
                .putString(K_AI_KEY, key.trim())
                .putString(K_AI_PROVIDER, d.providerId)
                .putString(K_AI_BASE, Ai.CUSTOM.equals(d.providerId) ? d.base : "")
                .putString(K_AI_MODEL, d.model)
                .putString(K_AI_MODELS, String.join("\n", d.models))
                .apply();
    }

    static void setAiModel(Context c, String model) {
        prefs(c).edit().putString(K_AI_MODEL, model == null ? "" : model).apply();
    }

    static void setAiModels(Context c, String providerId, List<String> models) {
        prefs(c).edit().putString(K_AI_PROVIDER, providerId).putString(K_AI_MODELS, String.join("\n", models)).apply();
    }

    static void clearAi(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : AI_KEYS) e.remove(k);
        e.apply();
    }

    // ---------- Домашнее задание ----------

    /** Действие, которому нужен действующий пропуск. */
    interface Call {
        void run(String token, String schoolId) throws IOException;
    }

    /**
     * Выполнить запрос к BilimClass с действующим пропуском; при устаревшем пропуске —
     * продлить и повторить один раз. Вызывать НЕ из главного потока.
     */
    static void withToken(Context context, Call call) throws IOException {
        Context c = context.getApplicationContext();
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            String refresh = p.getString(K_REFRESH, "");
            String school = p.getString(K_SCHOOL, "");
            if (refresh.isEmpty() || school.isEmpty()) throw new BilimApi.HttpError(401);
            String token = p.getString(K_TOKEN, "");
            long left = BilimApi.secondsLeft(token, System.currentTimeMillis() / 1000);
            if (token.isEmpty() || (left >= 0 && left < 300)) token = renewOrFlag(c, token, refresh);
            try {
                call.run(token, school);
            } catch (BilimApi.HttpError e) {
                if (!e.isAuth()) throw e;
                token = renewOrFlag(c, token, p.getString(K_REFRESH, refresh));
                call.run(token, school);
            }
        }
    }

    private static String renewOrFlag(Context c, String token, String refresh) throws IOException {
        try {
            return renew(c, token, refresh);
        } catch (BilimApi.HttpError e) {
            if (e.isAuth()) prefs(c).edit().putBoolean(K_NEED_LOGIN, true).apply();
            throw e;
        }
    }

    private static String renew(Context c, String token, String refresh) throws IOException {
        BilimApi.Tokens t = BilimApi.refresh(token, refresh);
        prefs(c).edit().putString(K_TOKEN, t.access).putString(K_REFRESH, t.refresh).apply();
        return t.access;
    }

    /** Фоновое обновление раз в ~3 часа, когда есть интернет. */
    static void schedule(Context c) {
        if (!isConnected(c)) return;
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null || js.getPendingJob(JOB_ID) != null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJob.class))
                .setPeriodic(JOB_PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    private static String readFile(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
    }

    private Bilim() {
    }
}
