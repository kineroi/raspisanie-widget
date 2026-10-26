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
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Напоминание за 5 минут до урока. */
final class Reminders {

    static final int MINUTES = 5;
    private static final String CHANNEL = "lessons";
    private static final String PREFS = "settings";
    private static final String K_ENABLED = "reminders";
    private static final String EXTRA_DATE = "date";
    private static final String EXTRA_START = "start";

    /** Ближайшее напоминание. */
    static final class Next {
        final LocalDate date;
        final Schedule.Lesson lesson;
        final LocalDateTime at;

        Next(LocalDate date, Schedule.Lesson lesson, LocalDateTime at) {
            this.date = date;
            this.lesson = lesson;
            this.at = at;
        }
    }

    /** Ближайший урок, до которого больше 5 минут (смотрим до трёх недель вперёд). */
    static Next next(LocalDateTime now, Schedule.Source source) {
        LocalDate today = now.toLocalDate();
        for (int i = 0; i <= 21; i++) {
            LocalDate d = today.plusDays(i);
            for (Schedule.Lesson l : source.day(d).lessons) {
                LocalDateTime at = d.atTime(l.start).minusMinutes(MINUTES);
                if (at.isAfter(now)) return new Next(d, l, at);
            }
        }
        return null;
    }

    static String title(Schedule.Lesson l) {
        return "Через " + MINUTES + " минут: " + l.title;
    }

    static String body(Schedule.Lesson l) {
        List<String> parts = new ArrayList<>();
        parts.add(WidgetState.range(l));
        for (Schedule.Detail d : l.details) {
            if (!d.theme.isEmpty()) parts.add((l.details.size() > 1 ? d.cls + ": " : "") + d.theme);
        }
        return String.join(" · ", parts);
    }

    // ---------- Настройка ----------

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

    // ---------- Будильник ----------

    private static PendingIntent alarmIntent(Context c, Next n) {
        Intent i = new Intent(c, Receiver.class);
        if (n != null) {
            i.putExtra(EXTRA_DATE, n.date.toString()).putExtra(EXTRA_START, n.lesson.start.toString());
        }
        return PendingIntent.getBroadcast(c, 7, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Поставить будильник на ближайшее напоминание (старый снимается). */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        am.cancel(alarmIntent(c, null));
        if (!enabled(c)) return;
        Next n = next(LocalDateTime.now(), Bilim.source(c));
        if (n == null) return;
        long at = n.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        PendingIntent pi = alarmIntent(c, n);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    // ---------- Уведомление ----------

    private static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Напоминания об уроках",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("За 5 минут до начала урока");
        nm.createNotificationChannel(ch);
    }

    static void show(Context c, LocalDate date, Schedule.Lesson l) {
        ensureChannel(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        Intent open = new Intent(c, HomeworkActivity.class)
                .putExtra(HomeworkActivity.EXTRA_DATE, date.toString())
                .putExtra(HomeworkActivity.EXTRA_START, l.start.toString())
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 8, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String body = body(l);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF7B1FA2)
                .setContentTitle(title(l))
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(15L * 60 * 1000)
                .build();
        nm.notify(1, n);
    }

    /** Срабатывает за 5 минут до урока: показывает уведомление и ставит следующее. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                LocalDate date = LocalDate.parse(intent.getStringExtra(EXTRA_DATE));
                LocalTime start = LocalTime.parse(intent.getStringExtra(EXTRA_START));
                // Урок мог измениться после обновления расписания — берём свежие данные
                for (Schedule.Lesson l : Bilim.source(context).day(date).lessons) {
                    if (l.start.equals(start) && LocalDateTime.now().isBefore(date.atTime(start))) {
                        show(context, date, l);
                    }
                }
            } catch (RuntimeException ignored) {
                // нет данных — просто поставим следующее напоминание
            }
            schedule(context);
        }
    }

    private Reminders() {
    }
}
