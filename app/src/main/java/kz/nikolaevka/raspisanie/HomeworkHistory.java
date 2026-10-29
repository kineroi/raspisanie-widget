package kz.nikolaevka.raspisanie;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * История отправленной домашки и подсказки «как в прошлый раз» / «частые задания».
 * Та же логика, что в версии для Windows (desktop/src/shared/logic.js, homeworkSuggestions).
 */
final class HomeworkHistory {

    static final int MAX = 200;
    private static final String PREFS = "homework_history";
    private static final String KEY = "entries";
    /** Сколько дней расписания назад просматривать в поиске прошлой домашки. */
    private static final int LOOKBACK_DAYS = 21;

    private HomeworkHistory() {
    }

    static final class Entry {
        final String text;
        final String cls;
        /** Дата урока, ISO (2026-10-26). */
        final String date;
        /** Начало урока, «09:00». */
        final String start;

        Entry(String text, String cls, String date, String start) {
            this.text = text.trim();
            this.cls = cls;
            this.date = date;
            this.start = start;
        }
    }

    static final class Suggestions {
        /** Домашка последнего прошедшего урока тех же классов или null. */
        Entry last;
        /** Самые частые задания, при равенстве — более свежие. */
        final List<String> frequent = new ArrayList<>();
    }

    /** Новая запись — в начало, не больше MAX. */
    static List<Entry> add(List<Entry> history, Entry e) {
        List<Entry> out = new ArrayList<>();
        out.add(e);
        out.addAll(history);
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    /** Классы урока; у запасного расписания их нет — берём из названия «7А + 8А». */
    static Set<String> classesOf(Schedule.Lesson l) {
        Set<String> out = new HashSet<>();
        for (Schedule.Detail d : l.details) out.add(d.cls);
        if (out.isEmpty()) out.addAll(Arrays.asList(l.title.split("\\s*\\+\\s*")));
        return out;
    }

    static Suggestions suggest(List<Entry> fromSchedule, List<Entry> history, Collection<String> classes,
                               String date, String start, int limit) {
        List<Entry> entries = new ArrayList<>(fromSchedule);
        entries.addAll(history);

        Suggestions s = new Suggestions();
        for (Entry e : entries) {
            if (e.text.isEmpty() || !classes.contains(e.cls)) continue;
            boolean earlier = e.date.compareTo(date) < 0 || (e.date.equals(date) && e.start.compareTo(start) < 0);
            if (!earlier) continue;
            if (s.last == null || (e.date + e.start).compareTo(s.last.date + s.last.start) > 0) s.last = e;
        }

        // Одна и та же отправка есть и в истории, и в расписании — считаем её один раз
        Set<String> seen = new HashSet<>();
        Map<String, int[]> count = new LinkedHashMap<>();
        Map<String, String> latest = new LinkedHashMap<>();
        for (Entry e : entries) {
            if (e.text.isEmpty() || !seen.add(e.text + "|" + e.cls + "|" + e.date)) continue;
            count.computeIfAbsent(e.text, k -> new int[1])[0]++;
            String l = latest.get(e.text);
            if (l == null || e.date.compareTo(l) > 0) latest.put(e.text, e.date);
        }
        List<String> texts = new ArrayList<>(count.keySet());
        texts.sort((a, b) -> {
            int c = Integer.compare(count.get(b)[0], count.get(a)[0]);
            return c != 0 ? c : latest.get(b).compareTo(latest.get(a));
        });
        s.frequent.addAll(texts.subList(0, Math.min(limit, texts.size())));
        return s;
    }

    /** Домашка из расписания за последние недели до указанной даты включительно. */
    static List<Entry> fromSchedule(Schedule.Source source, LocalDate upTo) {
        List<Entry> out = new ArrayList<>();
        for (int i = LOOKBACK_DAYS; i >= 0; i--) {
            LocalDate d = upTo.minusDays(i);
            for (Schedule.Lesson l : source.day(d).lessons) {
                for (Schedule.Detail det : l.details) {
                    if (!det.homework.isEmpty()) out.add(new Entry(det.homework, det.cls, d.toString(), l.start.toString()));
                }
            }
        }
        return out;
    }

    // ---------- Хранение на телефоне ----------

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(c).getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.optString("text"), o.optString("cls"), o.optString("date"), o.optString("start")));
            }
        } catch (JSONException e) {
            // повреждённая история — начинаем заново
        }
        return out;
    }

    /** Запомнить отправленную домашку для каждого класса, которому она ушла. */
    static void record(Context c, String text, Collection<String> classes, LocalDate date, LocalTime start) {
        List<Entry> history = load(c);
        for (String cls : classes) history = add(history, new Entry(text, cls, date.toString(), start.toString()));
        JSONArray arr = new JSONArray();
        try {
            for (Entry e : history) {
                arr.put(new JSONObject().put("text", e.text).put("cls", e.cls).put("date", e.date).put("start", e.start));
            }
        } catch (JSONException e) {
            return;
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }
}
