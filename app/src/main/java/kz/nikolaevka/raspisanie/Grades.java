package kz.nikolaevka.raspisanie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Успеваемость — тот же расчёт, что в журнале BilimClass (формула 2025) и в версии для Windows:
 * %ФО = доля баллов ФО × 25, %СОР × 25, %СОЧ × 50; итог = округлить(сумму);
 * &lt; 40 → 2, 40–64 → 3, 65–84 → 4, ≥ 85 → 5. Пока СОЧ нет — прогноз по имеющимся частям.
 * Если BilimClass уже показывает рекомендуемую оценку — главная она.
 */
final class Grades {

    static final double W_FO = 25, W_SOR = 25, W_SOCH = 50;
    static final int RISK_MARGIN = 5;

    static final class Mark {
        final double mark;
        final double max;

        Mark(double mark, double max) {
            this.mark = mark;
            this.max = max;
        }
    }

    static final class Sched {
        final String uuid;
        final String date; // YYYY-MM-DD
        final int max;

        Sched(String uuid, String date, int max) {
            this.uuid = uuid;
            this.date = date;
            this.max = max;
        }
    }

    static final class Student {
        String id = "";
        String name = "";
        String finalScore = "";
        Integer siteGrade;
        boolean canFinal = true;
        final List<Mark> fo = new ArrayList<>();
        final Map<String, Mark> sor = new LinkedHashMap<>();
        final Map<String, Mark> soch = new LinkedHashMap<>();
    }

    static final class Period {
        String uuid = "";
        int n;
        String start = "";
        String end = "";
    }

    static final class Journal {
        String uuid = "";
        long groupId;
        String cls = "";
        String subject = "";
        Period period;
        String deadlineEnd; // может быть null
        final List<Sched> sor = new ArrayList<>();
        final List<Sched> soch = new ArrayList<>();
        final List<Student> students = new ArrayList<>();
    }

    static final class Lesson {
        final String date;
        final String cls;
        final String theme;
        final String homework;
        final boolean additional;

        Lesson(String date, String cls, String theme, String homework, boolean additional) {
            this.date = date;
            this.cls = cls;
            this.theme = theme;
            this.homework = homework;
            this.additional = additional;
        }
    }

    static final class Stats {
        Double fo, sor, soch; // взвешенные проценты (как в журнале), null — нет оценок
        Integer total;
        Integer grade;
        boolean forecast;
        boolean fromSite;
    }

    static final class Need {
        int max;
        final Integer[] need = new Integer[6]; // индексы 3, 4, 5; null — недостижимо
    }

    static final class Row {
        Student st;
        Stats stats;
        String risk; // "two" | "edge2" | "edge3" | null
        Need soch;
    }

    static final class Check {
        String end;
        String deadline;
        long daysLeft;
        final List<String> items = new ArrayList<>();
    }

    // ---------- Даты ----------

    /** «03.09.26» или «01.09.2026» → «2026-09-03». */
    static String dmy(String s) {
        String[] p = s.split("\\.");
        String y = p[2].length() == 2 ? "20" + p[2] : p[2];
        return y + "-" + p[1] + "-" + p[0];
    }

    static Integer gradeFor(Integer pct) {
        if (pct == null) return null;
        if (pct < 40) return 2;
        if (pct < 65) return 3;
        if (pct < 85) return 4;
        return 5;
    }

    private static final int[] LOWER = {0, 0, 0, 40, 65, 85};

    // ---------- Сборка журнала из ответов BilimClass ----------

