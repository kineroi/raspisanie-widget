package kz.nikolaevka.raspisanie;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Collator;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Оценки за урок (ФО) с комментарием → журнал BilimClass. Так же, как сайт bilimclass.kz:
 * новая оценка — POST journal-service/mark, изменение — PUT по markUuid. Ставить можно только за урок,
 * отмеченный проведённым, и если журнал разрешает (canManageScores). Тот же смысл, что desktop/src/shared/marks.js.
 * Нейросети уходит только текст заметки учителя — без ФИО и баллов.
 */
final class Marks {

    /** Столько символов BilimClass принимает в комментарии к оценке (поле на сайте, max-length 250). */
    static final int COMMENT_MAX = 250;

    /** Подсказка нейросети. max — на обе версии; каждой даём половину: так модели соблюдают предел надёжнее. */
    static String systemPrompt(int max) {
        int each = (max - 1) / 2;
        return "Ты помогаешь учителю английского языка в школе Казахстана писать комментарий к оценке ученика "
                + "в электронном журнале. Учитель даёт короткую заметку (может быть с сокращениями и опечатками). "
                + "Сделай из неё вежливый понятный комментарий для ученика и родителей на русском языке и тот же смысл на английском.\n"
                + "Правила: ничего не придумывай сверх заметки; не добавляй имён и обращений; не упоминай балл, если его нет в заметке; "
                + "если заметка длинная — оставь главное, сократи второстепенное; "
                + "русская версия — не длиннее " + each + " символов, английская — не длиннее " + each + " символов (с пробелами); "
                + "каждая версия — законченная фраза, без многоточия в конце.\n"
                + "Верни ТОЛЬКО объект JSON без пояснений: {\"ru\": \"…\", \"en\": \"…\"}";
    }

    private static String squash(String s) {
        return (s == null ? "" : s).replaceAll("\\s+", " ").trim();
    }

    /** Обрезать по границе слова, чтобы влезло в n символов (с многоточием). */
    static String cut(String s, int n) {
        String t = squash(s);
        if (t.length() <= n) return t;
        if (n <= 1) return "";
        String room = t.substring(0, n - 1);
        int sp = room.lastIndexOf(' ');
        String part = sp > n * 0.5 ? room.substring(0, sp) : room;
        return part.replaceAll("[\\s,.;:—-]+$", "") + "…";
    }

    /** Русская строка, затем английская — вместе не длиннее max; если не влезает, обе укорачиваются пропорционально. */
    static String fitComment(String ru, String en, int max) {
        String a = squash(ru), b = squash(en);
        if (a.isEmpty() || b.isEmpty()) return cut(a.isEmpty() ? b : a, max);
        if (a.length() + 1 + b.length() <= max) return a + "\n" + b;
        int room = max - 1;
        int na = (int) Math.floor((double) room * a.length() / (a.length() + b.length()));
        na = Math.min(a.length(), Math.max(1, na));
        return cut(a, na) + "\n" + cut(b, room - na);
    }

    /** «02.11.2026» или «02.11.26» → «2026-11-02». */
    static String isoFromDmy(String s) {
        String[] p = (s == null ? "" : s).split("\\.");
        if (p.length != 3) return "";
        return (p[2].length() == 2 ? "20" + p[2] : p[2]) + "-" + p[1] + "-" + p[0];
    }

