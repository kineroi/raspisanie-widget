package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/** Проверка разбора ответа BilimClass на выдуманном примере той же структуры. */
public class BilimDataTest {

    private static String lesson(String start, String end, String cls, boolean additional,
                                 String theme, String hw, String cab) {
        return "{\"time\":{\"timeStart\":\"" + start + "\",\"timeEnd\":\"" + end + "\"},"
                + "\"class\":{\"label\":\"" + cls + "\"},"
                + "\"subject\":{\"label\":\"Шетел тілі\",\"isAdditional\":" + additional + "},"
                + "\"theme\":{\"label\":\"" + theme + "\"},"
                + "\"scheduleUuid\":\"uuid-" + cls.replace(" ", "") + "-" + start + "\","
                + "\"homework\":{\"body\":\"" + hw + "\",\"files\":"
                + (cls.equals("3 А") ? "[{\"id\":77,\"name\":\"card\",\"extension\":\".jpeg\",\"link\":\"https://x/y\"}]" : "[]")
                + "},"
                + "\"cabinet\":{\"number\":" + (cab == null ? "null" : "\"" + cab + "\"") + "},"
                + "\"subgroup\":null}";
    }

    private static final String WEEK = "{\"data\":{\"days\":["
            + "{\"date\":\"02.11.2026\",\"isHoliday\":false},"
            + "{\"date\":\"03.11.2026\",\"isHoliday\":false},"
            + "{\"date\":\"04.11.2026\",\"isHoliday\":true},"
            + "{\"date\":\"05.11.2026\",\"isHoliday\":false},"
            + "{\"date\":\"06.11.2026\",\"isHoliday\":false},"
            + "{\"date\":\"07.11.2026\",\"isHoliday\":false},"
            + "{\"date\":\"08.11.2026\",\"isHoliday\":false}],"
            + "\"rows\":["
            // строки упорядочены не по времени — как в настоящем ответе
            + "{\"02.11.2026\":[" + lesson("08:30", "09:00", "9 класс", true,
                    "Кл. час «Тестовая тема»\\nЕщё строка", "не проставляется", "6") + "],"
            + "\"03.11.2026\":[" + lesson("13:35", "14:20", "3 А", false, "Colours", "p. 10", "4") + "]},"
            + "{\"02.11.2026\":["
            + lesson("09:00", "09:45", "7 А", false, "Theme A", "ex. 1", null) + ","
            + lesson("09:00", "09:45", "8 А", false, "Theme B", "ex. 2", null) + "]},"
            + "{\"02.11.2026\":[" + lesson("12:45", "13:30", "4 А", false, "Animals", "", null) + "],"
            + "\"03.11.2026\":[" + lesson("12:45", "13:30", "4 А", false, "Food", "revision", null) + "]},"
            + "{\"02.11.2026\":["
            + lesson("09:50", "10:35", "5 А", false, "Heroes", "revision", null) + ","
            + lesson("09:50", "10:35", "6 А", false, "Sport", "revision", null) + "]}"
            + "]}}";

    private static final String EMPTY_WEEK = "{\"data\":{\"days\":["
            + "{\"date\":\"26.10.2026\",\"isHoliday\":true},"
            + "{\"date\":\"27.10.2026\",\"isHoliday\":false},"
            + "{\"date\":\"28.10.2026\",\"isHoliday\":false},"
            + "{\"date\":\"29.10.2026\",\"isHoliday\":false},"
            + "{\"date\":\"30.10.2026\",\"isHoliday\":false},"
            + "{\"date\":\"31.10.2026\",\"isHoliday\":false},"
            + "{\"date\":\"01.11.2026\",\"isHoliday\":false}],\"rows\":[]}}";

