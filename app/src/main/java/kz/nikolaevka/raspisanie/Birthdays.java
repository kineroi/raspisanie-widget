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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Уведомление о днях рождения учеников: в день рождения с 8 утра, один раз в день. Список — в журнале посещаемости. */
final class Birthdays {

    private static final String PREFS = "birthdays", K_ENABLED = "enabled", K_SHOWN = "shown", CHANNEL = "birthdays";
    private static final LocalTime TIME = LocalTime.of(8, 0);

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

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 41, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Будильник на ближайшие 8 утра (сам список проверяем в момент срабатывания: он мог синхронизироваться). */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(alarm(c));
        if (!enabled(c)) return;
        LocalDateTime next = LocalDate.now().atTime(TIME);
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), alarm(c));
    }

    /** Показать сегодняшние дни рождения, если ещё не показывали (и уже 8 утра). */
    static void check(Context c) {
        if (!enabled(c) || LocalTime.now().isBefore(TIME)) return;
        LocalDate today = LocalDate.now();
        if (today.toString().equals(prefs(c).getString(K_SHOWN, ""))) return;
        List<String> names = new ArrayList<>();
        for (Attendance.Birthday b : Attendance.upcomingBirthdays(Attendance.load(c), today)) {
            if (b.daysLeft == 0) names.add(Attendance.birthdayText(b));
        }
        if (names.isEmpty()) return;
        prefs(c).edit().putString(K_SHOWN, today.toString()).apply();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Дни рождения учеников",
                    NotificationManager.IMPORTANCE_HIGH));
        }
        String body = String.join("\n", names);
        PendingIntent open = PendingIntent.getActivity(c, 42, new Intent(c, BirthdaysActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(41, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF7B1FA2)
                .setContentTitle(names.size() > 1 ? "Сегодня дни рождения: " + names.size() : "Сегодня день рождения")
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build());
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

    private Birthdays() {
    }
}
