package kz.nikolaevka.raspisanie;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * МОЁ РАСПИСАНИЕ.
 * Чтобы изменить расписание, поправьте строки в блоке static { ... } ниже:
 *   add(день, "начало", "конец", "что показывать");
 */
final class Schedule {

    static final class Lesson {
        final LocalTime start;
        final LocalTime end;
        final String title;

        Lesson(LocalTime start, LocalTime end, String title) {
            this.start = start;
            this.end = end;
            this.title = title;
        }
    }

    /** Если между уроками меньше этого, считается переменой, иначе «окно». */
    static final int MAX_BREAK_MINUTES = 40;

    private static final Map<DayOfWeek, List<Lesson>> WEEK = new EnumMap<>(DayOfWeek.class);

    static {
        // Понедельник
        add(DayOfWeek.MONDAY, "08:30", "09:00", "Кл. час, 9 класс (каб. 6)");
        add(DayOfWeek.MONDAY, "09:00", "09:45", "7А + 8А");
        add(DayOfWeek.MONDAY, "09:50", "10:35", "5А + 6А");
        add(DayOfWeek.MONDAY, "10:40", "11:25", "9А");
        add(DayOfWeek.MONDAY, "12:45", "13:30", "4А");

        // Вторник
        add(DayOfWeek.TUESDAY, "12:45", "13:30", "4А");
        add(DayOfWeek.TUESDAY, "13:35", "14:20", "3А (каб. 4)");

        // Среда
        add(DayOfWeek.WEDNESDAY, "09:00", "09:45", "7А + 8А");
        add(DayOfWeek.WEDNESDAY, "09:50", "10:35", "5А + 6А");

        // Четверг
        add(DayOfWeek.THURSDAY, "09:50", "10:35", "5А + 6А");
        add(DayOfWeek.THURSDAY, "11:55", "12:40", "9А");
        add(DayOfWeek.THURSDAY, "12:45", "13:30", "7А + 8А");

        // Пятница
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
