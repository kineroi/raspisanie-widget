package kz.nikolaevka.raspisanie;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Общее оформление экранов (по макету из Figma, см. ATTRIBUTION.md):
 * цвета из values/values-night, шрифт Onest, карточки, кнопки и поля.
 */
final class Ui {

    private Ui() {
    }

    private static final SparseArray<Typeface> FONTS = new SparseArray<>();

    static int c(Context ctx, int colorRes) {
        return ctx.getColor(colorRes);
    }

    static int dp(Context ctx, int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    /** Onest нужной насыщенности (переменный шрифт); если не загрузился — системный. */
    static Typeface font(Context ctx, int weight) {
        Typeface t = FONTS.get(weight);
        if (t != null) return t;
        try {
            t = new Typeface.Builder(ctx.getApplicationContext().getAssets(), "fonts/onest.ttf")
                    .setFontVariationSettings("'wght' " + weight)
                    .build();
        } catch (RuntimeException e) {
            t = null;
        }
        if (t == null) t = Typeface.create(Typeface.DEFAULT, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
        FONTS.put(weight, t);
        return t;
    }

    static TextView text(Context ctx, String s, int sp, int color, int weight) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(font(ctx, weight));
        return t;
    }

    static GradientDrawable round(Context ctx, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(ctx, radiusDp));
        return g;
    }

    /** Карточка на всю ширину с отступом сверху. */
    static LinearLayout card(Context ctx) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 16));
        card.setBackground(round(ctx, c(ctx, R.color.a_surface), 16));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.topMargin = dp(ctx, 16);
        card.setLayoutParams(cp);
        return card;
    }

    /** Основная кнопка — заливка основным цветом; обычная — рамка. */
    static Button button(Context ctx, String label, boolean primary) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setAllCaps(false);
        b.setTypeface(font(ctx, 500));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(c(ctx, primary ? R.color.a_on_primary : R.color.a_text));
        GradientDrawable g = round(ctx, c(ctx, primary ? R.color.a_primary : R.color.a_bg), 8);
        if (!primary) g.setStroke(dp(ctx, 1), c(ctx, R.color.a_border));
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0);
        return b;
    }

    static CheckBox checkBox(Context ctx, String label, boolean checked) {
        CheckBox cb = new CheckBox(ctx);
        cb.setText(label);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setTextColor(c(ctx, R.color.a_text));
        cb.setTypeface(font(ctx, 400));
        cb.setButtonTintList(ColorStateList.valueOf(c(ctx, R.color.a_primary)));
        cb.setChecked(checked);
        return cb;
    }

    /** Поле в рамке для многострочного текста. */
    static EditText box(Context ctx, String hint, int lines) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTypeface(font(ctx, 400));
        e.setTextColor(c(ctx, R.color.a_text));
        e.setHintTextColor(c(ctx, R.color.a_muted));
        GradientDrawable g = round(ctx, c(ctx, R.color.a_surface), 8);
        g.setStroke(dp(ctx, 1), c(ctx, R.color.a_border));
        e.setBackground(g);
        e.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));
        return e;
    }
}
