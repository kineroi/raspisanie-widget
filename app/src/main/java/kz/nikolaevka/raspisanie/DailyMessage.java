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

import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Сообщение в WhatsApp по учебным дням (например, в 09:00 классным руководителям: «напишите отсутствующих»).
 * Сам WhatsApp отправлять не даёт — приложение в нужное время показывает уведомление, нажатие открывает
 * WhatsApp с готовым текстом: остаётся выбрать группу и нажать «Отправить».
 * Учебные дни — из журнала посещаемости (четверти, праздники и дни, отмеченные выходными).
 */
final class DailyMessage {

    static final String DEFAULT_TEXT = "Здравствуйте, напишите пожалуйста отсутствующих";
    static final LocalTime DEFAULT_TIME = LocalTime.of(9, 0);

    private static final String PREFS = "daily_message";
    private static final String K_ENABLED = "enabled", K_TEXT = "text", K_TIME = "time", K_SHOWN = "shown";
    private static final String CHANNEL = "whatsapp";
    private static final int NOTIFY_ID = 31;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(K_ENABLED, false);
    }

    static String text(Context c) {
        String t = prefs(c).getString(K_TEXT, DEFAULT_TEXT);
        return t.trim().isEmpty() ? DEFAULT_TEXT : t;
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

    static void setText(Context c, String text) {
        prefs(c).edit().putString(K_TEXT, text == null ? "" : text).apply();
    }

    static void setTime(Context c, LocalTime t) {
        prefs(c).edit().putString(K_TIME, t.withSecond(0).withNano(0).toString()).apply();
        schedule(c);
    }

    /** Ближайший учебный день в это время позже now; null — учебный год закончился. */
    static LocalDateTime nextAt(LocalDateTime now, LocalTime time, JSONObject journal) {
        LocalDate d = now.toLocalDate();
        for (int i = 0; i < 400; i++, d = d.plusDays(1)) {
            LocalDateTime at = d.atTime(time);
            if (at.isAfter(now) && Attendance.isSchoolDay(journal, d.toString())) return at;
        }
        return null;
    }

    private static PendingIntent alarm(Context c) {
        return PendingIntent.getBroadcast(c, 31, new Intent(c, Receiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Поставить будильник на ближайший учебный день (старый снимается). */
    static void schedule(Context context) {
        Context c = context.getApplicationContext();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        if (am == null) return;
        PendingIntent pi = alarm(c);
        am.cancel(pi);
        if (!enabled(c)) return;
        LocalDateTime next = nextAt(LocalDateTime.now(), time(c), Attendance.load(c));
        if (next == null) return;
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        // ровно в назначенную минуту — как напоминания об уроках
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Показать уведомление: нажатие открывает WhatsApp с готовым текстом. */
    static void show(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Сообщение в WhatsApp",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Напоминание отправить сообщение в группу по учебным дням");
            nm.createNotificationChannel(ch);
        }
        String text = text(c);
        PendingIntent send = PendingIntent.getActivity(c, 32, new Intent(c, SendMessageActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setColor(0xFF7B1FA2)
                .setContentTitle("Пора отправить сообщение в WhatsApp")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text + "\n\nНажмите — откроется WhatsApp с этим текстом."))
                .setContentIntent(send)
                .addAction(new Notification.Action.Builder(null, "Открыть WhatsApp", send).build())
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build();
        nm.notify(NOTIFY_ID, n);
    }

    static void cancelNotification(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFY_ID);
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String today = LocalDate.now().toString();
                // будильник мог сработать с опозданием или дважды; праздник могли отметить уже после постановки
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

    private DailyMessage() {
    }
}
