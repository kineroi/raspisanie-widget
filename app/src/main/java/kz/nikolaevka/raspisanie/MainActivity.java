package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Chronometer;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Экран приложения: главная (сводка дня, текущий урок, уроки сегодня), неделя и настройки.
 * Оформление — по макету из Figma (см. ATTRIBUTION.md); цвета светлой и тёмной темы в values/values-night.
 */
public class MainActivity extends Activity {

    private static final int TAB_HOME = 0;
    private static final int TAB_WEEK = 1;
    private static final int TAB_SETTINGS = 2;

    private int tab = TAB_HOME;
    /** -1 прошлая, 0 эта, 1 следующая неделя. */
    private int weekOffset = 0;
    private boolean syncing = false;
    private String syncMessage = null;
    private int scrollY = 0;
    private ScrollView scroll;
    /** Сколько учеников в группе риска по всем классам; -1 — журнал не загружен. */
    private int riskTotal = -1;

    @Override
    protected void onResume() {
        super.onResume();
        ScheduleWidget.updateAll(this);
        render();
        if (Bilim.isConnected(this)) startSync();
        checkUpdates(false);
        askNotificationPermission();
        loadRisk();
        syncAttendance();
        handler.removeCallbacks(minuteTick);
        handler.postDelayed(minuteTick, 60_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(minuteTick);
    }

    @Override
    public void onBackPressed() {
        if (tab != TAB_HOME) {
            switchTab(TAB_HOME);
            return;
        }
        super.onBackPressed();
    }

    /** Раз в минуту обновляем главную: полоса прогресса и отметки уроков. */
    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            if (tab == TAB_HOME) render();
            handler.postDelayed(this, 60_000);
        }
    };

    private void switchTab(int t) {
        if (t == tab) return;
        tab = t;
        scrollY = 0;
        render();
    }

    private void loadRisk() {
        new Thread(() -> {
            int total = -1;
            if (Bilim.isConnected(this)) {
                Journals.Data d = Journals.load(getApplicationContext());
                if (!d.journals.isEmpty()) {
                    total = 0;
                    for (Grades.Journal j : d.journals) total += Grades.riskCount(j);
                }
            }
            final int t = total;
            runOnUiThread(() -> {
                if (t != riskTotal) {
                    riskTotal = t;
                    if (tab == TAB_HOME) render();
                }
            });
        }).start();
    }

    // ---------- Напоминания ----------

    private boolean askedNotifications = false;

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** На Android 13+ уведомления нужно разрешить; спрашиваем один раз за запуск. */
    private void askNotificationPermission() {
        if (askedNotifications || !Reminders.enabled(this) || notificationsAllowed()) return;
        askedNotifications = true;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 5);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        render();
    }

    private View remindersCard() {
        LinearLayout card = card();
        card.addView(text("Напоминания", 16, c(R.color.a_text), 500));
        android.widget.CheckBox cb = checkBox("Напоминать за 5 минут до урока", Reminders.enabled(this));
        cb.setOnCheckedChangeListener((v, on) -> {
            Reminders.setEnabled(this, on);
            if (on) {
                askedNotifications = false;
                askNotificationPermission();
            }
        });
        card.addView(cb);
        android.widget.CheckBox gcb = checkBox("Группа риска (пн) и контроль конца четверти", Digest.enabled(this));
        gcb.setOnCheckedChangeListener((v, on) -> Digest.setEnabled(this, on));
        card.addView(gcb);
        if (Reminders.enabled(this) && !notificationsAllowed()) {
            card.addView(text("Уведомления выключены в настройках Android — напоминания не будут видны.",
                    13, c(R.color.a_red), 400));
            Button settings = button("Разрешить уведомления", true);
            settings.setOnClickListener(v -> startActivity(new Intent(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
            card.addView(settings, buttonParams(ViewGroup.LayoutParams.MATCH_PARENT, 8));
        } else if (Reminders.enabled(this)) {
            Button test = button("Пробное напоминание", false);
            test.setOnClickListener(v -> {
                Reminders.Next n = Reminders.next(LocalDateTime.now(), Bilim.source(this));
                if (n == null) {
                    Toast.makeText(this, "Ближайших уроков не найдено", Toast.LENGTH_SHORT).show();
                } else {
                    Reminders.show(this, n.date, n.lesson);
                }
            });
            card.addView(test, buttonParams(ViewGroup.LayoutParams.WRAP_CONTENT, 6));
        }
        return card;
    }

    // ---------- Обновление приложения ----------

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String updateStatus = null;
    private long downloadId = -1;

    private void checkUpdates(final boolean force) {
        new Thread(() -> {
            final boolean has = Updater.check(getApplicationContext(), force);
            runOnUiThread(() -> {
                if (force && !has) {
                    Toast.makeText(this, "Установлена последняя версия", Toast.LENGTH_SHORT).show();
                }
                render();
            });
        }).start();
    }

    private View updateCard() {
        LinearLayout card = card();
        card.setBackground(round(c(R.color.a_primary_soft), 16));
        card.addView(text("Доступна новая версия " + Updater.updateVersion(this), 16, c(R.color.a_text), 600));
        card.addView(text(updateStatus != null ? updateStatus
                : "Расписание, вход и ключ сохранятся. Android спросит подтверждение установки.",
                13, c(R.color.a_muted), 400));
        if (downloadId < 0) {
            Button b = button("Обновить", true);
            b.setOnClickListener(v -> startUpdate());
            card.addView(b, buttonParams(ViewGroup.LayoutParams.MATCH_PARENT, 10));
        }
        return card;
    }

    private void startUpdate() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Нужно разрешение")
                    .setMessage("Android попросит разрешить этому приложению устанавливать обновления. "
                            + "Включите «Разрешить установку из этого источника», вернитесь назад и снова нажмите «Обновить».")
                    .setPositiveButton("Открыть настройки", (d, w) -> startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }
        try {
            downloadId = Updater.startDownload(this);
        } catch (Exception e) {
            updateStatus = "Не удалось начать загрузку. Проверьте интернет.";
            render();
            return;
        }
        updateStatus = "Загружаю… 0%";
        render();
        handler.postDelayed(downloadPoll, 700);
    }

    private final Runnable downloadPoll = new Runnable() {
        @Override
        public void run() {
            int p = Updater.progress(MainActivity.this, downloadId);
            if (p == 101) {
                updateStatus = "Скачано. Нажмите «Обновить» в окне Android.";
                long id = downloadId;
                downloadId = -1;
                render();
                if (!Updater.openInstaller(MainActivity.this, id)) {
                    updateStatus = "Не удалось открыть установку. Попробуйте ещё раз.";
                    render();
                }
            } else if (p < 0) {
                downloadId = -1;
                updateStatus = "Загрузка не удалась. Проверьте интернет и попробуйте ещё раз.";
                render();
            } else {
                updateStatus = "Загружаю… " + p + "%";
                render();
                handler.postDelayed(this, 700);
            }
        }
    };

    private void startSync() {
        if (syncing) return;
        syncing = true;
        syncMessage = null;
        render();
        new Thread(() -> {
            final Bilim.Result r = Bilim.sync(getApplicationContext());
            runOnUiThread(() -> {
                syncing = false;
                switch (r) {
                    case NO_NETWORK:
                        syncMessage = "Не удалось обновить: нет связи с BilimClass. Показано сохранённое.";
                        break;
                    case NEED_LOGIN:
                        syncMessage = "Вход в BilimClass устарел — войдите заново.";
                        break;
                    default:
                        syncMessage = null;
                        break;
                }
                render();
                loadRisk();
            });
        }).start();
    }

    // ---------- Экран ----------

    private void render() {
        if (scroll != null) scrollY = scroll.getScrollY();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(R.color.a_bg));
        root.setFitsSystemWindows(true);

        scroll = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(col);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        View line = new View(this);
        line.setBackgroundColor(c(R.color.a_border));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));
        root.addView(navBar());

        if (tab == TAB_WEEK) renderWeek(col);
        else if (tab == TAB_SETTINGS) renderSettings(col);
        else renderHome(col);

        setContentView(root);
        final int y = scrollY;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View navBar() {
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setBackgroundColor(c(R.color.a_surface));
        nav.setPadding(dp(8), dp(8), dp(8), dp(10));
        boolean connected = Bilim.isConnected(this);
        nav.addView(navItem(R.drawable.ic_app_home, "Главная", tab == TAB_HOME, true, () -> switchTab(TAB_HOME)));
        nav.addView(navItem(R.drawable.ic_app_calendar, "Неделя", tab == TAB_WEEK, true, () -> switchTab(TAB_WEEK)));
        nav.addView(navItem(R.drawable.ic_app_teacher, "Успеваемость", false, connected,
                () -> startActivity(new Intent(this, GradesActivity.class))));
        nav.addView(navItem(R.drawable.ic_app_setting, "Настройки", tab == TAB_SETTINGS, true,
                () -> switchTab(TAB_SETTINGS)));
        return nav;
    }

    private View navItem(int iconRes, String label, boolean active, boolean enabled, Runnable onClick) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        int color = c(active ? R.color.a_primary : R.color.a_muted);
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER);
        if (active) pill.setBackground(round(c(R.color.a_primary_soft), 15));
        pill.addView(icon(iconRes, color, 22));
        item.addView(pill, new LinearLayout.LayoutParams(dp(56), dp(30)));
        TextView t = text(label, 11, color, active ? 600 : 500);
        t.setPadding(0, dp(4), 0, 0);
        item.addView(t);
        item.setAlpha(enabled ? 1f : 0.4f);
        if (enabled) item.setOnClickListener(v -> onClick.run());
        item.setContentDescription(label);
        item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return item;
    }

    // ---------- Главная ----------

    private void renderHome(LinearLayout col) {
        LocalDateTime nowT = LocalDateTime.now();
        LocalDate today = nowT.toLocalDate();
        Schedule.Source source = Bilim.source(this);
        WidgetState s = WidgetState.compute(nowT, source);
        Schedule.Day day = source.day(today);

        int h = nowT.getHour();
        String greet = h < 5 ? "Доброй ночи" : h < 12 ? "Доброе утро" : h < 18 ? "Добрый день" : "Добрый вечер";
        col.addView(text(greet, 24, c(R.color.a_text), 400));
        col.addView(text(s.dayName + ", " + s.dateText, 13, c(R.color.a_muted), 400));

        if (Updater.hasUpdate(this) || updateStatus != null) col.addView(updateCard());
        if (!Bilim.isConnected(this) || Bilim.needLogin(this) || syncMessage != null) col.addView(bilimCard());

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipToPadding(false);
        LinearLayout tiles = new LinearLayout(this);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tiles);
        addTiles(tiles, today, day);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = dp(18);
        hp.rightMargin = -dp(20);
        col.addView(hs, hp);

        col.addView(heroCard(s, nowT));
        col.addView(todayCard(s, today, source));

        if (!Bilim.isConnected(this)) col.addView(templateNote());
    }

    private void addTiles(LinearLayout tiles, LocalDate today, Schedule.Day day) {
        int n = day.lessons.size();
        tiles.addView(tile(R.color.a_blue, R.color.a_blue_soft, R.drawable.ic_app_calendar,
                n > 0 ? n + " " + plural(n, "урок", "урока", "уроков") : "Нет уроков", "сегодня",
                () -> switchTab(TAB_WEEK)));

        List<Schedule.Lesson> missing = new ArrayList<>();
        for (Schedule.Lesson l : day.lessons) if (missingHomework(day, l)) missing.add(l);
        tiles.addView(tile(R.color.a_red, R.color.a_red_soft, R.drawable.ic_app_pen,
                missing.isEmpty() ? "ДЗ задано" : missing.size() + " без ДЗ",
                missing.isEmpty() ? "на сегодня всё" : "задать домашку",
                () -> {
                    if (missing.isEmpty()) switchTab(TAB_WEEK);
                    else openHomework(today, missing.get(0));
                }));

        View grades = tile(R.color.a_orange, R.color.a_orange_soft, R.drawable.ic_app_teacher,
                riskTotal > 0 ? riskTotal + " " + plural(riskTotal, "ученик", "ученика", "учеников") : "Успеваемость",
                riskTotal > 0 ? "в группе риска" : riskTotal == 0 ? "группа риска пуста" : "журнал и оценки",
                () -> startActivity(new Intent(this, GradesActivity.class)));
        if (!Bilim.isConnected(this)) {
            grades.setAlpha(0.5f);
            grades.setOnClickListener(null);
        }
        tiles.addView(grades);

        // посещаемость: долго болеют или пропускают без уважительной причины
        JSONObject journal = Attendance.load(this);
        int warn = Attendance.alerts(journal, today.toString()).size();
        boolean noLists = journal.optJSONArray("classes").length() == 0;
        tiles.addView(tile(warn > 0 ? R.color.a_red : R.color.a_green, warn > 0 ? R.color.a_red_soft : R.color.a_green_soft,
                R.drawable.ic_app_people,
                warn > 0 ? warn + " " + plural(warn, "предупреждение", "предупреждения", "предупреждений") : "Посещаемость",
                warn > 0 ? "болеют или прогулы" : noLists ? "загрузить списки" : "отметить сегодня",
                () -> startActivity(new Intent(this, AttendanceActivity.class))));

        tiles.addView(tile(R.color.a_primary, R.color.a_primary_soft, R.drawable.ic_app_medal,
                "Аттестация", "грамоты на Диск",
                () -> startActivity(new Intent(this, AttestationActivity.class))));

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        if (mgr.isRequestPinAppWidgetSupported()) {
            tiles.addView(tile(R.color.a_green, R.color.a_green_soft, R.drawable.ic_app_home,
                    "Виджет", "на рабочий стол", this::pinWidget));
        }
    }

    private View tile(int colorRes, int softRes, int iconRes, String value, String label, Runnable onClick) {
        int color = c(colorRes);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(14), dp(14), dp(14), dp(14));
        t.setBackground(round(c(softRes), 12));
        t.addView(icon(iconRes, color, 22));
        TextView v = text(value, 16, color, 600);
        v.setSingleLine(true);
        v.setPadding(0, dp(10), 0, 0);
        t.addView(v);
        TextView l = text(label, 12, color, 400);
        l.setSingleLine(true);
        t.addView(l);
        t.setOnClickListener(x -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    /** Карточка «Сейчас» — как в виджете: урок или перемена, отсчёт до звонка, прогресс. */
    private View heroCard(WidgetState s, LocalDateTime now) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(round(c(R.color.w_hero_bg), 16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(16);
        card.setLayoutParams(cp);
        int sub = c(R.color.w_hero_sub);

        LinearLayout r1 = new LinearLayout(this);
        r1.setOrientation(LinearLayout.HORIZONTAL);
        TextView label = text(s.heroLabel.toUpperCase(), 11, sub, 500);
        label.setLetterSpacing(0.08f);
        label.setSingleLine(true);
        r1.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s.heroRange != null) r1.addView(text(s.heroRange, 11, sub, 400));
        card.addView(r1);

        LinearLayout r2 = new LinearLayout(this);
        r2.setOrientation(LinearLayout.HORIZONTAL);
        r2.setGravity(Gravity.BOTTOM);
        TextView title = text(s.heroTitle, 24, c(R.color.w_hero_text), 600);
        title.setMaxLines(2);
        r2.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s.countdownTo != null) {
            LinearLayout cd = new LinearLayout(this);
            cd.setOrientation(LinearLayout.VERTICAL);
            cd.setGravity(Gravity.END);
            Chronometer ch = new Chronometer(this);
            ch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
            ch.setTextColor(c(R.color.w_accent));
            ch.setTypeface(font(600));
            long left = Math.max(0, Duration.between(now, s.countdownTo).toMillis());
            ch.setBase(SystemClock.elapsedRealtime() + left);
            ch.setCountDown(true);
            ch.start();
            cd.addView(ch);
            cd.addView(text(s.countdownLabel, 11, sub, 400));
            LinearLayout.LayoutParams cdp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cdp.leftMargin = dp(8);
            r2.addView(cd, cdp);
        }
        LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        r2p.topMargin = dp(2);
        card.addView(r2, r2p);

        if (s.progressFrom != null && s.countdownTo != null) {
            long total = Duration.between(s.progressFrom, s.countdownTo).toMillis();
            long done = Duration.between(s.progressFrom, now).toMillis();
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgressDrawable(getDrawable(R.drawable.progress_lesson));
            bar.setProgress(total > 0 ? (int) Math.max(0, Math.min(100, done * 100 / total)) : 0);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
            bp.topMargin = dp(10);
            card.addView(bar, bp);
        }
        if (s.subText != null && !s.subText.isEmpty()) {
            TextView t = text(s.subText, 12, sub, 400);
            t.setPadding(0, dp(8), 0, 0);
            card.addView(t);
        }
        return card;
    }

    private View todayCard(WidgetState s, LocalDate today, Schedule.Source source) {
        LinearLayout card = card();
        card.setPadding(dp(8), dp(12), dp(8), dp(8));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), 0, dp(8), dp(6));
        String title = s.listDate.equals(today) ? "Сегодня" : s.listTitle;
        TextView t = text(title, 18, c(R.color.a_text), 500);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (s.counter != null) head.addView(text(s.counter, 13, c(R.color.a_muted), 500));
        card.addView(head);
        if (s.lessons.isEmpty()) {
            TextView e = text("Уроков нет", 14, c(R.color.a_muted), 400);
            e.setPadding(dp(8), dp(4), dp(8), dp(8));
            card.addView(e);
            return card;
        }
        Schedule.Day day = source.day(s.listDate);
        for (int i = 0; i < s.lessons.size(); i++) {
            WidgetState.Row r = i < s.rows.length ? s.rows[i] : WidgetState.Row.NORMAL;
            card.addView(lessonRow(s.listDate, day, s.lessons.get(i), r, true));
        }
        return card;
    }

    // ---------- Неделя ----------

    private void renderWeek(LinearLayout col) {
        col.addView(text("Неделя", 24, c(R.color.a_text), 400));

        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset);
        LocalDate sunday = monday.plusDays(6);

        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setGravity(Gravity.CENTER_VERTICAL);
        sw.addView(arrow(R.drawable.ic_app_chevron_left, "Прошлая неделя", weekOffset > -1, -1));
        TextView range = text(WidgetState.dateText(monday) + " — " + WidgetState.dateText(sunday),
                14, c(R.color.a_text), 500);
        range.setGravity(Gravity.CENTER);
        sw.addView(range, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sw.addView(arrow(R.drawable.ic_app_chevron_right, "Следующая неделя", weekOffset < 1, 1));
        LinearLayout.LayoutParams swp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swp.topMargin = dp(10);
        col.addView(sw, swp);
        TextView hint = text("Нажмите на урок, чтобы задать или изменить домашнее задание.", 12, c(R.color.a_muted), 400);
        hint.setPadding(0, dp(4), 0, 0);
        col.addView(hint);

        Schedule.Source source = Bilim.source(this);
        WidgetState now = WidgetState.compute(LocalDateTime.now(), source);

        int shown = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            Schedule.Day day = source.day(d);
            if (i >= 5 && day.lessons.isEmpty()) continue;
            col.addView(dayCard(d, day, d.equals(today) ? now : null));
            shown++;
        }
        if (shown == 0) col.addView(text("На этой неделе уроков нет.", 15, c(R.color.a_muted), 400));
        if (!Bilim.isConnected(this)) col.addView(templateNote());
    }

    private View arrow(int iconRes, String label, boolean enabled, int step) {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.addView(icon(iconRes, c(R.color.a_text), 22));
        b.setBackground(round(c(R.color.a_surface), 12));
        b.setContentDescription(label);
        b.setAlpha(enabled ? 1f : 0.3f);
        if (enabled) {
            b.setOnClickListener(v -> {
                weekOffset += step;
                scrollY = 0;
                render();
            });
        }
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        return b;
    }

    private View dayCard(LocalDate d, Schedule.Day day, WidgetState now) {
        boolean isToday = now != null;
        LinearLayout card = card();
        card.setPadding(dp(8), dp(12), dp(8), dp(8));
        String head = WidgetState.DAY_NAMES[d.getDayOfWeek().getValue() - 1] + ", " + WidgetState.dateText(d)
                + (isToday ? " · сегодня" : "");
        TextView h = text(head, 14, c(isToday ? R.color.a_primary : R.color.a_muted), 600);
        h.setPadding(dp(8), 0, dp(8), dp(4));
        card.addView(h);

        if (day.lessons.isEmpty()) {
            TextView e = text(day.holiday ? "Праздничный день — уроков нет" : "Уроков нет", 14, c(R.color.a_muted), 400);
            e.setPadding(dp(8), dp(2), dp(8), dp(6));
            card.addView(e);
            return card;
        }
        List<Schedule.Lesson> lessons = day.lessons;
        for (int i = 0; i < lessons.size(); i++) {
            WidgetState.Row r = WidgetState.Row.NORMAL;
            if (isToday && now.lessons == lessons && i < now.rows.length) r = now.rows[i];
            card.addView(lessonRow(d, day, lessons.get(i), r, false));
        }
        return card;
    }

    /** Строка урока: время, класс, темы и домашка, метка «идёт»/«далее». Нажатие — окно домашки. */
    private View lessonRow(LocalDate d, Schedule.Day day, Schedule.Lesson l, WidgetState.Row r, boolean shortTime) {
        boolean cur = r == WidgetState.Row.CURRENT;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (cur) row.setBackground(round(c(R.color.a_primary_soft), 10));
        row.setOnClickListener(v -> openHomework(d, l));

        TextView time = text(shortTime ? WidgetState.hm(l.start) : WidgetState.range(l), 14,
                c(cur ? R.color.a_primary : R.color.a_muted), 500);
        row.addView(time, new LinearLayout.LayoutParams(dp(shortTime ? 52 : 104), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int nameColor = c(cur ? R.color.a_primary : r == WidgetState.Row.PAST ? R.color.a_muted : R.color.a_text);
        TextView name = text(l.title, 15, nameColor, cur || r == WidgetState.Row.NEXT ? 600 : 500);
        if (r == WidgetState.Row.PAST) name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        body.addView(name);
        boolean many = l.details.size() > 1;
        for (Schedule.Detail det : l.details) {
            StringBuilder sb = new StringBuilder();
            if (!det.theme.isEmpty()) sb.append(many ? det.cls + ": " : "").append(det.theme);
            if (!det.homework.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(many ? det.cls + " — " : "").append("ДЗ: ").append(det.homework);
            }
            if (!det.files.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("📎 файлов: ").append(det.files.size());
            }
            if (sb.length() > 0) body.addView(text(sb.toString(), 12, c(R.color.a_muted), 400));
        }
        if (missingHomework(day, l)) {
            body.addView(text("ДЗ не задано — нажмите, чтобы задать", 12, c(R.color.a_red), 400));
        }
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (cur || r == WidgetState.Row.NEXT) {
            TextView chip = text(cur ? "идёт" : "далее", 12,
                    c(cur ? R.color.w_chip_cur_text : R.color.a_orange), 500);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            chip.setBackground(round(c(cur ? R.color.w_accent : R.color.a_orange_soft), 6));
            LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chp.leftMargin = dp(8);
            row.addView(chip, chp);
        }
        return row;
    }

    /** Урок без домашки, которую можно задать (классный час не считается). */
    private static boolean missingHomework(Schedule.Day day, Schedule.Lesson l) {
        return day.fromBilim && !l.title.startsWith("Кл. час") && l.homeworkSummary() == null;
    }

    private void openHomework(LocalDate d, Schedule.Lesson l) {
        startActivity(new Intent(this, HomeworkActivity.class)
                .putExtra(HomeworkActivity.EXTRA_DATE, d.toString())
                .putExtra(HomeworkActivity.EXTRA_START, l.start.toString()));
    }

    private View templateNote() {
        TextView note = text("Пока BilimClass не подключён, показано расписание, записанное в приложении "
                + "(без тем и домашних заданий).", 12, c(R.color.a_muted), 400);
        note.setPadding(0, dp(12), 0, 0);
        return note;
    }

    // ---------- Настройки ----------

    private void renderSettings(LinearLayout col) {
        col.addView(text("Настройки", 24, c(R.color.a_text), 400));
        if (Updater.hasUpdate(this) || updateStatus != null) col.addView(updateCard());
        col.addView(bilimCard());
        col.addView(aiCard());
        col.addView(remindersCard());

        if (AppWidgetManager.getInstance(this).isRequestPinAppWidgetSupported()) {
            Button add = button("Добавить виджет на главный экран", false);
            add.setOnClickListener(v -> pinWidget());
            col.addView(add, buttonParams(ViewGroup.LayoutParams.MATCH_PARENT, 16));
        }

        LinearLayout vrow = new LinearLayout(this);
        vrow.setOrientation(LinearLayout.HORIZONTAL);
        vrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ver = text("Версия " + Updater.installedName(this), 12, c(R.color.a_muted), 400);
        vrow.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button chk = button("Проверить обновления", false);
        chk.setOnClickListener(v -> checkUpdates(true));
        vrow.addView(chk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(16);
        col.addView(vrow, vp);

        TextView credits = text("Оформление по шаблону «Educational Platform – Student Dashboard» "
                + "(Maharram Hasanli, CC BY 4.0), иконки Vuesax, шрифт Onest (SIL OFL 1.1).", 11, c(R.color.a_muted), 400);
        credits.setPadding(0, dp(16), 0, 0);
        col.addView(credits);
    }

    private void pinWidget() {
        boolean ok = AppWidgetManager.getInstance(this).requestPinAppWidget(
                new ComponentName(this, ScheduleWidget.class), null, null);
        if (!ok) {
            Toast.makeText(this, "Добавьте виджет вручную: долгое нажатие на рабочем столе → Виджеты",
                    Toast.LENGTH_LONG).show();
        }
    }

    private View bilimCard() {
        LinearLayout card = card();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("B", 20, 0xFFFFFFFF, 600);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(c(R.color.a_blue), 8));
        head.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));

        String status;
        int color = c(R.color.a_muted);
        if (!Bilim.isConnected(this)) {
            status = "Не подключён. Подключите, чтобы расписание, темы и домашка подтягивались автоматически.";
        } else if (Bilim.needLogin(this)) {
            status = "Вход устарел — нажмите «Войти снова».";
            color = c(R.color.a_red);
        } else if (syncing) {
            status = "Обновляю…";
        } else {
            long last = Bilim.lastSync(this);
            status = last == 0 ? "Подключён." : "Обновлено " + when(last);
        }
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(text("BilimClass", 16, c(R.color.a_text), 500));
        info.addView(text(status, 12, color, 400));
        head.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        if (syncMessage != null) {
            TextView m = text(syncMessage, 13, c(R.color.a_red), 400);
            m.setPadding(0, dp(8), 0, 0);
            card.addView(m);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(12);
        card.addView(buttons, bl);

        if (!Bilim.isConnected(this) || Bilim.needLogin(this)) {
            Button login = button(Bilim.isConnected(this) ? "Войти снова" : "Войти в BilimClass", true);
            login.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
            buttons.addView(login, new LinearLayout.LayoutParams(0, dp(44), 1f));
        } else {
            Button refresh = button("Обновить", true);
            refresh.setEnabled(!syncing);
            refresh.setOnClickListener(v -> startSync());
            buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));

            Button out = button("Выйти", false);
            out.setOnClickListener(v -> {
                Bilim.logout(this);
                CookieManager.getInstance().removeAllCookies(null);
                WebStorage.getInstance().deleteAllData();
                syncMessage = null;
                riskTotal = -1;
                ScheduleWidget.updateAll(this);
                render();
            });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(44), 1f);
            op.leftMargin = dp(8);
            buttons.addView(out, op);
        }
        return card;
    }

    private boolean editingKey = false;
    private boolean checkingKey = false;
    private String pendingKey = "";
    private String pendingBase = "";
    private String aiMsg = "";

    private View aiCard() {
        LinearLayout card = card();
        card.addView(text("Нейросеть (ключ API)", 16, c(R.color.a_text), 500));
        String key = Bilim.aiKey(this);
        Ai.Provider ai = Bilim.aiProvider(this);
        if (ai != null && !editingKey) {
            String tail = key.length() > 4 ? key.substring(key.length() - 4) : key;
            card.addView(text("● Подключено: " + ai.name, 15, c(R.color.a_green), 600));
            card.addView(text("Ключ …" + tail + (Ai.CUSTOM.equals(ai.id) ? " · " + ai.base : "")
                    + ". Нажмите на урок, чтобы задать домашку.", 13, c(R.color.a_muted), 400));
            List<String> models = Bilim.aiModels(this);
            String current = Bilim.aiModel(this);
            if (!current.isEmpty() && !models.contains(current)) models.add(0, current);
            if (!models.isEmpty()) {
                TextView ml = text("Модель:", 13, c(R.color.a_muted), 400);
                ml.setPadding(0, dp(8), 0, 0);
                card.addView(ml);
                Spinner spinner = new Spinner(this);
                ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, models);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spinner.setAdapter(adapter);
                spinner.setSelection(Math.max(0, models.indexOf(current)), false);
                spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                        String m = models.get(pos);
                        if (!m.equals(Bilim.aiModel(MainActivity.this))) Bilim.setAiModel(MainActivity.this, m);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
                card.addView(spinner);
            }
            if (!aiMsg.isEmpty()) card.addView(text(aiMsg, 13, c(R.color.a_red), 400));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button change = button("Изменить ключ", false);
            change.setOnClickListener(v -> {
                editingKey = true;
                aiMsg = "";
                pendingBase = Bilim.aiBase(this);
                render();
            });
            row.addView(change, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button refresh = button(checkingKey ? "Обновляю…" : "Обновить модели", false);
            refresh.setEnabled(!checkingKey);
            refresh.setOnClickListener(v -> refreshModels(ai, key));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            rp.leftMargin = dp(8);
            row.addView(refresh, rp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            card.addView(row, lp);
            return card;
        }
        card.addView(text("Нужен, чтобы ИИ расписывал домашку на русском и английском. Подходят ключи "
                + "Google Gemini, Claude, Groq, xAI Grok, OpenRouter, OpenAI, DeepSeek, Mistral, AnyModel — сервис "
                + "определится сам. Бесплатный ключ: aistudio.google.com → Get API key. "
                + "Ключ хранится только на этом телефоне.", 13, c(R.color.a_muted), 400));
        final EditText field = field("Вставьте ключ (AIza…, sk-…, gsk_…, xai-…)", 15);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setText(pendingKey);
        field.addTextChangedListener(watcher(t -> pendingKey = t));
        card.addView(field);
        card.addView(text("Адрес сервиса — только если вашего сервиса нет в списке. Обычно оставьте пустым:",
                12, c(R.color.a_muted), 400));
        final EditText baseField = field("https://…/v1 (необязательно)", 13);
        baseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseField.setText(pendingBase);
        baseField.addTextChangedListener(watcher(t -> pendingBase = t));
        card.addView(baseField);
        if (checkingKey) card.addView(text("Проверяю ключ…", 13, c(R.color.a_muted), 400));
        else if (!aiMsg.isEmpty()) card.addView(text(aiMsg, 13, c(R.color.a_red), 400));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("Проверить и сохранить", true);
        save.setEnabled(!checkingKey);
        save.setOnClickListener(v -> {
            String k = field.getText().toString().trim();
            if (k.isEmpty()) {
                field.setError("Вставьте ключ");
                return;
            }
            checkKey(k, baseField.getText().toString().trim());
        });
        row.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1f));
        if (!key.isEmpty()) {
            Button remove = button("Удалить ключ", false);
            remove.setTextColor(c(R.color.a_red));
            remove.setOnClickListener(v -> {
                Bilim.clearAi(this);
                editingKey = false;
                aiMsg = "";
                render();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            rp.leftMargin = dp(8);
            row.addView(remove, rp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        card.addView(row, lp);
        return card;
    }

    private interface OnText {
        void changed(String text);
    }

    private TextWatcher watcher(OnText cb) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                cb.changed(s.toString());
            }

            @Override
            public void afterTextChanged(Editable e) {
            }
        };
    }

    /** Проверить ключ: какой сервис его принимает и какие модели доступны. */
    private void checkKey(String key, String base) {
        checkingKey = true;
        aiMsg = "";
        render();
        new Thread(() -> {
            Ai.Detected d = null;
            String err = "";
            try {
                d = Ai.detect(key, base);
            } catch (IOException e) {
                err = Ai.explainDetect(e);
            }
            final Ai.Detected found = d;
            final String msg = err;
            runOnUiThread(() -> {
                checkingKey = false;
                if (found != null) {
                    Bilim.setAi(this, key, found);
                    pendingKey = "";
                    pendingBase = "";
                    editingKey = false;
                    Toast.makeText(this, "Подключено: " + found.name, Toast.LENGTH_SHORT).show();
                } else {
                    aiMsg = msg;
                }
                render();
            });
        }).start();
    }

    private void refreshModels(Ai.Provider ai, String key) {
        checkingKey = true;
        aiMsg = "";
        render();
        new Thread(() -> {
            List<String> models = null;
            String err = "";
            try {
                models = Ai.listModels(ai, key);
            } catch (IOException e) {
                err = Ai.explainDetect(e);
            }
            final List<String> found = models;
            final String msg = err;
            runOnUiThread(() -> {
                checkingKey = false;
                if (found != null) Bilim.setAiModels(this, ai.id, found);
                else aiMsg = msg;
                render();
            });
        }).start();
    }

    private String when(long epochMs) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (t.toLocalDate().equals(LocalDate.now())) return "сегодня в " + hm;
        return WidgetState.dateText(t.toLocalDate()) + " в " + hm;
    }

    /** 1 урок, 2 урока, 5 уроков */
    /** Отметки посещаемости с компьютера — чтобы плитка показывала свежие предупреждения. */
    private void syncAttendance() {
        if (Attestation.scriptUrl(this).isEmpty()) return;
        Context app = getApplicationContext();
        new Thread(() -> {
            try {
                if (Attendance.sync(app)) runOnUiThread(() -> {
                    if (!isFinishing()) render();
                });
            } catch (java.io.IOException ignored) {
                // без связи — покажем то, что есть на телефоне
            }
        }).start();
    }

    private static String plural(int n, String one, String few, String many) {
        int a = n % 100;
        int b = n % 10;
        if (a >= 11 && a <= 14) return many;
        if (b == 1) return one;
        if (b >= 2 && b <= 4) return few;
        return many;
    }

    // ---------- Элементы оформления ----------

    private int c(int colorRes) {
        return getColor(colorRes);
    }

    private final SparseArray<Typeface> fonts = new SparseArray<>();

    /** Onest нужной насыщенности (переменный шрифт); если не загрузился — системный. */
    private Typeface font(int weight) {
        Typeface t = fonts.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(getAssets(), "fonts/onest.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        fonts.put(weight, t);
        return t;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(round(c(R.color.a_surface), 16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(16);
        card.setLayoutParams(cp);
        return card;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(font(500));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(c(primary ? R.color.a_on_primary : R.color.a_text));
        GradientDrawable g = round(c(primary ? R.color.a_primary : R.color.a_bg), 8);
        if (!primary) g.setStroke(dp(1), c(R.color.a_border));
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private LinearLayout.LayoutParams buttonParams(int width, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(44));
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private android.widget.CheckBox checkBox(String label, boolean checked) {
        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setTextColor(c(R.color.a_text));
        cb.setTypeface(font(400));
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(c(R.color.a_primary)));
        cb.setChecked(checked);
        return cb;
    }

    private EditText field(String hint, int sp) {
        EditText f = new EditText(this);
        f.setHint(hint);
        f.setSingleLine(true);
        f.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        f.setTextColor(c(R.color.a_text));
        f.setHintTextColor(c(R.color.a_muted));
        f.setBackgroundTintList(android.content.res.ColorStateList.valueOf(c(R.color.a_border)));
        return f;
    }

    private ImageView icon(int res, int color, int sizeDp) {
        ImageView v = new ImageView(this);
        v.setImageResource(res);
        v.setColorFilter(color);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    private TextView text(String s, int sp, int color, int weight) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(weight));
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
