package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Spinner;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.io.IOException;
import java.util.List;

/** Экран приложения: уроки на неделю с темами и домашкой, подключение BilimClass. */
public class MainActivity extends Activity {

    private static final int PURPLE_DARK = 0xFF4A148C;
    private static final int PURPLE = 0xFF7B1FA2;
    private static final int PURPLE_LIGHT = 0xFFF3E5F5;
    private static final int GRAY = 0xFF555555;

    /** -1 прошлая, 0 эта, 1 следующая неделя. */
    private int weekOffset = 0;
    private boolean syncing = false;
    private String syncMessage = null;
    private int scrollY = 0;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PURPLE_DARK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScheduleWidget.updateAll(this);
        render();
        if (Bilim.isConnected(this)) startSync();
        checkUpdates(false);
        askNotificationPermission();
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
        LinearLayout card = card(Color.WHITE);
        android.widget.CheckBox cb = new android.widget.CheckBox(this);
        cb.setText("Напоминать за 5 минут до урока");
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setTextColor(PURPLE_DARK);
        cb.setChecked(Reminders.enabled(this));
        cb.setOnCheckedChangeListener((v, on) -> {
            Reminders.setEnabled(this, on);
            if (on) {
                askedNotifications = false;
                askNotificationPermission();
            }
        });
        card.addView(cb);
        android.widget.CheckBox gcb = new android.widget.CheckBox(this);
        gcb.setText("Группа риска (пн) и контроль конца четверти");
        gcb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        gcb.setTextColor(PURPLE_DARK);
        gcb.setChecked(Digest.enabled(this));
        gcb.setOnCheckedChangeListener((v, on) -> Digest.setEnabled(this, on));
        card.addView(gcb);
        if (Reminders.enabled(this) && !notificationsAllowed()) {
            card.addView(text("Уведомления выключены в настройках Android — напоминания не будут видны.",
                    13, 0xFFC62828, false));
            Button settings = button("Разрешить уведомления", PURPLE, Color.WHITE);
            settings.setOnClickListener(v -> startActivity(new Intent(
                    android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            lp.topMargin = dp(6);
            card.addView(settings, lp);
        } else if (Reminders.enabled(this)) {
            Button test = button("Пробное напоминание", Color.WHITE, PURPLE_DARK);
            test.setOnClickListener(v -> {
                Reminders.Next n = Reminders.next(LocalDateTime.now(), Bilim.source(this));
                if (n == null) {
                    Toast.makeText(this, "Ближайших уроков не найдено", Toast.LENGTH_SHORT).show();
                } else {
                    Reminders.show(this, n.date, n.lesson);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.topMargin = dp(4);
            card.addView(test, lp);
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
        LinearLayout card = card(PURPLE);
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) card.getLayoutParams();
        cp.topMargin = dp(12);
        card.addView(text("Доступна новая версия " + Updater.updateVersion(this), 16, Color.WHITE, true));
        card.addView(text(updateStatus != null ? updateStatus
                : "Расписание, вход и ключ сохранятся. Android спросит подтверждение установки.", 13, 0xFFE1BEE7, false));
        if (downloadId < 0) {
            Button b = button("Обновить", Color.WHITE, PURPLE_DARK);
            b.setOnClickListener(v -> startUpdate());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            lp.topMargin = dp(8);
            card.addView(b, lp);
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
            });
        }).start();
    }

    private void render() {
        if (scroll != null) scrollY = scroll.getScrollY();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(PURPLE_LIGHT);
        scroll.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        col.addView(text("Моё расписание", 24, PURPLE_DARK, true));
        if (Updater.hasUpdate(this) || updateStatus != null) col.addView(updateCard());
        col.addView(bilimCard());
        if (Bilim.isConnected(this)) {
            Button grades = button("📊 Успеваемость", PURPLE, Color.WHITE);
            grades.setOnClickListener(v -> startActivity(new Intent(this, GradesActivity.class)));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            glp.topMargin = dp(10);
            col.addView(grades, glp);
        }
        col.addView(aiCard());
        col.addView(remindersCard());

        // Переключатель недель
        LinearLayout weeks = new LinearLayout(this);
        weeks.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(14);
        col.addView(weeks, wp);
        String[] labels = {"Прошлая", "Эта неделя", "Следующая"};
        for (int i = 0; i < 3; i++) {
            final int off = i - 1;
            boolean sel = off == weekOffset;
            Button b = button(labels[i], sel ? PURPLE : Color.WHITE, sel ? Color.WHITE : PURPLE_DARK);
            b.setOnClickListener(v -> {
                weekOffset = off;
                scrollY = 0;
                render();
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) bp.leftMargin = dp(6);
            weeks.addView(b, bp);
        }

        LocalDate today = LocalDate.now();
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset);
        LocalDate sunday = monday.plusDays(6);
        TextView range = text(WidgetState.dateText(monday) + " — " + WidgetState.dateText(sunday), 14, GRAY, false);
        range.setGravity(Gravity.CENTER);
        range.setPadding(0, dp(8), 0, 0);
        col.addView(range);

        Schedule.Source source = Bilim.source(this);
        WidgetState now = WidgetState.compute(LocalDateTime.now(), source);

        int shown = 0;
        for (int i = 0; i < 7; i++) {
            LocalDate d = monday.plusDays(i);
            Schedule.Day day = source.day(d);
            boolean weekend = i >= 5;
            if (weekend && day.lessons.isEmpty()) continue;
            col.addView(dayCard(d, day, d.equals(today) ? now : null));
            shown++;
        }
        if (shown == 0) col.addView(text("На этой неделе уроков нет.", 15, GRAY, false));

        if (!Bilim.isConnected(this)) {
            TextView note = text("Пока BilimClass не подключён, показано расписание, записанное в приложении "
                    + "(без тем и домашних заданий).", 12, 0xFF9575CD, false);
            note.setPadding(0, dp(12), 0, 0);
            col.addView(note);
        }

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        if (mgr.isRequestPinAppWidgetSupported()) {
            Button add = button("Добавить виджет на главный экран", Color.WHITE, PURPLE_DARK);
            add.setOnClickListener(v -> {
                boolean ok = mgr.requestPinAppWidget(
                        new ComponentName(this, ScheduleWidget.class), null, null);
                if (!ok) {
                    Toast.makeText(this, "Добавьте виджет вручную: долгое нажатие на рабочем столе → Виджеты",
                            Toast.LENGTH_LONG).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
            lp.topMargin = dp(16);
            col.addView(add, lp);
        }

        LinearLayout vrow = new LinearLayout(this);
        vrow.setOrientation(LinearLayout.HORIZONTAL);
        vrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView ver = text("Версия " + Updater.installedName(this), 12, GRAY, false);
        vrow.addView(ver, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button chk = button("Проверить обновления", Color.WHITE, PURPLE_DARK);
        chk.setOnClickListener(v -> checkUpdates(true));
        vrow.addView(chk, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(16);
        col.addView(vrow, vp);

        setContentView(scroll);
        final int y = scrollY;
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View bilimCard() {
        LinearLayout card = card(Color.WHITE);
        LinearLayout.LayoutParams cp = (LinearLayout.LayoutParams) card.getLayoutParams();
        cp.topMargin = dp(12);

        String status;
        int color = GRAY;
        if (!Bilim.isConnected(this)) {
            status = "BilimClass не подключён. Подключите, чтобы расписание, темы и домашка "
                    + "подтягивались автоматически.";
        } else if (Bilim.needLogin(this)) {
            status = "Вход в BilimClass устарел — нажмите «Войти снова».";
            color = 0xFFC62828;
        } else if (syncing) {
            status = "BilimClass: обновляю…";
        } else {
            long last = Bilim.lastSync(this);
            status = last == 0 ? "BilimClass подключён." : "BilimClass: обновлено " + when(last);
        }
        card.addView(text(status, 14, color, false));
        if (syncMessage != null) {
            TextView m = text(syncMessage, 13, 0xFFC62828, false);
            m.setPadding(0, dp(4), 0, 0);
            card.addView(m);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(8);
        card.addView(buttons, bl);

        if (!Bilim.isConnected(this) || Bilim.needLogin(this)) {
            Button login = button(Bilim.isConnected(this) ? "Войти снова" : "Войти в BilimClass",
                    PURPLE, Color.WHITE);
            login.setOnClickListener(v -> startActivity(new Intent(this, LoginActivity.class)));
            buttons.addView(login, new LinearLayout.LayoutParams(0, dp(44), 1f));
        } else {
            Button refresh = button("Обновить", PURPLE, Color.WHITE);
            refresh.setEnabled(!syncing);
            refresh.setOnClickListener(v -> startSync());
            buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(44), 1f));

            Button out = button("Выйти", Color.WHITE, PURPLE_DARK);
            out.setOnClickListener(v -> {
                Bilim.logout(this);
                CookieManager.getInstance().removeAllCookies(null);
                WebStorage.getInstance().deleteAllData();
                syncMessage = null;
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
        LinearLayout card = card(Color.WHITE);
        String key = Bilim.aiKey(this);
        Ai.Provider ai = Bilim.aiProvider(this);
        if (ai != null && !editingKey) {
            String tail = key.length() > 4 ? key.substring(key.length() - 4) : key;
            card.addView(text("● Подключено: " + ai.name, 15, 0xFF2E7D32, true));
            card.addView(text("Ключ …" + tail + (Ai.CUSTOM.equals(ai.id) ? " · " + ai.base : "")
                    + ". Нажмите на урок, чтобы задать домашку.", 13, GRAY, false));
            List<String> models = Bilim.aiModels(this);
            String current = Bilim.aiModel(this);
            if (!current.isEmpty() && !models.contains(current)) models.add(0, current);
            if (!models.isEmpty()) {
                card.addView(text("Модель:", 13, GRAY, false));
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
            if (!aiMsg.isEmpty()) card.addView(text(aiMsg, 13, 0xFFC62828, false));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            Button change = button("Изменить ключ", Color.WHITE, PURPLE_DARK);
            change.setOnClickListener(v -> {
                editingKey = true;
                aiMsg = "";
                pendingBase = Bilim.aiBase(this);
                render();
            });
            row.addView(change, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button refresh = button(checkingKey ? "Обновляю…" : "Обновить модели", Color.WHITE, PURPLE_DARK);
            refresh.setEnabled(!checkingKey);
            refresh.setOnClickListener(v -> refreshModels(ai, key));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            rp.leftMargin = dp(8);
            row.addView(refresh, rp);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            card.addView(row, lp);
            return card;
        }
        card.addView(text("Нейросеть (ключ API)", 15, PURPLE_DARK, true));
        card.addView(text("Нужен, чтобы ИИ расписывал домашку на русском и английском. Подходят ключи "
                + "Google Gemini, Claude, Groq, OpenRouter, OpenAI, DeepSeek, Mistral, AnyModel — сервис "
                + "определится сам. Бесплатный ключ: aistudio.google.com → Get API key. "
                + "Ключ хранится только на этом телефоне.", 13, GRAY, false));
        final EditText field = new EditText(this);
        field.setHint("Вставьте ключ (AIza…, sk-…, gsk_…)");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setText(pendingKey);
        field.addTextChangedListener(watcher(t -> pendingKey = t));
        card.addView(field);
        final EditText baseField = new EditText(this);
        baseField.setHint("Адрес сервиса (необязательно)");
        baseField.setSingleLine(true);
        baseField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        baseField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        baseField.setText(pendingBase);
        baseField.addTextChangedListener(watcher(t -> pendingBase = t));
        card.addView(baseField);
        if (checkingKey) card.addView(text("Проверяю ключ…", 13, GRAY, false));
        else if (!aiMsg.isEmpty()) card.addView(text(aiMsg, 13, 0xFFC62828, false));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("Проверить и сохранить", PURPLE, Color.WHITE);
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
            Button remove = button("Удалить ключ", Color.WHITE, 0xFFC62828);
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
        lp.topMargin = dp(6);
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

    private View dayCard(LocalDate d, Schedule.Day day, WidgetState now) {
        boolean isToday = now != null;
        LinearLayout card = card(isToday ? PURPLE : Color.WHITE);
        int main = isToday ? Color.WHITE : PURPLE_DARK;
        int sub = isToday ? 0xFFE1BEE7 : GRAY;

        String head = WidgetState.DAY_NAMES[d.getDayOfWeek().getValue() - 1] + ", " + WidgetState.dateText(d)
                + (isToday ? " · сегодня" : "");
        card.addView(text(head, 17, main, true));

        if (day.lessons.isEmpty()) {
            card.addView(text(day.holiday ? "Праздничный день — уроков нет" : "Уроков нет", 14, sub, false));
            return card;
        }

        List<Schedule.Lesson> lessons = day.lessons;
        for (int i = 0; i < lessons.size(); i++) {
            Schedule.Lesson l = lessons.get(i);
            String mark = "";
            int color = main;
            if (isToday && now.lessons == lessons && i < now.rows.length) {
                switch (now.rows[i]) {
                    case CURRENT: mark = "▶ "; color = 0xFFFFD54F; break;
                    case PAST: mark = "✓ "; break;
                    case NEXT: mark = "→ "; color = 0xFFFFD54F; break;
                    default: break;
                }
            }
            // Весь блок урока нажимается — открывается окно «Домашнее задание»
            LinearLayout block = new LinearLayout(this);
            block.setOrientation(LinearLayout.VERTICAL);
            block.setPadding(0, 0, 0, dp(2));
            final Schedule.Lesson lesson = l;
            block.setOnClickListener(v -> startActivity(new Intent(this, HomeworkActivity.class)
                    .putExtra(HomeworkActivity.EXTRA_DATE, d.toString())
                    .putExtra(HomeworkActivity.EXTRA_START, lesson.start.toString())));
            card.addView(block);

            TextView row = text(mark + WidgetState.range(l) + "   " + l.title + "  ✏️", 15, color, true);
            row.setPadding(0, dp(8), 0, 0);
            block.addView(row);

            for (Schedule.Detail det : l.details) {
                StringBuilder sb = new StringBuilder();
                boolean many = l.details.size() > 1;
                if (!det.theme.isEmpty()) {
                    sb.append(many ? det.cls + ": " : "").append(det.theme);
                }
                if (!det.homework.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(many ? "   " : "").append("ДЗ: ").append(det.homework);
                }
                if (sb.length() == 0) continue;
                TextView t = text(sb.toString(), 13, sub, false);
                t.setPadding(dp(12), dp(2), 0, 0);
                block.addView(t);
                int nFiles = det.files.size();
                if (nFiles > 0) {
                    TextView f = text("📎 файлов: " + nFiles, 12, sub, false);
                    f.setPadding(dp(12), 0, 0, 0);
                    block.addView(f);
                }
            }
            if (day.fromBilim && !l.title.startsWith("Кл. час") && l.homeworkSummary() == null) {
                TextView t = text("ДЗ не указано — нажмите, чтобы задать", 13, isToday ? 0xFFFFCDD2 : 0xFFC62828, false);
                t.setPadding(dp(12), dp(2), 0, 0);
                block.addView(t);
            }
        }
        return card;
    }

    private String when(long epochMs) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (t.toLocalDate().equals(LocalDate.now())) return "сегодня в " + hm;
        return WidgetState.dateText(t.toLocalDate()) + " в " + hm;
    }

    private LinearLayout card(int bg) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        card.setBackground(round(bg, 16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(10);
        card.setLayoutParams(cp);
        return card;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable g = round(bg, 12);
        if (bg == Color.WHITE) g.setStroke(dp(1), PURPLE);
        b.setBackground(g);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
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
