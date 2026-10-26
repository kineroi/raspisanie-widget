package kz.nikolaevka.raspisanie;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Загрузка журналов из BilimClass (только чтение) и хранение успеваемости на телефоне. */
final class Journals {

    enum Result { OK, NOT_CONNECTED, NEED_LOGIN, NO_NETWORK, NO_JOURNALS }

    private static final String FILE = "grades.json";
    private static final String PREFS = "journals";
    private static final String K_LIST = "list";
    private static final long REFRESH_EVERY_MS = 6L * 60 * 60 * 1000;
    private static final Object LOCK = new Object();

    /** Загруженная успеваемость. */
    static final class Data {
        long updatedAt;
        List<Grades.Journal> journals = new ArrayList<>();
        List<Grades.Lesson> lessons = new ArrayList<>();
    }

    private static final class Info {
        String uuid;
        long groupId;
        long subjectId;
        String cls;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Мои журналы — из уроков в расписании; найденные раньше сохраняются (на каникулах уроков нет). */
    private static List<Info> discover(Context c) {
        Map<String, Info> map = new LinkedHashMap<>();
        try {
            JSONArray stored = new JSONArray(prefs(c).getString(K_LIST, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject o = stored.getJSONObject(i);
                Info in = new Info();
                in.uuid = o.getString("uuid");
                in.groupId = o.getLong("groupId");
                in.subjectId = o.optLong("subjectId");
                in.cls = o.optString("cls");
                map.put(in.uuid, in);
            }
        } catch (Exception ignored) {
            // нет сохранённого списка
        }
        JSONArray weeks = Bilim.cachedWeeksRaw(c);
        for (int w = 0; w < weeks.length(); w++) {
            JSONObject data = weeks.optJSONObject(w) == null ? null : weeks.optJSONObject(w).optJSONObject("data");
            JSONArray rows = data == null ? null : data.optJSONArray("rows");
            for (int r = 0; rows != null && r < rows.length(); r++) {
                JSONObject row = rows.optJSONObject(r);
                if (row == null) continue;
                Iterator<String> keys = row.keys();
                while (keys.hasNext()) {
                    JSONArray arr = row.optJSONArray(keys.next());
                    for (int k = 0; arr != null && k < arr.length(); k++) {
                        JSONObject l = arr.optJSONObject(k);
                        if (l == null) continue;
                        JSONObject subj = l.optJSONObject("subject");
                        JSONObject cls = l.optJSONObject("class");
                        if (subj == null || cls == null || subj.optBoolean("isAdditional")) continue;
                        String uuid = l.optString("educationProgramSubjectUuid", subj.optString("uuid", ""));
                        if (uuid.isEmpty() || "null".equals(uuid)) continue;
                        Info in = new Info();
                        in.uuid = uuid;
                        in.groupId = cls.optLong("groupId");
                        in.subjectId = subj.optLong("subjectId");
                        in.cls = BilimData.compactClass(cls.optString("label"));
                        map.put(uuid, in);
                    }
                }
            }
        }
        JSONArray out = new JSONArray();
        try {
            for (Info in : map.values()) {
                out.put(new JSONObject().put("uuid", in.uuid).put("groupId", in.groupId)
                        .put("subjectId", in.subjectId).put("cls", in.cls));
            }
        } catch (Exception ignored) {
            // не удалось сохранить — ничего страшного
        }
        prefs(c).edit().putString(K_LIST, out.toString()).apply();
        return new ArrayList<>(map.values());
    }

    /** Загрузить успеваемость из BilimClass. Вызывать НЕ из главного потока. */
    static Result refresh(Context context) {
        final Context c = context.getApplicationContext();
        synchronized (LOCK) {
            if (!Bilim.isConnected(c)) return Result.NOT_CONNECTED;
            final List<Info> infos = discover(c);
            if (infos.isEmpty()) return Result.NO_JOURNALS;
            final String today = LocalDate.now().toString();
            final LocalDate now = LocalDate.now();
            final int eduYear = BilimApi.eduYear(now);
            final Data result = new Data();
            try {
                Bilim.withToken(c, (token, school) -> {
                    result.journals.clear();
                    result.lessons.clear();
                    JSONObject ui = BilimApi.getJson(BilimApi.API + "/api/v2/os/user-info", token, school, null);
                    JSONObject user = ui.optJSONObject("data") != null ? ui.optJSONObject("data") : ui;
                    String chat = user.optString("chatToken", "");
                    long userId = user.optLong("userId");
                    if (chat.isEmpty() || userId == 0) throw new IOException("no chat token");
                    Map<String, String> chatHeaders = new HashMap<>();
                    chatHeaders.put("external", "1");
                    String host = eduYear <= 2025 ? "https://journal-service-past.bilimclass.kz"
                            : "https://journal-service.bilimclass.kz";
                    for (Info in : infos) {
                        try {
                            JSONObject pr = BilimApi.getJson(BilimApi.API + "/api/v4/os/frontoffice/journal/period-list/"
                                    + in.uuid + "?schoolId=" + school + "&eduYear=" + eduYear, token, school, null);
                            JSONArray pa = pr.optJSONArray("data");
                            List<Grades.Period> periods = new ArrayList<>();
                            for (int i = 0; pa != null && i < pa.length(); i++) {
                                JSONObject p = pa.getJSONObject(i);
                                Grades.Period gp = new Grades.Period();
                                gp.uuid = p.optString("uuid");
                                gp.n = p.optInt("period");
                                gp.start = Grades.dmy(p.getString("periodStart"));
                                gp.end = Grades.dmy(p.getString("periodEnd"));
                                periods.add(gp);
                            }
                            Grades.Period period = Grades.pickPeriod(periods, today);
                            if (period == null) continue;
                            JSONObject quarter = BilimApi.getJson(BilimApi.API + "/api/v4/os/frontoffice/journal/quarter/"
                                    + in.uuid + "?schoolId=" + school + "&eduYear=" + eduYear
                                    + "&educationProgramPeriodUuid=" + period.uuid, token, school, null);
                            JSONObject q = quarter.optJSONObject("data");
                            long subjectId = q != null && q.optLong("subjectId") != 0 ? q.optLong("subjectId") : in.subjectId;
                            JSONObject marks = BilimApi.getJson(host + "/journal?schoolId=" + school + "&eduYear=" + eduYear
                                    + "&userId=" + userId + "&groupId=" + in.groupId + "&subjectId=" + subjectId
                                    + "&dateFrom=" + period.start + "T00:00:00.000Z&dateTo=" + period.end + "T00:00:00.000Z",
                                    chat, school, chatHeaders);
                            result.journals.add(Grades.build(in.uuid, in.groupId, in.cls, period, quarter, marks));
                        } catch (org.json.JSONException e) {
                            throw new IOException(e);
                        }
                    }
                    // уроки за четверть нужны только в последнюю неделю четверти
                    for (Grades.Journal j : result.journals) {
                        if (j.period == null || j.period.end.compareTo(today) < 0) continue;
                        if (LocalDate.parse(j.period.end).minusDays(7).isAfter(now)) continue;
                        loadQuarterLessons(token, school, j.period, result.lessons);
                        break;
                    }
                });
            } catch (BilimApi.HttpError e) {
                return e.isAuth() ? Result.NEED_LOGIN : Result.NO_NETWORK;
            } catch (IOException e) {
                return Result.NO_NETWORK;
            }
            Collections.sort(result.journals, (a, b) -> a.cls.compareTo(b.cls));
            try {
                Bilim.writeText(new File(c.getFilesDir(), FILE),
                        Grades.toJson(result.journals, result.lessons, System.currentTimeMillis()).toString());
            } catch (Exception e) {
                return Result.NO_NETWORK;
            }
            Digest.schedule(c);
            return Result.OK;
        }
    }

    private static void loadQuarterLessons(String token, String school, Grades.Period p, List<Grades.Lesson> out)
            throws IOException {
        LocalDate start = LocalDate.parse(p.start), end = LocalDate.parse(p.end);
        for (LocalDate m = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); !m.isAfter(end); m = m.plusWeeks(1)) {
            try {
                Map<LocalDate, Schedule.Day> days = BilimData.parseWeek(new JSONObject(BilimApi.fetchWeek(token, school, m)));
                for (Map.Entry<LocalDate, Schedule.Day> e : days.entrySet()) {
                    LocalDate d = e.getKey();
                    if (d.isBefore(start) || d.isAfter(end)) continue;
                    for (Schedule.Lesson l : e.getValue().lessons) {
                        boolean extra = l.title.startsWith("Кл. час") || l.title.startsWith("Факультатив");
                        for (Schedule.Detail det : l.details) {
                            out.add(new Grades.Lesson(d.toString(), det.cls, det.theme, det.homework, extra));
                        }
                    }
                }
            } catch (org.json.JSONException e) {
                throw new IOException(e);
            }
        }
    }

    static void refreshIfStale(Context c) {
        if (System.currentTimeMillis() - load(c).updatedAt > REFRESH_EVERY_MS) refresh(c);
    }

    /** Сохранённая успеваемость (пустая, если ещё не загружалась). */
    static Data load(Context c) {
        Data d = new Data();
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return d;
        try {
            JSONObject o = new JSONObject(Bilim.readText(f));
            d.updatedAt = o.optLong("updatedAt");
            d.journals = Grades.journalsFromJson(o);
            d.lessons = Grades.lessonsFromJson(o);
        } catch (Exception ignored) {
            // повреждённый файл — как будто не загружалось
        }
        return d;
    }

    /** Удалить всё (при выходе из BilimClass). */
    static void clear(Context c) {
        //noinspection ResultOfMethodCallIgnored
        new File(c.getFilesDir(), FILE).delete();
        prefs(c).edit().clear().apply();
    }

    private Journals() {
    }
}
