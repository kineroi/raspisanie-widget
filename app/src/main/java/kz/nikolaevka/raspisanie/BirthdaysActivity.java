package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/** Дни рождения учеников: список по порядку от сегодня, добавление из таблицы. */
public class BirthdaysActivity extends Activity {

    private JSONObject journal;
    private EditText box;
    private String message = null;
    private boolean messageBad = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        journal = Attendance.load(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncThen(true);
    }

    /** Обменяться с компьютером через Google Диск; новое — показать. */
    private void syncThen(boolean reload) {
        if (Attestation.scriptUrl(this).isEmpty()) return;
        new Thread(() -> {
            try {
                boolean changed = Attendance.sync(getApplicationContext());
                if (reload && changed) {
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        journal = Attendance.load(this);
                        render();
                    });
                }
            } catch (IOException ignored) {
                // без связи — отправится при следующем открытии
            }
        }).start();
    }

    private void persist() {
        journal = Attendance.commit(this, journal);
        syncThen(false);
    }

    private void add() {
        Attendance.BirthdayList r = Attendance.parseBirthdays(box.getText().toString());
        int[] n = Attendance.addBirthdays(journal, r.items);
        persist();
        StringBuilder sb = new StringBuilder("Добавлено: " + n[0] + ".");
        if (n[1] > 0) sb.append(" Уже были в списке: ").append(n[1]).append('.');
        if (!r.bad.isEmpty()) {
            sb.append(" Не распознаны строки (нужны ФИО и дата ДД.ММ.ГГГГ):\n").append(String.join("\n", r.bad));
        }
        message = sb.toString();
        messageBad = !r.bad.isEmpty();
        boolean clear = r.bad.isEmpty();
        String keep = box.getText().toString();
        render();
        if (!clear) box.setText(keep);
        Birthdays.check(this);
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(c(R.color.a_bg));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Дни рождения", 24, c(R.color.a_text), 400));
        col.addView(text("В день рождения в 8 утра придёт уведомление. Список хранится на телефоне и в вашем файле "
                + "синхронизации на Google Диске — так он попадает на компьютер.", 13, c(R.color.a_muted), 400));

        List<Attendance.Birthday> list = Attendance.upcomingBirthdays(journal, LocalDate.now());
        LinearLayout card = Ui.card(this);
        if (list.isEmpty()) card.addView(text("Список пока пуст.", 14, c(R.color.a_muted), 400));
        for (Attendance.Birthday b : list) card.addView(row(b));
        col.addView(card);

        LinearLayout addCard = Ui.card(this);
        addCard.addView(text("Добавить список", 16, c(R.color.a_text), 500));
        addCard.addView(text("Вставьте строки из таблицы: в каждой ФИО и дата рождения (ДД.ММ.ГГГГ).", 13, c(R.color.a_muted), 400));
        box = Ui.box(this, "ИВАНОВ ИВАН ИВАНОВИЧ 29.04.2013", 4);
        addCard.addView(box, margins(dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));
        Button add = Ui.button(this, "Добавить", true);
        add.setOnClickListener(v -> add());
        addCard.addView(add, margins(dp(8), dp(44)));
        if (message != null) {
            addCard.addView(text(message, 13, c(messageBad ? R.color.a_orange : R.color.a_green), 500), margins(dp(8), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        col.addView(addCard);
        setContentView(scroll);
    }

    private LinearLayout row(Attendance.Birthday b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(8), 0, dp(8));
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(b.name, 15, c(R.color.a_text), 500));
        String dm = Attendance.dm(b.next.toString());
        String when = b.daysLeft == 0 ? "сегодня" : b.daysLeft == 1 ? "завтра, " + dm : dm + ", через " + b.daysLeft + " дн.";
        left.addView(text(when + " · исполнится " + b.age, 13, c(b.daysLeft == 0 ? R.color.a_primary : R.color.a_muted), 400));
        r.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button del = Ui.button(this, "✕", false);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Убрать " + b.name + " из списка?")
                .setPositiveButton("Убрать", (d, w) -> {
                    Attendance.removeBirthday(journal, b.id);
                    persist();
                    render();
                })
                .setNegativeButton("Отмена", null)
                .show());
        r.addView(del, new LinearLayout.LayoutParams(dp(44), dp(38)));
        return r;
    }

    private LinearLayout.LayoutParams margins(int top, int height) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
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
