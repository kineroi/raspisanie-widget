package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Окно «Домашнее задание» для одного урока:
 * краткая заметка → «Расписать» (нейросеть или правила) → правка → файлы → отправка в BilimClass.
 */
public class HomeworkActivity extends Activity {

    static final String EXTRA_DATE = "date";
    static final String EXTRA_START = "start";

    private static final int REQ_PICK = 1;
    private static final int REQ_CAMERA = 2;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private static final int PURPLE_DARK = 0xFF4A148C;
    private static final int PURPLE = 0xFF7B1FA2;
    private static final int GRAY = 0xFF555555;
    private static final int RED = 0xFFC62828;

    private LocalDate date;
    private Schedule.Lesson lesson;
    private final List<Schedule.Detail> targets = new ArrayList<>();
    private final List<CheckBox> classBoxes = new ArrayList<>();
    private final List<Uri> newFiles = new ArrayList<>();
    private Uri cameraUri;
    private boolean busy;

    private EditText noteField;
    private EditText resultField;
    private TextView statusView;
    private LinearLayout newFilesBox;
    private LinearLayout existingFilesBox;
    private Button expandButton;
    private Button sendButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        getWindow().setLayout((int) (dm.widthPixels * 0.95), (int) (dm.heightPixels * 0.9));

        if (savedInstanceState != null) {
            String cam = savedInstanceState.getString("camera");
            if (cam != null) cameraUri = Uri.parse(cam);
            ArrayList<String> files = savedInstanceState.getStringArrayList("files");
            if (files != null) for (String f : files) newFiles.add(Uri.parse(f));
        }

        try {
            date = LocalDate.parse(getIntent().getStringExtra(EXTRA_DATE));
            LocalTime start = LocalTime.parse(getIntent().getStringExtra(EXTRA_START));
            for (Schedule.Lesson l : Bilim.source(this).day(date).lessons) {
                if (l.start.equals(start)) lesson = l;
            }
        } catch (RuntimeException e) {
            lesson = null;
        }
        if (lesson == null) {
            Toast.makeText(this, "Урок не найден. Обновите расписание.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        for (Schedule.Detail d : lesson.details) {
            if (!d.scheduleUuid.isEmpty()) targets.add(d);
        }
        buildUi(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraUri != null) out.putString("camera", cameraUri.toString());
        ArrayList<String> files = new ArrayList<>();
        for (Uri u : newFiles) files.add(u.toString());
        out.putStringArrayList("files", files);
    }

