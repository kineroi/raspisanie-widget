package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** То же, что desktop/test/attendance.test.js. */
public class AttendanceTest {

    @Test
    public void namesAreFixed() {
        assertEquals("Иванов Иван Иванович", Attendance.fixName("ИВАНОВ ИВАН ИВАНОВИЧ"));
        assertEquals("Петров Петр Петрович", Attendance.fixName("Петров ПетрПетрович "));
        assertEquals("Сидорова Анна", Attendance.fixName("Сидоровa Анна"));
        assertEquals("Мамедов Али Гусейн оглы", Attendance.fixName("мамедов али гусейн оглы"));
        assertEquals("Римская-Корсакова Ольга", Attendance.fixName("Римская-корсакова Ольга"));
    }

    @Test
    public void schoolDays() {
        JSONObject j = Attendance.empty();
        List<String> nov = Attendance.schoolDays(j, "2026-11");
        assertEquals("2026-11-02", nov.get(0));
        assertEquals(21, nov.size());
        List<String> dec = Attendance.schoolDays(j, "2026-12");
        assertFalse(dec.contains("2026-12-16"));
        assertEquals("2026-12-25", dec.get(dec.size() - 1));
        assertEquals("2027-01-11", Attendance.schoolDays(j, "2027-01").get(0));
        assertFalse(Attendance.schoolDays(j, "2027-03").contains("2027-03-23"));
        assertEquals(Arrays.asList("2026-11", "2026-12", "2027-01", "2027-02", "2027-03", "2027-04", "2027-05"), Attendance.months());
        assertEquals("2026-11", Attendance.defaultMonth("2026-10-29"));
        assertEquals("Ноябрь 2026", Attendance.monthTitle("2026-11"));

        Attendance.setSchoolDay(j, "2026-11-06", false);
        assertFalse(Attendance.isSchoolDay(j, "2026-11-06"));
        Attendance.setSchoolDay(j, "2026-11-06", true);
        assertEquals(0, j.optJSONArray("offDays").length());
        Attendance.setSchoolDay(j, "2026-12-16", true);
        assertTrue(Attendance.schoolDays(j, "2026-12").contains("2026-12-16"));
    }

    @Test
    public void marksAndStats() {
        JSONObject j = Attendance.empty();
        JSONObject c = Attendance.addClass(j, "4 класс");
        String sid = Attendance.addStudent(c, "Иванов Иван").optString("id");
        Attendance.setMark(j, "2026-11-02", sid, "sick", null);
        Attendance.setMark(j, "2026-11-03", sid, "absent", null);
        Attendance.setMark(j, "2026-11-04", sid, "other", "  соревнования ");
        assertEquals("соревнования", Attendance.getMark(j, "2026-11-04", sid).optString("note"));
        assertEquals("н", Attendance.markCode(Attendance.getMark(j, "2026-11-03", sid)));
        Attendance.Stats st = Attendance.monthStats(j, "2026-11", sid);
        assertEquals(3, st.total);
        assertEquals(1, st.invalid);
        Attendance.setMark(j, "2026-11-02", sid, "", null);
        assertNull(Attendance.getMark(j, "2026-11-02", sid));
        assertFalse(j.optJSONObject("marks").has("2026-11-02"));
        Attendance.removeStudent(j, c, sid);
        assertEquals(0, j.optJSONObject("marks").length());
    }