    /** Допустимый балл: целое от 0 до max. */
    static boolean validMark(String v, int max) {
        if (v == null || v.isEmpty()) return false;
        try {
            int n = Integer.parseInt(v);
            return n >= 0 && n <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static final class Student {
        String id, name, comment = "", markUuid = "";
        /** null — оценки нет. */
        Integer mark;
    }

    /** Урок в журнале одного класса. */
    static final class Lesson {
        String cls = "", error, scheduleUuid = "";
        boolean found, completed, canBeCompleted, canManage;
        int markMax = 10, eduYear;
        long groupId, subjectId;
        final List<Student> students = new ArrayList<>();
    }

    /** Ответ «quarter» (уроки и ученики) + ответ journal-service (оценки) → урок. */
    static Lesson parseLesson(JSONObject quarter, JSONObject marks, String scheduleUuid) {
        Lesson l = new Lesson();
        l.scheduleUuid = scheduleUuid;
        JSONObject q = quarter == null ? new JSONObject() : quarter.optJSONObject("data") != null ? quarter.optJSONObject("data") : quarter;
        JSONArray cols = q.optJSONArray("lessonSchedules");
        JSONObject col = null;
        for (int i = 0; cols != null && i < cols.length(); i++) {
            JSONObject o = cols.optJSONObject(i);
            if (o != null && scheduleUuid.equals(o.optString("uuid"))) col = o;
        }
        if (col == null) return l;
        l.found = true;
        int journalMax = 10;
        try {
            journalMax = Integer.parseInt(q.optString("journalMaxScore", "10"));
        } catch (NumberFormatException ignored) {
            // по умолчанию 10
        }
        l.markMax = col.optInt("markMax", journalMax);
        l.completed = col.optBoolean("isCompletedLesson");
        l.canBeCompleted = col.optBoolean("canBeCompleted", true);
        l.canManage = col.optBoolean("canManageScores", true);
        JSONObject byStudent = marks == null ? new JSONObject()
                : marks.optJSONObject("data") != null ? marks.optJSONObject("data") : marks;
        JSONArray rows = q.optJSONArray("rows");
        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONObject r = rows.optJSONObject(i);
            if (r == null || !r.optBoolean("studentStatus", true)) continue;
            Object depart = r.opt("departDate");
            if (depart != null && depart != JSONObject.NULL && !String.valueOf(depart).isEmpty()) continue;
            Student s = new Student();
            s.id = r.optString("studentGroupUuid");
            s.name = r.optString("fullName", "");
            JSONObject st = byStudent.optJSONObject(s.id);
            JSONObject cell = st == null || st.optJSONObject("marks") == null ? null : st.optJSONObject("marks").optJSONObject(scheduleUuid);
            JSONObject f = cell == null ? null : cell.optJSONObject("formattedScore");
            if (f != null) {
                Object m = f.opt("mark");
                if (m instanceof Number) s.mark = ((Number) m).intValue();
                Object cm = f.opt("comment");
                s.comment = cm == null || cm == JSONObject.NULL ? "" : String.valueOf(cm);
                Object mu = f.opt("markUuid");
                s.markUuid = mu == null || mu == JSONObject.NULL ? "" : String.valueOf(mu);
            }
            l.students.add(s);
        }
        Collator col2 = Collator.getInstance(new Locale("ru"));
        Collections.sort(l.students, (x, y) -> col2.compare(x.name, y.name));
        return l;
    }

    /** Правка учителя: балл ("" — не ставить) и комментарий. */
    static final class Edit {
        String mark = "", comment = "";
        boolean polished = true;
    }

    static final class Change {
        String id, comment, markUuid;
        int mark;
        boolean create;
    }

    /**
     * Что отправить: правки против того, что уже в журнале. Новая оценка без балла не ставится;
     * удалить оценку отсюда нельзя (только на сайте).
     */
    static List<Change> changes(List<Student> students, Map<String, Edit> edits, int max) {
        List<Change> out = new ArrayList<>();
        for (Student s : students) {
            Edit e = edits.get(s.id);
            if (e == null) continue;
            String comment = e.comment == null ? s.comment : e.comment.trim();
            Change ch = new Change();
            ch.id = s.id;
            ch.comment = comment;
            ch.markUuid = s.markUuid;
            if (e.mark == null || e.mark.isEmpty()) {
                // без балла можно только поправить комментарий к уже стоящей оценке
                if (!s.markUuid.isEmpty() && s.mark != null && !comment.equals(s.comment)) {
                    ch.mark = s.mark;
                    out.add(ch);
                }
                continue;
            }
            if (!validMark(e.mark, max)) continue;
            int mark = Integer.parseInt(e.mark);
            if (s.mark != null && s.mark == mark && comment.equals(s.comment)) continue;
            ch.mark = mark;
            ch.create = s.markUuid.isEmpty();
            out.add(ch);
        }
        return out;
    }

    /** Тело запроса «поставить оценку» (POST), как на сайте. */
    static JSONObject createBody(long userId, int eduYear, String schoolId, long groupId, long subjectId,
                                 String scheduleUuid, String dateIso, int markMax, Change ch) throws JSONException {
        return new JSONObject()
                .put("userId", userId)
                .put("eduYear", eduYear)
                .put("schoolId", Long.parseLong(schoolId))
                .put("groupId", groupId)
                .put("subjectId", subjectId)
                .put("studentGroupUuid", ch.id)
                .put("scheduleUuid", scheduleUuid)
                .put("date", dateIso + "T00:00:00.000Z")
                .put("markType", "formattedScore")
                .put("mark", ch.mark)
                .put("markMax", markMax)
                .put("comment", ch.comment);
    }

    /** Тело запроса «изменить оценку» (PUT), как на сайте. */
    static JSONObject updateBody(long userId, int markMax, Change ch) throws JSONException {
        return new JSONObject()
                .put("userId", userId)
                .put("uuid", ch.markUuid)
                .put("markType", "formattedScore")
                .put("mark", ch.mark)
                .put("markMax", markMax)
                .put("comment", ch.comment);
    }

    // ---------- Сеть (НЕ из главного потока) ----------

    static String journalHost(int eduYear) {
        return eduYear <= 2025 ? "https://journal-service-past.bilimclass.kz" : "https://journal-service.bilimclass.kz";
    }

    private static final class User {
        String chat;
        long id;
    }

    private static User user(String token, String school) throws IOException {
        JSONObject ui = BilimApi.getJson(BilimApi.API + "/api/v2/os/user-info", token, school, null);
        JSONObject u = ui.optJSONObject("data") != null ? ui.optJSONObject("data") : ui;
        User r = new User();
        r.chat = u.optString("chatToken", "");
        r.id = u.optLong("userId");
        if (r.chat.isEmpty() || r.id == 0) throw new IOException("Не удалось получить доступ к журналу BilimClass.");
        return r;
    }

    private static Map<String, String> chatHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("external", "1");
        return h;
    }

