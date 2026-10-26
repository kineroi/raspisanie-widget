package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Те же проверки, что в версии для Windows (desktop/test/grades.test.js). */
public class GradesTest {

    private static String fo(int m) {
        return "{\"formattedScore\":{\"mark\":" + m + ",\"assessmentMax\":10},\"sor\":[],\"soch\":[]}";
    }

    private static final String QUARTER = "{\"data\":{\"group\":\"7 А РУС\",\"subjectName\":\"Иностранный язык\","
            + "\"sorSchedules\":[{\"uuid\":\"sor1\",\"date\":\"01.12.26\",\"markMax\":10},{\"uuid\":\"sor2\",\"date\":\"10.12.26\",\"markMax\":10}],"
            + "\"sochSchedules\":[{\"uuid\":\"soch1\",\"date\":\"20.12.26\",\"markMax\":24}],"
            + "\"finalMarkDeadline\":{\"startDate\":\"2026-12-21\",\"endDate\":\"2026-12-25\"},"
            + "\"rows\":["
            + "{\"studentGroupUuid\":\"a\",\"fullName\":\"Ученик А\",\"finalScore\":\"\",\"canGetFinalScore\":true,\"studentStatus\":true,\"departDate\":null},"
            + "{\"studentGroupUuid\":\"b\",\"fullName\":\"Ученик Б\",\"finalScore\":\"4\",\"canGetFinalScore\":true,\"studentStatus\":true,\"departDate\":null},"
            + "{\"studentGroupUuid\":\"c\",\"fullName\":\"Ученик В\",\"finalScore\":\"\",\"canGetFinalScore\":true,\"studentStatus\":true,\"departDate\":null},"
            + "{\"studentGroupUuid\":\"x\",\"fullName\":\"Выбыл\",\"finalScore\":\"\",\"canGetFinalScore\":true,\"studentStatus\":true,\"departDate\":\"10.11.2026\"}]}}";

    private static final String MARKS = "{\"data\":{"
            + "\"a\":{\"marks\":{\"l1\":" + fo(7) + ",\"l2\":" + fo(7) + ",\"l3\":" + fo(8) + ",\"l4\":" + fo(6) + ","
            + "\"sor1\":{\"formattedScore\":[],\"sor\":{\"mark\":8,\"assessmentMax\":10},\"soch\":[]},"
            + "\"sor2\":{\"formattedScore\":[],\"sor\":{\"mark\":7,\"assessmentMax\":10},\"soch\":[]}}},"
            + "\"b\":{\"marks\":{\"l1\":" + fo(7) + ",\"l2\":" + fo(7) + ",\"l3\":" + fo(8) + ",\"l4\":" + fo(6) + ","
            + "\"sor1\":{\"formattedScore\":[],\"sor\":{\"mark\":8,\"assessmentMax\":10},\"soch\":[]},"
            + "\"sor2\":{\"formattedScore\":[],\"sor\":{\"mark\":7,\"assessmentMax\":10},\"soch\":[]},"
            + "\"soch1\":{\"formattedScore\":[],\"sor\":[],\"soch\":{\"mark\":17,\"assessmentMax\":24}}}},"
            + "\"c\":{\"marks\":{\"l1\":" + fo(4) + ",\"l2\":" + fo(3) + ",\"l3\":{\"formattedScore\":{\"mark\":null},\"sor\":[],\"soch\":[]},"
            + "\"sor1\":{\"formattedScore\":[],\"sor\":{\"mark\":3,\"assessmentMax\":10},\"soch\":[]}}}}}";

    private static Grades.Journal journal(String quarter) throws Exception {
        Grades.Period p = new Grades.Period();
        p.n = 2;
        p.start = "2026-11-02";
        p.end = "2026-12-27";
        return Grades.build("u", 1, "7А", p, new JSONObject(quarter), new JSONObject(MARKS));
    }

    @Test
    public void buildsJournal() throws Exception {
        Grades.Journal j = journal(QUARTER);
        assertEquals("7А", j.cls);
        assertEquals("Иностранный язык", j.subject);
        assertEquals(3, j.students.size());
        assertEquals(4, j.students.get(0).fo.size());
        assertEquals(2, j.students.get(2).fo.size());
        assertEquals("2026-12-20", j.soch.get(0).date);
        assertEquals("2026-12-25", j.deadlineEnd);
    }

    @Test
    public void calculatesLikeBilimClass() throws Exception {
        Grades.Journal j = journal(QUARTER);
        Grades.Stats b = Grades.stats(j.students.get(1));
        assertEquals(Integer.valueOf(72), b.total);
        assertEquals(Integer.valueOf(4), b.grade);
        assertFalse(b.forecast);
        Grades.Stats a = Grades.stats(j.students.get(0));
        assertTrue(a.forecast);
        assertEquals(Integer.valueOf(73), a.total);
        Grades.Stats c = Grades.stats(j.students.get(2));
        assertEquals(Integer.valueOf(33), c.total);
        assertEquals(Integer.valueOf(2), c.grade);
    }

