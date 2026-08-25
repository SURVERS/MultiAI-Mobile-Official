package app.multiai.mobile.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.datepicker.MaterialDatePicker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

import app.multiai.mobile.R;

/**
 * Bottom-шит расходов/платежей по референсу:
 * • ряд 1 — тип операций (чипы);
 * • ряд 2 — API-ключи: «Все ключи» + ключи пользователя;
 * • ряд 3 — период «с дд.мм.гггг по дд.мм.гггг» (два поля);
 * • поиск по описанию;
 * • список строк через RowBinder.
 */
public class RecentSpendsSheet {

    public interface RowBinder {
        View bind(Activity ctx, JSONObject item);
    }

    /** Дополнительный отображаемый текст строки, участвующий в поиске. */
    public interface SearchTextProvider {
        String getSearchText(JSONObject item);
    }

    /** Фабрика чипов. */
    private static TextView chip(Activity ctx, String label) {
        TextView c = new TextView(ctx);
        c.setText(label);
        c.setTextSize(13);
        float dp = ctx.getResources().getDisplayMetrics().density;
        c.setPadding((int) (14 * dp), (int) (7 * dp), (int) (14 * dp), (int) (7 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = (int) (8 * dp);
        c.setLayoutParams(lp);
        return c;
    }

    private static void styleChip(Activity ctx, TextView c, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(18 * ctx.getResources().getDisplayMetrics().density);
        int accent = app.multiai.mobile.utils.AccentColor.resolve(ctx);
        if (selected) {
            bg.setColor(accent);
            c.setTextColor(app.multiai.mobile.utils.AccentColor.contrastForeground(ctx));
            c.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        } else {
            bg.setStroke((int) ctx.getResources().getDisplayMetrics().density, 0x33FFFFFF);
            bg.setColor(0x00000000);
            c.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
            c.setTypeface(Typeface.DEFAULT);
        }
        c.setBackground(bg);
    }

    private static void restyleRow(LinearLayout row, int selectedIdx) {
        for (int i = 0; i < row.getChildCount(); i++) {
            styleChip((Activity) row.getContext(), (TextView) row.getChildAt(i), i == selectedIdx);
        }
    }

    /** Поле даты открывает календарь и не вызывает клавиатуру. */
    private static EditText dateField(Activity ctx, String hint) {
        EditText f = new EditText(ctx);
        f.setHint(hint);
        f.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        f.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        f.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * ctx.getResources().getDisplayMetrics().density);
        bg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        f.setBackground(bg);
        float dp = ctx.getResources().getDisplayMetrics().density;
        f.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
        f.setSingleLine(true);
        f.setWidth((int) (110 * dp));
        f.setFocusable(false);
        f.setCursorVisible(false);
        f.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_calendar, 0);
        f.setCompoundDrawablePadding((int) (6 * dp));
        f.setOnClickListener(v -> showDatePicker(ctx, f));
        return f;
    }

    private static void showDatePicker(Activity ctx, EditText target) {
        if (!(ctx instanceof AppCompatActivity)) return;
        MaterialDatePicker<Long> picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(ctx.getString(R.string.sheet_date_title))
                .setSelection(parseDate(target.getText().toString()))
                .build();
        picker.addOnPositiveButtonClickListener(selection -> {
            if (selection == null) return;
            target.setText(new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                    .format(new java.util.Date(selection)));
        });
        picker.show(((AppCompatActivity) ctx).getSupportFragmentManager(), "sheet_date");
    }

