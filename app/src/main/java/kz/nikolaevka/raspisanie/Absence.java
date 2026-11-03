package kz.nikolaevka.raspisanie;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Посещаемость из нашего журнала → отметки отсутствия в журнале BilimClass (как desktop/src/shared/absence.js).
 * Наш журнал — по дням для всех классов; в BilimClass отсутствие ставится на урок в журнале учителя:
 * на выбранный день берём уроки учителя и для каждого отмечаем отсутствовавших учеников этого класса.
 * Как сайт: POST journal-service/attendance/bulk; только за проведённый урок.
 */
final class Absence {

    static final String PRESENT = "was_in_class";
    static final List<String> MISSING = Arrays.asList("missing_by_sick", "missing_due", "missing_without_reason");

    /** Наши причины → коды BilimClass. */
    static String code(String reason) {
        if ("sick".equals(reason)) return "missing_by_sick";
        if ("absent".equals(reason)) return "missing_without_reason";
        return "missing_due";
    }

    static String label(String code) {
        switch (code) {
            case "missing_by_sick": return "по болезни";
            case "missing_without_reason": return "без уважительной причины";
            case "missing_due": return "по уважительной причине";
            case "late": return "опоздал";
            default: return "был на уроке";
        }
    }

    private static final Locale RU = new Locale("ru");

