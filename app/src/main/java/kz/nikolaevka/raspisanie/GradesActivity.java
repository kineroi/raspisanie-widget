package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Успеваемость: группа риска, сколько нужно на СОЧ, контроль конца четверти. Только просмотр. */
public class GradesActivity extends Activity {

    private static final int PURPLE_DARK = 0xFF4A148C;
    private static final int PURPLE = 0xFF7B1FA2;
    private static final int PURPLE_LIGHT = 0xFFF3E5F5;
    private static final int GRAY = 0xFF555555;
    private static final int RED = 0xFFC62828;

    private boolean busy = false;
    private boolean onlyRisk = false;
    private String message = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PURPLE_DARK);
        render();
        Journals.Data d = Journals.load(this);
        if (System.currentTimeMillis() - d.updatedAt > 60L * 60 * 1000) refresh();
    }

    private void refresh() {
        if (busy) return;
        busy = true;
        message = null;
        render();
        new Thread(() -> {
            Journals.Result r = Journals.refresh(getApplicationContext());
            runOnUiThread(() -> {
                busy = false;
                switch (r) {
                    case NEED_LOGIN: message = "Вход в BilimClass устарел — войдите заново на главном экране."; break;
                    case NO_NETWORK: message = "Не удалось загрузить: нет связи с BilimClass. Показано сохранённое."; break;
                    case NOT_CONNECTED: message = "BilimClass не подключён — войдите на главном экране."; break;
                    case NO_JOURNALS: message = "Не нашёл ваших журналов: обновите расписание на главном экране."; break;
                    default: message = null; break;
                }
                render();
            });
        }).start();
    }

    private void render() {
        Journals.Data d = Journals.load(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PURPLE_LIGHT);
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(col);

        col.addView(text("Успеваемость", 24, PURPLE_DARK, true));
        col.addView(text(busy ? "Загружаю журналы из BilimClass…" : when(d.updatedAt), 13, GRAY, false));
        if (message != null) col.addView(text(message, 13, RED, false));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setText("Только группа риска");
        cb.setChecked(onlyRisk);
        cb.setOnCheckedChangeListener((v, on) -> {
            onlyRisk = on;
            render();
        });
        bar.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button upd = button("Обновить", PURPLE, Color.WHITE);
        upd.setEnabled(!busy);
        upd.setOnClickListener(v -> refresh());
        bar.addView(upd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        col.addView(bar, margins(dp(8)));

        Grades.Check check = Grades.quarterCheck(d.journals, d.lessons, LocalDate.now().toString());
        if (check != null) col.addView(checkCard(check));

        if (d.journals.isEmpty()) {
            LinearLayout card = card(Color.WHITE);
            card.addView(text(d.updatedAt == 0 ? "Нажмите «Обновить», чтобы загрузить журналы из BilimClass."
                    : "Журналов не найдено.", 14, GRAY, false));
            col.addView(card);
        }
        for (Grades.Journal j : d.journals) col.addView(classCard(j));

        col.addView(text("Оценка: если BilimClass уже рассчитал рекомендуемую — показана она; иначе — расчёт "
                + "приложения по формуле журнала (ФО 25%, СОР 25%, СОЧ 50%; <40% — 2, 40–64% — 3, 65–84% — 4, "
                + "от 85% — 5). «прогноз» — пока не все части выставлены. «На СОЧ» — сколько баллов нужно при "
                + "нынешних ФО и СОР. Оценки учеников хранятся только на этом телефоне.", 12, 0xFF9575CD, false));
        setContentView(scroll);
    }

    private View checkCard(Grades.Check c) {
        LinearLayout card = card(0xFFFFF8E1);
        String head = c.daysLeft >= 0 ? "Конец четверти через " + c.daysLeft + " дн. (" + dm(c.end) + ")"
                : "Четверть закончилась " + dm(c.end);
        card.addView(text(head, 16, 0xFF8D6E00, true));
        if (c.deadline != null) card.addView(text("Итоговые — до " + dm(c.deadline), 13, GRAY, false));
        if (c.items.isEmpty()) {
            card.addView(text("Всё в порядке: итоговые, СОР/СОЧ, темы и ДЗ заполнены ✓", 14, 0xFF2E7D32, false));
        }
        for (String it : c.items) card.addView(text("• " + it, 14, 0xFF333333, false));
        return card;
    }

    private View classCard(Grades.Journal j) {
        LinearLayout card = card(Color.WHITE);
        List<Grades.Row> rows = Grades.summary(j);
        int risk = 0;
        for (Grades.Row r : rows) if (r.risk != null) risk++;
        card.addView(text(j.cls + (j.subject.isEmpty() ? "" : " · " + j.subject), 17, PURPLE_DARK, true));
        StringBuilder sub = new StringBuilder();
        if (j.period != null) sub.append(j.period.n).append(" четверть, ").append(dm(j.period.start)).append("–").append(dm(j.period.end));
        if (risk > 0) sub.append(" · группа риска: ").append(risk);
        card.addView(text(sub.toString(), 13, risk > 0 ? RED : GRAY, false));
        int shown = 0;
        for (Grades.Row r : rows) {
            if (onlyRisk && r.risk == null) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(text(r.st.name, 15, 0xFF222222, true));
            String parts = "ФО " + pct(r.stats.fo) + " · СОР " + pct(r.stats.sor) + " · СОЧ " + pct(r.stats.soch)
                    + " · итог " + (r.stats.total == null ? "—" : r.stats.total + "%" + (r.stats.forecast ? " (прогноз)" : ""));
            left.addView(text(parts, 12, GRAY, false));
            if (r.risk != null) left.addView(text("⚠ " + Grades.riskText(r.risk), 13, RED, true));
            if (r.soch != null) left.addView(text(sochText(r.soch), 12, GRAY, false));
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (r.stats.grade != null) row.addView(gradeBadge(r.stats.grade));
            card.addView(row);
        }
        if (shown == 0) card.addView(text(onlyRisk ? "Никого в группе риска ✓" : "Нет учеников.", 14, GRAY, false));
        return card;
    }

    private TextView gradeBadge(int g) {
        int[] bg = {0, 0, 0xFFFFCDD2, 0xFFFFE0B2, 0xFFE1F5FE, 0xFFC8E6C9};
        int[] fg = {0, 0, 0xFFB71C1C, 0xFF8D4A00, 0xFF01579B, 0xFF1B5E20};
        TextView t = text(String.valueOf(g), 16, g >= 2 && g <= 5 ? fg[g] : GRAY, true);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(g >= 2 && g <= 5 ? bg[g] : 0xFFEEEEEE);
        d.setCornerRadius(dp(8));
        t.setBackground(d);
        t.setMinWidth(dp(34));
        t.setPadding(dp(6), dp(2), dp(6), dp(2));
        return t;
    }

    private static String sochText(Grades.Need n) {
        StringBuilder sb = new StringBuilder("На СОЧ (из " + n.max + "): ");
        for (int g = 3; g <= 5; g++) {
            Integer v = n.need[g];
            sb.append(g).append(" — ").append(v == null ? "нельзя" : v == 0 ? "уже есть" : "от " + v);
            if (g < 5) sb.append(", ");
        }
        return sb.toString();
    }

    private static String pct(Double v) {
        if (v == null) return "—";
        return String.format(java.util.Locale.ROOT, "%.1f%%", v).replace('.', ',');
    }

    private static String dm(String iso) {
        return iso.substring(8) + "." + iso.substring(5, 7);
    }

    private String when(long ms) {
        if (ms == 0) return "ещё не загружалось";
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
        String hm = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        if (t.toLocalDate().equals(LocalDate.now())) return "обновлено сегодня в " + hm;
        return "обновлено " + WidgetState.dateText(t.toLocalDate()) + " в " + hm;
    }

    private LinearLayout card(int bg) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(16));
        card.setBackground(g);
        card.setLayoutParams(margins(dp(10)));
        return card;
    }

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.setPadding(dp(14), 0, dp(14), 0);
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

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
