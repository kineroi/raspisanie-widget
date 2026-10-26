package kz.nikolaevka.raspisanie;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Что показать на виджете в данный момент. Без Android-кода, чтобы легко проверять. */
final class WidgetState {

    enum Row { PAST, CURRENT, NEXT, NORMAL }

    static final String[] DAY_NAMES = {
            "Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
    private static final String[] IN_DAY = {
            "В понедельник", "Во вторник", "В среду", "В четверг", "В пятницу", "В субботу", "В воскресенье"};
    static final String[] MONTHS = {
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря"};
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    String dayName;
    String dateText;
    String status;
    /** Момент, до которого идёт обратный отсчёт (или null). */
    LocalDateTime countdownTo;
    /** Подпись к отсчёту, %s заменяется на оставшееся время. */
    String countdownFormat;
    String subText;
    String listTitle;
    List<Schedule.Lesson> lessons;
    Row[] rows;
    /** Когда виджет нужно обновить в следующий раз. */
    LocalDateTime nextUpdate;

    static String hm(LocalTime t) {
        return t.format(HM);
    }

    static String range(Schedule.Lesson l) {
        return hm(l.start) + "–" + hm(l.end);
    }

    static String dateText(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1];
    }

    static WidgetState compute(LocalDateTime now) {
        return compute(now, Schedule.TEMPLATE);
    }

    static WidgetState compute(LocalDateTime now, Schedule.Source source) {
        WidgetState s = new WidgetState();
        LocalDate date = now.toLocalDate();
        LocalTime t = now.toLocalTime();
        DayOfWeek dow = date.getDayOfWeek();

        s.dayName = DAY_NAMES[dow.getValue() - 1];
        s.dateText = dateText(date);

        Schedule.Day day = source.day(date);
        List<Schedule.Lesson> today = day.lessons;
        LocalDateTime midnight = date.plusDays(1).atStartOfDay().plusSeconds(5);

        if (today.isEmpty()) {
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            if (weekend) {
                s.status = "Выходной 🎉";
            } else if (day.holiday) {
                s.status = "Праздничный день 🎉";
            } else {
                s.status = "Сегодня уроков нет";
            }
            LocalDate next = nextSchoolDay(date, source);
            if (next != null) {
                List<Schedule.Lesson> nl = source.day(next).lessons;
                s.subText = whenText(date, next) + ": с " + hm(nl.get(0).start) + " · " + nl.get(0).title;
                s.listTitle = DAY_NAMES[next.getDayOfWeek().getValue() - 1] + ", " + dateText(next)
                        + " · уроков: " + countLessons(nl);
                s.lessons = nl;
                s.rows = fill(nl.size(), Row.NORMAL);
            } else {
                s.lessons = today;
                s.rows = new Row[0];
                s.listTitle = "";
            }
            s.nextUpdate = midnight;
            return s;
        }

        s.lessons = today;
        s.rows = fill(today.size(), Row.NORMAL);
        s.listTitle = "Мои уроки сегодня · " + countLessons(today);

        int current = -1;
        int next = -1;
        for (int i = 0; i < today.size(); i++) {
            Schedule.Lesson l = today.get(i);
            if (!t.isBefore(l.start) && t.isBefore(l.end)) {
                current = i;
            }
            if (next == -1 && t.isBefore(l.start)) {
                next = i;
            }
            if (!t.isBefore(l.end)) {
                s.rows[i] = Row.PAST;
            }
        }

        if (current >= 0) {
            Schedule.Lesson l = today.get(current);
            s.rows[current] = Row.CURRENT;
            s.status = "Сейчас: " + l.title;
            s.countdownTo = date.atTime(l.end);
            s.countdownFormat = "до звонка %s (в " + hm(l.end) + ")";
            s.subText = l.homeworkSummary();
        } else if (next >= 0) {
            Schedule.Lesson l = today.get(next);
            s.rows[next] = Row.NEXT;
            if (next == 0) {
                s.status = "Уроки с " + hm(l.start) + " · " + l.title;
            } else {
                Schedule.Lesson prev = today.get(next - 1);
                long gap = Duration.between(prev.end, l.start).toMinutes();
                if (gap < Schedule.MAX_BREAK_MINUTES) {
                    s.status = "Перемена · далее " + l.title;
                } else {
                    s.status = "Окно · далее " + l.title;
                }
            }
            s.countdownTo = date.atTime(l.start);
            s.countdownFormat = "через %s (в " + hm(l.start) + ")";
        } else {
            s.status = "Уроки на сегодня закончились";
            LocalDate nd = nextSchoolDay(date, source);
            if (nd != null) {
                List<Schedule.Lesson> nl = source.day(nd).lessons;
                s.subText = whenText(date, nd) + ": с " + hm(nl.get(0).start) + " · " + nl.get(0).title;
            }
        }

        // Следующее обновление: ближайший звонок (начало или конец урока) после текущего момента.
        LocalDateTime upd = midnight;
        for (Schedule.Lesson l : today) {
            LocalDateTime a = date.atTime(l.start);
            LocalDateTime b = date.atTime(l.end);
            if (a.isAfter(now) && a.isBefore(upd)) upd = a;
            if (b.isAfter(now) && b.isBefore(upd)) upd = b;
        }
        s.nextUpdate = upd.equals(midnight) ? upd : upd.plusSeconds(1);
        return s;
    }

    /** Классный час не считается уроком английского. */
    static int countLessons(List<Schedule.Lesson> list) {
        int n = 0;
        for (Schedule.Lesson l : list) {
            if (!l.title.startsWith("Кл. час")) n++;
        }
        return n;
    }

    /** Ближайший день с уроками (смотрим до трёх недель вперёд — на случай каникул). */
    private static LocalDate nextSchoolDay(LocalDate from, Schedule.Source source) {
        for (int i = 1; i <= 21; i++) {
            LocalDate d = from.plusDays(i);
            if (!source.day(d).lessons.isEmpty()) return d;
        }
        return null;
    }

    private static String whenText(LocalDate today, LocalDate d) {
        if (d.equals(today.plusDays(1))) return "Завтра";
        if (d.isAfter(today.plusDays(6))) return dateText(d);
        return IN_DAY[d.getDayOfWeek().getValue() - 1];
    }

    private static Row[] fill(int n, Row r) {
        Row[] a = new Row[n];
        for (int i = 0; i < n; i++) a[i] = r;
        return a;
    }
}