    @Test
    public void parsesAndGroupsLessons() throws Exception {
        Map<LocalDate, Schedule.Day> days = BilimData.parseWeek(new JSONObject(WEEK));
        assertEquals(7, days.size());

        List<Schedule.Lesson> mon = days.get(LocalDate.of(2026, 11, 2)).lessons;
        assertEquals(4, mon.size());
        assertEquals("Кл. час, 9 класс (каб. 6)", mon.get(0).title);
        assertEquals(LocalTime.of(8, 30), mon.get(0).start);
        assertNull(mon.get(0).homeworkSummary());
        assertEquals("Кл. час «Тестовая тема» Ещё строка", mon.get(0).details.get(0).theme);

        assertEquals("7А + 8А", mon.get(1).title);
        assertEquals("ДЗ: 7А — ex. 1; 8А — ex. 2", mon.get(1).homeworkSummary());
        assertEquals("5А + 6А", mon.get(2).title);
        assertEquals("ДЗ: revision", mon.get(2).homeworkSummary());
        assertEquals("4А", mon.get(3).title);
        assertNull(mon.get(3).homeworkSummary());

        List<Schedule.Lesson> tue = days.get(LocalDate.of(2026, 11, 3)).lessons;
        assertEquals(2, tue.size());
        assertEquals("4А", tue.get(0).title);
        assertEquals("3А (каб. 4)", tue.get(1).title);

        Schedule.Day wed = days.get(LocalDate.of(2026, 11, 4));
        assertTrue(wed.holiday);
        assertTrue(wed.lessons.isEmpty());
        assertTrue(wed.fromBilim);
    }

    @Test
    public void widgetUsesBilimData() throws Exception {
        String cache = "[" + EMPTY_WEEK + "," + WEEK + "]";
        Schedule.Source src = BilimData.source(BilimData.parseCache(cache));

        // Во время урока 7А + 8А на виджете видна домашка
        WidgetState s = WidgetState.compute(LocalDateTime.of(2026, 11, 2, 9, 10), src);
        assertEquals("Сейчас: 7А + 8А", s.status);
        assertEquals("ДЗ: 7А — ex. 1; 8А — ex. 2", s.subText);
        assertEquals(WidgetState.Row.CURRENT, s.rows[1]);
        assertEquals("7А + 8А", s.heroTitle);
        assertEquals("Сейчас идёт урок", s.heroLabel);
        assertEquals("до звонка", s.countdownLabel);
        assertTrue(!s.progressFrom.isAfter(LocalDateTime.of(2026, 11, 2, 9, 10)));
        assertTrue(s.countdownTo.isAfter(LocalDateTime.of(2026, 11, 2, 9, 10)));
        assertTrue(s.counter, s.counter.startsWith("2 из "));

        // Каникулы: уроков нет, показывается ближайший учебный день (через неделю)
        WidgetState h = WidgetState.compute(LocalDateTime.of(2026, 10, 26, 10, 0), src);
        assertEquals("Праздничный день 🎉", h.status);
        assertEquals(h.status, h.heroTitle);
        assertNull(h.counter);
        assertNull(h.countdownTo);
        assertTrue(h.subText, h.subText.startsWith("2 ноября: с 08:30"));

        WidgetState h2 = WidgetState.compute(LocalDateTime.of(2026, 10, 27, 10, 0), src);
        assertEquals("Сегодня уроков нет", h2.status);
    }

    @Test
    public void lessonIdsAndFiles() throws Exception {
        Map<LocalDate, Schedule.Day> days = BilimData.parseWeek(new JSONObject(WEEK));
        Schedule.Lesson l78 = days.get(LocalDate.of(2026, 11, 2)).lessons.get(1);
        assertEquals("uuid-7А-09:00", l78.details.get(0).scheduleUuid);
        assertEquals("uuid-8А-09:00", l78.details.get(1).scheduleUuid);
        Schedule.Lesson l3 = days.get(LocalDate.of(2026, 11, 3)).lessons.get(1);
        assertEquals(1, l3.details.get(0).files.size());
        assertEquals(77, l3.details.get(0).files.get(0).id);
        assertEquals("card.jpeg", l3.details.get(0).files.get(0).name);
    }