    @Test
    public void alerts() {
        JSONObject j = Attendance.empty();
        JSONObject c = Attendance.addClass(j, "5 класс");
        String a = Attendance.addStudent(c, "Болеющий Ученик").optString("id");
        String b = Attendance.addStudent(c, "Прогульщик Ученик").optString("id");
        String d = Attendance.addStudent(c, "Хороший Ученик").optString("id");
        for (String day : new String[]{"2026-11-05", "2026-11-06", "2026-11-09", "2026-11-10"}) Attendance.setMark(j, day, a, "sick", null);
        Attendance.setMark(j, "2026-11-03", b, "absent", null);
        Attendance.setMark(j, "2026-11-09", b, "absent", null);
        Attendance.setMark(j, "2026-11-10", d, "sick", null);

        List<Attendance.Alert> list = Attendance.alerts(j, "2026-11-11");
        assertEquals(2, list.size());
        assertEquals("Болеющий Ученик (5 класс) болеет 4 учебных дня подряд — с 05.11", Attendance.alertText(list.get(0)));
        assertEquals("Прогульщик Ученик (5 класс): 2 пропуска без уважительной причины за 30 дней, последний 09.11",
                Attendance.alertText(list.get(1)));
        assertEquals(0, Attendance.alerts(j, "2026-12-15").size());
    }

    @Test
    public void templateImport() {
        String xml = "<w:body>" + p("Список учащихся 9 класса СЕНТЯБРЬ")
                + "<w:tbl>" + tr("№", "ФИО", "1") + tr("1", "ПЕТРОВ ПЁТР", "") + tr("2", "Сидоров Сидор&lt;", "") + "</w:tbl>"
                + p("Список учащихся 2 «Б» класса")
                + "<w:tbl>" + tr("№", "ФИО") + tr("1", "Иванова Анна") + "</w:tbl></w:body>";
        List<Attendance.Parsed> parsed = Attendance.parseTemplate(xml);
        assertEquals(2, parsed.size());
        assertEquals("9 класс", parsed.get(0).name);
        assertEquals(Arrays.asList("Петров Пётр", "Сидоров Сидор<"), parsed.get(0).students);
        assertEquals("2 Б класс", parsed.get(1).name);
        JSONObject j = Attendance.empty();
        assertEquals(Arrays.toString(new int[]{2, 3}), Arrays.toString(Attendance.importClasses(j, parsed)));
        assertEquals(Arrays.toString(new int[]{0, 0}), Arrays.toString(Attendance.importClasses(j, parsed)));
        assertEquals("2 Б класс", Attendance.sortedClasses(j).get(0).optString("name"));
    }

    @Test
    public void wordExport() throws Exception {
        JSONObject j = Attendance.empty();
        JSONObject c = Attendance.addClass(j, "4 класс");
        String sid = Attendance.addStudent(c, "Иванов Иван Иванович").optString("id");
        Attendance.setMark(j, "2026-11-02", sid, "absent", null);
        Attendance.setMark(j, "2026-11-03", sid, "other", "олимпиада <район>");
        String xml = Attendance.documentXml(j, "2026-11");
        assertTrue(xml.contains("Список учащихся 4 класса НОЯБРЬ 2026"));
        assertTrue(xml.contains("w:orient=\"landscape\""));
        assertTrue(xml.contains("w:fill=\"F2DCDB\""));
        assertTrue(xml.contains("Иванов И.И. (4 класс), 03.11: олимпиада &lt;район&gt;"));
        String header = xml.split("<w:tr>")[1];
        assertEquals(25, header.split("<w:tc>", -1).length - 1);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Attendance.writeDocx(j, "2026-11", out);
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries++;
                if (e.getName().equals("word/document.xml")) {
                    assertEquals(xml, new String(Attestation.readAll(zip, 1 << 22), StandardCharsets.UTF_8));
                }
            }
        }
        assertEquals(3, entries);
        assertEquals("Посещаемость — Ноябрь 2026.docx", Attendance.fileName("2026-11"));
    }

    private static String p(String t) {
        return "<w:p><w:r><w:t xml:space=\"preserve\">" + t + "</w:t></w:r></w:p>";
    }

    private static String tr(String... cells) {
        StringBuilder sb = new StringBuilder("<w:tr w:rsidR=\"1\">");
        for (String c : cells) sb.append("<w:tc><w:tcPr/>").append(p(c)).append("</w:tc>");
        return sb.append("</w:tr>").toString();
    }
}
