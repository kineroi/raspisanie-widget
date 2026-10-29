package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Те же проверки, что в версии для Windows (desktop/test/logic.test.js, «подсказки для повтора домашки»). */
public class HomeworkHistoryTest {

    private static HomeworkHistory.Entry e(String text, String cls, String date, String start) {
        return new HomeworkHistory.Entry(text, cls, date, start);
    }

    @Test
    public void suggestions() {
        List<HomeworkHistory.Entry> schedule = Arrays.asList(
                e("ex. 1", "7А", "2026-10-19", "09:00"),
                e("ex. 1", "8А", "2026-10-19", "09:00"),
                e("learn new words", "7А", "2026-10-21", "09:00"),
                e("learn new words", "9В", "2026-10-22", "11:00"));
        List<HomeworkHistory.Entry> history = Arrays.asList(
                e("Workbook p. 12", "6А", "2026-10-20", "12:05"),
                // та же отправка, что уже видна в расписании, — не считается дважды
                e("learn new words", "9В", "2026-10-22", "11:00"));

        HomeworkHistory.Suggestions s = HomeworkHistory.suggest(schedule, history,
                Arrays.asList("7А", "8А"), "2026-10-26", "09:00", 8);
        assertEquals("learn new words", s.last.text);
        assertEquals("2026-10-21", s.last.date);
        assertEquals("7А", s.last.cls);
        // по 2 раза — сначала более свежее
        assertEquals(Arrays.asList("learn new words", "ex. 1", "Workbook p. 12"), s.frequent);

        // Урок раньше всех известных — повторять нечего
        assertNull(HomeworkHistory.suggest(schedule, history, Arrays.asList("7А", "8А"),
                "2026-10-01", "09:00", 8).last);
    }

    @Test
    public void classesFromTitleWhenNoDetails() {
        Schedule.Lesson l = new Schedule.Lesson(LocalTime.of(9, 0), LocalTime.of(9, 45), "7А + 8А");
        assertEquals(new java.util.HashSet<>(Arrays.asList("7А", "8А")), HomeworkHistory.classesOf(l));
    }

    @Test
    public void historyIsCapped() {
        List<HomeworkHistory.Entry> h = new ArrayList<>(Collections.<HomeworkHistory.Entry>emptyList());
        for (int i = 0; i < HomeworkHistory.MAX + 5; i++) h = HomeworkHistory.add(h, e("t" + i, "5А", "2026-10-01", "09:00"));
        assertEquals(HomeworkHistory.MAX, h.size());
        assertEquals("t" + (HomeworkHistory.MAX + 4), h.get(0).text);
    }
}
