package kz.nikolaevka.raspisanie;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Журнал посещаемости: учебные дни, отметки, предупреждения, выгрузка в Word.
 * Журнал — объект JSON того же вида, что в программе для Windows (desktop/src/shared/attendance.js):
 * {classes: [{id, name, t, deleted?, mergedInto?, students: [{id, name, t, …}]}],
 *  marks: {"2026-11-02": {studentId: {r, note?, t}}}, days: {"2026-11-06": {school, t}}}.
 * t — время изменения: журналы телефона и компьютера объединяются по элементам (merge), побеждает более позднее.
 * ФИО учеников не отправляются ни в нейросеть, ни в BilimClass — только в файл на Google Диске учителя.
 */
final class Attendance {

    static final class Reason {
        final String id, code, name;
        /** Уважительная ли причина. */
        final boolean valid;

        Reason(String id, String code, String name, boolean valid) {
            this.id = id;
            this.code = code;
            this.name = name;
            this.valid = valid;
        }
    }

    static final Reason[] REASONS = {
            new Reason("sick", "б", "Болел", true),
            new Reason("free", "осв", "Освобождение", true),
            new Reason("family", "с/о", "По семейным обстоятельствам", true),
            new Reason("absent", "н", "Без уважительной причины", false),
            new Reason("other", "др", "Другое", true),
    };

    /** Учебные четверти 2026/2027 года (с ноября): начало и конец. */
    static final String[][] QUARTERS = {
            {"2026-11-02", "2026-12-27"},
            {"2027-01-11", "2027-03-19"},
            {"2027-03-29", "2027-05-25"},
    };

    /** Праздники Казахстана в учебные дни (и переносы с выходных). Любой день можно переключить вручную. */
    static final List<String> HOLIDAYS = Arrays.asList(
            "2026-12-16", "2027-03-08", "2027-05-01", "2027-05-03", "2027-05-07", "2027-05-09", "2027-05-10");

