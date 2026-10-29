package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Аттестация: учитель добавляет грамоту или сертификат (или «Поделиться» из WhatsApp, галереи),
 * нейросеть определяет раздел и уровень, файл уходит в папку «Аттестация» на Google Диске
 * через скрипт Google самого учителя (assets/attestation.gs).
 */
public class AttestationActivity extends Activity {

    private static final int PICK = 1;
    private static final String SCRIPT_LINK =
            "https://github.com/kineroi/raspisanie-widget/blob/main/app/src/main/assets/attestation.gs";

    private enum State { WAIT, BUSY, READY, SAVING, SAVED }

    private static final class Doc {
        Uri uri;
        String name, mime, ext;
        Attestation.Meta meta = new Attestation.Meta();
        State state = State.WAIT;
        String message = "Ждёт распознавания…";
        boolean error;
        String savedPath;
        LinearLayout card;
    }

    private final List<Doc> docs = new ArrayList<>();
    /** Распознаём и отправляем по одному, чтобы не упереться в лимит нейросети. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private LinearLayout driveBox, list;
    private Button saveAll;
    private boolean checking = false;
    private String driveMessage = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
        addFromIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        addFromIntent(intent);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(c(R.color.a_bg));
        scroll.setFitsSystemWindows(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(col);

        col.addView(text("Аттестация", 24, c(R.color.a_text), 400));
        col.addView(text("Грамоты, сертификаты, дипломы: нейросеть определит раздел и уровень, "
                + "а приложение положит файл в папку «Аттестация» на Google Диске.", 13, c(R.color.a_muted), 400));

        driveBox = new LinearLayout(this);
        driveBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(driveBox);
        renderDrive();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button add = Ui.button(this, "Добавить документы", true);
        add.setOnClickListener(v -> pick());
        bar.addView(add, new LinearLayout.LayoutParams(0, dp(46), 1f));
        saveAll = Ui.button(this, "Сохранить все", false);
        saveAll.setOnClickListener(v -> {
            for (Doc d : docs) if (d.state == State.READY) save(d);
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        sp.leftMargin = dp(10);
        bar.addView(saveAll, sp);
        col.addView(bar, margins(dp(16)));
        col.addView(text("PDF, фото (JPG, PNG) или Word. Можно и из WhatsApp или галереи: «Поделиться» → «В аттестацию».",
                12, c(R.color.a_muted), 400), margins(dp(6)));

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list);
        refreshSaveAll();
        setContentView(scroll);
    }

    // ---------- Google Диск ----------

    private boolean connected() {
        return !Attestation.scriptUrl(this).isEmpty();
    }

    private void renderDrive() {
        driveBox.removeAllViews();
        LinearLayout card = Ui.card(this);
        driveBox.addView(card);
        if (connected()) {
            card.addView(text("Google Диск подключён", 16, c(R.color.a_green), 600));
            card.addView(text("Файлы попадают в «Мой диск / " + Attestation.ROOT_FOLDER
                    + "» — ту же папку, что на компьютере.", 13, c(R.color.a_muted), 400));
            if (driveMessage != null) card.addView(text(driveMessage, 13, c(R.color.a_red), 400));
            Button off = Ui.button(this, "Отключить", false);
            off.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("Отключить Google Диск? Уже сохранённые файлы останутся на Диске.")
                    .setPositiveButton("Отключить", (dlg, w) -> {
                        Attestation.setScriptUrl(this, "");
                        driveMessage = null;
                        renderDrive();
                        refreshAllDocs();
                    })
                    .setNegativeButton("Отмена", null)
                    .show());
            card.addView(off, margins(dp(10), dp(40)));
            return;
        }

        card.addView(text("Подключите Google Диск", 16, c(R.color.a_text), 600));
        card.addView(text("Один раз, лучше с компьютера (~5 минут):\n"
                + "1. Откройте script.google.com → «Новый проект» и вставьте код скрипта (кнопка ниже копирует его; "
                + "на компьютере — по ссылке «Код на GitHub»).\n"
                + "2. «Развернуть» → «Новое развертывание» → тип «Веб-приложение». Запуск от имени: «Я», доступ: «Все». "
                + "Разрешите доступ к Диску (если Google предупредит — «Дополнительные настройки» → «Перейти»).\n"
                + "3. Скопируйте «URL веб-приложения» (…/exec) и вставьте сюда.", 13, c(R.color.a_text), 400), margins(dp(6)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button copy = Ui.button(this, "Скопировать код", false);
        copy.setOnClickListener(v -> copyScript());
        row.addView(copy, new LinearLayout.LayoutParams(0, dp(40), 1f));
        Button link = Ui.button(this, "Код на GitHub", false);
        link.setOnClickListener(v -> open(SCRIPT_LINK));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.leftMargin = dp(8);
        row.addView(link, lp);
        card.addView(row, margins(dp(10)));
        Button script = Ui.button(this, "Открыть script.google.com", false);
        script.setOnClickListener(v -> open("https://script.google.com/home/projects/create"));
        card.addView(script, margins(dp(8), dp(40)));

        EditText url = Ui.box(this, "https://script.google.com/macros/s/…/exec", 1);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setSingleLine(true);
        card.addView(url, margins(dp(12)));
        if (driveMessage != null) card.addView(text(driveMessage, 13, c(R.color.a_red), 400), margins(dp(6)));
        Button connect = Ui.button(this, checking ? "Проверяю…" : "Подключить", true);
        connect.setEnabled(!checking);
        connect.setOnClickListener(v -> connect(url.getText().toString().trim()));
        card.addView(connect, margins(dp(10), dp(44)));
    }

    private void connect(String url) {
        if (!Attestation.isScriptUrl(url)) {
            driveMessage = "Нужна ссылка вида https://script.google.com/macros/s/…/exec — её показывает «Развернуть».";
            renderDrive();
            return;
        }
        checking = true;
        driveMessage = null;
        renderDrive();
        String secret = Attestation.secret(this);
        new Thread(() -> {
            String error = null;
            try {
                Attestation.call(url, Attestation.pingRequest(secret));
            } catch (Exception e) {
                error = e.getMessage();
            }
            String err = error;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                checking = false;
                if (err == null) Attestation.setScriptUrl(this, url);
                driveMessage = err;
                renderDrive();
                refreshAllDocs();
            });
        }).start();
    }

    private void copyScript() {
        try (InputStream in = getAssets().open("attestation.gs")) {
            String code = new String(Attestation.readAll(in, 1 << 20), StandardCharsets.UTF_8);
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Скрипт аттестации", code));
            Toast.makeText(this, "Код скрипта скопирован", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось скопировать — откройте код на GitHub", Toast.LENGTH_LONG).show();
        }
    }

    private void open(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "Нет браузера", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- Документы ----------

    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/pdf", "image/*", Attestation.DOCX});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        addAll(uris);
    }

    /** «Поделиться» из другого приложения: один или несколько файлов. */
    private void addFromIntent(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> many = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (many != null) uris.addAll(many);
        }
        addAll(uris);
    }

    private void addAll(List<Uri> uris) {
        for (Uri u : uris) {
            if (u == null) continue;
            boolean dup = false;
            for (Doc d : docs) if (d.uri.equals(u)) dup = true;
            if (dup) continue;
            Doc d = new Doc();
            d.uri = u;
            d.mime = getContentResolver().getType(u);
            d.name = displayName(u);
            d.ext = Attestation.extension(d.name, d.mime);
            docs.add(d);
            d.card = Ui.card(this);
            list.addView(d.card, 0);
            renderDoc(d);
            worker.execute(() -> classify(d));
        }
        refreshSaveAll();
    }

    private String displayName(Uri u) {
        try (Cursor cur = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (RuntimeException e) {
            // имя не отдали — возьмём из адреса
        }
        String s = u.getLastPathSegment();
        return s == null ? "Документ" : s;
    }

    /** В фоне: прочитать файл и спросить нейросеть. */
    private void classify(Doc d) {
        post(d, State.BUSY, "Распознаю…", false);
        Ai.Provider p = Bilim.aiProvider(this);
        if (p == null) {
            post(d, State.READY, "Нужен ключ нейросети (Настройки → Нейросеть). Пока заполните поля вручную.", true);
            return;
        }
        String text = "Документ «" + d.name + "» во вложении.";
        List<Ai.FilePart> files = new ArrayList<>();
        try (InputStream in = getContentResolver().openInputStream(d.uri)) {
            if (in == null) throw new IOException("нет файла");
            if (".docx".equals(d.ext)) {
                text = "Документ «" + d.name + "»:\n" + Attestation.docxText(in);
            } else {
                String mime = Attestation.aiMime(d.ext);
                if (mime == null) {
                    post(d, State.READY, "Распознать можно PDF, JPG, PNG и docx. Заполните поля вручную.", true);
                    return;
                }
                byte[] bytes = Attestation.readAll(in, Attestation.MAX_FILE_BYTES);
                files.add(new Ai.FilePart(mime, Base64.encodeToString(bytes, Base64.NO_WRAP)));
            }
        } catch (IOException e) {
            post(d, State.READY, "too-big".equals(e.getMessage())
                    ? "Файл больше 15 МБ — нейросеть его не примет. Заполните поля вручную."
                    : "Не удалось прочитать файл. Заполните поля вручную.", true);
            return;
        }
        try {
            JSONObject out = Ai.completeJson(p, Bilim.aiKey(this), Bilim.aiModel(this), Attestation.SYSTEM, text, files);
            Attestation.Meta m = Attestation.parseClassification(out);
            runOnUiThread(() -> {
                d.meta = m;
                setState(d, State.READY, "Распознано: " + p.name + ". Проверьте и сохраните.", false);
            });
        } catch (IOException e) {
            String msg;
            if (e instanceof Ai.Failure && ((Ai.Failure) e).code == Ai.PDF_UNSUPPORTED) {
                msg = p.name + " не читает PDF. Подключите ключ Google Gemini или добавьте фото документа.";
            } else {
                msg = Ai.explain(e, p).replace(" Текст расписан без ИИ.", "")
                        .replace("на главном экране", "в настройках") + " Заполните поля вручную.";
            }
            post(d, State.READY, msg, true);
        }
    }

    private void post(Doc d, State s, String message, boolean error) {
        runOnUiThread(() -> setState(d, s, message, error));
    }

    private void setState(Doc d, State s, String message, boolean error) {
        if (isFinishing() || !docs.contains(d)) return;
        d.state = s;
        d.message = message;
        d.error = error;
        renderDoc(d);
        refreshSaveAll();
    }

    private void save(Doc d) {
        if (!connected() || d.state != State.READY) return;
        Attestation.Target t = Attestation.target(d.meta, d.ext);
        String url = Attestation.scriptUrl(this);
        String secret = Attestation.secret(this);
        setState(d, State.SAVING, "Отправляю на Google Диск…", false);
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(d.uri)) {
                if (in == null) throw new IOException("нет файла");
                byte[] bytes = Attestation.readAll(in, 40L * 1024 * 1024);
                String mime = d.mime != null ? d.mime : "application/octet-stream";
                JSONObject r = Attestation.call(url, Attestation.saveRequest(secret, t, mime,
                        Base64.encodeToString(bytes, Base64.NO_WRAP)));
                String path = r.optString("path", t.path());
                runOnUiThread(() -> {
                    d.savedPath = path;
                    setState(d, State.SAVED, "Сохранено ✓", false);
                });
            } catch (Attestation.DriveError e) {
                post(d, State.READY, e.getMessage(), true);
            } catch (Exception e) {
                post(d, State.READY, "too-big".equals(e.getMessage())
                        ? "Файл больше 40 МБ — Google Диск через скрипт его не примет."
                        : "Не удалось прочитать файл.", true);
            }
        });
    }

    private void refreshAllDocs() {
        for (Doc d : docs) renderDoc(d);
        refreshSaveAll();
    }

    private void refreshSaveAll() {
        int ready = 0;
        for (Doc d : docs) if (d.state == State.READY) ready++;
        saveAll.setVisibility(ready > 1 ? View.VISIBLE : View.GONE);
        saveAll.setEnabled(connected());
    }

    private void renderDoc(Doc d) {
        LinearLayout card = d.card;
        card.removeAllViews();
        card.addView(text(d.name, 16, c(R.color.a_text), 500));
        int color = d.error ? R.color.a_red : d.state == State.SAVED ? R.color.a_green : R.color.a_muted;
        if (d.message != null && !d.message.isEmpty()) card.addView(text(d.message, 13, c(color), 400));
        if (d.state == State.WAIT || d.state == State.BUSY) return;

        if (d.state == State.SAVED) {
            card.addView(text(d.savedPath, 13, c(R.color.a_muted), 400), margins(dp(6)));
            return;
        }

        boolean editable = d.state == State.READY;
        Attestation.Section section = Attestation.sectionById(d.meta.section);
        TextView dest = text("", 13, c(R.color.a_primary), 500);
        Runnable update = () -> dest.setText("→ " + Attestation.target(d.meta, d.ext).path());

        String[] sectionNames = new String[Attestation.SECTIONS.length];
        for (int i = 0; i < sectionNames.length; i++) sectionNames[i] = Attestation.SECTIONS[i].name;
        card.addView(label("Раздел"));
        card.addView(chooser(section.name, sectionNames, editable, i -> {
            d.meta.section = Attestation.SECTIONS[i].id;
            renderDoc(d);
        }), margins(dp(4), dp(42)));

        if (section.levels) {
            String[] levelNames = new String[Attestation.LEVELS.length + 1];
            levelNames[0] = "Не указан";
            for (int i = 0; i < Attestation.LEVELS.length; i++) levelNames[i + 1] = Attestation.LEVELS[i].name;
            Attestation.Level level = Attestation.levelById(d.meta.level);
            card.addView(label("Уровень"));
            card.addView(chooser(level == null ? "Не указан" : level.name, levelNames, editable, i -> {
                d.meta.level = i == 0 ? "" : Attestation.LEVELS[i - 1].id;
                renderDoc(d);
            }), margins(dp(4), dp(42)));
        }

        card.addView(label("Вид документа"));
        card.addView(field(d.meta.kind, "Грамота, Сертификат…", editable, v -> {
            d.meta.kind = v;
            update.run();
        }), margins(dp(4)));

        card.addView(label("Дата выдачи"));
        Button date = chooserButton(d.meta.date.isEmpty() ? "Без даты" : d.meta.date, editable);
        date.setOnClickListener(v -> pickDate(d));
        card.addView(date, margins(dp(4), dp(42)));

        card.addView(label("За что / мероприятие"));
        card.addView(field(d.meta.title, "Например: Лучший открытый урок", editable, v -> {
            d.meta.title = v;
            update.run();
        }), margins(dp(4)));

        if (!d.meta.issuer.isEmpty()) card.addView(text("Выдал: " + d.meta.issuer, 12, c(R.color.a_muted), 400), margins(dp(8)));
        update.run();
        card.addView(dest, margins(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button save = Ui.button(this, d.state == State.SAVING ? "Отправляю…" : "Сохранить на Диск", true);
        save.setEnabled(editable && connected());
        save.setOnClickListener(v -> save(d));
        row.addView(save, new LinearLayout.LayoutParams(0, dp(42), 1f));
        Button remove = Ui.button(this, "Убрать", false);
        remove.setEnabled(editable);
        remove.setOnClickListener(v -> {
            docs.remove(d);
            list.removeView(d.card);
            refreshSaveAll();
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        rp.leftMargin = dp(8);
        row.addView(remove, rp);
        card.addView(row, margins(dp(12)));
        if (!connected()) card.addView(text("Сначала подключите Google Диск (вверху).", 12, c(R.color.a_muted), 400), margins(dp(6)));
    }

    private interface IntChoice {
        void chosen(int index);
    }

    private interface TextChange {
        void changed(String value);
    }

    private TextView label(String s) {
        TextView t = text(s, 12, c(R.color.a_muted), 500);
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    private Button chooserButton(String value, boolean enabled) {
        Button b = Ui.button(this, value, false);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setSingleLine(true);
        b.setEnabled(enabled);
        return b;
    }

    private Button chooser(String value, String[] items, boolean enabled, IntChoice onChoice) {
        Button b = chooserButton(value + "  ▾", enabled);
        b.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setItems(items, (dlg, which) -> onChoice.chosen(which))
                .show());
        return b;
    }

    private EditText field(String value, String hint, boolean enabled, TextChange onChange) {
        EditText e = Ui.box(this, hint, 1);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setText(value);
        e.setEnabled(enabled);
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onChange.changed(s.toString().trim());
            }
        });
        return e;
    }

    private void pickDate(Doc d) {
        LocalDate start = d.meta.date.isEmpty() ? LocalDate.now() : LocalDate.parse(d.meta.date);
        DatePickerDialog dlg = new DatePickerDialog(this, (v, y, m, day) -> {
            d.meta.date = LocalDate.of(y, m + 1, day).toString();
            renderDoc(d);
        }, start.getYear(), start.getMonthValue() - 1, start.getDayOfMonth());
        dlg.setButton(DatePickerDialog.BUTTON_NEUTRAL, "Без даты", (x, w) -> {
            d.meta.date = "";
            renderDoc(d);
        });
        dlg.show();
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
