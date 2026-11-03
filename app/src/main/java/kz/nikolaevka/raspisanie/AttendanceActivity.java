package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Журнал посещаемости на телефоне: один день — все классы, нажатие на ученика — отметить отсутствие.
 * Списки загружаются из Word-шаблона, месяц выгружается в Word. Всё хранится только на телефоне.
 */
public class AttendanceActivity extends Activity {

    private static final int IMPORT = 1, EXPORT = 2;
    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE, d MMMM", new Locale("ru"));

    private JSONObject journal;
    private String date;
    private String exportMonth;
    private boolean editing = false;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        journal = Attendance.load(this);
        date = startDate(today());
        render();
    }

    private static String today() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    private static final String FIRST_DAY = Attendance.QUARTERS[0][0];
    private static final String LAST_DAY = Attendance.QUARTERS[Attendance.QUARTERS.length - 1][1];

    /** Сегодня, а если сегодня не учебный день — ближайший прошедший (или первый учебный). */
    private String startDate(String t) {
        if (t.compareTo(FIRST_DAY) < 0) return step(FIRST_DAY, 0);
        if (t.compareTo(LAST_DAY) > 0) t = LAST_DAY;
        if (Attendance.isSchoolDay(journal, t)) return t;
        String prev = step(t, -1);
        return prev != null ? prev : step(t, 1);
    }

    /** Следующий (+1) или предыдущий (−1) учебный день; 0 — этот или следующий. */
    private String step(String from, int dir) {
        LocalDate d = LocalDate.parse(from);
        if (dir != 0) d = d.plusDays(dir);
        for (int i = 0; i < 200; i++) {
            String s = d.toString();
            if (s.compareTo(FIRST_DAY) < 0 || s.compareTo(LAST_DAY) > 0) return null;
            if (Attendance.isSchoolDay(journal, s)) return s;
            d = d.plusDays(dir == 0 ? 1 : dir);
        }
        return null;
    }

    /** Сохранить и через пару секунд отправить на компьютер (несколько отметок подряд уйдут вместе). */
    private void persist() {
        journal = Attendance.commit(this, journal);
        handler.removeCallbacks(syncTick);
        handler.postDelayed(syncTick, 2000);
    }

    // ---------- Синхронизация с компьютером ----------

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean syncing = false;
    private String syncText = null;
    private boolean syncError = false;

    private final Runnable syncTick = new Runnable() {
        @Override
        public void run() {
            startSync();
            handler.removeCallbacks(this);
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(syncTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(syncTick);
        // отметки, поставленные перед выходом, — отправить сразу
        if (!Attestation.scriptUrl(this).isEmpty()) new Thread(this::syncQuietly).start();
    }

    private void syncQuietly() {
        try {
            Attendance.sync(getApplicationContext());
        } catch (IOException ignored) {
            // отправится при следующем открытии
        }
    }

    private void startSync() {
        if (syncing || Attestation.scriptUrl(this).isEmpty()) return;
        syncing = true;
        new Thread(() -> {
            String error = null;
            boolean changed = false;
            try {
                changed = Attendance.sync(getApplicationContext());
            } catch (IOException e) {
                error = e.getMessage();
            }
            String err = error;
            boolean ch = changed;
            runOnUiThread(() -> {
                syncing = false;
                if (isFinishing()) return;
                syncError = err != null;
                syncText = err != null ? err
                        : "Синхронизировано с компьютером · " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
                // новое с компьютера: берём сохранённое (в нём уже объединены и наши отметки)
                if (ch) journal = Attendance.load(this);
                // пока вставляют ответ из WhatsApp, экран не перерисовываем — иначе пропадёт клавиатура
                if (replyText == null) render();
            });
        }).start();
    }

    private View syncLine() {
        boolean connected = !Attestation.scriptUrl(this).isEmpty();
        String s = !connected ? "Журнал только на телефоне: подключите Google Диск на экране «Аттестация», "
                + "чтобы отметки попадали на компьютер."
                : syncText != null ? syncText : "Синхронизация с компьютером…";
        TextView t = text(s, 12, c(!connected ? R.color.a_muted : syncError ? R.color.a_red : R.color.a_green), 500);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    // ---------- Экран ----------

    private void render() {
        int y = scroll == null ? 0 : scroll.getScrollY();
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(c(R.color.a_bg));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Посещаемость", 24, c(R.color.a_text), 400));
        col.addView(text(editing ? "Нажмите на ученика, чтобы исправить ФИО или удалить."
                : "Нажмите на ученика, чтобы отметить отсутствие. Без отметки — был на уроках.", 13, c(R.color.a_muted), 400));
        col.addView(syncLine());

        List<JSONObject> classes = Attendance.sortedClasses(journal);
        if (classes.isEmpty() && !editing) {
            col.addView(emptyCard());
        } else {
            List<Attendance.Alert> alerts = Attendance.alerts(journal, today());
            if (!alerts.isEmpty() && !editing) col.addView(alertsCard(alerts));
            if (!editing) col.addView(dateCard());
            if (!editing && replyText != null) col.addView(replyCard());
            boolean school = Attendance.isSchoolDay(journal, date);
            for (JSONObject cls : classes) col.addView(classCard(cls, school || editing));
            if (editing) {
                Button add = Ui.button(this, "Добавить класс", false);
                add.setOnClickListener(v -> askText("Новый класс", "Например: 5 класс", "", name -> {
                    Attendance.addClass(journal, name);
                    persist();
                    render();
                }));
                col.addView(add, margins(dp(16), dp(44)));
            }
        }

        Button export = Ui.button(this, "Выгрузить месяц в Word", true);
        export.setEnabled(!classes.isEmpty());
        export.setOnClickListener(v -> chooseExportMonth());
        col.addView(export, margins(dp(20), dp(46)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button imp = Ui.button(this, "Списки из Word", false);
        imp.setOnClickListener(v -> pickImport());
        row.addView(imp, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button edit = Ui.button(this, editing ? "Готово" : "Изменить списки", editing);
        edit.setOnClickListener(v -> {
            editing = !editing;
            render();
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(44), 1f);
        ep.leftMargin = dp(8);
        row.addView(edit, ep);
        col.addView(row, margins(dp(8)));

        col.addView(text("Журнал хранится на телефоне и в вашем Google Диске («Мой диск / Моё расписание / "
                + "посещаемость.json») — через него отметки попадают на компьютер и обратно. ФИО учеников не "
                + "отправляются ни в нейросеть, ни в BilimClass.", 12, c(R.color.a_muted), 400), margins(dp(16)));
        setContentView(scroll);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private View emptyCard() {
        LinearLayout card = Ui.card(this);
        card.addView(text("Журнал пока пуст", 18, c(R.color.a_text), 500));
        card.addView(text("Загрузите списки учащихся из документа Word (таблицы «№ | ФИО» с заголовком "
                + "«Список учащихся N класса») или добавьте классы вручную.", 14, c(R.color.a_muted), 400), margins(dp(6)));
        Button imp = Ui.button(this, "Загрузить списки из Word", true);
        imp.setOnClickListener(v -> pickImport());
        card.addView(imp, margins(dp(12), dp(44)));
        Button manual = Ui.button(this, "Добавить вручную", false);
        manual.setOnClickListener(v -> {
            editing = true;
            render();
        });
        card.addView(manual, margins(dp(8), dp(44)));
        return card;
    }

    private View alertsCard(List<Attendance.Alert> alerts) {
        LinearLayout card = Ui.card(this);
        card.setBackground(Ui.round(this, c(R.color.a_red_soft), 16));
        card.addView(text("Обратите внимание", 16, c(R.color.a_red), 600));
        for (Attendance.Alert a : alerts) {
            card.addView(text("• " + Attendance.alertText(a), 14, c(R.color.a_text), 400), margins(dp(4)));
        }
        return card;
    }

    private View dateCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        String prev = step(date, -1), next = step(date, 1);
        Button p = Ui.button(this, "‹", false);
        p.setEnabled(prev != null);
        p.setOnClickListener(v -> {
            date = prev;
            render();
        });
        row.addView(p, new LinearLayout.LayoutParams(dp(48), dp(44)));
        String label = LocalDate.parse(date).format(DAY);
        TextView d = text(label.substring(0, 1).toUpperCase(new Locale("ru")) + label.substring(1)
                + (date.equals(today()) ? " · сегодня" : ""), 16, c(R.color.a_text), 500);
        d.setGravity(Gravity.CENTER);
        d.setOnClickListener(v -> pickDate());
        row.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button n = Ui.button(this, "›", false);
        n.setEnabled(next != null);
        n.setOnClickListener(v -> {
            date = next;
            render();
        });
        row.addView(n, new LinearLayout.LayoutParams(dp(48), dp(44)));
        card.addView(row);

        boolean school = Attendance.isSchoolDay(journal, date);
        int absent = 0;
        for (JSONObject cls : Attendance.sortedClasses(journal)) {
            for (JSONObject s : Attendance.sortedStudents(cls)) if (Attendance.getMark(journal, date, s.optString("id")) != null) absent++;
        }
        TextView info = text(!school ? "Не учебный день — отметки не ставятся."
                : absent == 0 ? "Все на месте" : "Отсутствуют: " + absent, 13,
                c(!school ? R.color.a_muted : absent == 0 ? R.color.a_green : R.color.a_orange), 500);
        info.setGravity(Gravity.CENTER);
        card.addView(info, margins(dp(8)));
        Button toggle = Ui.button(this, school ? "Сделать выходным" : "Сделать учебным днём", false);
        toggle.setTextSize(13);
        toggle.setOnClickListener(v -> {
            Attendance.setSchoolDay(journal, date, !school);
            persist();
            render();
        });
        card.addView(toggle, margins(dp(8), dp(38)));
        if (replyText == null) {
            Button wa = Ui.button(this, "Ответ из WhatsApp", true);
            wa.setOnClickListener(v -> openReply());
            card.addView(wa, margins(dp(8), dp(42)));
        }
        if (school && Bilim.isConnected(this)) {
            Button bc = Ui.button(this, "Перенести в BilimClass", false);
            bc.setOnClickListener(v -> transferToBilim());
            card.addView(bc, margins(dp(8), dp(42)));
        }
        return card;
    }

    // ---------- Перенос в BilimClass ----------
    // На выбранный день: по каждому уроку учителя — отсутствовавшие ученики этого класса из нашего журнала.

    private void transferToBilim() {
        final LocalDate d = LocalDate.parse(date);
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Сверяю с журналом BilimClass…").setCancelable(false).show();
        new Thread(() -> {
            List<Absence.LessonPlan> plans = null;
            String error = null;
            try {
                plans = Absence.planDay(this, d);
            } catch (IOException e) {
                error = HomeworkActivity.errorText(e);
            }
            List<Absence.LessonPlan> got = plans;
            String err = error;
            runOnUiThread(() -> {
                wait.dismiss();
                if (isFinishing()) return;
                if (err != null) {
                    new AlertDialog.Builder(this).setMessage(err).setPositiveButton("OK", null).show();
                    return;
                }
                if (got.isEmpty()) {
                    new AlertDialog.Builder(this).setMessage("В этот день у вас нет уроков в расписании BilimClass.")
                            .setPositiveButton("OK", null).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                int total = 0;
                for (Absence.LessonPlan p : got) {
                    sb.append(p.range).append(" · ").append(p.cls).append(": ");
                    if (p.error != null) sb.append(p.error);
                    else if (!p.blocked.isEmpty()) sb.append("пропущен — ").append(p.blocked);
                    else if (p.send.isEmpty()) sb.append("нечего отмечать");
                    else {
                        for (int i = 0; i < p.send.size(); i++) {
                            if (i > 0) sb.append("; ");
                            sb.append(p.send.get(i).name).append(" — ").append(Absence.label(p.send.get(i).code));
                        }
                    }
                    if (!p.unmatched.isEmpty()) sb.append("\n   нет в журнале BilimClass: ").append(String.join(", ", p.unmatched));
                    sb.append("\n\n");
                    total += p.send.size();
                }
                AlertDialog.Builder b = new AlertDialog.Builder(this)
                        .setTitle("В BilimClass за " + Attendance.dm(date))
                        .setMessage(sb.toString().trim())
                        .setNegativeButton(total > 0 ? "Отмена" : "Закрыть", null);
                if (total > 0) b.setPositiveButton("Перенести (" + total + ")", (x, w) -> sendTransfer(got, d));
                b.show();
            });
        }).start();
    }

    private void sendTransfer(List<Absence.LessonPlan> plans, LocalDate d) {
        AlertDialog wait = new AlertDialog.Builder(this).setMessage("Отправляю в BilimClass…").setCancelable(false).show();
        new Thread(() -> {
            List<String> errors = new ArrayList<>();
            int sent = 0;
            for (Absence.LessonPlan p : plans) {
                if (p.send.isEmpty() || p.ctx == null) continue;
                try {
                    Absence.send(this, d, p);
                    sent += p.send.size();
                } catch (IOException e) {
                    errors.add(p.cls + " (" + p.range + "): " + HomeworkActivity.errorText(e));
                }
            }
            String text = errors.isEmpty() ? "Перенесено в BilimClass ✓ (" + sent + ")"
                    : "Отправлено: " + sent + ". Не получилось:\n" + String.join("\n", errors);
            runOnUiThread(() -> {
                wait.dismiss();
                if (!isFinishing()) new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
            });
        }).start();
    }

    private View classCard(JSONObject cls, boolean marksAllowed) {
        LinearLayout card = Ui.card(this);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(cls.optString("name"), 18, c(R.color.a_text), 500),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (editing) {
            Button opts = Ui.button(this, "Класс…", false);
            opts.setOnClickListener(v -> classMenu(cls));
            head.addView(opts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)));
        }
        card.addView(head);

        String month = date.substring(0, 7);
        for (JSONObject s : Attendance.sortedStudents(cls)) {
            String sid = s.optString("id");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.VERTICAL);
            left.addView(text(s.optString("name"), 15, c(R.color.a_text), 500));
            Attendance.Stats st = Attendance.monthStats(journal, month, sid);
            if (st.total > 0 && !editing) {
                left.addView(text("за " + Attendance.monthTitle(month).split(" ")[0].toLowerCase(new Locale("ru"))
                        + ": пропусков " + st.total + (st.invalid > 0 ? ", без ув. пр. " + st.invalid : ""), 12,
                        c(st.invalid > 0 ? R.color.a_red : R.color.a_muted), 400));
            }
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!editing && marksAllowed) {
                JSONObject m = Attendance.getMark(journal, date, sid);
                row.addView(badge(m == null ? null : m.optString("r")));
            }
            if (editing) row.setOnClickListener(v -> studentMenu(cls, s));
            else if (marksAllowed) row.setOnClickListener(v -> markMenu(s));
            card.addView(row);
        }
        if (editing) {
            Button add = Ui.button(this, "Добавить ученика", false);
            String cid = cls.optString("id");
            add.setOnClickListener(v -> askText("Новый ученик · " + cls.optString("name"), "Фамилия Имя Отчество", "", name -> {
                JSONObject now = findClass(cid);
                if (now != null) Attendance.addStudent(now, name);
                persist();
                render();
            }));
            card.addView(add, margins(dp(8), dp(40)));
        }
        return card;
    }

    /** Отметка: «✓» — был, иначе код причины в цвете. Заглавными: строчная «б» похожа на «6». */
    private TextView badge(String reason) {
        Attendance.Reason r = Attendance.reasonById(reason);
        int[] colors = colors(reason);
        TextView t = text(r == null ? "✓" : r.code.toUpperCase(new Locale("ru")), 13, c(colors[0]), 600);
        t.setGravity(Gravity.CENTER);
        t.setMinWidth(dp(44));
        t.setPadding(dp(8), dp(4), dp(8), dp(4));
        t.setBackground(Ui.round(this, c(colors[1]), 8));
        return t;
    }

    private static int[] colors(String reason) {
        if (reason == null) return new int[]{R.color.a_green, R.color.a_green_soft};
        switch (reason) {
            case "sick": return new int[]{R.color.a_orange, R.color.a_orange_soft};
            case "free": return new int[]{R.color.a_blue, R.color.a_blue_soft};
            case "family": return new int[]{R.color.a_primary, R.color.a_primary_soft};
            case "absent": return new int[]{R.color.a_red, R.color.a_red_soft};
            default: return new int[]{R.color.a_muted, R.color.a_bg};
        }
    }

    // ---------- Ответ из WhatsApp ----------
    // Учитель копирует ответы классных руководителей, приложение находит учеников и причины,
    // учитель проверяет галочки и нажимает «Отметить». Разбор — на телефоне, без нейросети.

    /** Строка проверки: найденный ученик (или один из подходящих, если непонятно, кто именно). */
    private static final class ReplyRow {
        String sid, name, cls, reason, note, line, group;
        boolean on;
    }

    /** null — панель закрыта. */
    private String replyText = null;
    private List<ReplyRow> replyRows = null;
    private List<String> replyPresent = new java.util.ArrayList<>(), replyUnknown = new java.util.ArrayList<>();

    private void openReply() {
        replyText = "";
        replyRows = null;
        // если только что скопировали ответ в WhatsApp — сразу подставим
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
            if (t != null && !t.toString().equals(DailyMessage.text(this))) replyText = t.toString();
        }
        if (!replyText.isEmpty()) parseReply();
        render();
    }

    private void parseReply() {
        Attendance.Reply r = Attendance.parseReply(journal, replyText);
        replyRows = new java.util.ArrayList<>();
        for (Attendance.ReplyItem it : r.items) {
            if (!it.sid.isEmpty()) {
                replyRows.add(row(it.sid, it.name, it.cls, it, null, true));
            } else {
                // непонятно, кто именно: показываем всех подходящих без галочек — учитель отметит нужных
                for (String[] o : it.options) replyRows.add(row(o[0], o[1], o[2], it, it.name, false));
            }
        }
        replyPresent = r.present;
        replyUnknown = r.unknown;
    }

    private static ReplyRow row(String sid, String name, String cls, Attendance.ReplyItem it, String group, boolean on) {
        ReplyRow x = new ReplyRow();
        x.sid = sid;
        x.name = name;
        x.cls = cls;
        x.reason = it.reason;
        x.note = it.note;
        x.line = it.line;
        x.group = group;
        x.on = on;
        return x;
    }

    private void applyReply() {
        int n = 0;
        for (ReplyRow x : replyRows) {
            if (!x.on) continue;
            Attendance.setMark(journal, date, x.sid, x.reason, "other".equals(x.reason) ? x.note : null);
            n++;
        }
        replyText = null;
        replyRows = null;
        persist();
        render();
        toast("Отмечено отсутствующих: " + n + " (" + Attendance.dm(date) + ").");
    }

    private View replyCard() {
        LinearLayout card = Ui.card(this);
        card.addView(text("Ответ из WhatsApp", 18, c(R.color.a_text), 500));
        card.addView(text("Скопируйте в WhatsApp ответы классных руководителей (можно несколько сообщений сразу) "
                + "и вставьте сюда. Отметки встанут на " + Attendance.dm(date) + " — день выбирается стрелками выше.",
                13, c(R.color.a_muted), 400), margins(dp(4)));
        if (!Attendance.isSchoolDay(journal, date)) {
            card.addView(text("Выбран не учебный день — проверьте дату.", 13, c(R.color.a_orange), 500), margins(dp(4)));
        }
        EditText box = Ui.box(this, "Например:\n4 кл: Иванов, Петрова болеют\n6 класс — все на месте", 4);
        box.setText(replyText);
        box.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                replyText = s.toString();
            }
        });
        card.addView(box, margins(dp(10)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button parse = Ui.button(this, replyRows == null ? "Разобрать" : "Разобрать заново", replyRows == null);
        parse.setOnClickListener(v -> {
            parseReply();
            render();
        });
        bar.addView(parse, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button close = Ui.button(this, "Закрыть", false);
        close.setOnClickListener(v -> {
            replyText = null;
            replyRows = null;
            render();
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        cp.leftMargin = dp(8);
        bar.addView(close, cp);
        card.addView(bar, margins(dp(10)));

        if (replyRows == null) return card;
        if (replyRows.isEmpty()) card.addView(text("Отсутствующих в тексте не нашлось.", 13, c(R.color.a_muted), 400), margins(dp(10)));
        String lastGroup = null;
        int chosen = 0, missing = 0;
        for (ReplyRow x : replyRows) {
            if (x.group != null && !x.group.equals(lastGroup)) {
                card.addView(text("Непонятно, кто именно («" + x.line + "») — отметьте нужных:", 12, c(R.color.a_orange), 500),
                        margins(dp(12)));
            }
            lastGroup = x.group;
            if (x.on) {
                chosen++;
                if (x.reason.isEmpty()) missing++;
            }
            card.addView(replyRowView(x));
        }
        if (!replyPresent.isEmpty()) {
            card.addView(text("Все на месте: " + String.join(", ", replyPresent) + ".", 13, c(R.color.a_green), 400), margins(dp(8)));
        }
        if (!replyUnknown.isEmpty()) {
            card.addView(text("Нет в журнале: " + String.join(", ", replyUnknown) + " — может быть, опечатка.", 13,
                    c(R.color.a_orange), 400), margins(dp(8)));
        }
        Button go = Ui.button(this, missing > 0 ? "Выберите причину (" + missing + ")" : "Отметить: " + chosen, true);
        go.setEnabled(chosen > 0 && missing == 0);
        go.setOnClickListener(v -> applyReply());
        card.addView(go, margins(dp(12), dp(46)));
        return card;
    }

    private View replyRowView(ReplyRow x) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        android.widget.CheckBox cb = Ui.checkBox(this, x.name + " · " + x.cls, x.on);
        cb.setOnCheckedChangeListener((v, on) -> {
            x.on = on;
            render();
        });
        row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Attendance.Reason r = Attendance.reasonById(x.reason);
        TextView reason = r != null ? badge(x.reason) : text("Причина?", 13, c(R.color.a_red), 600);
        if (r == null) {
            reason.setPadding(dp(8), dp(4), dp(8), dp(4));
            reason.setBackground(Ui.round(this, c(R.color.a_red_soft), 8));
        }
        reason.setOnClickListener(v -> chooseReason(x));
        row.addView(reason);
        return row;
    }

    private void chooseReason(ReplyRow x) {
        String[] items = new String[Attendance.REASONS.length];
        for (int i = 0; i < items.length; i++) {
            items[i] = Attendance.REASONS[i].code.toUpperCase(new Locale("ru")) + " — " + Attendance.REASONS[i].name;
        }
        new AlertDialog.Builder(this)
                .setTitle(x.name)
                .setItems(items, (dlg, which) -> {
                    x.reason = Attendance.REASONS[which].id;
                    x.on = true;
                    if ("other".equals(x.reason)) {
                        askText("Другое — причина", "Например: на соревнованиях", x.note, t -> {
                            x.note = t;
                            render();
                        });
                    }
                    render();
                })
                .show();
    }

    // ---------- Диалоги ----------

    private void markMenu(JSONObject s) {
        String sid = s.optString("id");
        JSONObject cur = Attendance.getMark(journal, date, sid);
        String[] items = new String[Attendance.REASONS.length + 1];
        items[0] = "✓ Был на уроках";
        for (int i = 0; i < Attendance.REASONS.length; i++) {
            Attendance.Reason r = Attendance.REASONS[i];
            items[i + 1] = r.code.toUpperCase(new Locale("ru")) + " — " + r.name + ("other".equals(r.id) ? "…" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(s.optString("name") + " · " + Attendance.dm(date))
                .setItems(items, (dlg, which) -> {
                    if (which == 0) {
                        Attendance.setMark(journal, date, sid, "", null);
                    } else if ("other".equals(Attendance.REASONS[which - 1].id)) {
                        String note = cur != null && "other".equals(cur.optString("r")) ? cur.optString("note") : "";
                        askText("Другое — причина", "Например: на соревнованиях", note, t -> {
                            Attendance.setMark(journal, date, sid, "other", t);
                            persist();
                            render();
                        });
                        return;
                    } else {
                        Attendance.setMark(journal, date, sid, Attendance.REASONS[which - 1].id, null);
                    }
                    persist();
                    render();
                })
                .show();
    }

    private void studentMenu(JSONObject cls, JSONObject s) {
        new AlertDialog.Builder(this)
                .setTitle(s.optString("name"))
                .setItems(new String[]{"Исправить ФИО", "Удалить из журнала"}, (dlg, which) -> {
                    if (which == 0) {
                        askText("ФИО ученика", "Фамилия Имя Отчество", s.optString("name"), name -> {
                            JSONObject now = findStudent(s.optString("id"));
                            if (now != null) Attendance.rename(now, Attendance.fixName(name));
                            persist();
                            render();
                        });
                    } else {
                        confirm("Удалить " + s.optString("name") + " из журнала вместе с отметками?", () -> {
                            Attendance.removeStudent(journal, cls, s.optString("id"));
                            persist();
                            render();
                        });
                    }
                })
                .show();
    }

    private void classMenu(JSONObject cls) {
        new AlertDialog.Builder(this)
                .setTitle(cls.optString("name"))
                .setItems(new String[]{"Переименовать", "Удалить класс"}, (dlg, which) -> {
                    if (which == 0) {
                        askText("Название класса", "Например: 5 класс", cls.optString("name"), name -> {
                            JSONObject now = findClass(cls.optString("id"));
                            if (now != null) Attendance.rename(now, name);
                            persist();
                            render();
                        });
                    } else {
                        confirm("Удалить «" + cls.optString("name") + "» вместе с отметками?", () -> {
                            Attendance.removeClass(journal, cls.optString("id"));
                            persist();
                            render();
                        });
                    }
                })
                .show();
    }

    /**
     * Класс и ученик — по id в нынешнем журнале: пока открыт диалог, могли прийти отметки
     * с компьютера, и журнал на экране уже другой объект.
     */
    private JSONObject findClass(String cid) {
        for (JSONObject c : Attendance.sortedClasses(journal)) if (cid.equals(c.optString("id"))) return c;
        return null;
    }

    private JSONObject findStudent(String sid) {
        for (JSONObject c : Attendance.sortedClasses(journal)) {
            for (JSONObject s : Attendance.sortedStudents(c)) if (sid.equals(s.optString("id"))) return s;
        }
        return null;
    }

    private interface TextResult {
        void done(String text);
    }

    private void askText(String title, String hint, String value, TextResult onDone) {
        EditText e = Ui.box(this, hint, 1);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setText(value);
        e.setSelection(e.getText().length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("Сохранить", (dlg, w) -> {
                    String t = e.getText().toString().trim();
                    if (!t.isEmpty()) onDone.done(t);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("Удалить", (dlg, w) -> yes.run())
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void pickDate() {
        LocalDate d = LocalDate.parse(date);
        DatePickerDialog dlg = new DatePickerDialog(this, (v, y, m, day) -> {
            date = LocalDate.of(y, m + 1, day).toString();
            render();
        }, d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth());
        ZoneId z = ZoneId.systemDefault();
        dlg.getDatePicker().setMinDate(LocalDate.parse(FIRST_DAY).atStartOfDay(z).toInstant().toEpochMilli());
        dlg.getDatePicker().setMaxDate(LocalDate.parse(LAST_DAY).atStartOfDay(z).toInstant().toEpochMilli());
        dlg.show();
    }

    // ---------- Word: загрузка списков и выгрузка месяца ----------

    private void pickImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Attestation.DOCX);
        try {
            startActivityForResult(i, IMPORT);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseExportMonth() {
        List<String> months = Attendance.months();
        String[] titles = new String[months.size()];
        for (int i = 0; i < titles.length; i++) titles[i] = Attendance.monthTitle(months.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Какой месяц выгрузить?")
                .setItems(titles, (dlg, which) -> {
                    exportMonth = months.get(which);
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(Attestation.DOCX);
                    i.putExtra(Intent.EXTRA_TITLE, Attendance.fileName(exportMonth));
                    try {
                        startActivityForResult(i, EXPORT);
                    } catch (RuntimeException e) {
                        Toast.makeText(this, "Нет приложения для сохранения файлов", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == IMPORT) importWord(uri);
        else if (requestCode == EXPORT && exportMonth != null) exportWord(uri, exportMonth);
    }

    private void importWord(Uri uri) {
        String xml = null;
        try (InputStream in = getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    xml = new String(Attestation.readAll(zip, 30L * 1024 * 1024), StandardCharsets.UTF_8);
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            xml = null;
        }
        if (xml == null) {
            toast("Не удалось прочитать документ: нужен файл Word (.docx).");
            return;
        }
        List<Attendance.Parsed> parsed = Attendance.parseTemplate(xml);
        if (parsed.isEmpty()) {
            toast("В документе не нашлось таблиц со столбцами «№» и «ФИО».");
            return;
        }
        int[] added = Attendance.importClasses(journal, parsed);
        persist();
        date = startDate(today());
        render();
        toast(added[0] + added[1] == 0 ? "Все ученики из документа уже есть в журнале."
                : "Загружено: классов — " + added[0] + ", учеников — " + added[1] + ". Проверьте ФИО в «Изменить списки».");
    }

    private void exportWord(Uri uri, String month) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("нет файла");
            Attendance.writeDocx(journal, month, out);
        } catch (IOException | RuntimeException e) {
            toast("Не удалось сохранить документ.");
            return;
        }
        new AlertDialog.Builder(this)
                .setMessage("Журнал за " + Attendance.monthTitle(month).toLowerCase(new Locale("ru")) + " сохранён.")
                .setPositiveButton("Открыть", (dlg, w) -> {
                    Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, Attestation.DOCX)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(i);
                    } catch (RuntimeException e) {
                        toast("Нет приложения, которое открывает Word.");
                    }
                })
                .setNeutralButton("Поделиться", (dlg, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND).setType(Attestation.DOCX)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "Отправить журнал"));
                })
                .setNegativeButton("Готово", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- Оформление ----------

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