    static Journal build(String uuid, long groupId, String clsFallback, Period period,
                         JSONObject quarterResp, JSONObject marksResp) throws JSONException {
        JSONObject q = quarterResp.optJSONObject("data") != null ? quarterResp.getJSONObject("data") : quarterResp;
        JSONObject byStudent = marksResp.optJSONObject("data") != null ? marksResp.getJSONObject("data") : marksResp;
        Journal j = new Journal();
        j.uuid = uuid;
        j.groupId = groupId;
        String group = q.optString("group", "");
        j.cls = group.isEmpty() || "null".equals(group) ? clsFallback : BilimData.compactClass(group.replace(" РУС", "").replace(" ҚАЗ", ""));
        j.subject = q.isNull("subjectName") ? "" : q.optString("subjectName", "");
        j.period = period;
        JSONObject fd = q.optJSONObject("finalMarkDeadline");
        if (fd != null && !fd.isNull("endDate")) j.deadlineEnd = fd.optString("endDate", null);
        readScheds(q.optJSONArray("sorSchedules"), j.sor);
        readScheds(q.optJSONArray("sochSchedules"), j.soch);
        JSONArray rows = q.optJSONArray("rows");
        if (rows != null) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.getJSONObject(i);
                if (r.has("studentStatus") && !r.isNull("studentStatus") && !r.optBoolean("studentStatus", true)) continue;
                if (!r.isNull("departDate") && !r.optString("departDate", "").isEmpty()) continue;
                Student st = new Student();
                st.id = r.optString("studentGroupUuid", "");
                st.name = r.isNull("fullName") ? "" : r.optString("fullName", "");
                st.finalScore = r.isNull("finalScore") ? "" : r.optString("finalScore", "");
                String rec = r.isNull("recommendedScore") ? "" : r.optString("recommendedScore", "");
                try {
                    if (!rec.isEmpty()) st.siteGrade = Integer.parseInt(rec);
                } catch (NumberFormatException ignored) {
                    // «зачёт» и т. п. — без оценки
                }
                st.canFinal = r.optBoolean("canGetFinalScore", true);
                JSONObject cellWrap = byStudent.optJSONObject(st.id);
                JSONObject cells = cellWrap == null ? null : cellWrap.optJSONObject("marks");
                if (cells != null) {
                    Iterator<String> keys = cells.keys();
                    while (keys.hasNext()) {
                        String sched = keys.next();
                        JSONObject c = cells.optJSONObject(sched);
                        if (c == null) continue;
                        Mark f = mark(c.optJSONObject("formattedScore"), 10);
                        if (f != null) st.fo.add(f);
                        Mark s = mark(c.optJSONObject("sor"), 0);
                        if (s != null) st.sor.put(sched, s);
                        Mark h = mark(c.optJSONObject("soch"), 0);
                        if (h != null) st.soch.put(sched, h);
                    }
                }
                j.students.add(st);
            }
        }
        return j;
    }

    private static Mark mark(JSONObject o, double defMax) {
        if (o == null || o.isNull("mark")) return null;
        Object m = o.opt("mark");
        if (!(m instanceof Number)) return null;
        double max = o.optDouble("assessmentMax", defMax);
        if (Double.isNaN(max) || max <= 0) max = defMax;
        return new Mark(((Number) m).doubleValue(), max);
    }

    private static void readScheds(JSONArray arr, List<Sched> out) throws JSONException {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            out.add(new Sched(s.optString("uuid", ""), dmy(s.getString("date")), s.optInt("markMax", 0)));
        }
    }

    // ---------- Расчёты ----------

    private static Double ratio(Iterable<Mark> items) {
        double sum = 0, max = 0;
        for (Mark m : items) {
            sum += m.mark;
            max += m.max;
        }
        return max > 0 ? sum / max : null;
    }

    static Stats stats(Student st) {
        Double fo = ratio(st.fo), sor = ratio(st.sor.values()), soch = ratio(st.soch.values());
        Stats s = new Stats();
        s.fo = fo == null ? null : fo * W_FO;
        s.sor = sor == null ? null : sor * W_SOR;
        s.soch = soch == null ? null : soch * W_SOCH;
        boolean forecast = false;
        if (s.fo != null && s.sor != null && s.soch != null) {
            s.total = (int) Math.round(s.fo + s.sor + s.soch);
        } else {
            double w = 0, sum = 0;
            if (fo != null) { w += W_FO; sum += fo * W_FO; }
            if (sor != null) { w += W_SOR; sum += sor * W_SOR; }
            if (soch != null) { w += W_SOCH; sum += soch * W_SOCH; }
            if (w > 0) {
                s.total = (int) Math.round(sum / w * 100);
                forecast = true;
            }
        }
        s.fromSite = st.siteGrade != null;
        s.grade = st.siteGrade != null ? st.siteGrade : gradeFor(s.total);
        s.forecast = !s.fromSite && forecast;
        return s;
    }

    static String risk(Integer total, Integer grade) {
        if (total == null && grade == null) return null;
        Integer g = grade != null ? grade : gradeFor(total);
        if (g == null) return null;
        if (g == 2) return "two";
        if (g == 3 && total != null && total < 40 + RISK_MARGIN) return "edge2";
        if (g == 4 && total != null && total < 65 + RISK_MARGIN) return "edge3";
        return null;
    }

    static String riskText(String r) {
        if ("two".equals(r)) return "сейчас выходит 2";
        if ("edge2".equals(r)) return "на грани двойки";
        if ("edge3".equals(r)) return "на грани тройки";
        return "";
    }

    static Need needForSoch(Student st, Journal j) {
        if (j.soch.isEmpty() || !st.soch.isEmpty()) return null;
        int max = 0;
        for (Sched s : j.soch) max += s.max;
        if (max <= 0) return null;
        Double fo = ratio(st.fo), sor = ratio(st.sor.values());
        double base = (fo == null ? 0 : fo) * W_FO + (sor == null ? 0 : sor) * W_SOR;
        Need n = new Need();
        n.max = max;
        for (int g = 3; g <= 5; g++) {
            for (int s = 0; s <= max; s++) {
                if (Math.round(base + (double) s / max * W_SOCH) >= LOWER[g]) {
                    n.need[g] = s;
                    break;
                }
            }
        }
        return n;
    }

    static List<Row> summary(Journal j) {
        List<Row> rows = new ArrayList<>();
        for (Student st : j.students) {
            Row r = new Row();
            r.st = st;
            r.stats = stats(st);
            r.risk = risk(r.stats.total, r.stats.grade);
            r.soch = needForSoch(st, j);
            rows.add(r);
        }
        Collections.sort(rows, (a, b) -> Integer.compare(
                a.stats.total == null ? 999 : a.stats.total, b.stats.total == null ? 999 : b.stats.total));
        return rows;
    }

    static int riskCount(Journal j) {
        int n = 0;
        for (Row r : summary(j)) if (r.risk != null) n++;
        return n;
    }

    private static long days(String a, String b) {
        return ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b));
    }

    private static String dm(String iso) {
        return iso.substring(8) + "." + iso.substring(5, 7);
    }

    /** Контроль конца четверти (null — до конца больше 7 дней или срок итоговых прошёл). */
    static Check quarterCheck(List<Journal> journals, List<Lesson> lessons, String today) {
        Check c = new Check();
        String periodStart = null;
        for (Journal j : journals) {
            if (j.period == null) continue;
            String pEnd = j.period.end;
            String lastDay = j.deadlineEnd != null && j.deadlineEnd.compareTo(pEnd) > 0 ? j.deadlineEnd : pEnd;
            if (days(today, pEnd) > 7 || today.compareTo(lastDay) > 0) continue;
            if (c.end == null || pEnd.compareTo(c.end) < 0) c.end = pEnd;
            if (j.deadlineEnd != null && (c.deadline == null || j.deadlineEnd.compareTo(c.deadline) < 0)) c.deadline = j.deadlineEnd;
            if (periodStart == null || j.period.start.compareTo(periodStart) < 0) periodStart = j.period.start;
            int noFinal = 0;
            for (Student s : j.students) if (s.canFinal && s.finalScore.isEmpty()) noFinal++;
            if (noFinal > 0) c.items.add(j.cls + ": не выставлено итоговых — " + noFinal);
            addMissing(c, j, j.sor, true, today);
            addMissing(c, j, j.soch, false, today);
        }
        if (c.end == null) return null;
        int noTheme = 0, noHw = 0;
        for (Lesson l : lessons) {
            if (l.additional || l.date.compareTo(today) >= 0 || (periodStart != null && l.date.compareTo(periodStart) < 0)) continue;
            if (l.theme.isEmpty()) noTheme++;
            if (l.homework.isEmpty()) noHw++;
        }
        if (noTheme > 0) c.items.add("Уроков без темы — " + noTheme);
        if (noHw > 0) c.items.add("Уроков без ДЗ — " + noHw);
        c.daysLeft = days(today, c.end);
        return c;
    }

    private static void addMissing(Check c, Journal j, List<Sched> list, boolean sor, String today) {
        for (Sched s : list) {
            if (s.date.compareTo(today) > 0) continue;
            int missing = 0;
            for (Student st : j.students) {
                if (!(sor ? st.sor : st.soch).containsKey(s.uuid)) missing++;
            }
            if (missing > 0) c.items.add(j.cls + ": " + (sor ? "СОР" : "СОЧ") + " от " + dm(s.date) + " — без оценки " + missing);
        }
    }

    /** Текущая четверть; на каникулах — последняя прошедшая. */
    static Period pickPeriod(List<Period> periods, String today) {
        for (Period p : periods) if (p.start.compareTo(today) <= 0 && today.compareTo(p.end) <= 0) return p;
        Period best = null;
        for (Period p : periods) if (p.end.compareTo(today) < 0 && (best == null || p.end.compareTo(best.end) > 0)) best = p;
        return best;
    }

    // ---------- Хранение (в памяти приложения на телефоне) ----------

    static JSONObject toJson(List<Journal> journals, List<Lesson> lessons, long updatedAt) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("updatedAt", updatedAt);
        JSONArray ja = new JSONArray();
        for (Journal j : journals) {
            JSONObject x = new JSONObject();
            x.put("uuid", j.uuid).put("groupId", j.groupId).put("cls", j.cls).put("subject", j.subject);
            if (j.deadlineEnd != null) x.put("deadlineEnd", j.deadlineEnd);
            if (j.period != null) {
                x.put("period", new JSONObject().put("uuid", j.period.uuid).put("n", j.period.n)
                        .put("start", j.period.start).put("end", j.period.end));
            }
            x.put("sor", scheds(j.sor));
            x.put("soch", scheds(j.soch));
            JSONArray sts = new JSONArray();
            for (Student st : j.students) {
                JSONObject s = new JSONObject();
                s.put("id", st.id).put("name", st.name).put("finalScore", st.finalScore).put("canFinal", st.canFinal);
                if (st.siteGrade != null) s.put("siteGrade", st.siteGrade);
                JSONArray fo = new JSONArray();
                for (Mark m : st.fo) fo.put(new JSONArray().put(m.mark).put(m.max));
                s.put("fo", fo);
                s.put("sor", marks(st.sor));
                s.put("soch", marks(st.soch));
                sts.put(s);
            }
            x.put("students", sts);
            ja.put(x);
        }
        o.put("journals", ja);
        JSONArray la = new JSONArray();
        for (Lesson l : lessons) {
            la.put(new JSONObject().put("date", l.date).put("cls", l.cls).put("theme", l.theme)
                    .put("homework", l.homework).put("additional", l.additional));
        }
        o.put("lessons", la);
        return o;
    }

    private static JSONArray scheds(List<Sched> list) throws JSONException {
        JSONArray a = new JSONArray();
        for (Sched s : list) a.put(new JSONObject().put("uuid", s.uuid).put("date", s.date).put("max", s.max));
        return a;
    }

    private static JSONObject marks(Map<String, Mark> map) throws JSONException {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, Mark> e : map.entrySet()) o.put(e.getKey(), new JSONArray().put(e.getValue().mark).put(e.getValue().max));
        return o;
    }

    static List<Journal> journalsFromJson(JSONObject o) throws JSONException {
        List<Journal> out = new ArrayList<>();
        JSONArray ja = o.optJSONArray("journals");
        if (ja == null) return out;
        for (int i = 0; i < ja.length(); i++) {
            JSONObject x = ja.getJSONObject(i);
            Journal j = new Journal();
            j.uuid = x.optString("uuid");
            j.groupId = x.optLong("groupId");
            j.cls = x.optString("cls");
            j.subject = x.optString("subject");
            j.deadlineEnd = x.has("deadlineEnd") ? x.optString("deadlineEnd") : null;
            JSONObject p = x.optJSONObject("period");
            if (p != null) {
                j.period = new Period();
                j.period.uuid = p.optString("uuid");
                j.period.n = p.optInt("n");
                j.period.start = p.optString("start");
                j.period.end = p.optString("end");
            }
            readStored(x.optJSONArray("sor"), j.sor);
            readStored(x.optJSONArray("soch"), j.soch);
            JSONArray sts = x.optJSONArray("students");
            for (int k = 0; sts != null && k < sts.length(); k++) {
                JSONObject s = sts.getJSONObject(k);
                Student st = new Student();
                st.id = s.optString("id");
                st.name = s.optString("name");
                st.finalScore = s.optString("finalScore");
                st.canFinal = s.optBoolean("canFinal", true);
                if (s.has("siteGrade")) st.siteGrade = s.getInt("siteGrade");
                JSONArray fo = s.optJSONArray("fo");
                for (int m = 0; fo != null && m < fo.length(); m++) {
                    JSONArray pair = fo.getJSONArray(m);
                    st.fo.add(new Mark(pair.getDouble(0), pair.getDouble(1)));
                }
                readMarks(s.optJSONObject("sor"), st.sor);
                readMarks(s.optJSONObject("soch"), st.soch);
                j.students.add(st);
            }
            out.add(j);
        }
        return out;
    }

    static List<Lesson> lessonsFromJson(JSONObject o) {
        List<Lesson> out = new ArrayList<>();
        JSONArray la = o.optJSONArray("lessons");
        for (int i = 0; la != null && i < la.length(); i++) {
            JSONObject l = la.optJSONObject(i);
            if (l == null) continue;
            out.add(new Lesson(l.optString("date"), l.optString("cls"), l.optString("theme"),
                    l.optString("homework"), l.optBoolean("additional")));
        }
        return out;
    }

    private static void readStored(JSONArray a, List<Sched> out) throws JSONException {
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject s = a.getJSONObject(i);
            out.add(new Sched(s.optString("uuid"), s.optString("date"), s.optInt("max")));
        }
    }

    private static void readMarks(JSONObject o, Map<String, Mark> out) throws JSONException {
        if (o == null) return;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            JSONArray pair = o.getJSONArray(k);
            out.put(k, new Mark(pair.getDouble(0), pair.getDouble(1)));
        }
    }

    private Grades() {
    }
}
