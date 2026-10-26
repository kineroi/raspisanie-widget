package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.text.InputType;
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
    }

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
        col.addView(bilimCard());
        col.addView(geminiCard());

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
    private String pendingKey = "";

    private View geminiCard() {
        LinearLayout card = card(Color.WHITE);
        String key = Bilim.aiKey(this);
        if (!key.isEmpty() && !editingKey) {
            String tail = key.length() > 4 ? key.substring(key.length() - 4) : key;
            card.addView(text("Gemini подключён (ключ …" + tail + "). Нажмите на урок, чтобы задать домашку.",
                    14, GRAY, false));
            Button change = button("Изменить ключ", Color.WHITE, PURPLE_DARK);
            change.setOnClickListener(v -> {
                editingKey = true;
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.topMargin = dp(6);
            card.addView(change, lp);
            return card;
        }
        card.addView(text("Ключ Google Gemini", 15, PURPLE_DARK, true));
        card.addView(text("Нужен, чтобы ИИ расписывал домашку на русском и английском. "
                + "Получить бесплатно: aistudio.google.com → Get API key → Create API key. "
                + "Ключ хранится только на этом телефоне.", 13, GRAY, false));
        final EditText field = new EditText(this);
        field.setHint("Вставьте ключ (AIza…)");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setText(pendingKey);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                pendingKey = s.toString();
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
            }
        });
        card.addView(field);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = button("Сохранить ключ", PURPLE, Color.WHITE);
        save.setOnClickListener(v -> {
            String k = field.getText().toString().trim();
            if (k.isEmpty()) {
                field.setError("Вставьте ключ");
                return;
            }
            Bilim.setAiKey(this, k);
            pendingKey = "";
            editingKey = false;
            Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_SHORT).show();
            render();
        });
        row.addView(save, new LinearLayout.LayoutParams(0, dp(44), 1f));
        if (!key.isEmpty()) {
            Button remove = button("Удалить ключ", Color.WHITE, 0xFFC62828);
            remove.setOnClickListener(v -> {
                Bilim.setAiKey(this, "");
                editingKey = false;
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
