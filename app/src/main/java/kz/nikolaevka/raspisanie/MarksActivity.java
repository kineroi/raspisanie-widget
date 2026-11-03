package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Оценки за урок (ФО) и комментарии → журнал BilimClass. Комментарий учитель пишет кратко —
 * нейросеть делает его на русском и английском, вместе не длиннее 250 символов. Нейросети уходит только текст комментария.
 */
public class MarksActivity extends Activity {

    private LocalDate date;
    private Schedule.Lesson lesson;
    private List<Marks.Lesson> classes = new ArrayList<>();
    /** Правки: класс → ученик → балл и комментарий. */
    private final Map<String, Map<String, Marks.Edit>> edits = new HashMap<>();
    /** Итог отправки: ученик → "" (отправлено) или ошибка. */
    private Map<String, String> results = new HashMap<>();
    private boolean busy = false;
    private String status = null;
    private boolean statusError = false;
    private Button sendButton, polishButton;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            date = LocalDate.parse(getIntent().getStringExtra(HomeworkActivity.EXTRA_DATE));
            LocalTime start = LocalTime.parse(getIntent().getStringExtra(HomeworkActivity.EXTRA_START));
            for (Schedule.Lesson l : Bilim.source(this).day(date).lessons) if (l.start.equals(start)) lesson = l;
        } catch (RuntimeException e) {
            lesson = null;
        }
        if (lesson == null || lesson.title.startsWith("Кл. час")) {
            android.widget.Toast.makeText(this, "Урок не найден (или это классный час — оценок к нему нет).",
                    android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
        load(null);
    }

    // ---------- Загрузка и отправка ----------

    private void load(String done) {
        setBusy(true, "Загружаю журнал из BilimClass…", false);
        new Thread(() -> {
            List<Marks.Lesson> list = null;
            String error = null;
            try {
                list = Marks.load(this, date, lesson);
            } catch (IOException e) {
                error = HomeworkActivity.errorText(e);
            }
            List<Marks.Lesson> got = list;
            String err = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                busy = false;
                if (got != null) {
                    classes = got;
                    edits.clear();
                }
                status = err != null ? err : done;
                statusError = err != null;
                render();
            });
        }).start();
    }

    private Marks.Edit editOf(Marks.Lesson c, Marks.Student s) {
        Map<String, Marks.Edit> byCls = edits.get(c.cls);
        if (byCls == null) edits.put(c.cls, byCls = new HashMap<>());
        Marks.Edit e = byCls.get(s.id);
        if (e == null) {
            e = new Marks.Edit();
            e.mark = s.mark == null ? "" : String.valueOf(s.mark);
            e.comment = s.comment;
            byCls.put(s.id, e);
        }
        return e;
    }

    private List<Marks.Change> pending(Marks.Lesson c) {
        Map<String, Marks.Edit> byCls = edits.get(c.cls);
        if (byCls == null || !c.completed || !c.canManage) return new ArrayList<>();
        return Marks.changes(c.students, byCls, c.markMax);
    }

    /** Комментарии, которые учитель изменил, но ещё не прогнал через нейросеть. */
    private List<Marks.Edit> unpolished() {
        List<Marks.Edit> out = new ArrayList<>();
        for (Marks.Lesson c : classes) {
            Map<String, Marks.Edit> byCls = edits.get(c.cls);
            if (byCls == null) continue;
            for (Marks.Student s : c.students) {
                Marks.Edit e = byCls.get(s.id);
                if (e != null && !e.polished && !e.comment.trim().isEmpty() && !e.comment.trim().equals(s.comment)) out.add(e);
            }
        }
        return out;
    }

    private boolean tooLong() {
        for (Map<String, Marks.Edit> byCls : edits.values()) {
            for (Marks.Edit e : byCls.values()) if (e.comment.trim().length() > Marks.COMMENT_MAX) return true;
        }
        return false;
    }

    private void polishAll(Runnable after) {
        List<Marks.Edit> list = unpolished();
        if (list.isEmpty()) {
            if (after != null) after.run();
            return;
        }
        setBusy(true, "Нейросеть пишет комментарии: " + list.size() + "…", false);
        new Thread(() -> {
            String error = null;
            for (Marks.Edit e : list) {
                try {
                    e.comment = Marks.polish(this, e.comment);
                    e.polished = true;
                } catch (IOException ex) {
                    Ai.Provider p = Bilim.aiProvider(this);
                    error = ex instanceof Ai.Failure && p != null
                            ? Ai.explain(ex, p).replace(" Текст расписан без ИИ.", " Комментарий не изменён.")
                            : ex.getMessage();
                    break;
                }
            }
            String err = error;
            runOnUiThread(() -> {
                busy = false;
                status = err != null ? err : "Комментарии готовы — проверьте их и нажмите «Отправить».";
                statusError = err != null;
                render();
            });
        }).start();
    }

    private void send() {
        if (busy) return;
        // сначала — комментарии на двух языках, и учитель видит их до отправки
        if (Bilim.aiProvider(this) != null && !unpolished().isEmpty()) {
            polishAll(null);
            return;
        }
        if (tooLong()) {
            setBusy(false, "Комментарий длиннее " + Marks.COMMENT_MAX + " символов — сократите его.", true);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (Marks.Lesson c : classes) {
            for (Marks.Change ch : pending(c)) {
                total++;
                String name = "";
                for (Marks.Student s : c.students) if (s.id.equals(ch.id)) name = s.name;
                sb.append(c.cls).append(": ").append(name).append(" — ").append(ch.mark)
                        .append(ch.comment.isEmpty() ? "" : " + комментарий").append(ch.create ? "" : " (изменение)").append('\n');
            }
        }
        if (total == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Отправить в журнал BilimClass?")
                .setMessage(sb + "\nОценки сразу увидят ученики и родители.")
                .setPositiveButton("Отправить", (d, w) -> doSend())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void doSend() {
        setBusy(true, "Отправляю оценки…", false);
        final List<Marks.Lesson> plan = new ArrayList<>(classes);
        final Map<Marks.Lesson, List<Marks.Change>> lists = new HashMap<>();
        for (Marks.Lesson c : plan) lists.put(c, pending(c));
        new Thread(() -> {
            Map<String, String> all = new HashMap<>();
            String fatal = null;
            for (Marks.Lesson c : plan) {
                List<Marks.Change> list = lists.get(c);
                if (list == null || list.isEmpty()) continue;
                try {
                    all.putAll(Marks.save(this, date, c, list, HomeworkActivity::errorText));
                } catch (IOException e) {
                    fatal = HomeworkActivity.errorText(e);
                    break;
                }
            }
            int ok = 0, bad = 0;
            for (String v : all.values()) {
                if (v.isEmpty()) ok++;
                else bad++;
            }
            String err = fatal;
            int okN = ok, badN = bad;
            runOnUiThread(() -> {
                results = all;
                if (err != null) {
                    busy = false;
                    status = err;
                    statusError = true;
                    render();
                    return;
                }
                // перечитать журнал: видно, что оценки действительно встали
                load(badN > 0 ? "Отправлено: " + okN + ", с ошибкой: " + badN + " — см. у учеников."
                        : "Оценки отправлены в BilimClass ✓ (" + okN + ")");
            });
        }).start();
    }

    private void conduct(Marks.Lesson c) {
        new AlertDialog.Builder(this)
                .setMessage("Отметить урок " + c.cls + " проведённым в BilimClass?\n\nТак же, как кнопка «Урок проведён» в журнале на сайте.")
                .setPositiveButton("Отметить", (d, w) -> {
                    setBusy(true, "Отмечаю урок…", false);
                    new Thread(() -> {
                        String error = null;
                        try {
                            Marks.conduct(this, date, c.scheduleUuid);
                        } catch (IOException e) {
                            error = HomeworkActivity.errorText(e);
                        }
                        String err = error;
                        runOnUiThread(() -> {
                            if (err == null) {
                                load("Урок отмечен проведённым ✓");
                            } else {
                                setBusy(false, err, true);
                            }
                        });
                    }).start();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ---------- Экран ----------

    private void setBusy(boolean b, String text, boolean error) {
        busy = b;
        status = text;
        statusError = error;
        render();
    }

    private void render() {
        int y = scroll == null ? 0 : scroll.getScrollY();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(c(R.color.a_bg));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Оценки за урок", 24, c(R.color.a_text), 400));
        col.addView(text(WidgetState.DAY_NAMES[date.getDayOfWeek().getValue() - 1] + ", " + WidgetState.dateText(date)
                + " · " + WidgetState.range(lesson) + " · " + lesson.title, 14, c(R.color.a_muted), 400));
        col.addView(text("Балл ФО за урок и комментарий. Комментарий напишите кратко — нейросеть сделает его на русском "
                + "и английском, вместе не длиннее 250 символов. Нейросети уходит только текст комментария — без ФИО и баллов.",
                12, c(R.color.a_muted), 400), margins(dp(6)));

        for (Marks.Lesson cl : classes) col.addView(classCard(cl));

        if (status != null && !status.isEmpty()) {
            col.addView(text(status, 13, c(statusError ? R.color.a_red : busy ? R.color.a_muted : R.color.a_green), 500), margins(dp(12)));
        }
        int n = 0;
        for (Marks.Lesson cl : classes) n += pending(cl).size();
        boolean hasKey = Bilim.aiProvider(this) != null;
        polishButton = Ui.button(this, "✨ Комментарии на двух языках", false);
        polishButton.setEnabled(!busy && hasKey && !unpolished().isEmpty());
        polishButton.setOnClickListener(v -> polishAll(null));
        col.addView(polishButton, margins(dp(14), dp(44)));
        sendButton = Ui.button(this, n > 0 ? "Отправить в BilimClass: " + n : "Отправить в BilimClass", true);
        sendButton.setEnabled(!busy && n > 0 && !tooLong());
        sendButton.setOnClickListener(v -> send());
        col.addView(sendButton, margins(dp(8), dp(46)));
        if (!hasKey) {
            col.addView(text("Ключ нейросети не указан — комментарии уйдут как написаны. Ключ можно добавить в настройках.",
                    12, c(R.color.a_muted), 400), margins(dp(6)));
        }
        setContentView(scroll);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** Обновить только кнопки — без перерисовки экрана, чтобы не сбивать набор текста. */
    private void refreshButtons() {
        if (sendButton == null) return;
        int n = 0;
        for (Marks.Lesson cl : classes) n += pending(cl).size();
        sendButton.setText(n > 0 ? "Отправить в BilimClass: " + n : "Отправить в BilimClass");
        sendButton.setEnabled(!busy && n > 0 && !tooLong());
        polishButton.setEnabled(!busy && Bilim.aiProvider(this) != null && !unpolished().isEmpty());
    }

    private View classCard(Marks.Lesson cl) {
        LinearLayout card = Ui.card(this);
        card.addView(text(cl.cls, 18, c(R.color.a_text), 500));
        if (cl.error != null) {
            card.addView(text(cl.error, 13, c(R.color.a_red), 400));
            return card;
        }
        if (!cl.canManage) {
            card.addView(text("Журнал не разрешает вам ставить оценки за этот урок.", 13, c(R.color.a_red), 400));
        } else if (cl.completed) {
            card.addView(text("Урок проведён ✓ · баллы от 0 до " + cl.markMax, 13, c(R.color.a_green), 500));
        } else if (!cl.canBeCompleted) {
            card.addView(text("Урок ещё не прошёл — оценки можно будет поставить после урока.", 13, c(R.color.a_orange), 500));
        } else {
            card.addView(text("Урок не отмечен проведённым — пока оценки поставить нельзя.", 13, c(R.color.a_orange), 500));
            Button b = Ui.button(this, "Отметить урок проведённым", false);
            b.setEnabled(!busy);
            b.setOnClickListener(v -> conduct(cl));
            card.addView(b, margins(dp(8), dp(40)));
        }
        boolean editable = cl.completed && cl.canManage && !busy;
        if (cl.students.isEmpty()) card.addView(text("В журнале нет учеников.", 13, c(R.color.a_muted), 400));
        for (Marks.Student s : cl.students) card.addView(studentRow(cl, s, editable));
        return card;
    }

    private View studentRow(Marks.Lesson cl, Marks.Student s, boolean editable) {
        Marks.Edit e = editOf(cl, s);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(12), 0, dp(4));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(s.name, 15, c(R.color.a_text), 500));
        if (s.mark != null || !s.comment.isEmpty()) {
            left.addView(text("в журнале: " + (s.mark == null ? "—" : s.mark) + (s.comment.isEmpty() ? "" : " · есть комментарий"),
                    12, c(R.color.a_muted), 400));
        }
        top.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button mark = Ui.button(this, e.mark.isEmpty() ? "Балл ▾" : e.mark + " из " + cl.markMax, !e.mark.isEmpty());
        mark.setEnabled(editable);
        mark.setOnClickListener(v -> {
            String[] items = new String[cl.markMax + 2];
            items[0] = "— без оценки";
            for (int i = 0; i <= cl.markMax; i++) items[i + 1] = String.valueOf(cl.markMax - i);
            new AlertDialog.Builder(this)
                    .setTitle(s.name)
                    .setItems(items, (d, which) -> {
                        e.mark = which == 0 ? "" : items[which];
                        render();
                    })
                    .show();
        });
        top.addView(mark, new LinearLayout.LayoutParams(dp(104), dp(40)));
        box.addView(top);

        EditText comment = Ui.box(this, "Комментарий кратко, например: «молодец, активно работал, но не сдал тетрадь»", 2);
        comment.setText(e.comment);
        comment.setEnabled(editable);
        TextView count = text("", 11, c(R.color.a_muted), 400);
        count.setGravity(Gravity.END);
        Runnable updateCount = () -> {
            int len = comment.getText().toString().trim().length();
            boolean raw = !e.polished && len > 0 && !comment.getText().toString().trim().equals(s.comment);
            count.setText(len + "/" + Marks.COMMENT_MAX + (raw ? " · ещё не переведён" : ""));
            count.setTextColor(c(len > Marks.COMMENT_MAX ? R.color.a_red : R.color.a_muted));
        };
        comment.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable x) {
                e.comment = x.toString();
                e.polished = false;
                updateCount.run();
                refreshButtons();
            }
        });
        updateCount.run();
        box.addView(comment, margins(dp(8)));
        box.addView(count);

        String res = results.get(s.id);
        if (res != null) {
            box.addView(text(res.isEmpty() ? "Отправлено ✓" : res, 12, c(res.isEmpty() ? R.color.a_green : R.color.a_red), 500));
        }
        return box;
    }

    private LinearLayout.LayoutParams margins(int top) {
        return margins(top, ViewGroup.LayoutParams.WRAP_CONTENT);
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