    @Test
    public void siteGradeWins() throws Exception {
        Grades.Journal j = journal(QUARTER.replace("\"studentGroupUuid\":\"a\",", "\"studentGroupUuid\":\"a\",\"recommendedScore\":\"3\","));
        Grades.Stats a = Grades.stats(j.students.get(0));
        assertEquals(Integer.valueOf(73), a.total);
        assertEquals(Integer.valueOf(3), a.grade);
        assertTrue(a.fromSite);
        assertFalse(a.forecast);
    }

    @Test
    public void riskGroup() throws Exception {
        assertEquals("two", Grades.risk(38, null));
        assertEquals("edge2", Grades.risk(42, null));
        assertNull(Grades.risk(45, null));
        assertEquals("edge3", Grades.risk(67, null));
        assertNull(Grades.risk(72, null));
        assertNull(Grades.risk(69, 3));
        assertEquals("two", Grades.risk(50, 2));
        List<Grades.Row> rows = Grades.summary(journal(QUARTER));
        assertEquals("Ученик В", rows.get(0).st.name);
        assertEquals(1, Grades.riskCount(journal(QUARTER)));
    }

    @Test
    public void needForSoch() throws Exception {
        Grades.Journal j = journal(QUARTER);
        Grades.Need n = Grades.needForSoch(j.students.get(0), j);
        assertEquals(24, n.max);
        assertEquals(Integer.valueOf(2), n.need[3]);
        assertEquals(Integer.valueOf(14), n.need[4]);
        assertEquals(Integer.valueOf(24), n.need[5]);
        assertNull(Grades.needForSoch(j.students.get(1), j));
        Grades.Need weak = Grades.needForSoch(j.students.get(2), j);
        assertNull(weak.need[5]);
        assertEquals(Integer.valueOf(12), weak.need[3]);
    }

    @Test
    public void quarterCheck() throws Exception {
        List<Grades.Journal> js = Collections.singletonList(journal(QUARTER));
        assertNull(Grades.quarterCheck(js, new ArrayList<>(), "2026-12-10"));
        List<Grades.Lesson> lessons = Arrays.asList(
                new Grades.Lesson("2026-12-21", "7А", "X", "", false),
                new Grades.Lesson("2026-12-21", "7А", "", "ex 1", false),
                new Grades.Lesson("2026-12-21", "9 класс", "", "", true),
                new Grades.Lesson("2026-10-20", "7А", "", "", false));
        Grades.Check c = Grades.quarterCheck(js, lessons, "2026-12-22");
        assertEquals(5, c.daysLeft);
        assertEquals("2026-12-25", c.deadline);
        assertTrue(c.items.toString(), c.items.contains("7А: не выставлено итоговых — 2"));
        assertTrue(c.items.toString(), c.items.contains("7А: СОР от 10.12 — без оценки 1"));
        assertTrue(c.items.toString(), c.items.contains("7А: СОЧ от 20.12 — без оценки 2"));
        assertTrue(c.items.contains("Уроков без темы — 1"));
        assertTrue(c.items.contains("Уроков без ДЗ — 1"));
        assertNull(Grades.quarterCheck(js, lessons, "2026-12-28"));
    }

    @Test
    public void periodAndStorage() throws Exception {
        Grades.Period q1 = new Grades.Period();
        q1.uuid = "q1"; q1.start = "2026-09-01"; q1.end = "2026-10-25";
        Grades.Period q2 = new Grades.Period();
        q2.uuid = "q2"; q2.start = "2026-11-02"; q2.end = "2026-12-27";
        assertEquals("q1", Grades.pickPeriod(Arrays.asList(q1, q2), "2026-10-26").uuid);
        assertEquals("q2", Grades.pickPeriod(Arrays.asList(q1, q2), "2026-11-05").uuid);
        assertNull(Grades.pickPeriod(Arrays.asList(q1, q2), "2026-08-20"));
        assertEquals("2026-09-03", Grades.dmy("03.09.26"));

        // сохранение и чтение дают тот же результат
        Grades.Journal j = journal(QUARTER);
        JSONObject saved = Grades.toJson(Collections.singletonList(j), new ArrayList<>(), 1L);
        Grades.Journal back = Grades.journalsFromJson(new JSONObject(saved.toString())).get(0);
        assertEquals(Grades.stats(j.students.get(1)).total, Grades.stats(back.students.get(1)).total);
        assertEquals(j.soch.get(0).uuid, back.soch.get(0).uuid);
    }
}
