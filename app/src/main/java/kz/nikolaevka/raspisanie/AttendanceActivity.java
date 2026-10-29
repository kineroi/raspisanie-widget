package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private void persist() {
        Attendance.save(this, journal);
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

        List<JSONObject> classes = Attendance.sortedClasses(journal);
        if (classes.isEmpty() && !editing) {
            col.addView(emptyCard());
        } else {
            List<Attendance.Alert> alerts = Attendance.alerts(journal, today());
            if (!alerts.isEmpty() && !editing) col.addView(alertsCard(alerts));
            if (!editing) col.addView(dateCard());
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

        col.addView(text("Журнал и ФИО учеников хранятся только на этом телефоне и никуда не отправляются — "
                + "ни в нейросеть, ни в BilimClass. Журнал на компьютере ведётся отдельно.", 12, c(R.color.a_muted), 400), margins(dp(16)));
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
        return card;
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
            add.setOnClickListener(v -> askText("Новый ученик · " + cls.optString("name"), "Фамилия Имя Отчество", "", name -> {
                Attendance.addStudent(cls, name);
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
                            try {
                                s.put("name", Attendance.fixName(name));
                            } catch (Exception ignored) {
                                // строка в JSON — без ошибок
                            }
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
                            try {
                                cls.put("name", name);
                            } catch (Exception ignored) {
                                // строка в JSON — без ошибок
                            }
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
