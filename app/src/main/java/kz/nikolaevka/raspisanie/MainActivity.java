package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Экран приложения: расписание на всю неделю и кнопка «Добавить виджет». */
public class MainActivity extends Activity {

    private static final int PURPLE_DARK = 0xFF4A148C;
    private static final int PURPLE = 0xFF7B1FA2;
    private static final int PURPLE_LIGHT = 0xFFF3E5F5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(PURPLE_DARK);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ScheduleWidget.updateAll(this);
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PURPLE_LIGHT);
        scroll.setFitsSystemWindows(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        TextView title = text("Моё расписание", 24, PURPLE_DARK, true);
        col.addView(title);

        AppWidgetManager mgr = AppWidgetManager.getInstance(this);
        if (mgr.isRequestPinAppWidgetSupported()) {
            Button add = new Button(this);
            add.setText("Добавить виджет на главный экран");
            add.setAllCaps(false);
            add.setTextColor(Color.WHITE);
            add.setBackground(round(PURPLE, 14));
            add.setOnClickListener(v -> {
                boolean ok = mgr.requestPinAppWidget(
                        new ComponentName(this, ScheduleWidget.class), null, null);
                if (!ok) {
                    Toast.makeText(this, "Добавьте виджет вручную (см. подсказку ниже)",
                            Toast.LENGTH_LONG).show();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(12);
            col.addView(add, lp);
        }

        TextView hint = text("Или вручную: долгое нажатие на пустом месте рабочего стола → "
                + "«Виджеты» → «Моё расписание». Размер виджета можно менять, "
                + "потянув за края. Нажатие на виджет открывает этот экран.", 13, 0xFF6A1B9A, false);
        hint.setPadding(0, dp(10), 0, dp(6));
        col.addView(hint);

        DayOfWeek today = LocalDate.now().getDayOfWeek();
        WidgetState now = WidgetState.compute(LocalDateTime.now());
        DayOfWeek[] days = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY};

        for (DayOfWeek d : days) {
            boolean isToday = d == today;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.setBackground(round(isToday ? PURPLE : Color.WHITE, 16));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(10);
            col.addView(card, cp);

            int main = isToday ? Color.WHITE : PURPLE_DARK;
            String head = WidgetState.DAY_NAMES[d.getValue() - 1] + (isToday ? " · сегодня" : "");
            card.addView(text(head, 17, main, true));

            List<Schedule.Lesson> lessons = Schedule.forDay(d);
            for (int i = 0; i < lessons.size(); i++) {
                Schedule.Lesson l = lessons.get(i);
                String mark = "";
                int color = isToday ? Color.WHITE : 0xFF333333;
                if (isToday && now.rows != null && i < now.rows.length && now.lessons == lessons) {
                    switch (now.rows[i]) {
                        case CURRENT: mark = "▶ "; color = 0xFFFFD54F; break;
                        case PAST: mark = "✓ "; color = 0xB3FFFFFF; break;
                        case NEXT: mark = "→ "; color = 0xFFFFD54F; break;
                        default: break;
                    }
                }
                TextView row = text(mark + WidgetState.range(l) + "   " + l.title, 15, color, false);
                row.setPadding(0, dp(4), 0, 0);
                card.addView(row);
            }
        }

        TextView foot = text("Чтобы изменить расписание, поправьте файл Schedule.java "
                + "на GitHub и установите новую версию.", 12, 0xFF9575CD, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(16), 0, dp(8));
        col.addView(foot);
        return scroll;
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
