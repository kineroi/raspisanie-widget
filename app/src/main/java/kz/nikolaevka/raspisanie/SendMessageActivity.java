package kz.nikolaevka.raspisanie;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Невидимый экран из уведомления «Сообщение в WhatsApp»: открывает WhatsApp (или WhatsApp Business)
 * с готовым текстом. Группу WhatsApp выбрать за пользователя не даёт — её выбирают в открывшемся списке.
 */
public class SendMessageActivity extends Activity {

    private static final String[] WHATSAPP = {"com.whatsapp", "com.whatsapp.w4b"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String text = DailyMessage.text(this);
        DailyMessage.cancelNotification(this);
        // на всякий случай текст и в буфере обмена — если WhatsApp откроется без него, его можно вставить
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Сообщение", text));

        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        String pkg = installedWhatsApp();
        try {
            if (pkg != null) startActivity(new Intent(send).setPackage(pkg));
            else startActivity(Intent.createChooser(send, "Отправить сообщение"));
        } catch (RuntimeException e) {
            Toast.makeText(this, "WhatsApp не найден. Текст скопирован — вставьте его в группу.", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private String installedWhatsApp() {
        PackageManager pm = getPackageManager();
        for (String p : WHATSAPP) {
            try {
                pm.getPackageInfo(p, 0);
                return p;
            } catch (PackageManager.NameNotFoundException ignored) {
                // нет такого — пробуем следующий
            }
        }
        return null;
    }
}
