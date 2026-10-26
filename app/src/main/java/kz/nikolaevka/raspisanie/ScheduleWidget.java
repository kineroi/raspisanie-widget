package kz.nikolaevka.raspisanie;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Duration;

public class ScheduleWidget extends AppWidgetProvider {

    static final String ACTION_TICK = "kz.nikolaevka.raspisanie.TICK";

    private static final int[] ROW_IDS = {R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6};
    private static final int[] TIME_IDS = {R.id.row1_time, R.id.row2_time, R.id.row3_time,
            R.id.row4_time, R.id.row5_time, R.id.row6_time};
    private static final int[] NAME_IDS = {R.id.row1_name, R.id.row2_name, R.id.row3_name,
            R.id.row4_name, R.id.row5_name, R.id.row6_name};

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_PAST = 0x80FFFFFF;
    private static final int COLOR_CURRENT = 0xFF4A148C;
    private static final int COLOR_ACCENT = 0xFFFFD54F;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        updateAll(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String a = intent.getAction();
        if (a == null) return;
        switch (a) {
            case ACTION_TICK:
            case Intent.ACTION_BOOT_COMPLETED:
            case Intent.ACTION_TIME_CHANGED:
            case Intent.ACTION_TIMEZONE_CHANGED:
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                updateAll(context);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDisabled(Context context) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(tickIntent(context));
    }

    /** Перерисовать все виджеты и запланировать следующее обновление. */
    static void updateAll(Context context) {
        // Напоминания нужны, даже если виджета нет на экране
        Reminders.schedule(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, ScheduleWidget.class));
        if (ids == null || ids.length == 0) return;

        LocalDateTime now = LocalDateTime.now();
        WidgetState s = WidgetState.compute(now, Bilim.source(context));
        if (s.subText == null && Bilim.needLogin(context)) {
            s.subText = "Войдите в BilimClass заново (откройте приложение)";
        }
        manager.updateAppWidget(ids, build(context, s, now));
        Bilim.schedule(context);
        scheduleNext(context, s.nextUpdate);
    }

    private static RemoteViews build(Context context, WidgetState s, LocalDateTime now) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget);

        v.setTextViewText(R.id.day_name, s.dayName);
        v.setTextViewText(R.id.date, s.dateText);
        v.setTextViewText(R.id.status_text, s.status);

        if (s.countdownTo != null) {
            long left = Duration.between(now, s.countdownTo).toMillis();
            long base = SystemClock.elapsedRealtime() + Math.max(left, 0);
            v.setChronometer(R.id.countdown, base, s.countdownFormat, true);
            v.setChronometerCountDown(R.id.countdown, true);
            v.setViewVisibility(R.id.countdown, View.VISIBLE);
        } else {
            v.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.countdown, View.GONE);
        }

        if (s.subText != null && !s.subText.isEmpty()) {
            v.setTextViewText(R.id.sub_text, s.subText);
            v.setViewVisibility(R.id.sub_text, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.sub_text, View.GONE);
        }

        v.setTextViewText(R.id.list_title, s.listTitle);

        for (int i = 0; i < ROW_IDS.length; i++) {
            if (i >= s.lessons.size()) {
                v.setViewVisibility(ROW_IDS[i], View.GONE);
                continue;
            }
            Schedule.Lesson l = s.lessons.get(i);
            WidgetState.Row r = s.rows[i];
            v.setViewVisibility(ROW_IDS[i], View.VISIBLE);

            String time = WidgetState.range(l);
            String name = l.title;
            int color;
            int bg;
            switch (r) {
                case CURRENT:
                    color = COLOR_CURRENT;
                    bg = R.drawable.row_current;
                    name = "▶ " + name;
                    break;
                case NEXT:
                    color = COLOR_ACCENT;
                    bg = R.drawable.row_next;
                    break;
                case PAST:
                    color = COLOR_PAST;
                    bg = R.drawable.row_empty;
                    name = "✓ " + name;
                    break;
                default:
                    color = COLOR_TEXT;
                    bg = R.drawable.row_empty;
                    break;
            }
            boolean bold = r == WidgetState.Row.CURRENT || r == WidgetState.Row.NEXT;
            v.setTextViewText(TIME_IDS[i], styled(time, bold));
            v.setTextViewText(NAME_IDS[i], styled(name, bold));
            v.setTextColor(TIME_IDS[i], color);
            v.setTextColor(NAME_IDS[i], color);
            v.setInt(ROW_IDS[i], "setBackgroundResource", bg);

            // Нажатие на урок — окно «Домашнее задание» для этого урока
            Intent hw = new Intent(context, HomeworkActivity.class)
                    .putExtra(HomeworkActivity.EXTRA_DATE, s.listDate.toString())
                    .putExtra(HomeworkActivity.EXTRA_START, l.start.toString())
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            v.setOnClickPendingIntent(ROW_IDS[i], PendingIntent.getActivity(context, 100 + i, hw,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }

        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.root, pi);
        return v;
    }

    private static CharSequence styled(String text, boolean bold) {
        if (!bold) return text;
        SpannableString sp = new SpannableString(text);
        sp.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private static PendingIntent tickIntent(Context context) {
        Intent i = new Intent(context, ScheduleWidget.class).setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void scheduleNext(Context context, LocalDateTime when) {
        AlarmManager am = context.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = when.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        PendingIntent pi = tickIntent(context);
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC, at, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pi);
        }
    }
}
