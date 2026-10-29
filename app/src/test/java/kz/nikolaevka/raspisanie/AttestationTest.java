package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public class AttestationTest {

    @Test
    public void classificationIsCleanedUp() throws Exception {
        Attestation.Meta m = Attestation.parseClassification(new JSONObject()
                .put("section", "awards").put("level", "district").put("date", "2026-10-15")
                .put("kind", "«Грамота»").put("title", "  Лучший   урок ").put("issuer", "Районный отдел"));
        assertEquals("awards", m.section);
        assertEquals("district", m.level);
        assertEquals("2026-10-15", m.date);
        assertEquals("Грамота", m.kind);
        assertEquals("Лучший урок", m.title);
        assertEquals("Районный отдел", m.issuer);
    }

    @Test
    public void unknownValuesBecomeSafe() throws Exception {
        Attestation.Meta m = Attestation.parseClassification(new JSONObject()
                .put("section", "медали").put("level", "город").put("date", "15.10.2026"));
        assertEquals("other", m.section);
        assertEquals("", m.level);
        assertEquals("", m.date);
        assertEquals("Документ", m.kind);
        assertEquals("other", Attestation.parseClassification(null).section);
    }

    @Test
    public void targetMatchesDesktopFolders() throws Exception {
        Attestation.Meta m = Attestation.parseClassification(new JSONObject()
                .put("section", "awards").put("level", "national").put("date", "2026-10-15")
                .put("kind", "Грамота").put("title", "За вклад: 2026/27"));
        Attestation.Target t = Attestation.target(m, ".PDF");
        assertEquals(Arrays.asList("2. Грамоты и благодарности", "Республиканский и международный"), t.parts);
        assertEquals("2026-10-15 Грамота — За вклад 2026 27.pdf", t.name);
        assertEquals("Аттестация / 2. Грамоты и благодарности / Республиканский и международный / "
                + "2026-10-15 Грамота — За вклад 2026 27.pdf", t.path());

        Attestation.Meta noLevel = Attestation.parseClassification(new JSONObject().put("section", "contests"));
        assertEquals("Уровень не указан", Attestation.target(noLevel, ".jpg").parts.get(1));

        Attestation.Meta course = Attestation.parseClassification(new JSONObject().put("section", "courses").put("level", "region"));
        Attestation.Target ct = Attestation.target(course, ".png");
        assertEquals(Arrays.asList("1. Курсы повышения квалификации"), ct.parts);
        assertEquals("Без даты Сертификат.png", ct.name);
    }

    @Test
    public void extensionFromNameOrMime() {
        assertEquals(".pdf", Attestation.extension("Грамота.PDF", null));
        assertEquals(".jpg", Attestation.extension("IMG-20261015-WA0003", "image/jpeg"));
        assertEquals(".docx", Attestation.extension("1000123", Attestation.DOCX));
        assertEquals("", Attestation.extension("файл", "application/zip"));
        assertEquals("image/jpeg", Attestation.aiMime(".jpeg"));
        assertEquals(null, Attestation.aiMime(".docx"));
    }

    @Test
    public void docxXmlToText() {
        String xml = "<w:document><w:body><w:p><w:r><w:t>Грамота</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>награждается</w:t></w:r><w:tab/><w:r><w:t>Иванова &amp; К</w:t></w:r></w:p></w:body></w:document>";
        assertEquals("Грамота\nнаграждается Иванова & К", Attestation.xmlText(xml));
    }

    @Test
    public void scriptUrlCheck() {
        assertTrue(Attestation.isScriptUrl("https://script.google.com/macros/s/AKfycbx-1_abc/exec"));
        assertTrue(Attestation.isScriptUrl(" https://script.google.com/a/macros/school.kz/s/AKfy/exec "));
        assertFalse(Attestation.isScriptUrl("https://script.google.com/macros/s/AKfy/dev"));
        assertFalse(Attestation.isScriptUrl("https://example.com/macros/s/AKfy/exec"));
        assertFalse(Attestation.isScriptUrl(""));
    }

    @Test
    public void saveRequestCarriesFolders() throws Exception {
        Attestation.Meta m = Attestation.parseClassification(new JSONObject().put("section", "students").put("level", "school"));
        JSONObject r = Attestation.saveRequest("s3cret", Attestation.target(m, ".jpg"), "image/jpeg", "QUJD");
        assertEquals("save", r.getString("action"));
        assertEquals("Аттестация", r.getString("root"));
        assertEquals("4. Достижения учеников", r.getJSONArray("parts").getString(0));
        assertEquals("Школьный", r.getJSONArray("parts").getString(1));
        assertEquals("QUJD", r.getString("data"));
        assertEquals("ping", Attestation.pingRequest("x").getString("action"));
    }

    @Test
    public void scriptResponses() throws Exception {
        assertEquals("Аттестация / x.pdf",
                Attestation.parseResponse("{\"ok\":true,\"path\":\"Аттестация / x.pdf\"}").getString("path"));
        expectError("<!DOCTYPE html><html>Вход</html>", "доступ «Все»");
        expectError("{\"ok\":false,\"error\":\"secret\"}", "SECRET");
        expectError("{\"ok\":false,\"error\":\"Quota\"}", "Quota");
    }

    private static void expectError(String body, String part) {
        try {
            Attestation.parseResponse(body);
            fail("ждали ошибку");
        } catch (Attestation.DriveError e) {
            assertTrue(e.getMessage(), e.getMessage().contains(part));
        }
    }
}
