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

/**
 * Напоминание отсканировать QR-коды учащихся для бесплатного питания — по учебным дням в 11:25
 * (как сообщение про посещаемость). Только напоминает, ни с чем не связывается.
 */
final class MealReminder {

    static final String TITLE = "Бесплатное питание";
    static final String TEXT = "Не забудь отсканировать QR-коды учащихся в Business Wallet.";
    static final LocalTime DEFAULT_TIME = LocalTime.of(11, 25);

    private static final String PREFS = "meal_reminder";
    private static final String K_ENABLED = "enabled", K_TIME = "time", K_SHOWN = "shown";
    private static final String CHANNEL = "meal";
    private static final int NOTIFY_ID = 51;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, true);
    }

    static LocalTime time(Context c) {
        try {
            return LocalTime.parse(prefs(c).getString(K_TIME, DEFAULT_TIME.toString()));
        } catch (RuntimeException e) {
            return DEFAULT_TIME;
        }
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(K_ENABLED, on).apply();
        schedule(c);
    }

    static void setTime(Context c, LocalTime t) {
        prefs(c).edit().putString(K_TIME, t.withSecond(0).withNano(0).toString()).apply();
        schedule(c);
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, NOTIFY_ID, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Будильник на ближайший учебный день в это время (учебные дни — из журнала посещаемости). */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = alarm(c);
        am.cancel(pi);
        if (!enabled(c)) return;
        LocalDateTime next = DailyMessage.nextAt(LocalDateTime.now(), time(c), Attendance.load(c));
        if (next == null) return;
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    static void show(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, TITLE, NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Напоминание отсканировать QR-коды учащихся по учебным дням");
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(c, NOTIFY_ID, new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFY_ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF7B1FA2)
                .setContentTitle(TITLE)
                .setContentText(TEXT)
                .setStyle(new Notification.BigTextStyle().bigText(TEXT))
                .setContentIntent(open)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build());
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String today = LocalDate.now().toString();
                // один раз в день и только в учебный (праздник могли отметить уже после постановки)
                if (enabled(context) && Attendance.isSchoolDay(Attendance.load(context), today)
                        && !today.equals(prefs(context).getString(K_SHOWN, ""))) {
                    prefs(context).edit().putString(K_SHOWN, today).apply();
                    show(context);
                }
            } finally {
                schedule(context);
            }
        }
    }

    private MealReminder() {
    }
}