    /** Парс «дд.мм.гггг» → millis; null если пусто/бито. */
    private static Long parseDate(String s) {
        if (s == null) return null;
        String[] p = s.trim().split("\\.");
        if (p.length != 3) return null;
        try {
            Calendar c = Calendar.getInstance();
            c.set(Integer.parseInt(p[2]), Integer.parseInt(p[1]) - 1,
                    Integer.parseInt(p[0]), 0, 0, 0);
            return c.getTimeInMillis();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** millis транзакции из created_at. */
    private static long txTime(JSONObject t) {
        String raw = t.optString("created_at", "");
        if (raw.length() >= 19) {
            try {
                java.text.SimpleDateFormat f =
                        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                return f.parse(raw.substring(0, 19)).getTime();
            } catch (Exception ignored) {
            }
        }
        return 0L;
    }

    /**
     * @param titleId   заголовок
     * @param items     данные
     * @param mode      "ai" — операции: Все / Расход по API / Расход по подписке;
     *                  "tx" — Все / Пополнения / Возвраты / Покупки / Промокоды
     * @param keys      ключи юзера [{id,name}…] или null (ряд ключей скрыт)
     * @param binder    построитель строки
     */
    public static void show(Activity ctx, int titleId, JSONArray items, String mode,
                            JSONArray keys, RowBinder binder) {
        show(ctx, titleId, items, mode, keys, binder, null);
    }

    public static void show(Activity ctx, int titleId, JSONArray items, String mode,
                            JSONArray keys, RowBinder binder,
                            SearchTextProvider searchTextProvider) {
        FrameLayout dim = new FrameLayout(ctx);
        dim.setBackgroundColor(0xCC000000);
        dim.setOnClickListener(v -> dismiss(dim, null));

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        float dp = ctx.getResources().getDisplayMetrics().density;
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(ContextCompat.getColor(ctx, R.color.surface));
        bgd.setCornerRadius(20 * dp);
        sheet.setBackground(bgd);

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        sheetLp.height = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.72f);
        dim.addView(sheet, sheetLp);

        View handle = new View(ctx);
        GradientDrawable hd = new GradientDrawable();
        hd.setCornerRadius(3 * dp);
        hd.setColor(0x33FFFFFF);
        handle.setBackground(hd);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (4 * dp));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = (int) (10 * dp);
        handle.setLayoutParams(hlp);
        sheet.addView(handle);

        // Заголовок + крестик
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = (int) (18 * dp);
        header.setPadding(hPad, (int) (12 * dp), (int) (12 * dp), (int) (4 * dp));
        TextView title = new TextView(ctx);
        title.setText(titleId);
        title.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView close = new ImageView(ctx);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary));
        close.setPadding((int) (8 * dp), (int) (8 * dp), (int) (8 * dp), (int) (8 * dp));
        close.setOnClickListener(v -> dismiss(dim, sheet));
        header.addView(close, new LinearLayout.LayoutParams(
                (int) (36 * dp), (int) (36 * dp)));
        sheet.addView(header);

        // ── Ряд 1: операции ──
        final int[] opSel = {0};
        final Runnable[] refill = new Runnable[1]; // заполняется ниже
        final Runnable[] refillNow = new Runnable[1];
        final Runnable[] delayedSearch = new Runnable[1];
        final Handler uiHandler = new Handler(Looper.getMainLooper());
        LinearLayout opsRow = new LinearLayout(ctx);
        opsRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] ops;
        if ("ai".equals(mode)) {
            ops = new String[]{ctx.getString(R.string.sheet_ops_all),
                    ctx.getString(R.string.sheet_ops_api)};
        } else {
            ops = new String[]{ctx.getString(R.string.sheet_ops_all),
                    ctx.getString(R.string.sheet_ops_topup),
                    ctx.getString(R.string.sheet_ops_refund),
                    ctx.getString(R.string.sheet_ops_purchase),
                    ctx.getString(R.string.sheet_ops_promo)};
        }
        for (int i = 0; i < ops.length; i++) {
            final int idx = i;
            TextView c = chip(ctx, ops[i]);
            c.setOnClickListener(v -> {
                opSel[0] = idx;
                restyleRow(opsRow, idx);
                if (refillNow[0] != null) refillNow[0].run();
            });
            opsRow.addView(c);
        }
        HorizontalScrollView opsScroll = new HorizontalScrollView(ctx);
        opsScroll.setHorizontalScrollBarEnabled(false);
        opsScroll.setPadding(hPad, (int) (8 * dp), hPad, 0);
        opsScroll.addView(opsRow);
        sheet.addView(opsScroll);
        restyleRow(opsRow, 0);

        // ── Ряд 2: ключи юзера ──
        final int[] keySel = {0};
        LinearLayout keysRow = new LinearLayout(ctx);
        keysRow.setOrientation(LinearLayout.HORIZONTAL);
        java.util.List<Long> keyIds = new java.util.ArrayList<>();
        keyIds.add(-1L); // «Все ключи»
        if (keys != null && keys.length() > 0) {
            TextView all = chip(ctx, ctx.getString(R.string.sheet_keys_all));
            all.setOnClickListener(v -> {
                keySel[0] = 0;
                restyleRow(keysRow, 0);
                if (refillNow[0] != null) refillNow[0].run();
            });
            keysRow.addView(all);
            for (int i = 0; i < keys.length(); i++) {
                JSONObject k = keys.optJSONObject(i);
                if (k == null) continue;
                long id = k.optLong("id", -1);
                String name = k.optString("name", "Ключ");
                keyIds.add(id);
                final int idx = keyIds.size() - 1;
                TextView c = chip(ctx, name);
                c.setOnClickListener(v -> {
                    keySel[0] = idx;
                    restyleRow(keysRow, idx);
                    if (refillNow[0] != null) refillNow[0].run();
                });
                keysRow.addView(c);
            }
            HorizontalScrollView keysScroll = new HorizontalScrollView(ctx);
            keysScroll.setHorizontalScrollBarEnabled(false);
            keysScroll.setPadding(hPad, (int) (8 * dp), hPad, 0);
            keysScroll.addView(keysRow);
            sheet.addView(keysScroll);
            restyleRow(keysRow, 0);
        }

        // ── Ряд 3: период ──
        LinearLayout dateRow = new LinearLayout(ctx);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        dateRow.setGravity(Gravity.CENTER_VERTICAL);
        dateRow.setPadding(hPad, (int) (8 * dp), hPad, 0);
        EditText dateFrom = dateField(ctx, ctx.getString(R.string.sheet_date_from));
        EditText dateTo = dateField(ctx, ctx.getString(R.string.sheet_date_to));
        TextView dash = new TextView(ctx);
        dash.setText("—");
        dash.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        LinearLayout.LayoutParams dashLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dashLp.leftMargin = (int) (6 * dp);
        dashLp.rightMargin = (int) (6 * dp);
        dateRow.addView(dateFrom, dashLp);
        dateRow.addView(dash, dashLp);
        dateRow.addView(dateTo, dashLp);
        sheet.addView(dateRow);

        // ── Поиск ──
        EditText search = new EditText(ctx);
        search.setHint(R.string.filter_search_hint);
        search.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary));
        search.setHintTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
        search.setTextSize(14);
        GradientDrawable sBg = new GradientDrawable();
        sBg.setCornerRadius(12 * dp);
        sBg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        search.setBackground(sBg);
        search.setPadding((int) (14 * dp), (int) (10 * dp), (int) (14 * dp), (int) (10 * dp));
        search.setSingleLine(true);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.setMargins(hPad, (int) (10 * dp), hPad, (int) (6 * dp));
        search.setLayoutParams(sLp);
        sheet.addView(search);

        // ── Список: максимум 50 строк на страницу ──
        final int pageSize = 50;
        final int[] pageIndex = {0};
        final String[] filterSignature = {null};
        final String[] searchHayCache = new String[items.length()];
        final long[] txTimeCache = new long[items.length()];
        final boolean[] txTimeCached = new boolean[items.length()];
        final java.util.List<JSONObject> filteredCache = new java.util.ArrayList<>();

        ScrollView listScroll = new ScrollView(ctx);
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, (int) (4 * dp), 0, (int) (16 * dp));
        listScroll.addView(list);
        sheet.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout pager = new LinearLayout(ctx);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER);
        final int pagerTopPadding = (int) (6 * dp);
        final int pagerBottomPadding = (int) (22 * dp);
        pager.setPadding(hPad, pagerTopPadding, hPad, pagerBottomPadding);
        ViewCompat.setOnApplyWindowInsetsListener(pager, (view, insets) -> {
            int navigationBottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
            view.setPadding(hPad, pagerTopPadding, hPad,
                    pagerBottomPadding + navigationBottom);
            return insets;
        });
        TextView prevPage = chip(ctx, "‹");
        TextView pageInfo = new TextView(ctx);
        pageInfo.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        pageInfo.setTextSize(13);
        pageInfo.setGravity(Gravity.CENTER);
        pageInfo.setPadding((int) (14 * dp), 0, (int) (14 * dp), 0);
        TextView nextPage = chip(ctx, "›");
        styleChip(ctx, prevPage, false);
        styleChip(ctx, nextPage, false);
        pager.addView(prevPage);
        pager.addView(pageInfo);
        pager.addView(nextPage);
        sheet.addView(pager);

        prevPage.setOnClickListener(v -> {
            if (pageIndex[0] > 0) {
                pageIndex[0]--;
                if (refillNow[0] != null) refillNow[0].run();
            }
        });
        nextPage.setOnClickListener(v -> {
            pageIndex[0]++;
            if (refillNow[0] != null) refillNow[0].run();
        });

        refill[0] = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            Long from = parseDate(dateFrom.getText().toString());
            Long toRaw = parseDate(dateTo.getText().toString());
            Long to = toRaw == null ? null : toRaw + 86_400_000L - 1;
            long keyId = keySel[0] < keyIds.size() ? keyIds.get(keySel[0]) : -1L;
            int op = opSel[0];

            String signature = q + "|" + dateFrom.getText() + "|" + dateTo.getText()
                    + "|" + keyId + "|" + op;
            boolean filtersChanged = !signature.equals(filterSignature[0]);
            if (filtersChanged) {
                pageIndex[0] = 0;
                filterSignature[0] = signature;
                filteredCache.clear();

                for (int i = 0; i < items.length(); i++) {
                    JSONObject t = items.optJSONObject(i);
                    if (t == null) continue;
                    // Фильтр операций
                    String type = t.optString("type", "").toLowerCase(Locale.ROOT);
                    boolean promo = "promo".equals(type) || "promocode".equals(type)
                            || "promo_code".equals(type)
                            || ("bonus".equals(type) && (t.has("promo_code")
                            || t.optString("source", "").toLowerCase(Locale.ROOT).contains("promo")
                            || t.optString("title", "").toLowerCase(Locale.ROOT).contains("промо")));
                    boolean isAi = !"bonus".equals(type) && !promo
                            && !"subscription".equals(type) && !"refund".equals(type)
                            && !"purchase".equals(type);
                    if ("ai".equals(mode)) {
                        if (op == 1 && !isAi) continue; // только API
                    } else {
                        switch (op) {
                            case 1: if (!"bonus".equals(type) || promo) continue; break; // Пополнения
                            case 2: if (!"refund".equals(type)) continue; break;          // Возвраты
                            case 3: if (!"purchase".equals(type)) continue; break;        // Покупки
                            case 4: if (!promo) continue; break;                           // Промокоды
                        }
                    }
                    // Фильтр ключа
                    if (keyId != -1L && t.optLong("key_id", -1) != keyId) continue;
                    // Даты парсим только при активном диапазоне и не чаще раза на запись.
                    if (from != null || to != null) {
                        if (!txTimeCached[i]) {
                            txTimeCache[i] = txTime(t);
                            txTimeCached[i] = true;
                        }
                        long time = txTimeCache[i];
                        if (from != null && time < from) continue;
                        if (to != null && time > to) continue;
                    }
                    // Haystack и pretty-name неизменны на всём сроке жизни шита.
                    if (!q.isEmpty()) {
                        String hay = searchHayCache[i];
                        if (hay == null) {
                            String displayText = searchTextProvider != null
                                    ? searchTextProvider.getSearchText(t) : "";
                            hay = (t.optString("title", "") + " " + type
                                    + " " + t.optString("model", "") + " "
                                    + (displayText != null ? displayText : ""))
                                    .toLowerCase(Locale.ROOT);
                            searchHayCache[i] = hay;
                        }
                        if (!hay.contains(q)) continue;
                    }
                    filteredCache.add(t);
                }
            }

            int total = filteredCache.size();
            int pageCount = Math.max(1, (total + pageSize - 1) / pageSize);
            pageIndex[0] = Math.max(0, Math.min(pageIndex[0], pageCount - 1));
            int start = pageIndex[0] * pageSize;
            int end = Math.min(start + pageSize, total);
            list.removeAllViews();
            int shown = 0;
            for (int i = start; i < end; i++) {
                View row = binder.bind(ctx, filteredCache.get(i));
                if (row != null) {
                    list.addView(row);
                    shown++;
                }
            }

            if (shown == 0) {
                TextView empty = new TextView(ctx);
                empty.setText(R.string.filter_empty);
                empty.setTextColor(ContextCompat.getColor(ctx, R.color.text_hint));
                empty.setTextSize(13);
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, (int) (24 * dp), 0, (int) (24 * dp));
                list.addView(empty);
            }

            pageInfo.setText(total == 0 ? "0 / 0"
                    : String.format(Locale.getDefault(), "%d / %d · %d",
                    pageIndex[0] + 1, pageCount, total));
            prevPage.setEnabled(total > 0 && pageIndex[0] > 0);
            nextPage.setEnabled(total > 0 && pageIndex[0] + 1 < pageCount);
            prevPage.setAlpha(prevPage.isEnabled() ? 1f : 0.35f);
            nextPage.setAlpha(nextPage.isEnabled() ? 1f : 0.35f);
            listScroll.scrollTo(0, 0);
        };

        refillNow[0] = () -> {
            if (delayedSearch[0] != null) {
                uiHandler.removeCallbacks(delayedSearch[0]);
            }
            if (refill[0] != null) refill[0].run();
        };
        delayedSearch[0] = () -> {
            if (dim.getParent() != null && refill[0] != null) {
                refill[0].run();
            }
        };

        TextWatcher searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                uiHandler.removeCallbacks(delayedSearch[0]);
                uiHandler.postDelayed(delayedSearch[0], 220L);
            }
        };
        TextWatcher immediateDateWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (refillNow[0] != null) refillNow[0].run();
            }
        };
        search.addTextChangedListener(searchWatcher);
        dateFrom.addTextChangedListener(immediateDateWatcher);
        dateTo.addTextChangedListener(immediateDateWatcher);
        dim.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            @Override public void onViewDetachedFromWindow(View v) {
                uiHandler.removeCallbacks(delayedSearch[0]);
            }
        });

        refillNow[0].run();

        ViewGroup decor = (ViewGroup) ctx.getWindow().getDecorView();
        dim.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decor.addView(dim);
        sheet.setTranslationY(ctx.getResources().getDisplayMetrics().heightPixels);
        sheet.animate().translationY(0f).setDuration(280).start();
        dim.setAlpha(0f);
        dim.animate().alpha(1f).setDuration(200).start();
    }

    private static void dismiss(FrameLayout dim, View sheet) {
        if (dim.getContext() instanceof Activity) {
            Activity a = (Activity) dim.getContext();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
            View focused = a.getCurrentFocus();
            if (imm != null && focused != null) {
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
        }
        if (sheet != null) {
            sheet.animate().translationY(dim.getHeight())
                    .setDuration(240)
                    .withEndAction(() -> {
                        if (dim.getParent() instanceof ViewGroup) {
                            ((ViewGroup) dim.getParent()).removeView(dim);
                        }
                    }).start();
        } else if (dim.getParent() instanceof ViewGroup) {
            ((ViewGroup) dim.getParent()).removeView(dim);
        }
    }
}
