package app.multiai.mobile.ui.kit;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.multiai.mobile.R;
import app.multiai.mobile.models.ModelInfo;

/**
 * Каталог моделей MultiAI: bottom sheet с категориями-табами
 * (Все / Бесплатные / OpenAI / Anthropic / Google / Китайские / Другие),
 * скроллящимся списком и карточкой деталей модели (контекст-кольцо,
 * effort-переключатель, лимиты, мультипликаторы).
 */
public final class ModelCatalogUi {

    private ModelCatalogUi() {}

    /** Что выбрал юзер. */
    public interface OnModelPicked {
        void onPicked(ModelInfo model, String effort);
    }

    /** Использование контекста текущим диалогом (в токенах). */
    public static final class Usage {
        public final long systemPromptTokens;
        public final long messagesTokens;
        public final long toolsTokens;
        public Usage(long sys, long msgs, long tools) {
            systemPromptTokens = sys;
            messagesTokens = msgs;
            toolsTokens = tools;
        }
    }

    private static Usage currentUsage; // в рамках одного показа каталога

    /** Показать каталог. @param currentId выбранная модель, @param currentEffort текущий effort */
    public static void show(android.app.Activity act, List<ModelInfo> models, String currentId,
                            String currentEffort, OnModelPicked listener) {
        show(act, models, currentId, currentEffort, null, listener);
    }

    /** То же + использование контекста диалогом для блока лимитов в карточке. */
    public static void show(android.app.Activity act, List<ModelInfo> models, String currentId,
                            String currentEffort, Usage usage, OnModelPicked listener) {
        currentUsage = usage;
        Context ctx = act;
        // Скелетон пока каталог пуст
        if (models == null || models.isEmpty()) {
            android.widget.Toast.makeText(ctx, ctx.getString(R.string.chat_loading_models),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        FrameLayout root = new FrameLayout(ctx);

        Dialog[] ref = new Dialog[1];

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(ContextCompat.getDrawable(ctx, R.drawable.kit_sheet_bg));

        FrameLayout.LayoutParams sheetLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.gravity = Gravity.BOTTOM;
        sheet.setLayoutParams(sheetLp);

        // Заголовок
        TextView title = UiKit.text(ctx, ctx.getString(R.string.chat_model_picker_title), 17,
                R.color.text_primary, true);
        title.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 12), UiKit.dp(ctx, 20), 0);
        sheet.addView(title);

        // Табы категорий (горизонтальный скролл)
        final String[] order = buildCategoryOrder(models);
        HorizontalScrollView tabsScroll = new HorizontalScrollView(ctx);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        int tp = UiKit.dp(ctx, 20);
        tabs.setPadding(tp, UiKit.dp(ctx, 14), tp, UiKit.dp(ctx, 12));
        tabsScroll.addView(tabs);
        sheet.addView(tabsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Контейнер списка (скролл) — высота ограничена 60% экрана.
        // overScroll никогда + clipToOutline: карточки не вылезают на табы.
        final ScrollView listScroll = new ScrollView(ctx);
        listScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        listScroll.setClipToPadding(true);
        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        int lp2 = UiKit.dp(ctx, 8);
        list.setPadding(lp2, lp2, lp2, UiKit.dp(ctx, 24));
        listScroll.addView(list);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.6f));
        sheet.addView(listScroll, scrollLp);