    @Test
    public void widgetListDate() throws Exception {
        String cache = "[" + EMPTY_WEEK + "," + WEEK + "]";
        Schedule.Source src = BilimData.source(BilimData.parseCache(cache));
        WidgetState h = WidgetState.compute(LocalDateTime.of(2026, 10, 26, 10, 0), src);
        assertEquals(LocalDate.of(2026, 11, 2), h.listDate);
        WidgetState s = WidgetState.compute(LocalDateTime.of(2026, 11, 2, 9, 10), src);
        assertEquals(LocalDate.of(2026, 11, 2), s.listDate);
    }

    @Test
    public void homeworkRules() {
        assertEquals("Задание: Страница 45, упражнение 3 (письменно), выучить слова.\n"
                        + "Task: Page 45, exercise 3 (in writing), learn the words.",
                HomeworkRules.expand("стр 45 упр 3 письм, сл выуч"));
        assertEquals("Задание: Рабочая тетрадь, страница 12, упражнение 1-2.\n"
                        + "Task: Workbook, page 12, exercise 1-2.",
                HomeworkRules.expand("р.т. стр. 12 упр 1-2"));
        assertEquals("", HomeworkRules.expand("  "));
    }

    @Test
    public void geminiRequestAndResponse() throws Exception {
        JSONObject req = new JSONObject(Gemini.buildRequest("стр 45", "9А", "Heroes"));
        String user = req.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getString("text");
        assertTrue(user.contains("Класс: 9А"));
        assertTrue(user.contains("Тема урока: Heroes"));
        assertTrue(user.contains("Заметка учителя: стр 45"));
        assertTrue(req.getJSONObject("systemInstruction").toString().contains("Task:"));

        String resp = "{\"candidates\":[{\"content\":{\"parts\":["
                + "{\"text\":\"думаю...\",\"thought\":true},"
                + "{\"text\":\"**Задание:** Страница 45.\\n\\nTask: Page 45.\"}]}}]}";
        assertEquals("Задание: Страница 45.\nTask: Page 45.", Gemini.parseResponse(resp));
        assertEquals("", Gemini.parseResponse("{\"candidates\":[]}"));
    }

    @Test
    public void reminderFiveMinutesBefore() throws Exception {
        Schedule.Source src = BilimData.source(BilimData.parseCache("[" + EMPTY_WEEK + "," + WEEK + "]"));
        Reminders.Next n = Reminders.next(LocalDateTime.of(2026, 11, 2, 7, 0), src);
        assertEquals(LocalDateTime.of(2026, 11, 2, 8, 25), n.at);
        n = Reminders.next(LocalDateTime.of(2026, 11, 2, 8, 56), src);
        assertEquals(LocalTime.of(9, 50), n.lesson.start);
        n = Reminders.next(LocalDateTime.of(2026, 10, 26, 12, 0), src);
        assertEquals(LocalDate.of(2026, 11, 2), n.date);
        Schedule.Lesson l78 = src.day(LocalDate.of(2026, 11, 2)).lessons.get(1);
        assertEquals("Через 5 минут: 7А + 8А", Reminders.title(l78));
        assertEquals("09:00–09:45 · 7А: Theme A · 8А: Theme B", Reminders.body(l78));
    }

    @Test
    public void updateVersionFromTag() {
        assertEquals(12, Updater.codeFromTag("v3.0.12"));
        assertEquals(5, Updater.codeFromTag("v5"));
        assertEquals(0, Updater.codeFromTag(""));
    }

    @Test
    public void compactClassNames() {
        assertEquals("7А", BilimData.compactClass("7 А"));
        assertEquals("9 класс", BilimData.compactClass("9 класс"));
        assertEquals("10Б", BilimData.compactClass(" 10 Б "));
    }

    @Test
    public void templateStillWorks() {
        WidgetState s = WidgetState.compute(LocalDateTime.of(2026, 10, 19, 9, 47));
        assertEquals("Перемена · далее 5А + 6А", s.status);
        assertEquals("5А + 6А", s.heroTitle);
        assertEquals("Перемена · далее", s.heroLabel);
        assertEquals("до урока", s.countdownLabel);
        assertNotNull(s.progressFrom);
        assertTrue(s.progressFrom.isBefore(s.countdownTo));
    }
}
