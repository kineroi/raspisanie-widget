package kz.nikolaevka.raspisanie;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Модель расписания.
 * Основной источник — BilimClass (см. BilimData). Если приложение ещё не подключено
 * к BilimClass или данных на нужную дату нет, используется запасное расписание ниже.
 */
final class Schedule {

    /** Файл, прикреплённый к домашке в BilimClass. */
    static final class FileRef {
        final long id;
        final String name;
        final String link;

        FileRef(long id, String name, String link) {
            this.id = id;
            this.name = name;
            this.link = link;
        }
    }

    /** Тема и домашнее задание одного класса на уроке. */
    static final class Detail {
        final String cls;
        final String theme;
        final String homework;
        /** Идентификатор урока в BilimClass (нужен для отправки домашки), "" если нет. */
        final String scheduleUuid;
        final List<FileRef> files;

        Detail(String cls, String theme, String homework) {
            this(cls, theme, homework, "", Collections.<FileRef>emptyList());
        }

        Detail(String cls, String theme, String homework, String scheduleUuid, List<FileRef> files) {
            this.cls = cls;
            this.theme = theme == null ? "" : theme;
            this.homework = homework == null ? "" : homework;
            this.scheduleUuid = scheduleUuid == null ? "" : scheduleUuid;
            this.files = files == null ? Collections.<FileRef>emptyList() : files;
        }
    }

    static final class Lesson {
        final LocalTime start;
        final LocalTime end;
        final String title;
        final List<Detail> details;

        Lesson(LocalTime start, LocalTime end, String title) {
            this(start, end, title, Collections.<Detail>emptyList());
        }

        Lesson(LocalTime start, LocalTime end, String title, List<Detail> details) {
            this.start = start;
            this.end = end;
            this.title = title;
            this.details = details;
        }

        /** «ДЗ: revision» или «ДЗ: 7А — …; 8А — …», либо null, если задания нет. */
        String homeworkSummary() {
            Map<String, String> hw = new LinkedHashMap<>();
            for (Detail d : details) {
                if (!d.homework.isEmpty()) hw.put(d.cls, d.homework);
            }
            if (hw.isEmpty()) return null;
            if (hw.size() == 1 || allSame(hw.values())) {
                return "ДЗ: " + hw.values().iterator().next();
            }
            StringBuilder sb = new StringBuilder("ДЗ: ");
            boolean first = true;
            for (Map.Entry<String, String> e : hw.entrySet()) {
                if (!first) sb.append("; ");
                sb.append(e.getKey()).append(" — ").append(e.getValue());
                first = false;
            }
            return sb.toString();
        }

        private static boolean allSame(Iterable<String> values) {
            String first = null;
            for (String v : values) {
                if (first == null) first = v;
                else if (!first.equals(v)) return false;
            }
            return true;
        }
    }

    /** Один день: уроки и отметка праздника. */
    static final class Day {
        final boolean holiday;
        /** true — данные из BilimClass, false — запасное расписание. */
        final boolean fromBilim;
        final List<Lesson> lessons;

        Day(boolean holiday, boolean fromBilim, List<Lesson> lessons) {
            this.holiday = holiday;
            this.fromBilim = fromBilim;
            this.lessons = lessons;
        }
    }

    /** Откуда брать расписание на дату. */
    interface Source {
        Day day(LocalDate date);
    }

    /** Запасное расписание (по дням недели). */
    static final Source TEMPLATE = new Source() {
        @Override
        public Day day(LocalDate date) {
            return new Day(false, false, forDay(date.getDayOfWeek()));
        }
    };

    /** Если между уроками меньше этого, считается переменой, иначе «окно». */
    static final int MAX_BREAK_MINUTES = 40;

    private static final Map<DayOfWeek, List<Lesson>> WEEK = new EnumMap<>(DayOfWeek.class);

    static {
        // Запасное расписание (используется, пока не подключён BilimClass).
        add(DayOfWeek.MONDAY, "08:30", "09:00", "Кл. час, 9 класс (каб. 6)");
        add(DayOfWeek.MONDAY, "09:00", "09:45", "7А + 8А");
        add(DayOfWeek.MONDAY, "09:50", "10:35", "5А + 6А");
        add(DayOfWeek.MONDAY, "10:40", "11:25", "9А");
        add(DayOfWeek.MONDAY, "12:45", "13:30", "4А");

        add(DayOfWeek.TUESDAY, "12:45", "13:30", "4А");
        add(DayOfWeek.TUESDAY, "13:35", "14:20", "3А (каб. 4)");

        add(DayOfWeek.WEDNESDAY, "09:00", "09:45", "7А + 8А");
        add(DayOfWeek.WEDNESDAY, "09:50", "10:35", "5А + 6А");

        add(DayOfWeek.THURSDAY, "09:50", "10:35", "5А + 6А");
        add(DayOfWeek.THURSDAY, "11:55", "12:40", "9А");
        add(DayOfWeek.THURSDAY, "12:45", "13:30", "7А + 8А");

        add(DayOfWeek.FRIDAY, "09:00", "09:45", "9А");
        add(DayOfWeek.FRIDAY, "12:45", "13:30", "3А (каб. 4)");
    }

    private static void add(DayOfWeek day, String start, String end, String title) {
        List<Lesson> list = WEEK.get(day);
        if (list == null) {
            list = new ArrayList<>();
            WEEK.put(day, list);
        }
        list.add(new Lesson(LocalTime.parse(start), LocalTime.parse(end), title));
    }

    static List<Lesson> forDay(DayOfWeek day) {
        List<Lesson> list = WEEK.get(day);
        return list == null ? Collections.<Lesson>emptyList() : list;
    }

    private Schedule() {
    }
}
