package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

/** То же, что desktop/test/absence.test.js (ученики выдуманные). */
public class AbsenceTest {

    private static Marks.Student st(String id, String name, String attendance) {
        Marks.Student s = new Marks.Student();
        s.id = id;
        s.name = name;
        s.attendance = attendance;
        return s;
    }

    private static Marks.Lesson lesson() {
        Marks.Lesson l = new Marks.Lesson();
        l.cls = "4А";
        l.completed = true;
        l.canBeCompleted = true;
        l.canManage = true;
        l.students.addAll(Arrays.asList(
                st("S1", "ИВАНОВА АННА ПЕТРОВНА", ""),
                st("S2", "Котов Илья Сергеевич", "missing_by_sick"),
                st("S3", "Лис Кирилл", "late"),
                st("S4", "Сидоров Пётр", "missing_due")));
        return l;
    }

    @Test
    public void classesAndNames() {
        assertTrue(Absence.sameClass("4 класс", "4А"));
        assertTrue(Absence.sameClass("4 А класс", "4А"));
        assertFalse(Absence.sameClass("4 Б класс", "4А"));
        assertFalse(Absence.sameClass("5 класс", "4А"));
        assertEquals(Absence.nameKey("ИВАНОВА АННА ПЕТРОВНА"), Absence.nameKey("Иванова Анна"));
        assertEquals(Absence.nameKey("Сидоров Петр Иванович"), Absence.nameKey("Сидоров Пётр"));
    }

    @Test
    public void plansLesson() {
        Absence.LessonPlan p = Absence.plan(lesson(), Arrays.asList(
                new String[]{"Иванова Анна Петровна", "absent"},
                new String[]{"Котов Илья", "sick"},
                new String[]{"Петров Неизвестный", "family"}),
                Arrays.asList("Сидоров Пётр", "Лис Кирилл"));
        assertEquals(2, p.send.size());
        assertEquals("S1", p.send.get(0).id);
        assertEquals("missing_without_reason", p.send.get(0).code);
        assertEquals("S4", p.send.get(1).id);
        assertEquals("was_in_class", p.send.get(1).code);
        assertEquals(Collections.singletonList("Петров Неизвестный"), p.unmatched);
        assertEquals("", p.blocked);

        Marks.Lesson notDone = lesson();
        notDone.completed = false;
        Absence.LessonPlan b = Absence.plan(notDone, Collections.singletonList(new String[]{"Лис Кирилл", "sick"}),
                Collections.<String>emptyList());
        assertEquals("урок не отмечен проведённым", b.blocked);
        assertTrue(b.send.isEmpty());
    }

    @Test
    public void bodyLikeSite() throws Exception {
        Marks.Lesson l = lesson();
        l.eduYear = 2026;
        l.groupId = 11;
        l.subjectId = 22;
        l.scheduleUuid = "L1";
        JSONObject body = Absence.bulkBody(97, "1007", LocalDate.of(2026, 11, 2), l,
                Collections.singletonList(new Absence.Item("S1", "x", "missing_by_sick")));
        assertEquals(97, body.getLong("userId"));
        JSONObject a = body.getJSONArray("attendances").getJSONObject(0);
        assertEquals("S1", a.getString("studentGroupUuid"));
        assertEquals("L1", a.getString("scheduleUuid"));
        assertEquals("2026-11-02T00:00:00.000Z", a.getString("date"));
        assertEquals("missing_by_sick", a.getString("attendance"));
        assertEquals(1007, a.getLong("schoolId"));
        assertEquals("missing_by_sick", Absence.code("sick"));
        assertEquals("missing_due", Absence.code("other"));
    }
}
