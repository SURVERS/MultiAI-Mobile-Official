package app.multiai.mobile.utils;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import app.multiai.mobile.ui.kit.UiKit;

/**
 * Runtime accent-color resolver.
 *
 * <p>The app's {@code @color/accent} resource is static — black in light theme,
 * white in dark theme — and XML layouts cannot be re-tinted at runtime. This
 * helper reads the user-selected accent from {@link AppSettings} and returns the
 * matching color int for the <em>current</em> night-mode configuration, so that
 * programmatic UI (send buttons, toggles, active model chips, clocks, …) can
 * honor the chosen accent in both light and dark mode.
 *
 * <h3>Palette</h3>
 * <table border="1">
 *   <tr><th>key</th><th>label</th><th>light</th><th>dark</th></tr>
 *   <tr><td>default</td><td>По умолчанию</td><td>#000000</td><td>#FFFFFF</td></tr>
 *   <tr><td>blue</td>    <td>Синий</td>     <td>#2563EB</td><td>#3B82F6</td></tr>
 *   <tr><td>green</td>   <td>Зелёный</td>   <td>#16A34A</td><td>#22C55E</td></tr>
 *   <tr><td>yellow</td>  <td>Жёлтый</td>    <td>#CA8A04</td><td>#EAB308</td></tr>
 *   <tr><td>pink</td>    <td>Розовый</td>   <td>#DB2777</td><td>#EC4899</td></tr>
 *   <tr><td>orange</td>  <td>Оранжевый</td> <td>#EA580C</td><td>#F97316</td></tr>
 *   <tr><td>purple</td>  <td>Фиолетовый</td><td>#9333EA</td><td>#A855F7</td></tr>
 *   <tr><td>black</td>   <td>Чёрный</td>    <td>#000000</td><td>#000000</td></tr>
 * </table>
 *
 * <p>"black" is intentionally always #000000 even in dark mode — it is meant
 * for high-contrast use on white surfaces.
 */
public final class AccentColor {

    private AccentColor() {}

    /** Ordered list of accent keys; matches the palette table above. */
    private static final String[] KEYS = {
            "default", "blue", "green", "yellow", "pink", "orange", "purple", "black"
    };

    /** Resource ids for localized display labels indexed in parallel with {@link #KEYS}. */
    private static final int[] LABEL_RES = {
            app.multiai.mobile.R.string.kit_accent_default,
            app.multiai.mobile.R.string.kit_accent_blue,
            app.multiai.mobile.R.string.kit_accent_green,
            app.multiai.mobile.R.string.kit_accent_yellow,
            app.multiai.mobile.R.string.kit_accent_pink,
            app.multiai.mobile.R.string.kit_accent_orange,
            app.multiai.mobile.R.string.kit_accent_purple,
            app.multiai.mobile.R.string.kit_accent_black,
    };

    /** Light-theme palette (ARGB). */
    private static final int[] LIGHT = {
            0xFF000000, // default
            0xFF2563EB, // blue
            0xFF16A34A, // green
            0xFFCA8A04, // yellow
            0xFFDB2777, // pink
            0xFFEA580C, // orange
            0xFF9333EA, // purple
            0xFF000000, // black
    };

    /** Dark-theme palette (ARGB). Note: "black" stays #000000 by design. */
    private static final int[] DARK = {
            0xFFFFFFFF, // default
            0xFF3B82F6, // blue
            0xFF22C55E, // green
            0xFFEAB308, // yellow
            0xFFEC4899, // pink
            0xFFF97316, // orange
            0xFFA855F7, // purple
            0xFF000000, // black
    };

    /** All supported accent keys in display order. */
    public static String[] keys() {
        return KEYS.clone();
    }

