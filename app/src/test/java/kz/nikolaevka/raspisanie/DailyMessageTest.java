package kz.nikolaevka.raspisanie;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDateTime;
import java.time.LocalTime;

public class DailyMessageTest {

    private static final LocalTime NINE = LocalTime.of(9, 0);

    private static LocalDateTime next(String now, JSONObject j) {
        return DailyMessage.nextAt(LocalDateTime.parse(now), NINE, j);
    }

    @Test
    public void onlySchoolDaysAtTheTime() {
        JSONObject j = Attendance.empty();
        // до начала четверти — первый учебный день
        assertEquals(LocalDateTime.parse("2026-11-02T09:00"), next("2026-10-29T20:00", j));
        // в учебный день до 09:00 — сегодня, после — завтра
        assertEquals(LocalDateTime.parse("2026-11-03T09:00"), next("2026-11-03T08:59", j));
        assertEquals(LocalDateTime.parse("2026-11-04T09:00"), next("2026-11-03T09:00", j));
        // пятница вечером — понедельник
        assertEquals(LocalDateTime.parse("2026-11-09T09:00"), next("2026-11-06T10:00", j));
        // 16 декабря — праздник
        assertEquals(LocalDateTime.parse("2026-12-17T09:00"), next("2026-12-15T12:00", j));
        // зимние каникулы
        assertEquals(LocalDateTime.parse("2027-01-11T09:00"), next("2026-12-25T12:00", j));
        // после конца учебного года
        assertNull(next("2027-05-25T12:00", j));
    }

    @Test
    public void respectsDaysMarkedInJournal() {
        JSONObject j = Attendance.empty();
        Attendance.setSchoolDay(j, "2026-11-04", false);
        assertEquals(LocalDateTime.parse("2026-11-05T09:00"), next("2026-11-03T12:00", j));
    }
}
