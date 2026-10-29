package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.os.Bundle;
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

    private boolean busy = false;
    private boolean onlyRisk = false;
    private String message = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        scroll.setBackgroundColor(c(R.color.a_bg));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Успеваемость", 24, c(R.color.a_text), 400));
        col.addView(text(busy ? "Загружаю журналы из BilimClass…" : when(d.updatedAt), 13, c(R.color.a_muted), 400));
        if (message != null) col.addView(text(message, 13, c(R.color.a_red), 400));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox cb = Ui.checkBox(this, "Только группа риска", onlyRisk);
        cb.setOnCheckedChangeListener((v, on) -> {
            onlyRisk = on;
            render();
        });
        bar.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button upd = Ui.button(this, "Обновить", true);
        upd.setEnabled(!busy);
        upd.setOnClickListener(v -> refresh());
        bar.addView(upd, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)));
        col.addView(bar, margins(dp(12)));

        Grades.Check check = Grades.quarterCheck(d.journals, d.lessons, LocalDate.now().toString());
        if (check != null) col.addView(checkCard(check));

        if (d.journals.isEmpty()) {
            LinearLayout card = Ui.card(this);
            card.addView(text(d.updatedAt == 0 ? "Нажмите «Обновить», чтобы загрузить журналы из BilimClass."
                    : "Журналов не найдено.", 14, c(R.color.a_muted), 400));
            col.addView(card);
        }
        for (Grades.Journal j : d.journals) col.addView(classCard(j));

        col.addView(text("Оценка: если BilimClass уже рассчитал рекомендуемую — показана она; иначе — расчёт "
                + "приложения по формуле журнала (ФО 25%, СОР 25%, СОЧ 50%; <40% — 2, 40–64% — 3, 65–84% — 4, "
                + "от 85% — 5). «прогноз» — пока не все части выставлены. «На СОЧ» — сколько баллов нужно при "
                + "нынешних ФО и СОР. Оценки учеников хранятся только на этом телефоне.", 12, c(R.color.a_muted), 400));
        setContentView(scroll);
    }

    private View checkCard(Grades.Check c) {
        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.round(this, c(R.color.a_orange_soft), 16));
        String head = c.daysLeft >= 0 ? "Конец четверти через " + c.daysLeft + " дн. (" + dm(c.end) + ")"
                : "Четверть закончилась " + dm(c.end);
        card.addView(text(head, 16, c(R.color.a_orange), 600));
        if (c.deadline != null) card.addView(text("Итоговые — до " + dm(c.deadline), 13, c(R.color.a_muted), 400));
        if (c.items.isEmpty()) {
            card.addView(text("Всё в порядке: итоговые, СОР/СОЧ, темы и ДЗ заполнены ✓", 14, c(R.color.a_green), 500));
        }
        for (String it : c.items) card.addView(text("• " + it, 14, c(R.color.a_text), 400));
        return card;
    }

    private View classCard(Grades.Journal j) {
        LinearLayout card = Ui.card(this);
        List<Grades.Row> rows = Grades.summary(j);
        int risk = 0;
        for (Grades.Row r : rows) if (r.risk != null) risk++;
        card.addView(text(j.cls + (j.subject.isEmpty() ? "" : " · " + j.subject), 18, c(R.color.a_text), 500));
        StringBuilder sub = new StringBuilder();
        if (j.period != null) sub.append(j.period.n).append(" четверть, ").append(dm(j.period.start)).append("–").append(dm(j.period.end));
        if (risk > 0) sub.append(" · группа риска: ").append(risk);
        card.addView(text(sub.toString(), 13, c(risk > 0 ? R.color.a_red : R.color.a_muted), 400));
        int shown = 0;
        for (Grades.Row r : rows) {
            if (onlyRisk && r.risk == null) continue;
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, 0);
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(text(r.st.name, 15, c(R.color.a_text), 500));
            String parts = "ФО " + pct(r.stats.fo) + " · СОР " + pct(r.stats.sor) + " · СОЧ " + pct(r.stats.soch)
                    + " · итог " + (r.stats.total == null ? "—" : r.stats.total + "%" + (r.stats.forecast ? " (прогноз)" : ""));
            left.addView(text(parts, 12, c(R.color.a_muted), 400));
            if (r.risk != null) left.addView(text("⚠ " + Grades.riskText(r.risk), 13, c(R.color.a_red), 600));
            if (r.soch != null) left.addView(text(sochText(r.soch), 12, c(R.color.a_muted), 400));
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (r.stats.grade != null) row.addView(gradeBadge(r.stats.grade));
            card.addView(row);
        }
        if (shown == 0) card.addView(text(onlyRisk ? "Никого в группе риска ✓" : "Нет учеников.", 14, c(R.color.a_muted), 400));
        return card;
    }

    private TextView gradeBadge(int g) {
        // 2 — красный, 3 — оранжевый, 4 — синий, 5 — зелёный, как плашки на главном экране
        int[] bg = {0, 0, R.color.a_red_soft, R.color.a_orange_soft, R.color.a_blue_soft, R.color.a_green_soft};
        int[] fg = {0, 0, R.color.a_red, R.color.a_orange, R.color.a_blue, R.color.a_green};
        boolean known = g >= 2 && g <= 5;
        TextView t = text(String.valueOf(g), 16, c(known ? fg[g] : R.color.a_muted), 600);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.round(this, c(known ? bg[g] : R.color.a_border), 8));
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

    private LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int c(int colorRes) {
        return Ui.c(this, colorRes);
    }

    private TextView text(String s, int sp, int color, int weight) {
        return Ui.text(this, s, sp, color, weight);
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