        // Рендер табов + список
        final List<TextView> tabViews = new ArrayList<>();
        for (int i = 0; i < order.length; i++) {
            final String cat = order[i];
            TextView tab = UiKit.text(ctx, categoryLabel(ctx, cat, countInCat(models, cat)),
                    13, R.color.text_secondary, false);
            tab.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 7), UiKit.dp(ctx, 12), UiKit.dp(ctx, 7));
            tab.setBackground(tabBg(ctx, false));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setOnClickListener(v -> {
                for (TextView t : tabViews) {
                    t.setBackground(tabBg(ctx, false));
                    t.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary));
                    t.setTypeface(null, Typeface.NORMAL);
                }
                v.setBackground(tabBg(ctx, true));
                ((TextView) v).setTextColor(ContextCompat.getColor(ctx, R.color.accent));
                ((TextView) v).setTypeface(null, Typeface.BOLD);
                populate(ctx, models, cat, list, currentId, currentEffort, ref, listener);
                listScroll.scrollTo(0, 0);
            });
            tabs.addView(tab);
            LinearLayout.LayoutParams tabLp = (LinearLayout.LayoutParams) tab.getLayoutParams();
            tabLp.setMarginEnd(UiKit.dp(ctx, 6));
            tabViews.add(tab);
        }
        // Активный таб = категория текущей модели (или "all")
        String startCat = ModelInfo.CAT_ALL;
        for (ModelInfo m : models) {
            if (m.id.equals(currentId)) { startCat = m.category; break; }
        }
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(startCat)) tabViews.get(i).performClick();
        }

        root.addView(sheet);

        AnimatedDialog d = new AnimatedDialog(ctx, AnimatedDialog.Mode.SHEET);
        // Затемнение: клик по нему закрывает пикер (AnimatedDialog сам
        // проиграет плавный exit-анимацией вниз).
        View backdrop = new View(ctx);
        backdrop.setBackgroundColor(ContextCompat.getColor(ctx, R.color.overlay_dim));
        backdrop.setOnClickListener(v -> d.dismiss());
        root.addView(backdrop, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        d.setCancelable(true);
        d.setContentView(root);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                try { w.setBackgroundBlurRadius(UiKit.dp(ctx, 24)); } catch (Throwable ignored) {}
            }
        }
        ref[0] = d;
        d.bind(backdrop, sheet);
        d.show();
        d.playEnter();
    }

    // ─────────── иконки моделей ───────────

    /** Ресурс иконки модели по её id (null = заглушка-буква). */
    public static Integer iconFor(String modelId) {
        if (modelId == null) return null;
        String s = modelId.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (s.startsWith("ma-")) s = s.substring(3);

        // Usage history может вернуть provider/model или provider:ma-model.
        // Сопоставляем и полный ID, и нормализованный хвост.
        int separator = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String tail = separator >= 0 && separator + 1 < s.length()
                ? s.substring(separator + 1) : s;
        if (tail.startsWith("ma-")) tail = tail.substring(3);
        String match = s + " " + tail;

        if (match.contains("big-pickle")) return R.drawable.ic_model_big_pickle;
        if (match.contains("x-preview")) return R.drawable.ic_model_big_pickle;
        if (match.contains("claude")) return R.drawable.ic_model_claude;
        if (match.contains("openai") || tail.startsWith("gpt")
                || tail.startsWith("o1") || tail.startsWith("o3")
                || tail.startsWith("o4")) return R.drawable.ic_model_openai;
        if (match.contains("gemini")) return R.drawable.ic_model_gemini;
        if (match.contains("nano-banana") || match.contains("nano_banana")) return R.drawable.ic_model_gemini;
        if (match.contains("gemma")) return R.drawable.ic_model_gemma;
        if (match.contains("grok") || match.contains("composer")) return R.drawable.ic_model_grok;
        if (match.contains("deepseek-v4-flash-free")) return R.drawable.ic_model_deepseek_v4_flash_free;
        if (match.contains("deepseek")) return R.drawable.ic_model_deepseek;
        if (match.contains("qwen")) return R.drawable.ic_model_qwen;
        if (match.contains("glm")) return R.drawable.ic_model_glm;
        if (match.contains("kimi")) return R.drawable.ic_model_kimi;
        if (match.contains("minimax")) return R.drawable.ic_model_minimax;
        if (match.contains("mistral")) return R.drawable.ic_model_mistral;
        if (match.contains("nemotron")) return R.drawable.ic_model_nemotron_3_ultra_free;
        if (match.contains("mimo")) return R.drawable.ic_model_mimo_v2_5_free;
        if (match.contains("laguna")) return R.drawable.ic_model_laguna_s_2_1_free;
        if (match.contains("longcat")) return R.drawable.ic_model_meta;
        if (match.contains("hy3")) return R.drawable.ic_model_hy3_free;
        if (match.contains("meta") || match.contains("llama")) return R.drawable.ic_model_meta;
        return null;
    }

    /** Иконка модели 28dp; если ресурса нет — кружок с первой буквой. */
    private static View modelIcon(Context ctx, ModelInfo m) {
        Integer res = iconFor(m.id);
        if (res != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(res);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 28), UiKit.dp(ctx, 28));
            iv.setLayoutParams(lp);
            return iv;
        }
        TextView letter = UiKit.text(ctx,
                m.displayName.substring(0, 1).toUpperCase(Locale.US), 13, R.color.text_secondary, true);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        letter.setBackground(bg);
        letter.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 28), UiKit.dp(ctx, 28));
        letter.setLayoutParams(lp);
        return letter;
    }

    // ─────────── список моделей ───────────

    private static void populate(Context ctx, List<ModelInfo> models, String cat,
                                 LinearLayout list, String currentId, String currentEffort,
                                 Dialog[] sheetRef, OnModelPicked listener) {
        list.removeAllViews();
        for (ModelInfo m : models) {
            if (!cat.equals(ModelInfo.CAT_ALL)
                    && !(cat.equals(ModelInfo.CAT_FREE) && m.free)
                    && !m.category.equals(cat)) continue;
            list.addView(modelRow(ctx, m, currentId, currentEffort, sheetRef, listener));
        }
    }

    private static View modelRow(Context ctx, ModelInfo m, String currentId,
                                 String currentEffort, Dialog[] sheetRef, OnModelPicked listener) {
        boolean selected = m.id.equals(currentId);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10), UiKit.dp(ctx, 12), UiKit.dp(ctx, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(ctx, 12));
        if (selected) {
            int accent = ContextCompat.getColor(ctx, R.color.accent);
            bg.setColor((accent & 0x00FFFFFF) | 0x26000000);
            bg.setStroke(1, (accent & 0x00FFFFFF) | 0x59000000);
        } else {
            bg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        }
        row.setBackground(bg);
        row.setClickable(true);
        row.setFocusable(true);
        // currentEffort относится только к уже выбранной модели. Для любой
        // другой карточки стартуем с её собственного catalog default.
        row.setOnClickListener(v -> showModelCard(ctx, m,
                selected ? currentEffort : m.reasoningDefault, sheetRef, listener));

        // Иконка модели
        row.addView(modelIcon(ctx, m));

        // Имя + мета
        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = UiKit.text(ctx, m.displayName, 15,
                R.color.text_primary, selected);
        textCol.addView(name);

        StringBuilder meta = new StringBuilder();
        if (m.free) meta.append(ctx.getString(R.string.mc_free));
        else if (m.inputMultiplier > 0) meta.append("×").append(trim(m.inputMultiplier));
        if (m.contextWindow > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(fmtTokens(m.contextWindow)).append(" ").append(ctx.getString(R.string.mc_ctx));
        }
        if (m.maxOutputTokens > 0) {
            meta.append(" · out ").append(fmtTokens(m.maxOutputTokens));
        }
        TextView metaTv = UiKit.text(ctx, meta.toString(), 11, R.color.text_hint, false);
        metaTv.setPadding(0, UiKit.dp(ctx, 1), 0, 0);
        textCol.addView(metaTv);

        LinearLayout.LayoutParams tcLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tcLp.setMarginStart(UiKit.dp(ctx, 12));
        textCol.setLayoutParams(tcLp);
        row.addView(textCol);

        // Кастомная галочка выбранной модели
        if (selected) {
            ImageView check = new ImageView(ctx);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(ContextCompat.getColor(ctx, R.color.accent));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 18), UiKit.dp(ctx, 18));
            cLp.setMarginEnd(UiKit.dp(ctx, 8));
            check.setLayoutParams(cLp);
            row.addView(check);
        }

        // Chevron
        ImageView chev = new ImageView(ctx);
        chev.setImageResource(R.drawable.ic_chevron_down);
        chev.setRotation(-90f);
        chev.setColorFilter(ContextCompat.getColor(ctx, R.color.icon));
        chev.setLayoutParams(new LinearLayout.LayoutParams(UiKit.dp(ctx, 16), UiKit.dp(ctx, 16)));
        row.addView(chev);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = UiKit.dp(ctx, 6);
        row.setLayoutParams(rowLp);
        return row;
    }

    // ─────────── карточка модели ───────────

    private static void showModelCard(Context ctx, ModelInfo m, String currentEffort,
                                      Dialog[] catalogRef, OnModelPicked listener) {
        if (catalogRef[0] != null) catalogRef[0].dismiss();

        LinearLayout body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(ctx, 20);
        body.setPadding(pad, 0, pad, UiKit.dp(ctx, 8));

        // Заголовок: иконка модели + имя
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        long catalogMax = 1_000_000L;
        View icon = modelIcon(ctx, m);
        if (icon instanceof ImageView) {
            LinearLayout.LayoutParams iLp = new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 40), UiKit.dp(ctx, 40));
            icon.setLayoutParams(iLp);
        }
        head.addView(icon);

        LinearLayout nameCol = new LinearLayout(ctx);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setPadding(UiKit.dp(ctx, 14), 0, 0, 0);
        TextView name = UiKit.text(ctx, m.displayName, 19, R.color.text_primary, true);
        nameCol.addView(name);
        TextView sub = UiKit.text(ctx, m.id + (m.free ? " · FREE" : ""), 12, R.color.text_hint, false);
        nameCol.addView(sub);
        head.addView(nameCol);
        body.addView(head);

        // Характеристики
        Map<String, String> stats = new LinkedHashMap<>();
        if (m.contextWindow > 0)
            stats.put(ctx.getString(R.string.mc_stat_context), fmtTokens(m.contextWindow) + " " + ctx.getString(R.string.mc_tokens_unit));
        if (m.maxOutputTokens > 0)
            stats.put(ctx.getString(R.string.mc_stat_output), fmtTokens(m.maxOutputTokens) + " " + ctx.getString(R.string.mc_tokens_unit));
        if (m.inputMultiplier > 0)
            stats.put(ctx.getString(R.string.mc_stat_input), "×" + trim(m.inputMultiplier));
        if (m.outputMultiplier > 0)
            stats.put(ctx.getString(R.string.mc_stat_output_mult), "×" + trim(m.outputMultiplier));
        if (m.cachedInputMultiplier > 0)
            stats.put(ctx.getString(R.string.mc_stat_cached), "×" + trim(m.cachedInputMultiplier));
        stats.put(ctx.getString(R.string.mc_stat_modalities), localizedModalities(ctx, m.inputModalities));
        if (!"text".equals(m.modelKind))
            stats.put(ctx.getString(R.string.mc_stat_kind), m.modelKind);
        stats.put(ctx.getString(R.string.mc_stat_reasoning), "levels".equals(m.reasoningKind)
                ? joinLevels(m.reasoningLevels) : m.reasoningKind);

        for (Map.Entry<String, String> e : stats.entrySet()) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            TextView k = UiKit.text(ctx, e.getKey(), 13, R.color.text_secondary, false);
            line.addView(k);
            TextView v = UiKit.text(ctx, e.getValue(), 13, R.color.text_primary, true);
            v.setGravity(Gravity.END);
            line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.setPadding(0, UiKit.dp(ctx, 5), 0, UiKit.dp(ctx, 5));
            body.addView(line);
        }

        // ── Использование контекста живёт в sheet по клику на кольцо ввода,
        //    из карточки модели убрано. ──

        // Effort-переключатель (если есть уровни) + кнопка выбора — ниже,
        // после создания sheet-диалога (см. pickButton/bindChips).
        final String initialEffort = m.resolveEffort(currentEffort);
        final String[] selectedCardEffort = new String[]{initialEffort};
        if (m.hasEffort()) {
            TextView effTitle = UiKit.text(ctx, ctx.getString(R.string.mc_reasoning_effort), 13, R.color.text_secondary, true);
            effTitle.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 6));
            body.addView(effTitle);

            // Чипы effort: переносятся строками (не рвут слово по буквам),
            // каждый — однострочный.
            List<TextView> chipViews = new ArrayList<>();
            for (final String lvl : m.reasoningLevels) {
                if (lvl.isEmpty()) continue;
                TextView chip = UiKit.text(ctx, effortLabel(ctx, lvl), 12,
                        R.color.text_secondary, false);
                chip.setSingleLine(true);
                boolean on = lvl.equals(initialEffort);
                chip.setBackground(chipBg(ctx, on));
                if (on) chip.setTextColor(ContextCompat.getColor(ctx, R.color.accent));
                chip.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 7), UiKit.dp(ctx, 14), UiKit.dp(ctx, 7));
                chip.setClickable(true);
                chipViews.add(chip);
            }
            body.addView(chipRows(ctx, chipViews, UiKit.dp(ctx, 6)));
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(body);
        final Dialog card = ModernSheet.custom(ctx, null, scroll);

        // Даже без переключателя передаём валидный catalog default; у managed
        // и defaultless моделей resolveEffort вернёт null и поле будет опущено.
        if (m.hasEffort()) {
            LinearLayout chips = findChips(body);
            if (chips != null) bindChips(chips, selectedCardEffort);
        }
        body.addView(pickButton(ctx, m, selectedCardEffort, card, listener));
    }

    /** Найти LinearLayout с effort-чипами (второй вертикальный блок после заголовка). */
    /** Разложить чипы по строкам с переносом (после measure), чтобы длинные
     *  подписи не сжимались в столбик по букве. Ряды разделены отступом,
     *  второй ряд выравнивается по левому краю с первым. */
    private static LinearLayout chipRows(Context ctx, List<TextView> chips, int gapPx) {
        final LinearLayout flow = new LinearLayout(ctx);
        flow.setOrientation(LinearLayout.VERTICAL);
        final LinearLayout[] current = {null};
        final int[] used = {0};
        Runnable newLine = () -> {
            current[0] = new LinearLayout(ctx);
            current[0].setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (flow.getChildCount() > 0) rowLp.topMargin = gapPx;
            current[0].setLayoutParams(rowLp);
            flow.addView(current[0]);
            used[0] = 0;
        };
        newLine.run();
        for (TextView chip : chips) {
            chip.setSingleLine(true);
            chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int w = chip.getMeasuredWidth();
            int max = ctx.getResources().getDisplayMetrics().widthPixels - UiKit.dp(ctx, 80);
            if (used[0] > 0 && used[0] + gapPx + w > max) newLine.run();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(gapPx);
            current[0].addView(chip, lp);
            used[0] += gapPx + w;
        }
        return flow;
    }

    /** Найти flow-контейнер чипов effort (вертикальный, ряды внутри). */
    private static LinearLayout findChips(LinearLayout body) {
        for (int i = body.getChildCount() - 1; i >= 0; i--) {
            View v = body.getChildAt(i);
            if (v instanceof LinearLayout && ((LinearLayout) v).getOrientation() == LinearLayout.VERTICAL
                    && ((LinearLayout) v).getChildCount() > 0
                    && ((LinearLayout) v).getChildAt(0) instanceof LinearLayout) {
                return (LinearLayout) v;
            }
        }
        return null;
    }

    /** Повесить выбор effort на все чипы во всех рядах flow-контейнера. */
    private static void bindChips(final LinearLayout flow, final String[] eff) {
        List<TextView> chips = new ArrayList<>();
        collectChipViews(flow, chips);
        for (final TextView c : chips) {
            c.setOnClickListener(v -> {
                eff[0] = unlabelEffort(flow.getContext(), c.getText().toString());
                for (TextView t : chips) {
                    boolean active = t == c;
                    t.setBackground(chipBg(flow.getContext(), active));
                    t.setTextColor(ContextCompat.getColor(flow.getContext(),
                            active ? R.color.accent : R.color.text_secondary));
                }
            });
        }
    }

    private static void collectChipViews(ViewGroup vg, List<TextView> out) {
        for (int i = 0; i < vg.getChildCount(); i++) {
            View v = vg.getChildAt(i);
            if (v instanceof TextView) out.add((TextView) v);
            else if (v instanceof ViewGroup) collectChipViews((ViewGroup) v, out);
        }
    }

    /** Обратный маппинг локализованной подписи → уровень. */
    private static String unlabelEffort(Context ctx, String label) {
        if (label.equals(ctx.getString(R.string.mc_effort_none))) return "none";
        if (label.equals(ctx.getString(R.string.mc_effort_low))) return "low";
        if (label.equals(ctx.getString(R.string.mc_effort_medium))) return "medium";
        if (label.equals(ctx.getString(R.string.mc_effort_high))) return "high";
        if (label.equals(ctx.getString(R.string.mc_effort_max))) return "max";
        return label.toLowerCase(Locale.US);
    }

    private static View pickButton(Context ctx, ModelInfo m, final String[] eff,
                                   final Dialog card, OnModelPicked listener) {
        TextView btn = UiKit.text(ctx, ctx.getString(R.string.mc_select_model), 15, R.color.text_on_accent, true);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(ctx, 14));
        bg.setColor(ContextCompat.getColor(ctx, R.color.accent));
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setOnClickListener(v -> {
            if (card != null) card.dismiss();
            String pickedEffort = m.resolveEffort(eff != null ? eff[0] : null);
            m.effort = pickedEffort;
            if (listener != null) listener.onPicked(m, pickedEffort);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 46));
        lp.topMargin = UiKit.dp(ctx, 18);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ─────────── контекст-кольцо ───────────

    /**
     * Кольцо для списка моделей в пикере: дуга 300° с текстом размера контекста.
     */
    public static final class CatalogRing extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint label = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float fraction;
        private final String text;

        public CatalogRing(Context ctx, long contextWindow, boolean accent, long catalogMax) {
            super(ctx);
            int size = UiKit.dp(ctx, 44);
            setLayoutParams(new ViewGroup.LayoutParams(size, size));

            fraction = Math.max(0.06f, Math.min(1f,
                    contextWindow > 0 ? contextWindow / (float) catalogMax : 0.06f));

            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(UiKit.dp(ctx, 3.5f));
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(UiKit.dp(ctx, 3.5f));
            fill.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(ContextCompat.getColor(ctx, R.color.stroke));
            fill.setColor(ContextCompat.getColor(ctx,
                    accent ? R.color.accent : R.color.text_secondary));

            label.setColor(ContextCompat.getColor(ctx, R.color.text_primary));
            label.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    contextWindow >= 1_000_000 ? 9 : 10,
                    ctx.getResources().getDisplayMetrics()));
            label.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            label.setTextAlign(Paint.Align.CENTER);

            String t;
            if (contextWindow <= 0) t = "?";
            else if (contextWindow >= 1_000_000) {
                double mm = contextWindow / 1_000_000.0;
                t = (mm == Math.floor(mm)) ? String.valueOf((long) mm) + "M"
                        : String.valueOf(Math.round(mm * 10) / 10.0) + "M";
            } else t = Math.round(contextWindow / 1000.0) + "K";
            text = t;
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            super.onDraw(c);
            float pad = UiKit.dp(getContext(), 2.5f);
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            c.drawArc(rect, 120, 300, false, ring);
            if (fraction > 0) {
                c.drawArc(rect, 120, 300 * fraction, false, fill);
            }
            float y = rect.centerY() - (label.descent() + label.ascent()) / 2f;
            c.drawText(text, rect.centerX(), y, label);
        }
    }

    /**
     * Контекст-кольцо строки ввода: маленький ПОЛНЫЙ круг без текста.
     * Серый круг + полоска-заполнение контекста диалогом (0 сообщений = пустой).
     */
    public static final class ContextRing extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean withText;
        private float fraction;

        /** Кольцо-индикатор для строки ввода: 16dp, полный круг, без текста. */
        public ContextRing(Context ctx) {
            this(ctx, false);
        }

        private ContextRing(Context ctx, boolean withText) {
            super(ctx);
            this.withText = withText;
            float strokeDp = withText ? 3.5f : 2f;
            sizePx = UiKit.dp(ctx, withText ? 44 : 16);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(UiKit.dp(ctx, strokeDp));
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(UiKit.dp(ctx, strokeDp));
            fill.setStrokeCap(Paint.Cap.ROUND);
            ring.setColor(ContextCompat.getColor(ctx, R.color.stroke));
            fill.setColor(ContextCompat.getColor(ctx, R.color.text_secondary));
        }

        private final int sizePx;

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(sizePx, sizePx);
        }

        /** Обновить заполненность контекста диалогом (использовано/лимит). */
        public void setUsage(long usedTokens, long contextLimit) {
            float f = contextLimit > 0 ? usedTokens / (float) contextLimit : 0f;
            fraction = Math.min(1f, Math.max(0f, f));
            int colorRes = fraction >= 0.9f ? R.color.error
                    : fraction >= 0.7f ? R.color.accent : R.color.text_secondary;
            fill.setColor(ContextCompat.getColor(getContext(), colorRes));
            // Пустой чат — кольцо не нужно: прячем целиком, чтобы не висела
            // серая заглушка на строке ввода.
            setVisibility(fraction <= 0f ? View.GONE : View.VISIBLE);
            invalidate();
        }

        @Override
        protected void onDraw(@NonNull Canvas c) {
            super.onDraw(c);
            float pad = UiKit.dp(getContext(), withText ? 2.5f : 1f);
            rect.set(pad, pad, getWidth() - pad, getHeight() - pad);
            // Полный круг
            c.drawArc(rect, 0, 360, false, ring);
            if (fraction > 0) {
                c.drawArc(rect, -90, 360 * fraction, false, fill);
            }
        }
    }

    /** Sheet «Использование контекста» по клику на кольцо в строке ввода. */
    public static void showContextSheet(android.app.Activity act, Usage usage,
                                        long contextLimit, String modelName) {
        if (act == null || act.isFinishing() || act.isDestroyed()) return;
        android.view.Window window = act.getWindow();
        if (window == null || window.getDecorView() == null
                || window.getDecorView().getWindowToken() == null) {
            return;
        }

        if (usage == null) usage = new Usage(0, 0, 0);
        if (modelName == null || modelName.trim().isEmpty()) modelName = "MultiAI";
        long systemTokens = Math.max(0L, usage.systemPromptTokens);
        long messageTokens = Math.max(0L, usage.messagesTokens);
        long toolTokens = Math.max(0L, usage.toolsTokens);
        long total = systemTokens + messageTokens + toolTokens;
        long limit = contextLimit > 0 ? contextLimit : 1;

        LinearLayout body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = UiKit.dp(act, 20);
        body.setPadding(pad, 0, pad, UiKit.dp(act, 8));

        TextView model = UiKit.text(act, modelName, 12, R.color.text_hint, false);
        body.addView(model);
        int pct = Math.min(100, Math.round(total / (float) limit * 100f));

        TextView big = UiKit.text(act, pct + "%", 34, R.color.text_primary, true);
        big.setPadding(0, UiKit.dp(act, 6), 0, 0);
        body.addView(big);
        TextView sub = UiKit.text(act, fmtTokens(total) + " / "
                + (contextLimit > 0 ? fmtTokens(contextLimit) : "?") + " " + act.getString(R.string.mc_tokens_unit),
                12, R.color.text_secondary, false);
        sub.setPadding(0, UiKit.dp(act, 2), 0, UiKit.dp(act, 10));
        body.addView(sub);

        body.addView(usageRow(act, act.getString(R.string.ctx_system_prompt), systemTokens, limit));
        body.addView(usageRow(act, act.getString(R.string.ctx_messages), messageTokens, limit));
        body.addView(usageRow(act, act.getString(R.string.ctx_tools), toolTokens, limit));

        try {
            ModernSheet.custom(act, act.getString(R.string.ctx_title), body);
        } catch (RuntimeException e) {
            android.util.Log.w("MultiAI", "Unable to open context sheet", e);
        }
    }

    /** Строка использования: label + тонкий прогрессбар + "токены (%, ширина = доля)". */
    private static View usageRow(Context ctx, String label, long tokens, long limit) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, UiKit.dp(ctx, 4), 0, UiKit.dp(ctx, 6));

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView nameTv = UiKit.text(ctx, label, 12, R.color.text_secondary, false);
        top.addView(nameTv);
        int pct = limit > 0 ? Math.round(Math.min(1f, tokens / (float) limit) * 100f) : 0;
        TextView valTv = UiKit.text(ctx,
                fmtTokens(tokens) + "  ·  " + pct + "%", 12, R.color.text_primary, true);
        valTv.setGravity(Gravity.END);
        top.addView(valTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        block.addView(top);

        // Тонкий прогрессбар: серый трек + заполнение
        android.widget.ProgressBar pb = new android.widget.ProgressBar(ctx,
                null, android.R.attr.progressBarStyleHorizontal);
        pb.setMax(100);
        pb.setProgress(pct);
        pb.getProgressDrawable().setColorFilter(
                pct >= 90 ? ContextCompat.getColor(ctx, R.color.error)
                        : pct >= 70 ? ContextCompat.getColor(ctx, R.color.accent)
                        : ContextCompat.getColor(ctx, R.color.text_secondary),
                android.graphics.PorterDuff.Mode.SRC_IN);
        block.addView(pb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(ctx, 4)));

        return block;
    }

    // ─────────── категории ───────────

    private static String[] buildCategoryOrder(List<ModelInfo> models) {
        List<String> out = new ArrayList<>();
        out.add(ModelInfo.CAT_ALL);
        if (any(models, ModelInfo.CAT_FREE)) out.add(ModelInfo.CAT_FREE);
        if (any(models, ModelInfo.CAT_OPENAI)) out.add(ModelInfo.CAT_OPENAI);
        if (any(models, ModelInfo.CAT_ANTHROPIC)) out.add(ModelInfo.CAT_ANTHROPIC);
        if (any(models, ModelInfo.CAT_GOOGLE)) out.add(ModelInfo.CAT_GOOGLE);
        if (any(models, ModelInfo.CAT_CHINESE)) out.add(ModelInfo.CAT_CHINESE);
        if (any(models, ModelInfo.CAT_OTHER)) out.add(ModelInfo.CAT_OTHER);
        return out.toArray(new String[0]);
    }

    private static boolean any(List<ModelInfo> models, String cat) {
        if (ModelInfo.CAT_FREE.equals(cat)) {
            for (ModelInfo m : models) if (m.free) return true;
            return false;
        }
        for (ModelInfo m : models) if (m.category.equals(cat)) return true;
        return false;
    }

    private static int countInCat(List<ModelInfo> models, String cat) {
        if (ModelInfo.CAT_FREE.equals(cat)) {
            int n = 0;
            for (ModelInfo m : models) if (m.free) n++;
            return n;
        }
        if (ModelInfo.CAT_ALL.equals(cat)) return models.size();
        int n = 0;
        for (ModelInfo m : models) if (m.category.equals(cat)) n++;
        return n;
    }

    private static String categoryLabel(Context ctx, String cat, int count) {
        String name;
        switch (cat) {
            case ModelInfo.CAT_FREE:     name = ctx.getString(R.string.mc_tab_free); break;
            case ModelInfo.CAT_OPENAI:   name = ctx.getString(R.string.mc_tab_openai); break;
            case ModelInfo.CAT_ANTHROPIC:name = ctx.getString(R.string.mc_tab_anthropic); break;
            case ModelInfo.CAT_GOOGLE:   name = ctx.getString(R.string.mc_tab_google); break;
            case ModelInfo.CAT_CHINESE:  name = ctx.getString(R.string.mc_tab_chinese); break;
            case ModelInfo.CAT_OTHER:    name = ctx.getString(R.string.mc_tab_other); break;
            default:                     name = ctx.getString(R.string.mc_tab_all); break;
        }
        return name + " " + count;
    }

    /** Локализованная подпись effort-уровня (none→Никакой и т.д.). */
    private static String effortLabel(Context ctx, String level) {
        switch (level) {
            case "none":   return ctx.getString(R.string.mc_effort_none);
            case "low":    return ctx.getString(R.string.mc_effort_low);
            case "medium": return ctx.getString(R.string.mc_effort_medium);
            case "high":   return ctx.getString(R.string.mc_effort_high);
            case "max":    return ctx.getString(R.string.mc_effort_max);
            default:       return level;
        }
    }

    // ─────────── мелочи ───────────

    private static GradientDrawable tabBg(Context ctx, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(UiKit.dp(ctx, 20));
        if (active) {
            int accent = ContextCompat.getColor(ctx, R.color.accent);
            g.setColor((accent & 0x00FFFFFF) | 0x2E000000);
            g.setStroke(1, (accent & 0x00FFFFFF) | 0x59000000);
        } else {
            g.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        }
        return g;
    }

    private static GradientDrawable chipBg(Context ctx, boolean active) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(UiKit.dp(ctx, 18));
        if (active) {
            int accent = ContextCompat.getColor(ctx, R.color.accent);
            g.setColor((accent & 0x00FFFFFF) | 0x2E000000);
            g.setStroke(1, (accent & 0x00FFFFFF) | 0x73000000);
        } else {
            g.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        }
        return g;
    }

    private static String trim(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.valueOf(Math.round(d * 100) / 100.0);
    }

    private static String fmtTokens(long v) {
        if (v >= 1_000_000) {
            double mm = v / 1_000_000.0;
            return (mm == Math.floor(mm) ? String.valueOf((long) mm)
                    : String.valueOf(Math.round(mm * 10) / 10.0)) + "M";
        }
        if (v >= 1000) return Math.round(v / 1000.0) + "K";
        return String.valueOf(v);
    }

    /** Публичный формат токенов для тултипов вне каталога. */
    public static String fmtTokensStatic(long v) { return fmtTokens(v); }

    /** CSV модальностей («text,image») → локализованный список («Текст, Изображение»). */
    private static String localizedModalities(Context ctx, String csv) {
        String source = csv == null || csv.isEmpty() ? "text" : csv;
        StringBuilder sb = new StringBuilder();
        for (String part : source.split(",")) {
            String key = part.trim().toLowerCase(java.util.Locale.ROOT);
            String localized;
            switch (key) {
                case "image": localized = ctx.getString(R.string.mc_modality_image); break;
                case "audio": localized = ctx.getString(R.string.mc_modality_audio); break;
                case "video": localized = ctx.getString(R.string.mc_modality_video); break;
                case "text":
                default:      localized = ctx.getString(R.string.mc_modality_text); break;
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(localized);
        }
        return sb.toString();
    }

    private static String joinLevels(List<String> levels) {
        StringBuilder sb = new StringBuilder();
        for (String l : levels) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(l);
        }
        return sb.toString();
    }
}