    private static String norm(String s) {
        return (s == null ? "" : s).toLowerCase(RU).replace('ё', 'е').replaceAll("[^а-яa-z\\s-]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** Фамилия и имя — так сверяем ученика в двух журналах. */
    static String nameKey(String name) {
        String[] p = norm(name).split(" ");
        return p.length > 1 ? p[0] + " " + p[1] : p[0];
    }

    private static final Pattern CLASS = Pattern.compile("(\\d{1,2})\\s*[«\"-]?\\s*([А-Яа-яЁё])?(?![а-яё])");

    /** {номер, буква}: «4 класс» → {4, ""}, «4А» → {4, "а"}. */
    static String[] classKey(String name) {
        Matcher m = CLASS.matcher(name == null ? "" : name);
        if (!m.find()) return null;
        String letter = m.group(2) == null ? "" : norm(m.group(2));
        return new String[]{m.group(1), "к".equals(letter) ? "" : letter};
    }

    static boolean sameClass(String ours, String bilim) {
        String[] a = classKey(ours), b = classKey(bilim);
        if (a == null || b == null || !a[0].equals(b[0])) return false;
        return a[1].isEmpty() || b[1].isEmpty() || a[1].equals(b[1]);
    }

    /** Отметка для ученика: {id, имя, код}. */
    static final class Item {
        final String id, name, code;

        Item(String id, String name, String code) {
            this.id = id;
            this.name = name;
            this.code = code;
        }
    }

    /** Урок дня: что отметить. */
    static final class LessonPlan {
        String range = "", cls = "", blocked = "", error;
        final List<Item> send = new ArrayList<>();
        final List<String> unmatched = new ArrayList<>();
        Marks.Lesson ctx;
    }

    /**
     * absent — {имя, причина} отсутствовавших в нашем журнале; corrected — кому отсутствие сняли («был на уроках»).
     */
    static LessonPlan plan(Marks.Lesson lesson, List<String[]> absent, List<String> corrected) {
        LessonPlan p = new LessonPlan();
        p.cls = lesson.cls;
        if (!lesson.canManage) p.blocked = "журнал не разрешает отмечать этот урок";
        else if (!lesson.completed) p.blocked = lesson.canBeCompleted ? "урок не отмечен проведённым" : "урок ещё не прошёл";
        Map<String, Marks.Student> byKey = new HashMap<>();
        for (Marks.Student s : lesson.students) byKey.put(nameKey(s.name), s);
        for (String[] a : absent) {
            Marks.Student s = byKey.get(nameKey(a[0]));
            if (s == null) {
                p.unmatched.add(a[0]);
                continue;
            }
            String c = code(a[1]);
            if (!c.equals(s.attendance)) p.send.add(new Item(s.id, s.name, c));
        }
        // в нашем журнале отсутствие сняли — снимаем и в BilimClass (опоздания и чужие отметки не трогаем)
        for (String name : corrected) {
            Marks.Student s = byKey.get(nameKey(name));
            if (s != null && MISSING.contains(s.attendance)) p.send.add(new Item(s.id, s.name, PRESENT));
        }
        if (!p.blocked.isEmpty()) p.send.clear();
        return p;
    }

    /** Тело запроса, как на сайте: одна отметка на ученика и урок. */
    static JSONObject bulkBody(long userId, String schoolId, LocalDate date, Marks.Lesson l, List<Item> items) throws JSONException {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            arr.put(new JSONObject()
                    .put("userId", userId)
                    .put("eduYear", l.eduYear)
                    .put("schoolId", Long.parseLong(schoolId))
                    .put("groupId", l.groupId)
                    .put("subjectId", l.subjectId)
                    .put("studentGroupUuid", it.id)
                    .put("scheduleUuid", l.scheduleUuid)
                    .put("date", date + "T00:00:00.000Z")
                    .put("attendance", it.code));
        }
        return new JSONObject().put("userId", userId).put("attendances", arr);
    }

    // ---------- Сеть (НЕ из главного потока) ----------

    /** План на день по всем урокам учителя (кроме классного часа). */
    static List<LessonPlan> planDay(Context context, LocalDate date) throws IOException {
        Context c = context.getApplicationContext();
        List<LessonPlan> out = new ArrayList<>();
        JSONObject j = Attendance.load(c);
        List<JSONObject> ourClasses = Attendance.sortedClasses(j);
        String iso = date.toString();
        for (Schedule.Lesson l : Bilim.source(c).day(date).lessons) {
            if (l.title.startsWith("Кл. час")) continue;
            List<Marks.Lesson> loaded = Marks.load(c, date, l);
            for (Marks.Lesson ml : loaded) {
                LessonPlan p;
                JSONObject ours = null;
                for (JSONObject oc : ourClasses) if (sameClass(oc.optString("name"), ml.cls)) ours = oc;
                if (ml.error != null) {
                    p = new LessonPlan();
                    p.cls = ml.cls;
                    p.error = ml.error;
                } else if (ours == null) {
                    p = new LessonPlan();
                    p.cls = ml.cls;
                    p.blocked = "этого класса нет в журнале посещаемости";
                } else {
                    List<String[]> absent = new ArrayList<>();
                    List<String> corrected = new ArrayList<>();
                    JSONObject day = j.optJSONObject("marks").optJSONObject(iso);
                    for (JSONObject s : Attendance.sortedStudents(ours)) {
                        String sid = s.optString("id");
                        JSONObject m = Attendance.getMark(j, iso, sid);
                        if (m != null) absent.add(new String[]{s.optString("name"), m.optString("r")});
                        else if (day != null && day.has(sid)) corrected.add(s.optString("name"));
                    }
                    p = plan(ml, absent, corrected);
                    p.ctx = ml;
                }
                p.range = WidgetState.range(l);
                out.add(p);
            }
        }
        return out;
    }

    /** Отправить отметки одного урока одним запросом (как сайт). */
    static void send(Context context, LocalDate date, LessonPlan p) throws IOException {
        Bilim.withToken(context.getApplicationContext(), (token, school) -> {
            JSONObject ui = BilimApi.getJson(BilimApi.API + "/api/v2/os/user-info", token, school, null);
            JSONObject u = ui.optJSONObject("data") != null ? ui.optJSONObject("data") : ui;
            String chat = u.optString("chatToken", "");
            long userId = u.optLong("userId");
            if (chat.isEmpty() || userId == 0) throw new IOException("Не удалось получить доступ к журналу BilimClass.");
            Map<String, String> h = new HashMap<>();
            h.put("external", "1");
            try {
                BilimApi.sendJson("POST", Marks.journalHost(p.ctx.eduYear) + "/attendance/bulk", chat, school, h,
                        bulkBody(userId, school, date, p.ctx, p.send).toString());
            } catch (JSONException e) {
                throw new IOException("Не удалось подготовить запрос.");
            }
        });
    }

    private Absence() {
    }
}