    private void buildUi(Bundle saved) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(16), dp(18), dp(16));
        scroll.addView(col);

        col.addView(text("Домашнее задание", 20, PURPLE_DARK, true));
        String head = WidgetState.DAY_NAMES[date.getDayOfWeek().getValue() - 1] + ", "
                + WidgetState.dateText(date) + " · " + WidgetState.range(lesson) + "\n" + lesson.title;
        TextView h = text(head, 14, GRAY, false);
        h.setPadding(0, dp(4), 0, dp(4));
        col.addView(h);
        for (Schedule.Detail d : lesson.details) {
            if (d.theme.isEmpty()) continue;
            col.addView(text((lesson.details.size() > 1 ? d.cls + ": " : "Тема: ") + d.theme, 13, GRAY, false));
        }

        boolean canSend = Bilim.isConnected(this) && !targets.isEmpty();
        if (!canSend) {
            TextView warn = text(Bilim.isConnected(this)
                    ? "Этого урока нет в BilimClass — отправить не получится."
                    : "BilimClass не подключён — текст можно расписать, но отправить не получится. "
                    + "Подключите BilimClass в приложении.", 13, RED, false);
            warn.setPadding(0, dp(8), 0, 0);
            col.addView(warn);
        }

        // Для сдвоенных уроков — каким классам отправить
        if (targets.size() > 1) {
            col.addView(label("Кому задать"));
            for (Schedule.Detail d : targets) {
                CheckBox cb = new CheckBox(this);
                cb.setText(d.cls);
                cb.setChecked(true);
                cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                classBoxes.add(cb);
                col.addView(cb);
            }
        }

        col.addView(label("Кратко, что задать"));
        noteField = edit("Например: стр 45 упр 3 письм, сл выуч", 2);
        col.addView(noteField);

        expandButton = button("✨ Расписать на русском и английском", PURPLE, Color.WHITE);
        expandButton.setOnClickListener(v -> expand());
        col.addView(expandButton, fullWidth(dp(46), dp(8)));
        Ai.Provider ai = Bilim.aiProvider(this);
        TextView aiNote = ai == null
                ? text("Ключ нейросети не указан — текст распишется по простым правилам. "
                + "Ключ можно добавить на главном экране приложения.", 12, GRAY, false)
                : text("Расписывает: " + Ai.label(ai, Bilim.aiModel(this)), 12, GRAY, false);
        aiNote.setPadding(0, dp(4), 0, 0);
        col.addView(aiNote);

        col.addView(label("Текст для BilimClass (можно исправить)"));
        resultField = edit("Здесь появится готовый текст", 5);
        if (saved == null) resultField.setText(currentHomework());
        col.addView(resultField);

        // Файлы
        col.addView(label("Файлы"));
        existingFilesBox = new LinearLayout(this);
        existingFilesBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(existingFilesBox);
        renderExistingFiles();
        newFilesBox = new LinearLayout(this);
        newFilesBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(newFilesBox);
        renderNewFiles();

        LinearLayout fileButtons = new LinearLayout(this);
        fileButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button pick = button("📎 Фото или документ", Color.WHITE, PURPLE_DARK);
        pick.setOnClickListener(v -> pickFiles());
        fileButtons.addView(pick, new LinearLayout.LayoutParams(0, dp(44), 1f));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button cam = button("📷 Сфотографировать", Color.WHITE, PURPLE_DARK);
            cam.setOnClickListener(v -> takePhoto());
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            cp.leftMargin = dp(8);
            fileButtons.addView(cam, cp);
        }
        col.addView(fileButtons, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, dp(6)));

        statusView = text("", 13, GRAY, false);
        statusView.setPadding(0, dp(10), 0, 0);
        col.addView(statusView);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = button("Закрыть", Color.WHITE, PURPLE_DARK);
        cancel.setOnClickListener(v -> finish());
        actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1f));
        sendButton = button("Отправить в BilimClass", PURPLE, Color.WHITE);
        sendButton.setEnabled(canSend);
        if (!canSend) sendButton.setAlpha(0.4f);
        sendButton.setOnClickListener(v -> confirmSend());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(48), 1.6f);
        sp.leftMargin = dp(8);
        actions.addView(sendButton, sp);
        col.addView(actions, fullWidth(ViewGroup.LayoutParams.WRAP_CONTENT, dp(12)));

        setContentView(scroll);
    }

    /** Текущая домашка урока (если у всех классов одинаковая — она, иначе первая непустая). */
    private String currentHomework() {
        for (Schedule.Detail d : lesson.details) {
            if (!d.homework.isEmpty()) return d.homework;
        }
        return "";
    }

    // ---------- Расписать ----------

    private void expand() {
        final String note = noteField.getText().toString().trim();
        if (note.isEmpty()) {
            noteField.setError("Напишите кратко, что задать");
            return;
        }
        final String key = Bilim.aiKey(this);
        final Ai.Provider ai = Bilim.aiProvider(this);
        final String model = Bilim.aiModel(this);
        final String classes = lesson.title;
        final String theme = lesson.details.isEmpty() ? "" : lesson.details.get(0).theme;
        if (ai == null) {
            resultField.setText(HomeworkRules.expand(note));
            status("Расписано по правилам (без ИИ). Проверьте текст.", GRAY);
            return;
        }
        setBusy(true, ai.name + " расписывает задание…");
        new Thread(() -> {
            String text;
            String msg;
            int color = GRAY;
            try {
                text = Ai.expand(ai, key, model, note, classes, theme);
                msg = "Готово (" + ai.name + "). Проверьте текст перед отправкой.";
            } catch (IOException e) {
                text = HomeworkRules.expand(note);
                msg = Ai.explain(e, ai);
                color = RED;
            }
            final String t = text;
            final String m = msg;
            final int c = color;
            runOnUiThread(() -> {
                setBusy(false, null);
                resultField.setText(t);
                status(m, c);
            });
        }).start();
    }

    // ---------- Файлы ----------

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    private void takePhoto() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "ДЗ_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Расписание");
            cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (cameraUri == null) throw new IOException("no uri");
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            startActivityForResult(i, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть камеру", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                    addFile(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                addFile(data.getData());
            }
            renderNewFiles();
        } else if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && cameraUri != null) {
                newFiles.add(cameraUri);
            } else if (cameraUri != null) {
                try {
                    getContentResolver().delete(cameraUri, null, null);
                } catch (Exception ignored) {
                    // фото не было сделано — ничего страшного
                }
            }
            cameraUri = null;
            renderNewFiles();
        }
    }

    private void addFile(Uri u) {
        if (u == null || newFiles.contains(u)) return;
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // не все источники это поддерживают; файл всё равно читаем сразу при отправке
        }
        newFiles.add(u);
    }

    private void renderNewFiles() {
        if (newFilesBox == null) return;
        newFilesBox.removeAllViews();
        for (final Uri u : new ArrayList<>(newFiles)) {
            LinearLayout row = fileRow("➕ " + displayName(u), "✕", v -> {
                newFiles.remove(u);
                renderNewFiles();
            });
            newFilesBox.addView(row);
        }
    }

    private void renderExistingFiles() {
        existingFilesBox.removeAllViews();
        for (final Schedule.Detail d : targets) {
            for (final Schedule.FileRef f : d.files) {
                String name = "📄 " + (targets.size() > 1 ? d.cls + ": " : "") + f.name + " (уже в BilimClass)";
                existingFilesBox.addView(fileRow(name, "Удалить", v -> confirmDeleteFile(d, f)));
            }
        }
    }

    private LinearLayout fileRow(String name, String action, View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(name, 13, 0xFF333333, false);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button b = button(action, Color.WHITE, RED);
        b.setOnClickListener(onAction);
        row.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        row.setPadding(0, dp(3), 0, dp(3));
        return row;
    }

    private void confirmDeleteFile(final Schedule.Detail d, final Schedule.FileRef f) {
        new AlertDialog.Builder(this)
                .setMessage("Удалить файл «" + f.name + "» из домашки " + d.cls + " в BilimClass?")
                .setPositiveButton("Удалить", (dlg, w) -> deleteFile(f))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void deleteFile(final Schedule.FileRef f) {
        setBusy(true, "Удаляю файл…");
        final int year = BilimApi.eduYear(date);
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                Bilim.withToken(this, (token, school) -> BilimApi.deleteFile(token, school, year, f.id));
                Bilim.sync(this);
                msg = "Файл удалён.";
                ok = true;
            } catch (IOException e) {
                msg = errorText(e);
            }
            final String m = msg;
            final boolean success = ok;
            runOnUiThread(() -> {
                setBusy(false, null);
                status(m, success ? GRAY : RED);
                if (success) {
                    // убрать файл из списка
                    for (Schedule.Detail d : targets) d.files.remove(f);
                    renderExistingFiles();
                }
            });
        }).start();
    }

    // ---------- Отправка ----------

    private List<Schedule.Detail> selectedTargets() {
        if (targets.size() <= 1) return new ArrayList<>(targets);
        List<Schedule.Detail> sel = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            if (classBoxes.get(i).isChecked()) sel.add(targets.get(i));
        }
        return sel;
    }

    private void confirmSend() {
        final String body = resultField.getText().toString().trim();
        if (body.isEmpty()) {
            resultField.setError("Текст домашки пустой");
            return;
        }
        final List<Schedule.Detail> sel = selectedTargets();
        if (sel.isEmpty()) {
            status("Отметьте хотя бы один класс.", RED);
            return;
        }
        StringBuilder who = new StringBuilder();
        for (Schedule.Detail d : sel) {
            if (who.length() > 0) who.append(", ");
            who.append(d.cls);
        }
        String msg = "Отправить домашку в BilimClass для " + who + "?\n\nЕё сразу увидят ученики и родители."
                + (newFiles.isEmpty() ? "" : "\nФайлов: " + newFiles.size());
        new AlertDialog.Builder(this)
                .setTitle("Отправить?")
                .setMessage(msg)
                .setPositiveButton("Отправить", (dlg, w) -> send(body, sel))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void send(final String body, final List<Schedule.Detail> sel) {
        setBusy(true, "Отправляю…");
        final List<Uri> files = new ArrayList<>(newFiles);
        final int year = BilimApi.eduYear(date);
        final Context app = getApplicationContext();
        new Thread(() -> {
            String error = null;
            List<String> done = new ArrayList<>();
            try {
                final List<BilimApi.Upload> uploads = readFiles(files);
                for (final Schedule.Detail d : sel) {
                    Bilim.withToken(app, (token, school) ->
                            BilimApi.saveHomework(token, school, year, d.scheduleUuid, body, uploads));
                    done.add(d.cls);
                }
            } catch (IOException e) {
                error = errorText(e);
            }
            Bilim.sync(app);
            final String err = error;
            runOnUiThread(() -> {
                setBusy(false, null);
                if (err == null) {
                    Toast.makeText(app, "Домашка отправлена в BilimClass", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    String m = err;
                    if (!done.isEmpty()) m = "Отправлено: " + String.join(", ", done) + ". Остальным — ошибка: " + err;
                    status(m, RED);
                }
            });
        }).start();
    }

    private List<BilimApi.Upload> readFiles(List<Uri> uris) throws IOException {
        List<BilimApi.Upload> out = new ArrayList<>();
        for (Uri u : uris) {
            String name = displayName(u);
            String mime = getContentResolver().getType(u);
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new IOException("Не удалось открыть файл «" + name + "»");
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > MAX_FILE_BYTES) throw new IOException("Файл «" + name + "» больше 20 МБ");
                    buf.write(b, 0, n);
                }
                out.add(new BilimApi.Upload(name, mime, buf.toByteArray()));
            }
        }
        return out;
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {
            // имя не важно — подставим своё
        }
        String last = u.getLastPathSegment();
        return last == null ? "файл" : last;
    }

    private String errorText(IOException e) {
        if (e instanceof BilimApi.HttpError) {
            BilimApi.HttpError h = (BilimApi.HttpError) e;
            if (h.isAuth()) return "Вход в BilimClass устарел — войдите заново в приложении.";
            if (h.code == 413) return "Файл слишком большой для BilimClass.";
            if (h.code == 422) return "BilimClass не принял данные (проверьте текст и файлы).";
            return "BilimClass ответил ошибкой " + h.code + ".";
        }
        String m = e.getMessage();
        if (m != null && (m.startsWith("Файл") || m.startsWith("Не удалось"))) return m;
        return "Нет связи с BilimClass. Проверьте интернет.";
    }

    // ---------- Вспомогательное ----------

    private void setBusy(boolean b, String msg) {
        busy = b;
        expandButton.setEnabled(!b);
        if (sendButton.getAlpha() == 1f) sendButton.setEnabled(!b);
        if (msg != null) status(msg, GRAY);
    }

    private void status(String msg, int color) {
        statusView.setText(msg);
        statusView.setTextColor(color);
    }

    private TextView label(String s) {
        TextView t = text(s, 13, PURPLE, true);
        t.setPadding(0, dp(14), 0, dp(4));
        return t;
    }

    private EditText edit(String hint, int lines) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF8F3FB);
        g.setStroke(dp(1), 0xFFCE93D8);
        g.setCornerRadius(dp(10));
        e.setBackground(g);
        e.setPadding(dp(10), dp(8), dp(10), dp(8));
        return e;
    }

    private LinearLayout.LayoutParams fullWidth(int height, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        lp.topMargin = top;
        return lp;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(12));
        if (bg == Color.WHITE) g.setStroke(dp(1), fg);
        b.setBackground(g);
        b.setPadding(dp(8), 0, dp(8), 0);
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
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    @Override
    public void onBackPressed() {
        if (!busy) super.onBackPressed();
    }
}
