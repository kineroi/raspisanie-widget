package kz.nikolaevka.raspisanie;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/** Уведомления об успеваемости: по понедельникам в 07:45 — группа риска; в последнюю неделю четверти в 16:00 — что не заполнено. */
final class Digest {

    private static final String CHANNEL = "grades";
    private static final String PREFS = "settings";
    private static final String K_ENABLED = "grade_alerts";
    private static final String K_RISK_WEEK = "risk_week";
    private static final String K_QUARTER_DAY = "quarter_day";
    private static final LocalTime RISK_TIME = LocalTime.of(7, 45);
    private static final LocalTime QUARTER_TIME = LocalTime.of(16, 0);

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_ENABLED, on).apply();
        schedule(c);
    }

    /** Ближайший момент проверки: понедельник 07:45 или сегодня/завтра 16:00. */
    static LocalDateTime nextCheck(LocalDateTime now) {
        LocalDateTime monday = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).atTime(RISK_TIME);
        if (!monday.isAfter(now)) monday = monday.plusWeeks(1);
        LocalDateTime four = now.toLocalDate().atTime(QUARTER_TIME);
        if (!four.isAfter(now)) four = four.plusDays(1);
        return monday.isBefore(four) ? monday : four;
    }

    private static PendingIntent pending(Context c) {
        return PendingIntent.getBroadcast(c, 9, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(pending(c));
        if (!enabled(c) || !Bilim.isConnected(c)) return;
        long at = nextCheck(LocalDateTime.now()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // точность до минут не нужна — экономим батарею
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    /** Текст группы риска: «4А — 2, 7А — 1» или null. */
    static String riskText(List<Grades.Journal> journals) {
        List<String> parts = new ArrayList<>();
        for (Grades.Journal j : journals) {
            int n = Grades.riskCount(j);
            if (n > 0) parts.add(j.cls + " — " + n);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    static void check(Context c) {
        if (!enabled(c) || !Bilim.isConnected(c)) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        String week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
        boolean riskDue = today.getDayOfWeek() == DayOfWeek.MONDAY && !now.toLocalTime().isBefore(RISK_TIME)
                && !week.equals(prefs(c).getString(K_RISK_WEEK, ""));
        boolean quarterDue = !now.toLocalTime().isBefore(QUARTER_TIME)
                && !today.toString().equals(prefs(c).getString(K_QUARTER_DAY, ""));
        if (!riskDue && !quarterDue) return;
        Journals.Data d = Journals.load(c);
        if (riskDue) {
            prefs(c).edit().putString(K_RISK_WEEK, week).apply();
            String t = riskText(d.journals);
            if (t != null) notify(c, 21, "Группа риска на этой неделе", t + ". Нажмите, чтобы посмотреть.");
        }
        if (quarterDue) {
            Grades.Check q = Grades.quarterCheck(d.journals, d.lessons, today.toString());
            if (q != null) {
                prefs(c).edit().putString(K_QUARTER_DAY, today.toString()).apply();
                if (!q.items.isEmpty()) {
                    String head = q.daysLeft > 0 ? "До конца четверти " + q.daysLeft + " дн." : "Четверть закончилась";
                    List<String> first = q.items.subList(0, Math.min(4, q.items.size()));
                    String body = String.join("; ", first)
                            + (q.items.size() > 4 ? " и ещё " + (q.items.size() - 4) : "");
                    notify(c, 22, head + ": есть незаполненное", body);
                }
            }
        }
    }

    private static void notify(Context c, int id, String title, String body) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Успеваемость",
                    NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Группа риска и контроль конца четверти");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(c, 10, new Intent(c, GradesActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF7B1FA2)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        nm.notify(id, n);
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                check(context);
            } finally {
                schedule(context);
            }
        }
    }

    private Digest() {
    }
}