    /**
     * Localized label for {@code key}; falls back to the default label.
     * Uses the given context's resources so the label follows the app locale.
     */
    public static String label(String key, Context ctx) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) {
                return ctx.getString(LABEL_RES[i]);
            }
        }
        return ctx.getString(LABEL_RES[0]);
    }

    /**
     * Legacy overload used where no Context is available. Returns the raw key
     * (e.g. "blue") so callers that need a localized label should prefer
     * {@link #label(String, Context)}.
     */
    public static String label(String key) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return KEYS[i];
        }
        return KEYS[0];
    }

    /** @return true if the current configuration is in night (dark) mode. */
    public static boolean isDark(Context ctx) {
        if (ctx == null) return false;
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Color int for a given key in a given theme — used by picker previews
     * (so they can render both light and dark variants in one sheet).
     */
    public static int previewColor(String key, boolean dark) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(key)) return dark ? DARK[i] : LIGHT[i];
        }
        return dark ? DARK[0] : LIGHT[0];
    }

    /**
     * Resolved accent color for the user-selected accent in the CURRENT theme.
     * Reads {@link AppSettings#getAccent()} and honors night mode.
     */
    public static int resolve(Context ctx) {
        return previewColor(new AppSettings(ctx).getAccent(), isDark(ctx));
    }

    /**
     * A readable foreground (icon/text) color for content placed on top of
     * {@code bgColor}. Returns white for dark backgrounds, black for bright ones,
     * computed from relative luminance. Used for send-button arrow icons, etc.
     */
    public static int contrastForeground(int bgColor) {
        double luminance = 0.299 * Color.red(bgColor)
                         + 0.587 * Color.green(bgColor)
                         + 0.114 * Color.blue(bgColor);
        return luminance >= 140 ? Color.BLACK : Color.WHITE;
    }

    /**
     * Convenience: contrasting foreground for the resolved accent in the
     * current theme (e.g. the arrow on a tinted send button).
     */
    public static int contrastForeground(Context ctx) {
        return contrastForeground(resolve(ctx));
    }

    /**
     * Applies the resolved accent as the background tint of one or more
     * {@link ImageButton}s (send / stop / voice-send buttons) and re-tints
     * their icon to a contrasting color so it stays readable.
     *
     * <p>This is the runtime equivalent of {@code android:backgroundTint="@color/accent"}
     * + {@code android:tint="@color/icon_on_accent"} — needed because the static
     * XML resource cannot reflect a user-chosen accent.
     */
    public static void tintButtons(Context ctx, ImageButton... buttons) {
        if (ctx == null || buttons == null) return;
        int bg = resolve(ctx);
        int fg = contrastForeground(bg);
        ColorStateList bgCsl = ColorStateList.valueOf(bg);
        ColorStateList fgCsl = ColorStateList.valueOf(fg);
        for (ImageButton b : buttons) {
            if (b == null) continue;
            b.setBackgroundTintList(bgCsl);
            b.setImageTintList(fgCsl);
        }
    }

    /**
     * Tints the blinking text caret of an {@link EditText} with the resolved
     * accent color. On API 29+ uses the official {@code setTextCursorDrawable}
     * API with a thin accent-colored rectangle; on older versions falls back
     * to reflecting into the private {@code mCursorDrawable} field and
     * applying a color filter.
     *
     * @param edit        the EditText whose caret should be tinted
     * @param accentColor the accent color int to apply
     */
    public static void applyCursorTint(EditText edit, int accentColor) {
        if (edit == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            GradientDrawable cd = new GradientDrawable();
            cd.setShape(GradientDrawable.RECTANGLE);
            cd.setSize(UiKit.dp(edit.getContext(), 2), -1);
            cd.setColor(accentColor);
            edit.setTextCursorDrawable(cd);
        } else {
            try {
                java.lang.reflect.Field f = TextView.class.getDeclaredField("mEditor");
                f.setAccessible(true);
                Object editor = f.get(edit);
                java.lang.reflect.Field f2 = editor.getClass().getDeclaredField("mCursorDrawable");
                f2.setAccessible(true);
                Drawable[] drawables = (Drawable[]) f2.get(editor);
                if (drawables != null && drawables.length > 0 && drawables[0] != null) {
                    drawables[0].setColorFilter(accentColor, PorterDuff.Mode.SRC_IN);
                    f2.set(editor, drawables);
                }
            } catch (Exception ignored) {
            }
        }
    }
}
