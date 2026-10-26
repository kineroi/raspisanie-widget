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
import android.text.style.StrikethroughSpan;
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

    private static final int[] DOT_IDS = {R.id.row1_dot, R.id.row2_dot, R.id.row3_dot,
            R.id.row4_dot, R.id.row5_dot, R.id.row6_dot};
    private static final int[] CHIP_IDS = {R.id.row1_chip, R.id.row2_chip, R.id.row3_chip,
            R.id.row4_chip, R.id.row5_chip, R.id.row6_chip};

    /** Пока идёт урок или перемена, полосу прогресса обновляем не реже чем раз в 5 минут. */
    private static final Duration PROGRESS_STEP = Duration.ofMinutes(5);

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
        Digest.schedule(context);
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
        LocalDateTime next = s.nextUpdate;
        if (s.progressFrom != null) {
            LocalDateTime step = now.plus(PROGRESS_STEP);
            if (step.isBefore(next)) next = step;
        }
        scheduleNext(context, next);
    }

    private static RemoteViews build(Context context, WidgetState s, LocalDateTime now) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget);

        v.setTextViewText(R.id.day_name, s.dayName);
        v.setTextViewText(R.id.date, s.dateText);
        showText(v, R.id.pill, s.counter);
        v.setTextViewText(R.id.hero_label, s.heroLabel);
        showText(v, R.id.hero_range, s.heroRange);
        v.setTextViewText(R.id.status_text, s.heroTitle);

        if (s.countdownTo != null) {
            long left = Duration.between(now, s.countdownTo).toMillis();
            long base = SystemClock.elapsedRealtime() + Math.max(left, 0);
            v.setChronometer(R.id.countdown, base, null, true);
            v.setChronometerCountDown(R.id.countdown, true);
            v.setTextViewText(R.id.countdown_label, s.countdownLabel);
            v.setViewVisibility(R.id.countdown_box, View.VISIBLE);
        } else {
            v.setChronometer(R.id.countdown, SystemClock.elapsedRealtime(), null, false);
            v.setViewVisibility(R.id.countdown_box, View.GONE);
        }

        if (s.progressFrom != null && s.countdownTo != null) {
            long total = Duration.between(s.progressFrom, s.countdownTo).toMillis();
            long done = Duration.between(s.progressFrom, now).toMillis();
            int pct = total > 0 ? (int) Math.max(0, Math.min(100, done * 100 / total)) : 0;
            v.setProgressBar(R.id.progress, 100, pct, false);
            v.setViewVisibility(R.id.progress, View.VISIBLE);
        } else {
            v.setViewVisibility(R.id.progress, View.GONE);
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

            int bg = R.drawable.row_empty;
            int dot;
            int timeColor = R.color.w_muted;
            int nameColor = R.color.w_text;
            String chip = null;
            int chipBg = R.drawable.chip_next;
            int chipColor = R.color.w_chip_text;
            switch (r) {
                case CURRENT:
                    bg = R.drawable.row_current;
                    dot = R.drawable.dot_current;
                    timeColor = nameColor = R.color.w_row_cur_text;
                    chip = "идёт";
                    chipBg = R.drawable.chip_current;
                    chipColor = R.color.w_chip_cur_text;
                    break;
                case NEXT:
                    bg = R.drawable.row_next;
                    dot = R.drawable.dot_next;
                    timeColor = R.color.w_text;
                    chip = "далее";
                    break;
                case PAST:
                    dot = R.drawable.dot_past;
                    nameColor = R.color.w_muted;
                    break;
                default:
                    dot = R.drawable.dot_later;
                    break;
            }
            boolean bold = r == WidgetState.Row.CURRENT || r == WidgetState.Row.NEXT;
            v.setTextViewText(TIME_IDS[i], WidgetState.range(l));
            v.setTextViewText(NAME_IDS[i], styled(l.title, bold, r == WidgetState.Row.PAST));
            textColor(context, v, TIME_IDS[i], timeColor);
            textColor(context, v, NAME_IDS[i], nameColor);
            v.setInt(ROW_IDS[i], "setBackgroundResource", bg);
            v.setImageViewResource(DOT_IDS[i], dot);
            if (chip != null) {
                v.setTextViewText(CHIP_IDS[i], chip);
                v.setInt(CHIP_IDS[i], "setBackgroundResource", chipBg);
                textColor(context, v, CHIP_IDS[i], chipColor);
                v.setViewVisibility(CHIP_IDS[i], View.VISIBLE);
            } else {
                v.setViewVisibility(CHIP_IDS[i], View.GONE);
            }

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

    private static CharSequence styled(String text, boolean bold, boolean strike) {
        if (!bold && !strike) return text;
        SpannableString sp = new SpannableString(text);
        if (bold) sp.setSpan(new StyleSpan(Typeface.BOLD), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (strike) sp.setSpan(new StrikethroughSpan(), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /**
     * Цвет текста из ресурсов. На Android 12+ цвет подставляет сам рабочий стол,
     * поэтому виджет сразу перекрашивается при смене светлой/тёмной темы.
     */
    private static void textColor(Context context, RemoteViews v, int viewId, int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.setColorStateList(viewId, "setTextColor", colorRes);
        } else {
            v.setTextColor(viewId, context.getColor(colorRes));
        }
    }

    private static void showText(RemoteViews v, int viewId, String text) {
        if (text == null || text.isEmpty()) {
            v.setViewVisibility(viewId, View.GONE);
        } else {
            v.setTextViewText(viewId, text);
            v.setViewVisibility(viewId, View.VISIBLE);
        }
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
