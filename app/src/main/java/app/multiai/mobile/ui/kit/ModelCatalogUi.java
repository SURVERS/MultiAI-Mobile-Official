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
import app.multiai.mobile.models.CustomProvider;
import app.multiai.mobile.models.ModelInfo;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.CustomProviderStore;

/**
 * Каталог моделей: bottom sheet с дропдауном источника (MultiAI — всегда первый,
 * дальше кастомные провайдеры юзера), категориями-табами (для MultiAI),
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

        // ── Источники моделей: MultiAI всегда первый, дальше провайдеры юзера ──
        final List<CustomProvider> providers = CustomProviderStore.getAll(ctx);
        // Выбранный источник: null = MultiAI, иначе provider.id
        final String[] activeSource = {null};
        // Если текущая модель — кастомная, открываем шит сразу на её провайдере
        if (currentId != null) {
            for (CustomProvider p : providers) {
                for (CustomProvider.ProviderModel pm : p.models) {
                    if (pm.requestName != null
                            && CustomProviderStore.buildModelId(p.id, pm.requestName).equals(currentId)) {
                        activeSource[0] = p.id;
                        break;
                    }
                }
                if (activeSource[0] != null) break;
            }
        }
        // Иначе — восстанавливаем последний выбранный источник (если провайдер ещё существует)
        if (activeSource[0] == null) {
            String last = CustomProviderStore.lastPickerSource(ctx);
            if (last != null) {
                for (CustomProvider p : providers) {
                    if (last.equals(p.id)) { activeSource[0] = p.id; break; }
                }
            }
        }

        // Строка: дропдаун источника слева + кнопка "+" справа
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(UiKit.dp(ctx, 20), UiKit.dp(ctx, 14),
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 2));

        // ── Дропдаун источника (пилюля: иконка + имя + chevron) ──
        final LinearLayout sourcePill = new LinearLayout(ctx);
        sourcePill.setOrientation(LinearLayout.HORIZONTAL);
        sourcePill.setGravity(Gravity.CENTER_VERTICAL);
        sourcePill.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 8),
                UiKit.dp(ctx, 10), UiKit.dp(ctx, 8));
        sourcePill.setClickable(true);
        sourcePill.setFocusable(true);

        final ImageView sourceIcon = new ImageView(ctx);
        sourceIcon.setLayoutParams(new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 20), UiKit.dp(ctx, 20)));
        sourcePill.addView(sourceIcon);

        final TextView sourceName = UiKit.text(ctx, "", 15, R.color.text_primary, true);
        sourceName.setPadding(UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 4), 0);
        sourceName.setSingleLine(true);
        sourceName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        sourcePill.addView(sourceName);

        final ImageView sourceChevron = new ImageView(ctx);
        sourceChevron.setImageResource(R.drawable.ic_chevron_down);
        sourceChevron.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary));
        sourceChevron.setLayoutParams(new LinearLayout.LayoutParams(
                UiKit.dp(ctx, 16), UiKit.dp(ctx, 16)));
        sourcePill.addView(sourceChevron);

        // Пилюля занимает доступную ширину, но с зазором 12dp до "+".
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pillLp.setMarginEnd(UiKit.dp(ctx, 12));
        titleRow.addView(sourcePill, pillLp);

        // Кнопка "+" справа — открывает экран кастомных провайдеров
        android.widget.ImageView addProviderBtn = new android.widget.ImageView(ctx);
        addProviderBtn.setImageResource(R.drawable.ic_plus);
        addProviderBtn.setColorFilter(ContextCompat.getColor(ctx, R.color.text_secondary));
        android.graphics.drawable.GradientDrawable addBtnBg = new android.graphics.drawable.GradientDrawable();
        addBtnBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        addBtnBg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        addProviderBtn.setBackground(addBtnBg);
        int addBtnSize = UiKit.dp(ctx, 32);
        int addBtnPad = UiKit.dp(ctx, 7);
        addProviderBtn.setPadding(addBtnPad, addBtnPad, addBtnPad, addBtnPad);
        addProviderBtn.setClickable(true);
        addProviderBtn.setFocusable(true);
        addProviderBtn.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            try {
                android.content.Intent intent = new android.content.Intent(
                        ctx, app.multiai.mobile.ui.CustomProviderActivity.class);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
            } catch (Exception ignored) {}
        });
        titleRow.addView(addProviderBtn, new LinearLayout.LayoutParams(addBtnSize, addBtnSize));
        sheet.addView(titleRow);

        // Модели MultiAI и кастомных провайдеров — отдельно, таб "Мои" убран:
        // переключение источника идёт через дропдаун в шапке.
        final List<ModelInfo> multiaiModels = new ArrayList<>(models);
        final List<ModelInfo> customModels = customProviderModels(ctx);

        // Табы категорий (горизонтальный скролл) — только для источника MultiAI
        HorizontalScrollView tabsScroll = new HorizontalScrollView(ctx);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        final LinearLayout tabs = new LinearLayout(ctx);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        int tp = UiKit.dp(ctx, 20);
        tabs.setPadding(tp, UiKit.dp(ctx, 10), tp, UiKit.dp(ctx, 12));
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

        // ── Рендер выбранного источника: табы (MultiAI) или плоский список (провайдер) ──
        final List<TextView> tabViews = new ArrayList<>();
        final Runnable[] renderSource = new Runnable[1];
        renderSource[0] = () -> {
            tabs.removeAllViews();
            tabViews.clear();
            list.removeAllViews();

            final boolean multiai = activeSource[0] == null;
            tabsScroll.setVisibility(View.VISIBLE); // категории видны у ОБОИХ источников
            applySourcePillState(ctx, sourcePill, sourceIcon, sourceName,
                    activeSource[0], providers);

            if (!multiai) {
                // Кастомный провайдер — табы категорий по его моделям (как у MultiAI),
                // плоский список только когда выбрано "Все".
                String pid = activeSource[0];
                List<ModelInfo> providerModels = new ArrayList<>();
                for (ModelInfo m : customModels) {
                    if (m instanceof CustomModelInfo
                            && pid.equals(((CustomModelInfo) m).providerId)) {
                        providerModels.add(m);
                    }
                }
                if (providerModels.isEmpty()) {
                    tabs.removeAllViews();
                    tabViews.clear();
                    TextView empty = UiKit.text(ctx,
                            ctx.getString(R.string.custom_provider_reload_empty),
                            13, R.color.text_hint, false);
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(0, UiKit.dp(ctx, 32), 0, UiKit.dp(ctx, 32));
                    list.addView(empty, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
                    animateListIn(list);
                    return;
                }
                renderCategoryTabs(ctx, providerModels, tabs, tabViews, list, listScroll,
                        currentId, currentEffort, ref, listener);
                return;
            }

            // MultiAI — ВСЕ категории всегда видимы (не пропадают при смене
            // провайдера); пустые приглушены и не кликабельны.
            renderCategoryTabs(ctx, multiaiModels, tabs, tabViews, list, listScroll,
                    currentId, currentEffort, ref, listener);
        };

        // Клик по пилюле — дропдаун источников
        sourcePill.setOnClickListener(v ->
                showSourceDropdown(ctx, sourcePill, sourceIcon, sourceName, sourceChevron,
                        activeSource, providers, () -> renderSource[0].run()));

        renderSource[0].run();

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
        // OpenRouter alias-prefix "~z-ai/glm-flash-latest" ломал матчинг иконок.
        if (s.startsWith("~")) s = s.substring(1);
        if (s.startsWith("ma-")) s = s.substring(3);

        // Usage history может вернуть provider/model или provider:ma-model.
        // Сопоставляем и полный ID, и нормализованный хвост.
        int separator = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String tail = separator >= 0 && separator + 1 < s.length()
                ? s.substring(separator + 1) : s;
        if (tail.startsWith("ma-")) tail = tail.substring(3);
        String match = s + " " + tail;
        String vendor = s.contains("/") ? s.substring(0, s.indexOf('/')) : "";
        switch (vendor) {
            case "anthropic": return R.drawable.ic_model_claude;
            case "google": return s.contains("gemma") ? R.drawable.ic_model_gemma : R.drawable.ic_model_gemini;
            case "openai": return R.drawable.ic_model_openai;
            case "x-ai": return R.drawable.ic_model_grok;
            case "deepseek": return R.drawable.ic_model_deepseek;
            case "qwen": return R.drawable.ic_model_qwen;
            case "z-ai": return R.drawable.ic_model_glm;
            case "moonshotai": return R.drawable.ic_model_kimi;
            case "minimax": return R.drawable.ic_model_minimax;
            case "mistralai": return R.drawable.ic_model_mistral;
            case "meta": case "meta-llama": return R.drawable.ic_model_meta;
            case "nvidia": return R.drawable.ic_model_nvidia;
            case "openrouter": return R.drawable.ic_model_openrouter;
            case "perplexity": return R.drawable.ic_model_perplexity;
            case "stepfun": return R.drawable.ic_model_stepfun;
            case "upstage": return R.drawable.ic_model_upstage;
            case "rekaai": return R.drawable.ic_model_rekaai;
            case "writer": return R.drawable.ic_model_writer;
            case "sakana": return R.drawable.ic_model_sakana;
            case "dots-studio": return R.drawable.ic_model_dots;
            case "nex-agi": return R.drawable.ic_model_nex;
            case "perceptron": return R.drawable.ic_model_perceptron;
            case "thinkingmachines": return R.drawable.ic_model_thinkingmachines;
            case "thedrummer": return R.drawable.ic_model_drummer;
            case "cognitivecomputations": return R.drawable.ic_model_cognitive;
            case "sao10k": return R.drawable.ic_model_sao10k;
            case "anthracite-org": return R.drawable.ic_model_anthracite;
            case "mancer": return R.drawable.ic_model_mancer;
            case "undi95": return R.drawable.ic_model_undi95;
            case "gryphe": return R.drawable.ic_model_gryphe;
            case "aion-labs": return R.drawable.ic_model_aionlabs;
            case "amazon": return R.drawable.ic_model_aws;
            case "arcee-ai": return R.drawable.ic_model_arcee;
            case "baidu": return R.drawable.ic_model_baidu;
            case "bytedance": case "bytedance-seed": return R.drawable.ic_model_bytedance;
            case "cohere": return R.drawable.ic_model_cohere;
            case "ibm-granite": return R.drawable.ic_model_ibm;
            case "inception": return R.drawable.ic_model_inception;
            case "inclusionai": return R.drawable.ic_model_antgroup;
            case "kwaipilot": return R.drawable.ic_model_kwaipilot;
            case "liquid": return R.drawable.ic_model_liquid;
            case "meituan": return R.drawable.ic_model_meituan;
            case "microsoft": return R.drawable.ic_model_microsoft;
            case "morph": return R.drawable.ic_model_morph;
            case "nousresearch": return R.drawable.ic_model_nousresearch;
            case "poolside": return R.drawable.ic_model_poolside;
            case "relace": return R.drawable.ic_model_relace;
            case "xiaomi": return R.drawable.ic_model_xiaomi;
            case "tencent": return R.drawable.ic_model_hy3_free;
        }

        if (match.contains("big-pickle")) return R.drawable.ic_model_big_pickle;
        if (tail.equals("x-preview") || tail.startsWith("x-preview-")) return R.drawable.ic_model_big_pickle;
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
        // Все Hunyuan-модели (hy3, hy4-preview, "Hunyuan *") — иконка Tencent.
        if (match.matches(".*hy\\d.*") || match.contains("hunyuan")) {
            return R.drawable.ic_model_hy3_free;
        }
        if (match.contains("muse-spark") || match.contains("muse_spark")) {
            return R.drawable.ic_model_muse_spark;
        }
        if (match.contains("meta") || match.contains("llama")) return R.drawable.ic_model_meta;
        // MiniCPM (OpenBMB, id без префикса у AMD Radeon) и BGE (BAAI, freeinference).
        if (match.contains("minicpm")) return R.drawable.ic_model_openbmb;
        if (match.contains("bge") || match.contains("baai")) return R.drawable.ic_model_baai;
        if (match.contains("z-ai") || match.contains("zhipu")) return R.drawable.ic_model_glm;
        if (match.contains("moonshot")) return R.drawable.ic_model_moonshot;
        if (match.contains("openrouter")) return R.drawable.ic_model_openrouter;
        if (match.contains("ollama")) return R.drawable.ic_model_ollama;
        if (match.contains("upstage") || match.contains("solar")) return R.drawable.ic_model_upstage;
        if (match.contains("coze")) return R.drawable.ic_model_coze;
        if (match.contains("doubao")) return R.drawable.ic_model_doubao;
        if (match.contains("baichuan")) return R.drawable.ic_model_baichuan;
        if (match.contains("nvidia")) return R.drawable.ic_model_nvidia;
        if (match.contains("groq")) return R.drawable.ic_model_groq;
        if (match.contains("fireworks")) return R.drawable.ic_model_fireworks;
        if (match.contains("together")) return R.drawable.ic_model_together;
        if (match.contains("huggingface")) return R.drawable.ic_model_huggingface;
        if (match.contains("perplexity") || match.contains("sonar")) return R.drawable.ic_model_perplexity;
        if (match.contains("stepfun") || tail.startsWith("step-")) return R.drawable.ic_model_stepfun;
        if (match.contains("siliconcloud")) return R.drawable.ic_model_siliconcloud;
        if (match.contains("modelscope")) return R.drawable.ic_model_modelscope;
        if (match.contains("sense")) return R.drawable.ic_model_sensenova;
        if (match.contains("volcengine") || match.contains("volc-")) return R.drawable.ic_model_volcengine;
        return null;
    }

    /** Dark chip colour for near-white logos on a light surface. */
    private static final int LIGHT_THEME_CHIP = 0xFF1B1B1F;

    /**
     * Brand logos drawn in near-white ink (#EDEDED…#fff). They are invisible on a
     * light surface, so in the light theme they get a dark circular chip instead of
     * being tinted — tinting would destroy the second colour of two-tone marks.
     */
    private static boolean logoNeedsDarkChip(int iconRes) {
        return iconRes == R.drawable.ic_model_gemma
                || iconRes == R.drawable.ic_model_grok
                || iconRes == R.drawable.ic_model_kimi
                || iconRes == R.drawable.ic_model_minimax
                || iconRes == R.drawable.ic_model_mistral
                || iconRes == R.drawable.ic_model_big_pickle
                || iconRes == R.drawable.ic_model_openrouter
                || iconRes == R.drawable.ic_model_ollama
                || iconRes == R.drawable.ic_model_moonshot
                || iconRes == R.drawable.ic_model_together
                || iconRes == R.drawable.ic_model_stepfun
                || iconRes == R.drawable.ic_model_groq
                || iconRes == R.drawable.ic_model_inception
                || iconRes == R.drawable.ic_model_liquid
                || iconRes == R.drawable.ic_model_nousresearch
                || iconRes == R.drawable.ic_model_relace
                || iconRes == R.drawable.ic_model_ibm
                // BAAI — near-white ink (lobe), в светлой теме нужен тёмный чип.
                || iconRes == R.drawable.ic_model_baai;
    }

    /**
     * Keeps a model logo readable on the current surface. Call after
     * {@code setImageResource(iconRes)}; a no-op in the dark theme and for logos
     * that already carry their own contrast.
     */
    public static void applyLogoContrast(@NonNull Context ctx, @NonNull ImageView view, int iconRes) {
        if (AccentColor.isDark(ctx) || !logoNeedsDarkChip(iconRes)) return;
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.OVAL);
        chip.setColor(LIGHT_THEME_CHIP);
        view.setBackground(chip);
        int pad = UiKit.dp(ctx, 4);
        view.setPadding(pad, pad, pad, pad);
    }

    /** Иконка модели 28dp; если ресурса нет — кружок с первой буквой. */
    private static View modelIcon(Context ctx, ModelInfo m) {
        Integer res = iconFor(m instanceof CustomModelInfo
                ? ((CustomModelInfo) m).requestName : m.id);
        if (res != null) {
            ImageView iv = new ImageView(ctx);
            iv.setImageResource(res);
            applyLogoContrast(ctx, iv, res);
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

    /**
     * Табы категорий + список для любого источника (MultiAI или кастомный провайдер).
     * Все категории всегда видимы; пустые приглушены и не кликабельны.
     * Активный таб — всегда "Все": пикер открывается на полном каталоге.
     */
    private static void renderCategoryTabs(Context ctx, List<ModelInfo> models,
                                           LinearLayout tabs, List<TextView> tabViews,
                                           LinearLayout list, ScrollView listScroll,
                                           String currentId, String currentEffort,
                                           Dialog[] ref, OnModelPicked listener) {
        tabs.removeAllViews();
        tabViews.clear();
        list.removeAllViews();
        final String[] order = buildCategoryOrderAll();
        for (String cat : order) {
            final int count = countInCat(models, cat);
            final boolean hasModels = count > 0 || ModelInfo.CAT_ALL.equals(cat);
            TextView tab = UiKit.text(ctx, categoryLabel(ctx, cat, count),
                    13, R.color.text_secondary, false);
            tab.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 7),
                    UiKit.dp(ctx, 12), UiKit.dp(ctx, 7));
            tab.setBackground(tabBg(ctx, false));
            if (hasModels) {
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
                    animateListIn(list);
                });
            } else {
                tab.setAlpha(0.35f);
            }
            tabs.addView(tab);
            LinearLayout.LayoutParams tabLp = (LinearLayout.LayoutParams) tab.getLayoutParams();
            tabLp.setMarginEnd(UiKit.dp(ctx, 6));
            tabViews.add(tab);
        }
        // Активный таб — всегда "Все". Раньше таб ставился по категории текущей
        // модели, и пикер при китайской модели (GLM, Qwen, DeepSeek…) открывался
        // сразу на "Китайские" вместо полного списка.
        boolean clicked = false;
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(ModelInfo.CAT_ALL) && tabViews.get(i).isClickable()) {
                tabViews.get(i).performClick(); clicked = true; break;
            }
        }
        if (!clicked && !tabViews.isEmpty()) tabViews.get(0).performClick();
    }

    private static void populate(Context ctx, List<ModelInfo> models, String cat,
                                 LinearLayout list, String currentId, String currentEffort,
                                 Dialog[] sheetRef, OnModelPicked listener) {
        list.removeAllViews();
        for (ModelInfo m : models) {
            if ("embedding".equals(m.modelKind)
                    || app.multiai.mobile.network.CustomProviderClient.isEmbeddingModelId(m.id)) continue;
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
        else if (m instanceof CustomModelInfo && ((CustomModelInfo) m).hasPricing())
            meta.append("$").append(trim(m.inputMultiplier));
        else if (m.inputMultiplier > 0) meta.append("×").append(trim(m.inputMultiplier));
        if ("embedding".equals(m.modelKind)) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(ctx.getString(R.string.mc_kind_embedding));
        }
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
        // Подзаголовок: для кастомных — имя провайдера + requestName (без custom/uuid),
        // для MultiAI — обычный id каталога.
        String subText;
        if (m instanceof CustomModelInfo) {
            CustomModelInfo cm = (CustomModelInfo) m;
            String providerLabel = cm.providerLabel();
            subText = providerLabel + " · " + cm.requestName;
        } else {
            subText = m.id + (m.free ? " · FREE" : "");
        }
        TextView sub = UiKit.text(ctx, subText, 12, R.color.text_hint, false);
        nameCol.addView(sub);
        head.addView(nameCol);
        body.addView(head);

        // Характеристики
        Map<String, String> stats = new LinkedHashMap<>();
        if (m.contextWindow > 0)
            stats.put(ctx.getString(R.string.mc_stat_context), fmtTokens(m.contextWindow) + " " + ctx.getString(R.string.mc_tokens_unit));
        if (m.maxOutputTokens > 0)
            stats.put(ctx.getString(R.string.mc_stat_output), fmtTokens(m.maxOutputTokens) + " " + ctx.getString(R.string.mc_tokens_unit));
        // Цена: для кастомных провайдеров сервер отдаёт $ за 1M токенов (как на PC),
        // для MultiAI — мультипликатор ×. Различаем по типу модели.
        boolean custom = m instanceof CustomModelInfo;
        boolean showPricing = !custom || ((CustomModelInfo) m).hasPricing();
        String cur = custom ? "$" : "×";
        if (showPricing && (custom || m.inputMultiplier > 0))
            stats.put(ctx.getString(R.string.mc_stat_input), cur + trim(m.inputMultiplier));
        if (showPricing && (custom || m.outputMultiplier > 0))
            stats.put(ctx.getString(R.string.mc_stat_output_mult), cur + trim(m.outputMultiplier));
        if (showPricing && m.cachedInputMultiplier > 0)
            stats.put(ctx.getString(R.string.mc_stat_cached), cur + trim(m.cachedInputMultiplier));
        stats.put(ctx.getString(R.string.mc_stat_modalities), localizedModalities(ctx, m.inputModalities));
        if (!"text".equals(m.modelKind)) {
            // "embedding" локализуем, редкие типы показываем как отдал сервер.
            String kindLabel = "embedding".equals(m.modelKind)
                    ? ctx.getString(R.string.mc_kind_embedding) : m.modelKind;
            stats.put(ctx.getString(R.string.mc_stat_kind), kindLabel);
        }
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

        // ── Image models (gpt-image-2 / nano-banana): формат и качество, как
        //    в ПК-версии. Выбор сразу сохраняется в настройках. ──
        if (app.multiai.mobile.utils.ImageGenOptions.isImageModel(m.id)) {
            TextView fmtTitle = UiKit.text(ctx, ctx.getString(R.string.mc_image_format), 13, R.color.text_secondary, true);
            fmtTitle.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 6));
            body.addView(fmtTitle);
            final String[] selectedSize = {app.multiai.mobile.utils.ImageGenOptions.savedSize(ctx, m.id)};
            body.addView(optionChips(ctx,
                    app.multiai.mobile.utils.ImageGenOptions.sizes(),
                    app.multiai.mobile.utils.ImageGenOptions.sizeLabels(),
                    selectedSize,
                    v -> app.multiai.mobile.utils.ImageGenOptions.saveSize(ctx, v)));

            TextView qTitle = UiKit.text(ctx, ctx.getString(R.string.mc_image_quality), 13, R.color.text_secondary, true);
            qTitle.setPadding(0, UiKit.dp(ctx, 14), 0, UiKit.dp(ctx, 6));
            body.addView(qTitle);
            List<String> qualities = app.multiai.mobile.utils.ImageGenOptions.qualitiesFor(m.id);
            List<String> qualityLabels = new ArrayList<>();
            for (String q : qualities) {
                qualityLabels.add(ctx.getString(app.multiai.mobile.utils.ImageGenOptions.qualityLabelRes(q)));
            }
            final String[] selectedQuality = {app.multiai.mobile.utils.ImageGenOptions.savedQuality(ctx, m.id)};
            body.addView(optionChips(ctx, qualities, qualityLabels, selectedQuality,
                    v -> app.multiai.mobile.utils.ImageGenOptions.saveQuality(ctx, v)));
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

    /** Однострочный выбор опции чипами: тап мгновенно подсвечивает и отдаёт значение. */
    private static LinearLayout optionChips(Context ctx, List<String> values, List<String> labels,
                                            final String[] selected,
                                            final java.util.function.Consumer<String> onChange) {
        final List<TextView> chipViews = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            final String val = values.get(i);
            TextView chip = UiKit.text(ctx, labels.get(i), 12, R.color.text_secondary, false);
            chip.setSingleLine(true);
            boolean on = val.equals(selected[0]);
            chip.setBackground(chipBg(ctx, on));
            if (on) chip.setTextColor(ContextCompat.getColor(ctx, R.color.accent));
            chip.setPadding(UiKit.dp(ctx, 14), UiKit.dp(ctx, 7), UiKit.dp(ctx, 14), UiKit.dp(ctx, 7));
            chip.setClickable(true);
            chipViews.add(chip);
        }
        for (int i = 0; i < chipViews.size(); i++) {
            final int index = i;
            chipViews.get(i).setOnClickListener(v -> {
                selected[0] = values.get(index);
                for (int j = 0; j < chipViews.size(); j++) {
                    boolean active = values.get(j).equals(selected[0]);
                    chipViews.get(j).setBackground(chipBg(ctx, active));
                    chipViews.get(j).setTextColor(ContextCompat.getColor(ctx,
                            active ? R.color.accent : R.color.text_secondary));
                }
                onChange.accept(selected[0]);
            });
        }
        return chipRows(ctx, chipViews, UiKit.dp(ctx, 6));
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

    /** Собирает ModelInfo-обёртки для моделей кастомных провайдеров. */
    private static List<ModelInfo> customProviderModels(Context ctx) {
        List<ModelInfo> result = new ArrayList<>();
        List<app.multiai.mobile.models.CustomProvider> providers =
                CustomProviderStore.getAll(ctx);
        for (app.multiai.mobile.models.CustomProvider provider : providers) {
            for (app.multiai.mobile.models.CustomProvider.ProviderModel pm : provider.models) {
                if (pm.requestName == null || pm.requestName.isEmpty()) continue;
                org.json.JSONObject fake = new org.json.JSONObject();
                try {
                    fake.put("id", CustomProviderStore.buildModelId(provider.id, pm.requestName));
                    // Прокидываем реальные метаданные провайдера в карточку модели
                    // (context window, вывод, модальности) — как каталог MultiAI.
                    if (pm.displayName != null && !pm.displayName.isEmpty())
                        fake.put("display_name", pm.displayName);
                    if (pm.contextWindow > 0) fake.put("context_window", pm.contextWindow);
                    if (pm.maxOutputTokens > 0) fake.put("max_output_tokens", pm.maxOutputTokens);
                    if (pm.inputModalities != null && !pm.inputModalities.isEmpty()) {
                        // ModelInfo ждёт JSONArray, а не CSV-строку.
                        org.json.JSONArray mods = new org.json.JSONArray();
                        for (String part : pm.inputModalities.split(",")) {
                            String t = part.trim();
                            if (!t.isEmpty()) mods.put(t);
                        }
                        fake.put("input_modalities", mods);
                    }
                    // Уровни размышления → блок reasoning, как у каталога MultiAI.
                    if (pm.reasoningLevels != null && !pm.reasoningLevels.isEmpty()) {
                        org.json.JSONArray levels = new org.json.JSONArray();
                        for (String part : pm.reasoningLevels.split(",")) {
                            String t = part.trim();
                            if (!t.isEmpty()) levels.put(t);
                        }
                        if (levels.length() > 0) {
                            org.json.JSONObject reasoning = new org.json.JSONObject();
                            reasoning.put("kind", "levels");
                            reasoning.put("levels", levels);
                            if (pm.reasoningDefault != null && !pm.reasoningDefault.isEmpty())
                                reasoning.put("default", pm.reasoningDefault);
                            fake.put("reasoning", reasoning);
                        }
                    }
                    if (pm.hasPricing) {
                        fake.put("input_multiplier", pm.priceInput);
                        fake.put("output_multiplier", pm.priceOutput);
                        fake.put("cached_input_multiplier", pm.priceCached);
                    }
                    if (pm.free) fake.put("free", true);
                    // Эмбеддинг-модели (bge-m3) — тип виден в карточке и строке пикера.
                    if (pm.kind != null && !pm.kind.isEmpty() && !"text".equals(pm.kind))
                        fake.put("model_kind", pm.kind);
                } catch (Exception ignored) {}
                CustomModelInfo info = new CustomModelInfo(fake, provider, pm);
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Субкласс ModelInfo для кастомных провайдеров.
     * Показывается в плоском списке при выборе своего источника в дропдауне;
     * отображаемое имя = pm.displayName или requestName.
     */
    private static final class CustomModelInfo extends ModelInfo {
        final String providerId;
        final String requestName;
        private final String providerDisplayName;
        private final boolean pricingAvailable;

        CustomModelInfo(org.json.JSONObject fake,
                        CustomProvider provider,
                        CustomProvider.ProviderModel pm) {
            super(fake);
            providerId = provider.id;
            requestName = pm.requestName;
            providerDisplayName = provider.displayName;
            pricingAvailable = pm.hasPricing;
        }

        boolean hasPricing() { return pricingAvailable; }

        /** Имя провайдера для UI (displayName или метка paradigm как фолбэк). */
        String providerLabel() {
            return providerDisplayName == null || providerDisplayName.trim().isEmpty()
                    ? "Custom" : providerDisplayName.trim();
        }

        public boolean isCustom() { return true; }
    }

    /** Полный порядок категорий — все всегда, пустые гасятся в UI. */
    private static String[] buildCategoryOrderAll() {
        return new String[]{
                ModelInfo.CAT_ALL, ModelInfo.CAT_FREE, ModelInfo.CAT_OPENAI,
                ModelInfo.CAT_ANTHROPIC, ModelInfo.CAT_GOOGLE,
                ModelInfo.CAT_CHINESE, ModelInfo.CAT_OTHER
        };
    }

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
        for (ModelInfo m : models) if (cat.equals(m.category)) return true;
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
        for (ModelInfo m : models) if (cat.equals(m.category)) n++;
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

    // ─────────── дропдаун источника ───────────

    /** Обновляет пилюлю источника в шапке: иконка + имя MultiAI или провайдера. */
    private static void applySourcePillState(Context ctx, LinearLayout pill,
                                             ImageView icon, TextView name,
                                             String providerId, List<CustomProvider> providers) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(ctx, 18));
        bg.setColor(ContextCompat.getColor(ctx, R.color.surface_tonal));
        pill.setBackground(bg);

        if (providerId == null) {
            // PNG-логотип MultiAI уже тематический (drawable-night vs drawable) —
            // НЕ тонируем, иначе светлая иконка в тёмной теме станет цветной кляксой.
            icon.setImageResource(R.drawable.ic_bot_avatar_mono);
            icon.clearColorFilter();
            icon.setBackground(null);
            name.setText(ctx.getString(R.string.mc_source_multiai));
            return;
        }
        for (CustomProvider p : providers) {
            if (providerId.equals(p.id)) {
                String label = p.displayName == null || p.displayName.isEmpty()
                        ? p.paradigmLabel() : p.displayName;
                // Favicon сайта; если сайт его не отдаёт (SPA) — кружок с буквой провайдера.
                app.multiai.mobile.utils.FaviconLoader.into(ctx, icon, p.baseURL,
                        R.drawable.ic_provider_plug, R.color.text_secondary, label);
                icon.setBackground(null);
                name.setText(label);
                return;
            }
        }
        // Провайдер удалён, пока шит открыт — откатываемся на MultiAI
        icon.setImageResource(R.drawable.ic_bot_avatar_mono);
        icon.clearColorFilter();
        name.setText(ctx.getString(R.string.mc_source_multiai));
    }

    /** Дропдаун источников: MultiAI первый, дальше провайдеры. Скролл, если не влезает. */
    private static void showSourceDropdown(Context ctx, View anchor,
                                           ImageView pillIcon, TextView pillName,
                                           ImageView pillChevron,
                                           final String[] activeSource,
                                           List<CustomProvider> providers,
                                           Runnable onChanged) {
        // Строим пункты: MultiAI + все провайдеры
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Boolean> multiaiFlag = new ArrayList<>();
        ids.add(null);
        labels.add(ctx.getString(R.string.mc_source_multiai));
        multiaiFlag.add(true);
        for (CustomProvider p : providers) {
            ids.add(p.id);
            labels.add(p.displayName == null || p.displayName.isEmpty()
                    ? p.paradigmLabel() : p.displayName);
            multiaiFlag.add(false);
        }

        LinearLayout menu = new LinearLayout(ctx);
        menu.setOrientation(LinearLayout.VERTICAL);
        int mPad = UiKit.dp(ctx, 6);
        menu.setPadding(mPad, mPad, mPad, mPad);

        for (int i = 0; i < ids.size(); i++) {
            final String id = ids.get(i);
            LinearLayout item = new LinearLayout(ctx);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(UiKit.dp(ctx, 12), UiKit.dp(ctx, 10),
                    UiKit.dp(ctx, 12), UiKit.dp(ctx, 10));
            item.setClickable(true);
            item.setFocusable(true);

            ImageView iv = new ImageView(ctx);
            if (multiaiFlag.get(i)) {
                iv.setImageResource(R.drawable.ic_bot_avatar_mono);
                iv.clearColorFilter();
            } else {
                // Favicon сайта провайдера; если не отдаёт — кружок с буквой.
                app.multiai.mobile.utils.FaviconLoader.into(ctx, iv,
                        providers.get(i - 1).baseURL,
                        R.drawable.ic_provider_plug, R.color.text_secondary, labels.get(i));
            }
            item.addView(iv, new LinearLayout.LayoutParams(
                    UiKit.dp(ctx, 20), UiKit.dp(ctx, 20)));

            boolean sel = id == null ? activeSource[0] == null : id.equals(activeSource[0]);
            TextView tv = UiKit.text(ctx, labels.get(i), 14,
                    sel ? R.color.accent : R.color.text_primary, sel);
            tv.setPadding(UiKit.dp(ctx, 12), 0, 0, 0);
            item.addView(tv, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (sel) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(ContextCompat.getColor(ctx, R.color.accent));
                item.addView(check, new LinearLayout.LayoutParams(
                        UiKit.dp(ctx, 18), UiKit.dp(ctx, 18)));
            }

            GradientDrawable itemBg = new GradientDrawable();
            itemBg.setCornerRadius(UiKit.dp(ctx, 10));
            itemBg.setColor(sel
                    ? (ContextCompat.getColor(ctx, R.color.accent) & 0x00FFFFFF) | 0x1F000000
                    : android.graphics.Color.TRANSPARENT);
            item.setBackground(itemBg);

            item.setOnClickListener(v -> {
                Object tag = v.getTag();
                if (!(tag instanceof AnimatedPopupWindow)) return;
                activeSource[0] = id;
                CustomProviderStore.saveLastPickerSource(ctx, id);
                ((AnimatedPopupWindow) tag).dismiss();
                onChanged.run();
            });
            menu.addView(item);
        }

        // Скролл-обёртка с макс-высотой (пагинация по скроллу при переполнении)
        ScrollView scroll = new ScrollView(ctx);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(menu);
        int maxH = (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.5f);

        FrameLayout container = new FrameLayout(ctx);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setCornerRadius(UiKit.dp(ctx, 16));
        menuBg.setColor(ContextCompat.getColor(ctx, R.color.surface_elevated));
        menuBg.setStroke(1, ContextCompat.getColor(ctx, R.color.stroke));
        container.setBackground(menuBg);
        int cPad = UiKit.dp(ctx, 4);
        container.setPadding(cPad, cPad, cPad, cPad);
        container.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.max(UiKit.dp(ctx, 220), anchor.getWidth());
        final AnimatedPopupWindow popup = new AnimatedPopupWindow(container,
                width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setElevation(UiKit.dp(ctx, 12));
        popup.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);

        // Тег на каждом пункте, чтобы onClick мог закрыть попап
        for (int i = 0; i < menu.getChildCount(); i++) {
            menu.getChildAt(i).setTag(popup);
        }

        // Вход-анимация: fade + лёгкий scale сверху
        container.setAlpha(0f);
        container.setScaleY(0.92f);
        container.setPivotY(0f);

        // Chevron крутится вверх пока попап открыт
        pillChevron.animate().rotation(180f).setDuration(MotionKit.FAST).start();
        popup.setOnDismissListener(() ->
                pillChevron.animate().rotation(0f).setDuration(MotionKit.FAST).start());

        popup.showAsDropDown(anchor, 0, UiKit.dp(ctx, 6));
        // Ограничение высоты после show — PopupWindow сам обмерит контент
        container.post(() -> {
            if (container.getHeight() > maxH) {
                popup.update(width, maxH);
            }
        });
        container.animate().alpha(1f).scaleY(1f)
                .setDuration(MotionKit.FAST)
                .setInterpolator(MotionKit.STANDARD_EASING)
                .start();
    }

    /** Лёгкий staggered fade-in карточек списка при смене источника/таба. */
    private static void animateListIn(LinearLayout list) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(UiKit.dp(list.getContext(), 8));
            child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(Math.min(i, 8) * 24L)
                    .setDuration(MotionKit.STANDARD)
                    .setInterpolator(MotionKit.STANDARD_EASING)
                    .start();
        }
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
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (String part : source.split(",")) {
            String key = part.trim().toLowerCase(java.util.Locale.ROOT);
            // Дедуп: слияние плоских input_modalities и architecture-версии могло
            // дать "text,image,text" → «Текст, изображение, текст.»
            if (key.isEmpty() || !seen.add(key)) continue;
            String localized;
            switch (key) {
                case "image": localized = ctx.getString(R.string.mc_modality_image); break;
                case "audio": localized = ctx.getString(R.string.mc_modality_audio); break;
                case "video": localized = ctx.getString(R.string.mc_modality_video); break;
                case "file": localized = ctx.getString(R.string.kit_file_file); break;
                case "text": localized = ctx.getString(R.string.mc_modality_text); break;
                default: localized = key; break;
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