    static final String FIRST_MONTH = "2026-11", LAST_MONTH = "2027-05";
    private static final String[] MONTHS = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август",
            "сентябрь", "октябрь", "ноябрь", "декабрь"};

    static final int SICK_DAYS = 3;
    static final int ABSENT_COUNT = 2;

    private static final Locale RU = new Locale("ru");

    static Reason reasonById(String id) {
        for (Reason r : REASONS) if (r.id.equals(id)) return r;
        return null;
    }

    /** Часы (в тестах подменяются). */
    static LongSupplier clock = System::currentTimeMillis;

    static JSONObject empty() {
        return normalize(new JSONObject());
    }

    private static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<>();
        if (o == null) return out;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) out.add(it.next());
        return out;
    }

    /**
     * Сохранённый журнал → журнал с нужными полями. Старый вид (offDays/onDays, без времени изменений)
     * переводится в новый с временем 0 — любое новое изменение его перекроет.
     */
    static JSONObject normalize(JSONObject o) {
        try {
            if (o == null) o = new JSONObject();
            JSONArray classes = o.optJSONArray("classes");
            if (classes == null) o.put("classes", classes = new JSONArray());
            for (int i = 0; i < classes.length(); i++) {
                JSONObject c = classes.optJSONObject(i);
                if (c == null) continue;
                if (!c.has("t")) c.put("t", 0L);
                JSONArray st = c.optJSONArray("students");
                if (st == null) c.put("students", st = new JSONArray());
                for (int k = 0; k < st.length(); k++) {
                    JSONObject s = st.optJSONObject(k);
                    if (s != null && !s.has("t")) s.put("t", 0L);
                }
            }
            JSONObject marks = o.optJSONObject("marks");
            if (marks == null) o.put("marks", marks = new JSONObject());
            for (String d : keys(marks)) {
                JSONObject day = marks.optJSONObject(d);
                for (String sid : keys(day)) {
                    JSONObject m = day.optJSONObject(sid);
                    if (m != null && !m.has("t")) m.put("t", 0L);
                }
            }
            JSONObject days = o.optJSONObject("days");
            if (days == null) o.put("days", days = new JSONObject());
            JSONArray off = o.optJSONArray("offDays"), on = o.optJSONArray("onDays");
            for (int i = 0; off != null && i < off.length(); i++) {
                if (!days.has(off.optString(i))) days.put(off.optString(i), new JSONObject().put("school", false).put("t", 0L));
            }
            for (int i = 0; on != null && i < on.length(); i++) {
                if (!days.has(on.optString(i))) days.put(on.optString(i), new JSONObject().put("school", true).put("t", 0L));
            }
            o.remove("offDays");
            o.remove("onDays");
        } catch (JSONException ignored) {
            // ключи — обычные строки, ошибки не будет
        }
        return o;
    }

    /** Не удалён и не слит с другим. */
    static boolean live(JSONObject x) {
        return x != null && !x.optBoolean("deleted") && x.optString("mergedInto", "").isEmpty();
    }

    // ---------- Хранение (только на телефоне) ----------

    private static final String PREFS = "attendance", K_JOURNAL = "journal";

    static JSONObject load(Context c) {
        try {
            return normalize(new JSONObject(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_JOURNAL, "{}")));
        } catch (JSONException e) {
            return empty();
        }
    }

    static void save(Context c, JSONObject j) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_JOURNAL, j.toString()).commit();
    }

    /** Сохранить изменения с экрана: объединяем с сохранённым (вдруг уже пришли отметки с компьютера). */
    static synchronized JSONObject commit(Context c, JSONObject j) {
        JSONObject merged = merge(load(c), j);
        save(c, merged);
        return merged;
    }

    // ---------- Синхронизация с компьютером через Google Диск (скрипт из assets/attestation.gs) ----------

    static final String SYNC_FOLDER = "Моё расписание", SYNC_FILE = "посещаемость.json";

    private static JSONObject request(String action, String secret) throws JSONException {
        return new JSONObject().put("action", action).put("secret", secret).put("folder", SYNC_FOLDER).put("name", SYNC_FILE);
    }

    /**
     * Обменяться отметками с компьютером (НЕ из главного потока). Возвращает true, если на телефон пришло новое.
     * Ошибки — понятным текстом (Attestation.DriveError).
     */
    static boolean sync(Context c) throws IOException {
        String url = Attestation.scriptUrl(c);
        if (url.isEmpty()) throw new Attestation.DriveError("Google Диск не подключён.");
        String secret = Attestation.secret(c);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                JSONObject r = Attestation.call(url, request("journal-get", secret));
                String data = r.optString("data", "");
                JSONObject remote;
                try {
                    remote = data.isEmpty() ? empty() : normalize(new JSONObject(data));
                } catch (JSONException e) {
                    throw new Attestation.DriveError("Файл журнала на Google Диске не читается.");
                }
                JSONObject merged;
                boolean changed;
                synchronized (Attendance.class) {
                    JSONObject local = load(c);
                    merged = merge(local, remote);
                    changed = !same(merged, local);
                    if (changed) save(c, merged);
                }
                if (!data.isEmpty() && same(merged, remote)) return changed;
                try {
                    Attestation.call(url, request("journal-put", secret).put("etag", r.optString("etag", ""))
                            .put("data", merged.toString()));
                    return changed;
                } catch (Attestation.Conflict e) {
                    // журнал только что изменили на компьютере — заберём и объединим ещё раз
                }
            }
        } catch (JSONException e) {
            throw new Attestation.DriveError("Не удалось подготовить запрос.");
        }
        throw new Attestation.DriveError("Журнал одновременно меняют на другом устройстве — попробуйте ещё раз.");
    }

    // ---------- Даты ----------

    static boolean inQuarter(String iso) {
        for (String[] q : QUARTERS) if (iso.compareTo(q[0]) >= 0 && iso.compareTo(q[1]) <= 0) return true;
        return false;
    }

    private static boolean weekday(String iso) {
        int w = LocalDate.parse(iso).getDayOfWeek().getValue();
        return w <= 5;
    }

    /** Учебный ли день без ручных поправок: будни внутри четверти, не праздник. */
    static boolean defaultSchoolDay(String iso) {
        return weekday(iso) && inQuarter(iso) && !HOLIDAYS.contains(iso);
    }

    static boolean isSchoolDay(JSONObject j, String iso) {
        JSONObject e = j.optJSONObject("days").optJSONObject(iso);
        Object school = e == null ? null : e.opt("school");
        return school instanceof Boolean ? (Boolean) school : defaultSchoolDay(iso);
    }

    /** Сделать день учебным или выходным (перенос праздника, карантин). */
    static void setSchoolDay(JSONObject j, String iso, boolean on) {
        try {
            j.optJSONObject("days").put(iso, new JSONObject()
                    .put("school", on == defaultSchoolDay(iso) ? JSONObject.NULL : on).put("t", clock.getAsLong()));
        } catch (JSONException ignored) {
            // ключи — обычные строки
        }
    }

    static List<String> months() {
        List<String> out = new ArrayList<>();
        LocalDate d = LocalDate.parse(FIRST_MONTH + "-01");
        while (true) {
            String s = d.toString().substring(0, 7);
            out.add(s);
            if (s.equals(LAST_MONTH)) return out;
            d = d.plusMonths(1);
        }
    }

    /** «Ноябрь 2026». */
    static String monthTitle(String month) {
        String n = MONTHS[Integer.parseInt(month.substring(5)) - 1];
        return n.substring(0, 1).toUpperCase(RU) + n.substring(1) + " " + month.substring(0, 4);
    }

    /** Месяц журнала для даты (до ноября — ноябрь, после мая — май). */
    static String defaultMonth(String todayIso) {
        String m = todayIso.substring(0, 7);
        if (m.compareTo(FIRST_MONTH) < 0) return FIRST_MONTH;
        if (m.compareTo(LAST_MONTH) > 0) return LAST_MONTH;
        return m;
    }

    /** Учебные дни месяца по порядку. */
    static List<String> schoolDays(JSONObject j, String month) {
        List<String> out = new ArrayList<>();
        for (LocalDate d = LocalDate.parse(month + "-01"); d.toString().startsWith(month); d = d.plusDays(1)) {
            if (isSchoolDay(j, d.toString())) out.add(d.toString());
        }
        return out;
    }

    // ---------- Отметки ----------

    static JSONObject getMark(JSONObject j, String iso, String sid) {
        JSONObject day = j.optJSONObject("marks").optJSONObject(iso);
        JSONObject m = day == null ? null : day.optJSONObject(sid);
        return m != null && reasonById(m.optString("r")) != null ? m : null;
    }

    /** reason = "" — был на уроках (запись остаётся с временем, чтобы при синхронизации отметка не вернулась). */
    static void setMark(JSONObject j, String iso, String sid, String reason, String note) {
        try {
            JSONObject marks = j.optJSONObject("marks");
            JSONObject day = marks.optJSONObject(iso);
            if (day == null) {
                day = new JSONObject();
                marks.put(iso, day);
            }
            JSONObject m = new JSONObject().put("r", reason != null && reasonById(reason) != null ? reason : "")
                    .put("t", clock.getAsLong());
            String t = note == null ? "" : note.trim();
            if ("other".equals(reason) && !t.isEmpty()) m.put("note", t);
            day.put(sid, m);
        } catch (JSONException ignored) {
            // ключи — обычные строки
        }
    }

    static String markCode(JSONObject m) {
        Reason r = m == null ? null : reasonById(m.optString("r"));
        return r == null ? "" : r.code;
    }

    static final class Stats {
        int total, invalid;
    }

    /** Пропуски ученика за месяц: всего и без уважительной причины. */
    static Stats monthStats(JSONObject j, String month, String sid) {
        Stats s = new Stats();
        for (String d : schoolDays(j, month)) {
            JSONObject m = getMark(j, d, sid);
            Reason r = m == null ? null : reasonById(m.optString("r"));
            if (r == null) continue;
            s.total++;
            if (!r.valid) s.invalid++;
        }
        return s;
    }

    // ---------- Классы и ученики ----------

    private static final Pattern CYR = Pattern.compile("[А-Яа-яЁё]");
    private static final String LATIN_FROM = "aeopcxyABEKMHOPCTX", LATIN_TO = "аеорсхуАВЕКМНОРСТХ";
    private static final List<String> LOWER_WORDS = Arrays.asList("оглы", "улы", "кызы", "қызы", "гызы");

    private static String capitalize(String w) {
        String low = w.toLowerCase(RU);
        if (LOWER_WORDS.contains(low)) return low;
        String[] parts = low.split("-", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('-');
            String p = parts[i];
            sb.append(p.isEmpty() ? p : p.substring(0, 1).toUpperCase(RU) + p.substring(1));
        }
        return sb.toString();
    }

    /** ФИО как положено: «ИВАНОВ ИВАН» и «Иванов ИванИванович» → «Иванов Иван Иванович». */
    static String fixName(String s) {
        String t = (s == null ? "" : s).replaceAll("([а-яё])([А-ЯЁ])", "$1 $2").trim();
        StringBuilder out = new StringBuilder();
        for (String w : t.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (CYR.matcher(w).find()) {
                StringBuilder fixed = new StringBuilder();
                for (char ch : w.toCharArray()) {
                    int i = LATIN_FROM.indexOf(ch);
                    fixed.append(i >= 0 ? LATIN_TO.charAt(i) : ch);
                }
                w = fixed.toString();
            }
            if (out.length() > 0) out.append(' ');
            out.append(capitalize(w));
        }
        return out.toString();
    }

    /** «4 класс» → «4 класса». */
    static String classGenitive(String name) {
        String n = name == null ? "" : name.trim();
        return n.toLowerCase(RU).endsWith("класс") ? n + "а" : n;
    }

    private static int classNumber(JSONObject c) {
        Matcher m = Pattern.compile("^(\\d+)").matcher(c.optString("name"));
        return m.find() ? Integer.parseInt(m.group(1)) : 99;
    }

    static List<JSONObject> sortedClasses(JSONObject j) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = j.optJSONArray("classes");
        for (int i = 0; i < a.length(); i++) if (live(a.optJSONObject(i))) out.add(a.optJSONObject(i));
        Collator col = Collator.getInstance(RU);
        out.sort((x, y) -> {
            int d = classNumber(x) - classNumber(y);
            return d != 0 ? d : col.compare(x.optString("name"), y.optString("name"));
        });
        return out;
    }

    static List<JSONObject> sortedStudents(JSONObject cls) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = cls.optJSONArray("students");
        for (int i = 0; a != null && i < a.length(); i++) if (live(a.optJSONObject(i))) out.add(a.optJSONObject(i));
        Collator col = Collator.getInstance(RU);
        out.sort((x, y) -> col.compare(x.optString("name"), y.optString("name")));
        return out;
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static JSONObject addClass(JSONObject j, String name) {
        try {
            String n = name == null ? "" : name.trim();
            JSONObject c = new JSONObject().put("id", newId()).put("name", n.isEmpty() ? "Новый класс" : n)
                    .put("t", clock.getAsLong()).put("students", new JSONArray());
            j.optJSONArray("classes").put(c);
            return c;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static JSONObject addStudent(JSONObject cls, String name) {
        try {
            JSONObject s = new JSONObject().put("id", newId()).put("name", fixName(name)).put("t", clock.getAsLong());
            cls.optJSONArray("students").put(s);
            return s;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void touch(JSONObject item, String key, Object value) {
        try {
            item.put(key, value).put("t", clock.getAsLong());
        } catch (JSONException ignored) {
            // ключи — обычные строки
        }
    }

    /** Переименовать ученика или класс. */
    static void rename(JSONObject item, String name) {
        touch(item, "name", name);
    }

    /** Удалить ученика (его отметки больше нигде не показываются). Ищем во всех классах журнала j. */
    static void removeStudent(JSONObject j, JSONObject cls, String sid) {
        for (JSONObject c : list(j.optJSONArray("classes"))) {
            for (JSONObject s : list(c.optJSONArray("students"))) if (sid.equals(s.optString("id"))) touch(s, "deleted", true);
        }
    }

    static void removeClass(JSONObject j, String cid) {
        JSONArray a = j.optJSONArray("classes");
        for (int i = 0; i < a.length(); i++) if (cid.equals(a.optJSONObject(i).optString("id"))) touch(a.optJSONObject(i), "deleted", true);
    }

    // ---------- Синхронизация (как merge в desktop/src/shared/attendance.js) ----------

    private static String str(JSONObject x, String key) {
        Object v = x.opt(key);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    /** Подпись элемента: чем решать, если время изменения совпало до миллисекунды. */
    private static String sig(JSONObject x) {
        return str(x, "r") + "|" + str(x, "note") + "|" + str(x, "name") + "|" + str(x, "deleted") + "|"
                + str(x, "mergedInto") + "|" + str(x, "school");
    }

    /** Из двух версий одного элемента — более позднюю. */
    static JSONObject newer(JSONObject a, JSONObject b) {
        if (a == null) return b;
        if (b == null) return a;
        long ta = a.optLong("t"), tb = b.optLong("t");
        if (ta != tb) return tb > ta ? b : a;
        return sig(b).compareTo(sig(a)) > 0 ? b : a;
    }

    private static JSONObject copy(JSONObject o) {
        try {
            return new JSONObject(o == null ? "{}" : o.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    /** Объединить два журнала (порядок не важен). */
    static JSONObject merge(JSONObject a, JSONObject b) {
        JSONObject x = normalize(copy(a)), y = normalize(copy(b));
        try {
            Map<String, JSONObject> heads = new TreeMap<>();
            Map<String, Map<String, JSONObject>> students = new HashMap<>();
            for (JSONObject src : new JSONObject[]{x, y}) {
                JSONArray cl = src.optJSONArray("classes");
                for (int i = 0; i < cl.length(); i++) {
                    JSONObject c = cl.optJSONObject(i);
                    if (c == null) continue;
                    String id = c.optString("id");
                    heads.put(id, newer(heads.get(id), c));
                    Map<String, JSONObject> st = students.get(id);
                    if (st == null) students.put(id, st = new TreeMap<>());
                    JSONArray sa = c.optJSONArray("students");
                    for (int k = 0; k < sa.length(); k++) {
                        JSONObject s = sa.optJSONObject(k);
                        if (s != null) st.put(s.optString("id"), newer(st.get(s.optString("id")), s));
                    }
                }
            }
            JSONArray classes = new JSONArray();
            for (Map.Entry<String, JSONObject> e : heads.entrySet()) {
                JSONObject c = copy(e.getValue());
                JSONArray sa = new JSONArray();
                for (JSONObject s : students.get(e.getKey()).values()) sa.put(copy(s));
                c.put("students", sa);
                classes.put(c);
            }
            JSONObject marks = new JSONObject(), days = new JSONObject();
            for (JSONObject src : new JSONObject[]{x, y}) {
                JSONObject sm = src.optJSONObject("marks");
                for (String d : keys(sm)) {
                    JSONObject out = marks.optJSONObject(d);
                    if (out == null) marks.put(d, out = new JSONObject());
                    JSONObject day = sm.optJSONObject(d);
                    for (String sid : keys(day)) out.put(sid, newer(out.optJSONObject(sid), day.optJSONObject(sid)));
                }
                JSONObject sd = src.optJSONObject("days");
                for (String d : keys(sd)) days.put(d, newer(days.optJSONObject(d), sd.optJSONObject(d)));
            }
            JSONObject j = new JSONObject().put("classes", classes).put("marks", marks).put("days", days);
            dedupe(j);
            return j;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<JSONObject> list(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) if (a.optJSONObject(i) != null) out.add(a.optJSONObject(i));
        return out;
    }

    private static String lower(String s) {
        return (s == null ? "" : s).trim().toLowerCase(RU);
    }

    /**
     * Одинаковые классы и ученики (списки загружали и на компьютере, и на телефоне) сливаются в один:
     * остаётся тот, у кого меньше id, у другого — mergedInto. Отметки переносятся.
     */
    private static void dedupe(JSONObject j) throws JSONException {
        List<JSONObject> classes = list(j.optJSONArray("classes"));
        Map<String, List<JSONObject>> groups = new LinkedHashMap<>();
        for (JSONObject c : classes) {
            if (!live(c)) continue;
            String k = lower(c.optString("name"));
            if (!groups.containsKey(k)) groups.put(k, new ArrayList<>());
            groups.get(k).add(c);
        }
        for (List<JSONObject> g : groups.values()) {
            g.sort((p, q) -> p.optString("id").compareTo(q.optString("id")));
            for (int i = 1; i < g.size(); i++) touch(g.get(i), "mergedInto", g.get(0).optString("id"));
        }
        Map<String, JSONObject> byId = new HashMap<>();
        for (JSONObject c : classes) byId.put(c.optString("id"), c);
        for (JSONObject c : classes) {
            if (str(c, "mergedInto").isEmpty()) continue;
            JSONObject to = c;
            for (int i = 0; to != null && !str(to, "mergedInto").isEmpty() && i < 20; i++) to = byId.get(str(to, "mergedInto"));
            if (to == null || !live(to)) continue;
            JSONArray target = to.optJSONArray("students");
            for (JSONObject s : list(c.optJSONArray("students"))) {
                int found = -1;
                for (int i = 0; i < target.length(); i++) if (s.optString("id").equals(target.optJSONObject(i).optString("id"))) found = i;
                if (found < 0) target.put(s);
                else target.put(found, newer(target.optJSONObject(found), s));
            }
            c.put("students", new JSONArray());
        }
        Map<String, String> moved = new HashMap<>();
        for (JSONObject c : classes) {
            if (!live(c)) continue;
            List<JSONObject> st = list(c.optJSONArray("students"));
            st.sort((p, q) -> p.optString("id").compareTo(q.optString("id")));
            Map<String, String> names = new HashMap<>();
            for (JSONObject s : st) {
                if (!live(s)) continue;
                String k = lower(s.optString("name"));
                if (names.containsKey(k)) touch(s, "mergedInto", names.get(k));
                else names.put(k, s.optString("id"));
            }
            for (JSONObject s : st) if (!str(s, "mergedInto").isEmpty()) moved.put(s.optString("id"), str(s, "mergedInto"));
        }
        JSONObject marks = j.optJSONObject("marks");
        for (String d : keys(marks)) {
            JSONObject day = marks.optJSONObject(d);
            for (Map.Entry<String, String> e : moved.entrySet()) {
                JSONObject m = day.optJSONObject(e.getKey());
                if (m == null) continue;
                day.put(e.getValue(), newer(day.optJSONObject(e.getValue()), m));
                day.remove(e.getKey());
            }
        }
    }

    /** Одинаковы ли журналы по содержанию (порядок ключей не важен). */
    static boolean same(JSONObject a, JSONObject b) {
        return canon(normalize(copy(a))).equals(canon(normalize(copy(b))));
    }

    private static String canon(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> ks = keys(o);
            Collections.sort(ks);
            StringBuilder sb = new StringBuilder("{");
            for (String k : ks) sb.append(JSONObject.quote(k)).append(':').append(canon(o.opt(k))).append(',');
            return sb.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<String> items = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) items.add(canon(a.opt(i)));
            Collections.sort(items);
            return "[" + String.join(",", items) + "]";
        }
        if (v instanceof Number) return String.valueOf(((Number) v).doubleValue());
        return v == null || v == JSONObject.NULL ? "null" : JSONObject.quote(String.valueOf(v));
    }

    // ---------- Список из Word ----------

    static final class Parsed {
        final String name;
        final List<String> students;

        Parsed(String name, List<String> students) {
            this.name = name;
            this.students = students;
        }
    }

    private static final Pattern BLOCK = Pattern.compile("(?s)<w:tbl>.*?</w:tbl>|<w:p[ >].*?</w:p>");
    private static final Pattern ROW = Pattern.compile("(?s)<w:tr[ >].*?</w:tr>");
    private static final Pattern CELL = Pattern.compile("(?s)<w:tc>.*?</w:tc>");
    private static final Pattern RUN = Pattern.compile("<w:t(?:\\s[^>]*)?>([^<]*)</w:t>");
    private static final Pattern HEADING = Pattern.compile("(\\d{1,2})\\s*[«\"]?([А-Яа-яЁё])?[»\"]?\\s*класс",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static String runsText(String xml) {
        StringBuilder sb = new StringBuilder();
        Matcher m = RUN.matcher(xml);
        while (m.find()) sb.append(m.group(1));
        return sb.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    /** word/document.xml шаблона → классы и ФИО (над таблицей — «Список учащихся 9 класса …»). */
    static List<Parsed> parseTemplate(String xml) {
        List<Parsed> out = new ArrayList<>();
        String heading = "";
        Matcher b = BLOCK.matcher(xml);
        while (b.find()) {
            String block = b.group();
            if (!block.startsWith("<w:tbl>")) {
                String t = runsText(block).trim();
                if (!t.isEmpty()) heading = t;
                continue;
            }
            List<String> students = new ArrayList<>();
            Matcher r = ROW.matcher(block);
            while (r.find()) {
                List<String> cells = new ArrayList<>();
                Matcher c = CELL.matcher(r.group());
                while (c.find()) cells.add(runsText(c.group()).trim());
                if (cells.size() >= 2 && cells.get(0).matches("\\d+\\.?") && cells.get(1).matches("(?s).*[А-Яа-яЁёA-Za-z].*")) {
                    students.add(fixName(cells.get(1)));
                }
            }
            if (students.isEmpty()) continue;
            Matcher h = HEADING.matcher(heading);
            String name;
            if (h.find()) {
                name = h.group(1) + (h.group(2) != null ? " " + h.group(2).toUpperCase(RU) : "") + " класс";
            } else {
                name = heading.isEmpty() ? "Класс " + (out.size() + 1) : heading;
            }
            out.add(new Parsed(name, students));
        }
        return out;
    }

    /** Добавить классы и учеников из шаблона; уже записанных не дублируем. Возвращает {классов, учеников}. */
    static int[] importClasses(JSONObject j, List<Parsed> parsed) {
        int classes = 0, students = 0;
        for (Parsed p : parsed) {
            JSONObject c = null;
            for (JSONObject x : sortedClasses(j)) if (x.optString("name").equalsIgnoreCase(p.name)) c = x;
            if (c == null) {
                c = addClass(j, p.name);
                classes++;
            }
            for (String name : p.students) {
                boolean dup = false;
                for (JSONObject s : sortedStudents(c)) if (s.optString("name").equalsIgnoreCase(name)) dup = true;
                if (dup) continue;
                addStudent(c, name);
                students++;
            }
        }
        return new int[]{classes, students};
    }

    // ---------- Предупреждения ----------

    static final class Alert {
        String kind, cls, student, sid, since = "", last = "";
        int days, count;
    }

    static String dm(String iso) {
        return iso.substring(8) + "." + iso.substring(5, 7);
    }

    /** Кто болеет SICK_DAYS учебных дней подряд и кто пропускает без уважительной причины (ABSENT_COUNT за 30 дней). */
    static List<Alert> alerts(JSONObject j, String todayIso) {
        List<String> days = new ArrayList<>();
        String end = QUARTERS[QUARTERS.length - 1][1];
        for (LocalDate d = LocalDate.parse(QUARTERS[0][0]);
             d.toString().compareTo(todayIso) <= 0 && d.toString().compareTo(end) <= 0; d = d.plusDays(1)) {
            if (isSchoolDay(j, d.toString())) days.add(d.toString());
        }
        String from = LocalDate.parse(todayIso).minusDays(30).toString();
        List<Alert> sick = new ArrayList<>(), absent = new ArrayList<>();
        for (JSONObject c : sortedClasses(j)) {
            for (JSONObject s : sortedStudents(c)) {
                String sid = s.optString("id");
                int i = days.size() - 1;
                // сегодня ещё не отмечали — считаем по вчерашний день
                if (i >= 0 && days.get(i).equals(todayIso) && getMark(j, todayIso, sid) == null) i--;
                int run = 0;
                String since = "";
                while (i >= 0) {
                    JSONObject m = getMark(j, days.get(i), sid);
                    if (m == null || !"sick".equals(m.optString("r"))) break;
                    run++;
                    since = days.get(i);
                    i--;
                }
                if (run >= SICK_DAYS) {
                    Alert a = alert("sick", c, s);
                    a.days = run;
                    a.since = since;
                    sick.add(a);
                }
                int count = 0;
                String last = "";
                for (String d : days) {
                    JSONObject m = getMark(j, d, sid);
                    if (d.compareTo(from) > 0 && m != null && "absent".equals(m.optString("r"))) {
                        count++;
                        last = d;
                    }
                }
                if (count >= ABSENT_COUNT) {
                    Alert a = alert("absent", c, s);
                    a.count = count;
                    a.last = last;
                    absent.add(a);
                }
            }
        }
        sick.sort((x, y) -> y.days - x.days);
        absent.sort((x, y) -> y.count - x.count);
        sick.addAll(absent);
        return sick;
    }

    private static Alert alert(String kind, JSONObject c, JSONObject s) {
        Alert a = new Alert();
        a.kind = kind;
        a.cls = c.optString("name");
        a.student = s.optString("name");
        a.sid = s.optString("id");
        return a;
    }

    static String plural(int n, String one, String few, String many) {
        int a = n % 10, b = n % 100;
        if (a == 1 && b != 11) return one;
        if (a >= 2 && a <= 4 && (b < 12 || b > 14)) return few;
        return many;
    }

    static String alertText(Alert a) {
        if ("sick".equals(a.kind)) {
            return a.student + " (" + a.cls + ") болеет " + a.days + " "
                    + plural(a.days, "учебный день", "учебных дня", "учебных дней") + " подряд — с " + dm(a.since);
        }
        return a.student + " (" + a.cls + "): " + a.count + " " + plural(a.count, "пропуск", "пропуска", "пропусков")
                + " без уважительной причины за 30 дней, последний " + dm(a.last);
    }

    // ---------- Выгрузка в Word ----------

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String FONT = "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:cs=\"Times New Roman\"/>";

    private static String run(String text, int size, boolean bold) {
        return "<w:r><w:rPr>" + FONT + (bold ? "<w:b/>" : "") + "<w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size
                + "\"/></w:rPr><w:t xml:space=\"preserve\">" + esc(text) + "</w:t></w:r>";
    }

    private static String para(String text, int size, boolean bold, String align, int before, int after) {
        return "<w:p><w:pPr><w:spacing w:before=\"" + before + "\" w:after=\"" + after + "\"/><w:jc w:val=\"" + align
                + "\"/></w:pPr>" + (text.isEmpty() ? "" : run(text, size, bold)) + "</w:p>";
    }

    private static String cell(String text, int width, int size, boolean bold, String align, String shade) {
        return "<w:tc><w:tcPr><w:tcW w:w=\"" + width + "\" w:type=\"dxa\"/>"
                + (shade.isEmpty() ? "" : "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"" + shade + "\"/>")
                + "<w:vAlign w:val=\"center\"/></w:tcPr>" + para(text, size, bold, align, 0, 0) + "</w:tc>";
    }

    private static final int PAGE_W = 16838, PAGE_H = 11906, MARGIN_X = 1134, MARGIN_Y = 567;

    private static String shortName(String name) {
        String[] p = name.split(" ");
        if (p.length < 2) return name;
        StringBuilder sb = new StringBuilder(p[0]).append(' ');
        for (int i = 1; i < p.length; i++) if (!LOWER_WORDS.contains(p[i]) && !p[i].isEmpty()) sb.append(p[i].charAt(0)).append('.');
        return sb.toString();
    }

    /** word/document.xml: таблица на каждый класс, как в шаблоне, плюс итоги и обозначения. */
    static String documentXml(JSONObject j, String month) {
        List<String> days = schoolDays(j, month);
        String title = MONTHS[Integer.parseInt(month.substring(5)) - 1].toUpperCase(RU);
        String year = month.substring(0, 4);
        int avail = PAGE_W - 2 * MARGIN_X, wNum = 450, wTotal = 700;
        int wDay = days.isEmpty() ? 0 : Math.min(560, (avail - wNum - 2600 - 2 * wTotal) / days.size());
        int wName = avail - wNum - 2 * wTotal - wDay * days.size();
        List<String> notes = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (JSONObject c : sortedClasses(j)) {
            List<JSONObject> students = sortedStudents(c);
            if (students.isEmpty()) continue;
            body.append(para("Список учащихся " + classGenitive(c.optString("name")) + " " + title + " " + year,
                    26, true, "center", 120, 80));
            body.append("<w:tbl><w:tblPr><w:tblW w:w=\"0\" w:type=\"auto\"/><w:tblLayout w:type=\"fixed\"/><w:tblBorders>");
            for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
                body.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"000000\"/>");
            }
            body.append("</w:tblBorders><w:tblCellMar><w:left w:w=\"40\" w:type=\"dxa\"/><w:right w:w=\"40\" w:type=\"dxa\"/>"
                    + "</w:tblCellMar></w:tblPr><w:tblGrid>");
            body.append("<w:gridCol w:w=\"").append(wNum).append("\"/><w:gridCol w:w=\"").append(wName).append("\"/>");
            for (int i = 0; i < days.size(); i++) body.append("<w:gridCol w:w=\"").append(wDay).append("\"/>");
            body.append("<w:gridCol w:w=\"").append(wTotal).append("\"/><w:gridCol w:w=\"").append(wTotal).append("\"/></w:tblGrid>");
            body.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>").append(cell("№", wNum, 20, true, "center", ""))
                    .append(cell("ФИО", wName, 20, true, "center", ""));
            for (String d : days) body.append(cell(String.valueOf(Integer.parseInt(d.substring(8))), wDay, 18, true, "center", ""));
            body.append(cell("Всего", wTotal, 16, true, "center", "")).append(cell("Без ув. пр.", wTotal, 16, true, "center", ""))
                    .append("</w:tr>");
            int n = 0;
            for (JSONObject s : students) {
                String sid = s.optString("id");
                Stats st = monthStats(j, month, sid);
                body.append("<w:tr>").append(cell(String.valueOf(++n), wNum, 20, false, "center", ""))
                        .append(cell(s.optString("name"), wName, 22, false, "left", ""));
                for (String d : days) {
                    JSONObject m = getMark(j, d, sid);
                    Reason r = m == null ? null : reasonById(m.optString("r"));
                    if (r != null && "other".equals(r.id) && !m.optString("note").isEmpty()) {
                        notes.add(shortName(s.optString("name")) + " (" + c.optString("name") + "), " + dm(d) + ": " + m.optString("note"));
                    }
                    body.append(cell(markCode(m), wDay, 18, false, "center", r != null && !r.valid ? "F2DCDB" : ""));
                }
                body.append(cell(st.total > 0 ? String.valueOf(st.total) : "", wTotal, 20, false, "center", ""))
                        .append(cell(st.invalid > 0 ? String.valueOf(st.invalid) : "", wTotal, 20, false, "center", ""))
                        .append("</w:tr>");
            }
            body.append("</w:tbl>");
        }
        if (body.length() == 0) body.append(para("В журнале пока нет учеников.", 24, false, "left", 0, 0));
        StringBuilder legend = new StringBuilder("Обозначения: ");
        for (int i = 0; i < REASONS.length; i++) {
            if (i > 0) legend.append("; ");
            legend.append(REASONS[i].code).append(" — ").append(REASONS[i].name.toLowerCase(RU));
        }
        body.append(para(legend.append('.').toString(), 20, false, "left", 200, 0));
        if (!notes.isEmpty()) {
            body.append(para("Примечания:", 20, true, "left", 80, 0));
            for (String note : notes) body.append(para(note, 20, false, "left", 0, 0));
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                + body
                + "<w:sectPr><w:pgSz w:w=\"" + PAGE_W + "\" w:h=\"" + PAGE_H + "\" w:orient=\"landscape\"/>"
                + "<w:pgMar w:top=\"" + MARGIN_Y + "\" w:right=\"" + MARGIN_X + "\" w:bottom=\"" + MARGIN_Y + "\" w:left=\""
                + MARGIN_X + "\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
    }

    /** Все файлы документа Word: путь → текст. */
    static Map<String, String> docxFiles(JSONObject j, String month) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>");
        f.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
        f.put("word/document.xml", documentXml(j, month));
        return f;
    }

    /** Записать документ Word (zip) в поток. */
    static void writeDocx(JSONObject j, String month, OutputStream out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : docxFiles(j, month).entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /** «Посещаемость — Ноябрь 2026.docx». */
    static String fileName(String month) {
        return "Посещаемость — " + monthTitle(month) + ".docx";
    }

    private Attendance() {
    }
}