    /** Ученики и оценки за урок по всем его классам. */
    static List<Lesson> load(Context context, LocalDate date, Schedule.Lesson lesson) throws IOException {
        final Context c = context.getApplicationContext();
        final List<Lesson> out = new ArrayList<>();
        final String iso = date.toString();
        Bilim.withToken(c, (token, school) -> {
            out.clear();
            User u = user(token, school);
            List<Journals.Info> infos = Journals.discover(c);
            int eduYear = BilimApi.eduYear(date);
            for (Schedule.Detail d : lesson.details) {
                if (d.scheduleUuid.isEmpty()) continue;
                Lesson l = new Lesson();
                l.cls = d.cls;
                Journals.Info ji = null;
                for (Journals.Info in : infos) if (in.cls.equals(d.cls)) ji = in;
                if (ji == null) {
                    l.error = "Журнал этого класса не найден. Откройте «Успеваемость», чтобы обновить список журналов.";
                    out.add(l);
                    continue;
                }
                try {
                    JSONObject pr = BilimApi.getJson(BilimApi.API + "/api/v4/os/frontoffice/journal/period-list/" + ji.uuid
                            + "?schoolId=" + school + "&eduYear=" + eduYear, token, school, null);
                    JSONArray pa = pr.optJSONArray("data");
                    JSONObject period = null;
                    for (int i = 0; pa != null && i < pa.length(); i++) {
                        JSONObject p = pa.getJSONObject(i);
                        String ps = isoFromDmy(p.optString("periodStart")), pe = isoFromDmy(p.optString("periodEnd"));
                        if (ps.compareTo(iso) <= 0 && iso.compareTo(pe) <= 0) period = p;
                    }
                    if (period == null) {
                        l.error = "Эта дата не входит ни в одну четверть журнала.";
                        out.add(l);
                        continue;
                    }
                    JSONObject quarter = BilimApi.getJson(BilimApi.API + "/api/v4/os/frontoffice/journal/quarter/" + ji.uuid
                            + "?schoolId=" + school + "&eduYear=" + eduYear
                            + "&educationProgramPeriodUuid=" + period.optString("uuid"), token, school, null);
                    JSONObject q = quarter.optJSONObject("data");
                    long subjectId = q != null && q.optLong("subjectId") != 0 ? q.optLong("subjectId") : ji.subjectId;
                    JSONObject marks = BilimApi.getJson(journalHost(eduYear) + "/journal?schoolId=" + school
                            + "&eduYear=" + eduYear + "&userId=" + u.id + "&groupId=" + ji.groupId + "&subjectId=" + subjectId
                            + "&dateFrom=" + isoFromDmy(period.optString("periodStart")) + "T00:00:00.000Z"
                            + "&dateTo=" + isoFromDmy(period.optString("periodEnd")) + "T00:00:00.000Z", u.chat, school, chatHeaders());
                    Lesson parsed = parseLesson(quarter, marks, d.scheduleUuid);
                    parsed.cls = d.cls;
                    if (!parsed.found) parsed.error = "Этого урока нет в журнале класса.";
                    parsed.groupId = ji.groupId;
                    parsed.subjectId = subjectId;
                    parsed.eduYear = eduYear;
                    out.add(parsed);
                } catch (JSONException e) {
                    throw new IOException("Неожиданный ответ BilimClass");
                }
            }
        });
        return out;
    }

