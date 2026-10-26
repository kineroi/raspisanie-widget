package kz.nikolaevka.raspisanie;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Разбор ответа BilimClass (дэшборд учителя за неделю) в наше расписание. */
final class BilimData {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String NO_HOMEWORK = "не проставляется";

    /** Сырые данные одного урока одного класса. */
    private static final class Item {
        LocalTime start;
        LocalTime end;
        String cls;
        boolean additional;
        String subject;
        String theme;
        String homework;
        String cabinet;
        String scheduleUuid;
        List<Schedule.FileRef> files;
    }

    /** Разобрать ответ за одну неделю: дата → день. */
    static Map<LocalDate, Schedule.Day> parseWeek(JSONObject response) throws JSONException {
        JSONObject data = response.getJSONObject("data");
        Map<LocalDate, Schedule.Day> result = new HashMap<>();

        Map<LocalDate, Boolean> holidays = new LinkedHashMap<>();
        JSONArray days = data.getJSONArray("days");
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.getJSONObject(i);
            holidays.put(LocalDate.parse(d.getString("date"), DMY), d.optBoolean("isHoliday", false));
        }

        Map<LocalDate, List<Item>> items = new HashMap<>();
        JSONArray rows = data.optJSONArray("rows");
        if (rows != null) {
            for (int r = 0; r < rows.length(); r++) {
                JSONObject row = rows.optJSONObject(r);
                if (row == null) continue;
                Iterator<String> keys = row.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONArray arr = row.optJSONArray(key);
                    if (arr == null) continue;
                    LocalDate date = LocalDate.parse(key, DMY);
                    for (int k = 0; k < arr.length(); k++) {
                        Item it = parseItem(arr.getJSONObject(k));
                        if (it == null) continue;
                        List<Item> list = items.get(date);
                        if (list == null) {
                            list = new ArrayList<>();
                            items.put(date, list);
                        }
                        list.add(it);
                    }
                }
            }
        }

        for (Map.Entry<LocalDate, Boolean> e : holidays.entrySet()) {
            List<Item> list = items.get(e.getKey());
            List<Schedule.Lesson> lessons = list == null
                    ? Collections.<Schedule.Lesson>emptyList() : group(list);
            result.put(e.getKey(), new Schedule.Day(e.getValue(), true, lessons));
        }
        return result;
    }

    private static Item parseItem(JSONObject l) {
        JSONObject time = l.optJSONObject("time");
        if (time == null) return null;
        String ts = time.optString("timeStart", "");
        String te = time.optString("timeEnd", "");
        if (ts.isEmpty() || te.isEmpty()) return null;
        Item it = new Item();
        it.start = LocalTime.parse(ts);
        it.end = LocalTime.parse(te);
        JSONObject cls = l.optJSONObject("class");
        it.cls = compactClass(cls == null ? "" : str(cls, "label"));
        JSONObject subj = l.optJSONObject("subject");
        it.additional = subj != null && subj.optBoolean("isAdditional", false);
        it.subject = subj == null ? "" : str(subj, "label");
        JSONObject theme = l.optJSONObject("theme");
        it.theme = theme == null ? "" : clean(str(theme, "label"));
        JSONObject hw = l.optJSONObject("homework");
        String body = hw == null ? "" : clean(str(hw, "body"));
        it.homework = NO_HOMEWORK.equalsIgnoreCase(body) ? "" : body;
        it.files = new ArrayList<>();
        JSONArray files = hw == null ? null : hw.optJSONArray("files");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String name = str(f, "name") + (f.isNull("extension") ? "" : f.optString("extension", ""));
                it.files.add(new Schedule.FileRef(f.optLong("id", 0), name, str(f, "link")));
            }
        }
        it.scheduleUuid = str(l, "scheduleUuid");
        if (it.scheduleUuid.isEmpty()) it.scheduleUuid = str(l, "scheduleId");
        JSONObject cab = l.optJSONObject("cabinet");
        it.cabinet = cab == null ? "" : str(cab, "number");
        return it;
    }

    /** Уроки в одно время (например, 7А и 8А вместе) объединяются в один. */
    private static List<Schedule.Lesson> group(List<Item> items) {
        Map<String, List<Item>> byTime = new LinkedHashMap<>();
        for (Item it : items) {
            String k = it.start + "-" + it.end;
            List<Item> l = byTime.get(k);
            if (l == null) {
                l = new ArrayList<>();
                byTime.put(k, l);
            }
            l.add(it);
        }
        List<Schedule.Lesson> lessons = new ArrayList<>();
        for (List<Item> group : byTime.values()) {
            lessons.add(toLesson(group));
        }
        Collections.sort(lessons, new Comparator<Schedule.Lesson>() {
            @Override
            public int compare(Schedule.Lesson a, Schedule.Lesson b) {
                return a.start.compareTo(b.start);
            }
        });
        return lessons;
    }

    private static Schedule.Lesson toLesson(List<Item> group) {
        Item first = group.get(0);
        StringBuilder title = new StringBuilder();
        if (first.additional) {
            if (first.theme.startsWith("Кл")) {
                title.append("Кл. час, ").append(first.cls);
            } else {
                title.append("Факультатив, ").append(first.cls);
            }
        } else {
            List<String> classes = new ArrayList<>();
            for (Item it : group) {
                if (!classes.contains(it.cls)) classes.add(it.cls);
            }
            title.append(join(classes, " + "));
        }
        String cab = first.cabinet;
        for (Item it : group) {
            if (!it.cabinet.equals(cab)) cab = "";
        }
        if (!cab.isEmpty()) title.append(" (каб. ").append(cab).append(")");

        List<Schedule.Detail> details = new ArrayList<>();
        for (Item it : group) {
            details.add(new Schedule.Detail(it.cls, it.theme, it.homework, it.scheduleUuid, it.files));
        }
        return new Schedule.Lesson(first.start, first.end, title.toString(), details);
    }

    /** «7 А» → «7А»; «9 класс» не меняется. */
    static String compactClass(String label) {
        String s = label.trim();
        return s.matches("^\\d+\\s+\\S$") ? s.replaceAll("\\s+", "") : s;
    }

    private static String clean(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    /** optString возвращает "null" для JSON null — исправляем. */
    private static String str(JSONObject o, String key) {
        if (o.isNull(key)) return "";
        return o.optString(key, "");
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    /** Источник: данные BilimClass, а где их нет — запасное расписание. */
    static Schedule.Source source(final Map<LocalDate, Schedule.Day> days) {
        return new Schedule.Source() {
            @Override
            public Schedule.Day day(LocalDate date) {
                Schedule.Day d = days.get(date);
                return d != null ? d : Schedule.TEMPLATE.day(date);
            }
        };
    }

    /** Разобрать сохранённый кэш: JSON-массив ответов за несколько недель. */
    static Map<LocalDate, Schedule.Day> parseCache(String cache) throws JSONException {
        Map<LocalDate, Schedule.Day> all = new HashMap<>();
        JSONArray weeks = new JSONArray(cache);
        for (int i = 0; i < weeks.length(); i++) {
            all.putAll(parseWeek(weeks.getJSONObject(i)));
        }
        return all;
    }

    private BilimData() {
    }
}
