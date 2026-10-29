package kz.nikolaevka.raspisanie;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Вход в BilimClass. Открывается настоящая страница входа bilimclass.kz;
 * логин и пароль вводятся на сайте и приложением не сохраняются.
 * После входа приложение забирает только пропуск (токен) для загрузки расписания.
 */
public class LoginActivity extends Activity {

    private static final String SITE = "https://www.bilimclass.kz";

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (done || web == null) return;
            web.evaluateJavascript(
                    "(function(){try{return localStorage.getItem('token')||''}catch(e){return ''}})()",
                    value -> {
                        String token = unquote(value);
                        String cookies = CookieManager.getInstance().getCookie(SITE);
                        String refresh = cookie(cookies, "r_t");
                        String school = cookie(cookies, "schoolId");
                        if (!token.isEmpty() && !refresh.isEmpty() && !school.isEmpty()) {
                            done = true;
                            Bilim.saveLogin(LoginActivity.this, token, refresh, school);
                            setResult(RESULT_OK);
                            finish();
                        } else {
                            handler.postDelayed(poll, 1500);
                        }
                    });
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFitsSystemWindows(true);
        col.setBackgroundColor(Ui.c(this, R.color.a_bg));

        TextView hint = Ui.text(this, "Войдите в BilimClass как обычно. Пароль вводится на сайте BilimClass "
                + "и не сохраняется в приложении. После входа окно закроется само.",
                14, Ui.c(this, R.color.a_text), 400);
        hint.setBackgroundColor(Ui.c(this, R.color.a_primary_soft));
        int pad = Ui.dp(this, 16);
        hint.setPadding(pad, Ui.dp(this, 12), pad, Ui.dp(this, 12));
        col.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        col.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);
        web.loadUrl(SITE + "/login");
        handler.postDelayed(poll, 2000);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** evaluateJavascript возвращает строку в JSON-кавычках. */
    private static String unquote(String v) {
        if (v == null || v.equals("null")) return "";
        try {
            return new JSONArray("[" + v + "]").optString(0, "");
        } catch (Exception e) {
            return "";
        }
    }

    private static String cookie(String all, String name) {
        if (all == null) return "";
        for (String part : all.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0 && p.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return p.substring(eq + 1);
                }
            }
        }
        return "";
    }
}