    /**
     * Отправить оценки урока одного класса. Каждая отдельно; ошибки не останавливают остальные.
     * Возвращает id ученика → "" (отправлено) или текст ошибки.
     */
    static Map<String, String> save(Context context, LocalDate date, Lesson l, List<Change> list,
                                    ErrorText errors) throws IOException {
        final Map<String, String> result = new LinkedHashMap<>();
        // если пропуск устареет посреди отправки, withToken повторит всё — уже отправленные не дублируем
        final Set<String> sent = new HashSet<>();
        Bilim.withToken(context.getApplicationContext(), (token, school) -> {
            User u = user(token, school);
            String host = journalHost(l.eduYear);
            for (Change ch : list) {
                if (sent.contains(ch.id)) continue;
                try {
                    String body = (ch.create ? createBody(u.id, l.eduYear, school, l.groupId, l.subjectId, l.scheduleUuid,
                            date.toString(), l.markMax, ch) : updateBody(u.id, l.markMax, ch)).toString();
                    BilimApi.sendJson(ch.create ? "POST" : "PUT", host + "/mark", u.chat, school, chatHeaders(), body);
                    sent.add(ch.id);
                    result.put(ch.id, "");
                } catch (BilimApi.HttpError e) {
                    if (e.isExpired()) throw e;
                    result.put(ch.id, errors.of(e));
                } catch (JSONException e) {
                    result.put(ch.id, "Не удалось подготовить запрос.");
                }
            }
        });
        return result;
    }

    /** Понятный текст ошибки (берётся у экрана домашки, чтобы тексты были одинаковыми). */
    interface ErrorText {
        String of(IOException e);
    }

    /** Отметить урок проведённым (как кнопка в журнале сайта) — без этого оценки не ставятся. */
    static void conduct(Context context, LocalDate date, String scheduleUuid) throws IOException {
        Bilim.withToken(context.getApplicationContext(), (token, school) -> {
            try {
                String body = new JSONObject().put("schoolId", Long.parseLong(school))
                        .put("eduYear", BilimApi.eduYear(date)).put("scheduleUuid", scheduleUuid).toString();
                BilimApi.sendJson("POST", BilimApi.API + "/api/v4/os/frontoffice/lesson/complete", token, school, null, body);
            } catch (JSONException e) {
                throw new IOException("Не удалось подготовить запрос.");
            }
        });
    }

    /** Заметка учителя → комментарий на русском и английском, вместе не длиннее 250 символов. */
    static String polish(Context c, String note) throws IOException {
        Ai.Provider p = Bilim.aiProvider(c);
        if (p == null) throw new IOException("Нужен ключ нейросети (Настройки → Нейросеть).");
        String text = "Заметка учителя: " + note.trim();
        JSONObject r = Ai.completeJson(p, Bilim.aiKey(c), Bilim.aiModel(c), systemPrompt(COMMENT_MAX - 10), text,
                Collections.<Ai.FilePart>emptyList());
        String ru = r.optString("ru", ""), en = r.optString("en", "");
        if (ru.isEmpty() && en.isEmpty()) throw new IOException(p.name + " не вернул комментарий. Попробуйте ещё раз.");
        // не влезло — просим ещё короче, и только потом обрезаем сами
        if (ru.length() + 1 + en.length() > COMMENT_MAX) {
            r = Ai.completeJson(p, Bilim.aiKey(c), Bilim.aiModel(c), systemPrompt((int) (COMMENT_MAX * 0.6)), text,
                    Collections.<Ai.FilePart>emptyList());
            ru = r.optString("ru", "");
            en = r.optString("en", "");
        }
        return fitComment(ru, en, COMMENT_MAX);
    }

    private Marks() {
    }
}
