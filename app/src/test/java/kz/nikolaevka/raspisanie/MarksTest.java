package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** То же, что desktop/test/marks.test.js (ученики выдуманные). */
public class MarksTest {

    private static final String QUARTER = "{\"data\":{\"journalMaxScore\":\"10\",\"lessonSchedules\":["
            + "{\"uuid\":\"L1\",\"date\":\"02.11.26\",\"isCompletedLesson\":true,\"canBeCompleted\":true,\"markMax\":10,\"canManageScores\":true},"
            + "{\"uuid\":\"L2\",\"date\":\"04.11.26\",\"isCompletedLesson\":false,\"canBeCompleted\":true,\"markMax\":10,\"canManageScores\":true}],"
            + "\"rows\":[{\"studentGroupUuid\":\"S2\",\"fullName\":\"Петров Пётр\"},"
            + "{\"studentGroupUuid\":\"S1\",\"fullName\":\"Иванова Анна\"},"
            + "{\"studentGroupUuid\":\"S3\",\"fullName\":\"Выбывший Ученик\",\"departDate\":\"01.10.2026\"}]}}";

    private static final String MARKS = "{\"data\":{"
            + "\"S1\":{\"studentGroupUuid\":\"S1\",\"marks\":{\"L1\":{\"attendance\":\"\",\"formattedScore\":"
            + "{\"mark\":7,\"comment\":\"Хорошо\",\"markUuid\":\"M1\",\"assessmentMax\":10,\"type\":\"formattedScore\"}}}},"
            + "\"S2\":{\"studentGroupUuid\":\"S2\",\"marks\":{}}}}";

    private static Marks.Lesson lesson(String uuid) throws Exception {
        return Marks.parseLesson(new JSONObject(QUARTER), new JSONObject(MARKS), uuid);
    }

    private static Marks.Edit edit(String mark, String comment) {
        Marks.Edit e = new Marks.Edit();
        e.mark = mark;
        e.comment = comment;
        return e;
    }

    @Test
    public void parsesLesson() throws Exception {
        Marks.Lesson l = lesson("L1");
        assertTrue(l.found);
        assertTrue(l.completed);
        assertTrue(l.canManage);
        assertEquals(10, l.markMax);
        assertEquals(2, l.students.size());
        assertEquals("Иванова Анна", l.students.get(0).name);
        assertEquals(Integer.valueOf(7), l.students.get(0).mark);
        assertEquals("M1", l.students.get(0).markUuid);
        assertEquals("Хорошо", l.students.get(0).comment);
        assertNull(l.students.get(1).mark);
        assertFalse(lesson("L2").completed);
        assertFalse(lesson("нет").found);
    }

    @Test
    public void changes() throws Exception {
        Marks.Lesson l = lesson("L1");
        Map<String, Marks.Edit> e = new HashMap<>();
        e.put("S1", edit("7", "Хорошо"));
        e.put("S2", edit("9", "Молодец"));
        List<Marks.Change> ch = Marks.changes(l.students, e, 10);
        assertEquals(1, ch.size());
        assertEquals("S2", ch.get(0).id);
        assertTrue(ch.get(0).create);
        assertEquals(9, ch.get(0).mark);

        e.clear();
        e.put("S1", edit("8", "Хорошо"));
        ch = Marks.changes(l.students, e, 10);
        assertEquals(1, ch.size());
        assertFalse(ch.get(0).create);
        assertEquals("M1", ch.get(0).markUuid);

        e.clear();
        e.put("S1", edit("", "Отлично"));
        ch = Marks.changes(l.students, e, 10);
        assertEquals(7, ch.get(0).mark);
        assertEquals("Отлично", ch.get(0).comment);

        e.clear();
        e.put("S2", edit("", "Молодец"));
        assertEquals(0, Marks.changes(l.students, e, 10).size());
        e.put("S2", edit("11", ""));
        assertEquals(0, Marks.changes(l.students, e, 10).size());
    }

    @Test
    public void bodiesLikeSite() throws Exception {
        Marks.Change ch = new Marks.Change();
        ch.id = "S2";
        ch.mark = 9;
        ch.comment = "Молодец";
        JSONObject c = Marks.createBody(97, 2026, "1007", 11, 22, "L1", "2026-11-02", 10, ch);
        assertEquals(1007, c.getLong("schoolId"));
        assertEquals("2026-11-02T00:00:00.000Z", c.getString("date"));
        assertEquals("formattedScore", c.getString("markType"));
        assertEquals("S2", c.getString("studentGroupUuid"));
        ch.markUuid = "M1";
        JSONObject u = Marks.updateBody(97, 10, ch);
        assertEquals("M1", u.getString("uuid"));
        assertFalse(u.has("scheduleUuid"));
    }

    @Test
    public void commentFitsLimit() {
        assertEquals("Молодец.\nWell done.", Marks.fitComment("Молодец.", "Well done.", 250));
        StringBuilder ru = new StringBuilder(), en = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            ru.append("Очень длинное предложение ");
            en.append("A very long sentence ");
        }
        String c = Marks.fitComment(ru.toString(), en.toString(), 250);
        assertTrue(c.length() <= 250);
        String[] p = c.split("\n");
        assertTrue(p[0].startsWith("Очень") && p[0].endsWith("…"));
        assertTrue(p[1].startsWith("A very") && p[1].endsWith("…"));
        assertTrue(Marks.systemPrompt(249).contains("не длиннее 124 символов"));
        assertEquals("2026-12-27", Marks.isoFromDmy("27.12.26"));
        assertTrue(Marks.validMark("0", 10));
        assertFalse(Marks.validMark("7.5", 10));
    }
}
