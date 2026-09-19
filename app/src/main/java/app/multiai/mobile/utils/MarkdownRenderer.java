package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.ReplacementSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Link;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.MarkwonSpansFactory;
import io.noties.markwon.RenderProps;
import io.noties.markwon.core.CoreProps;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.ext.tables.TablePlugin;
import io.noties.markwon.ext.tasklist.TaskListPlugin;
import io.noties.markwon.linkify.LinkifyPlugin;
import io.noties.markwon.syntax.Prism4jTheme;
import io.noties.markwon.ext.latex.JLatexMathPlugin;
import io.noties.markwon.syntax.SyntaxHighlightPlugin;
import io.noties.prism4j.Prism4j;
import io.noties.prism4j.Prism4j.Syntax;
import io.noties.prism4j.annotations.PrismBundle;

/**
 * Markdown рендерер на базе Markwon
 * Поддерживает ВСЕ элементы GFM + кастомные стили MultiAI
 * 
 * Фичи:
 * - Кодблоки с подсветкой синтаксиса (Prism4j)
 * - Кнопка копирования в кодблоках
 * - Номера строк в кодблоках
 * - Кликабельные ссылки
 * - Изображения inline
 * - Вложенные списки (bullet и numbered)
 * - HTML теги
 * - Таблицы
 * - Чек-листы с эмодзи
 * - Mermaid placeholder
 */
@PrismBundle(
    includeAll = true,
    grammarLocatorClassName = ".Prism4jGrammarLocator"
)
public class MarkdownRenderer {

    private static Markwon instance;
    private static Prism4j prism4j;
    // Signature of the colors + night-mode used to build the current instance,
    // so the singleton is rebuilt when the effective theme changes (light/dark
    // recreate or accent/token update). Without this the Markwon singleton would
    // keep the first theme's colors forever.
    private static String themeSignature = "";
    private static final android.util.LruCache<String, Spanned> renderedCache =
            new android.util.LruCache<>(24);
    /** Project-relative paths in normal prose (paths inside backticks are handled separately). */
    private static final Pattern PROJECT_PATH_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9_./-])(?:[A-Za-z0-9._-]+/)+[A-Za-z0-9._-]+(:[0-9]+)?");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`\\r\\n]+)`");

    /**
     * Compute a signature string capturing night-mode + all semantic code/syntax
     * token colors, so a stale singleton can be detected and rebuilt.
     */
    private static String computeThemeSignature(Context context) {
        boolean dark = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return (dark ? "d" : "l") + "|"
                + resolve(context, R.color.code_bg) + "|"
                + resolve(context, R.color.code_text) + "|"
                + resolve(context, R.color.syntax_comment) + "|"
                + resolve(context, R.color.syntax_string) + "|"
                + resolve(context, R.color.syntax_keyword) + "|"
                + resolve(context, R.color.syntax_literal) + "|"
                + resolve(context, R.color.syntax_number) + "|"
                + resolve(context, R.color.text_primary);
    }

    /**
     * Получить singleton instance Markwon
     */
    public static Markwon getInstance(Context context) {
        String signature = computeThemeSignature(context);
        if (instance == null || !signature.equals(themeSignature)) {
            // Use application context to avoid leaking the Activity that first
            // triggered singleton creation. Colors are resolved from the calling
            // context (theme-aware) and baked into spans/plugins at build time.
            Context appContext = context.getApplicationContext();
            final float latexTextSize = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP,
                    16f,
                    appContext.getResources().getDisplayMetrics());
            // Инициализируем Prism4j для подсветки синтаксиса
            if (prism4j == null) {
                prism4j = new Prism4j(new Prism4jGrammarLocator());
            }

            instance = Markwon.builder(appContext)
                // Кастомные span'ы для кликабельных ссылок (theme-aware link color)
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureSpansFactory(@NonNull MarkwonSpansFactory.Builder builder) {
                        builder.setFactory(Link.class, (configuration, props) -> {
                            return new ClickableLinkSpan(
                                CoreProps.LINK_DESTINATION.require(props),
                                resolve(context, R.color.syntax_keyword)
                            );
                        });
                    }
                })

                // Подсветка синтаксиса в кодблоках — theme-aware (light/dark tokens)
                .usePlugin(SyntaxHighlightPlugin.create(prism4j, createTheme(context)))

                // Зачеркнутый текст ~~text~~
                .usePlugin(StrikethroughPlugin.create())

                // Таблицы
                .usePlugin(TablePlugin.create(appContext))

                // Чек-листы - [ ] и - [x]
                .usePlugin(TaskListPlugin.create(appContext))

                // Автолинки (URL автоматически становятся кликабельными)
                .usePlugin(LinkifyPlugin.create())

                // LaTeX: $...$ (inline) и $$...$$ / \[...\] (блоки) → картинка.
                // Inline-LaTeX требует MarkwonInlineParserPlugin — без него
                // build() падает с "Requested plugin is not added".
                .usePlugin(io.noties.markwon.inlineparser.MarkwonInlineParserPlugin.create())
                .usePlugin(JLatexMathPlugin.create(JLatexMathPlugin.builder(latexTextSize)
                        .blocksEnabled(true)
                        .blocksLegacy(true)
                        .inlinesEnabled(true)
                        .build()))

                .build();
            themeSignature = signature;
            synchronized (renderedCache) {
                renderedCache.evictAll();
            }
        }
        return instance;
    }
    
    /**
     * Отрендерить markdown в LinearLayout (для поддержки кастомных кодблоков с кнопками)
     */
    public static void renderToLayout(Context context, LinearLayout container, String markdown) {
        container.removeAllViews();
        
        if (markdown == null || markdown.isEmpty()) {
            return;
        }
        
        // Препроцессинг
        markdown = preprocessMarkdown(markdown);

        // Парсим markdown на части: текст, кодблоки и Mermaid диаграммы
        java.util.List<ContentPart> parts = parseContentParts(markdown);

        for (int i = 0; i < parts.size(); i++) {
            ContentPart part = parts.get(i);
            if (part.isMath && part.isDisplayMath) {
                // Display-математика ($$..$$, \[..\]) — отдельный блок-картинка.
                View mathView = createMathView(context, part.content, true);
                container.addView(mathView);
            } else if (part.isMermaid) {
                // Mermaid-рендер на бэкенде удалён — показываем исходник как кодблок.
                View codeBlockView = createCodeBlockView(context, "mermaid", part.content);
                container.addView(codeBlockView);
            } else if (part.isCode) {
                // Кодблок с кнопкой копирования
                View codeBlockView = createCodeBlockView(context, part.language, part.content);
                container.addView(codeBlockView);
            } else {
                // Обычный текст + таблицы + inline-LaTeX ($...$ рисует Markwon).
                addTextAndTables(context, container, part.content);
            }
        }
    }

    /**
     * Тот же renderToLayout, но с защитой от невалидного LaTeX: если JLatexMath
     * падает на битой формуле, откатываемся на деградированный текст.
     */
    public static void renderToLayoutSafe(Context context, LinearLayout container, String markdown) {
        try {
            renderToLayout(context, container, markdown);
        } catch (Throwable t) {
            container.removeAllViews();
            try {
                renderToLayout(context, container, degradeLatex(markdown));
            } catch (Throwable ignored) {
                TextView fallback = new TextView(context);
                fallback.setTextColor(resolve(context, R.color.text_primary));
                fallback.setTextSize(16);
                fallback.setText(markdown);
                container.addView(fallback);
            }
        }
    }
    
    /**
     * Рендерит весь markdown в один TextView, который занимает контейнер целиком.
     * Это основной путь: bold/LaTeX/ссылки обрабатывает Markwon единым проходом,
     * так что инлайн-формулы вроде $x = 22$ и текст вроде **жирного** не
     * «теряются» между кастомными виджетами.
     *
     * @return true, если рендер удался; false — если Markwon упал на невалидном
     *         LaTeX и надо откатиться на постраничный рендер.
     */
    public static boolean containsMarkdownTable(String markdown) {
        return MarkdownTableParser.containsTable(markdown);
    }

    private static void addTextAndTables(Context context, LinearLayout container, String markdown) {
        java.util.List<MarkdownTableParser.Segment> segments = MarkdownTableParser.parse(markdown);
        if (segments.isEmpty()) return;
        for (MarkdownTableParser.Segment segment : segments) {
            if (segment.isTable()) {
                container.addView(createTableView(context, segment.table));
            } else {
                TextView textView = new TextView(context);
                textView.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                textView.setTextColor(resolve(context, R.color.text_primary));
                textView.setTextSize(16);
                textView.setLineSpacing(dpToPx(context, 4), 1f);
                render(context, textView, segment.text);
                container.addView(textView);
            }
        }
    }

    private static View createTableView(Context context, MarkdownTableParser.Table data) {
        LinearLayout shell = new LinearLayout(context);
        shell.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable shellBackground =
                new android.graphics.drawable.GradientDrawable();
        shellBackground.setColor(resolve(context, R.color.surface_card));
        shellBackground.setCornerRadius(dpToPx(context, 12));
        shellBackground.setStroke(Math.max(1, dpToPx(context, 1)), resolve(context, R.color.stroke));
        shell.setBackground(shellBackground);
        shell.setClipToOutline(true);
        LinearLayout.LayoutParams shellParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shellParams.setMargins(0, dpToPx(context, 10), 0, dpToPx(context, 10));
        shell.setLayoutParams(shellParams);

        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(true);
        scroll.setFillViewport(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        // Prevent parent RecyclerView from stealing horizontal scroll gestures.
        scroll.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                v.getParent().requestDisallowInterceptTouchEvent(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
            }
            return false;
        });
        android.widget.TableLayout table = new android.widget.TableLayout(context);
        table.setShrinkAllColumns(false);
        table.setStretchAllColumns(false);
        int viewportWidth = Math.max(dpToPx(context, 240),
                context.getResources().getDisplayMetrics().widthPixels - dpToPx(context, 40));
        int[] widths = tableColumnWidths(context, data, viewportWidth);
        int stroke = Math.max(1, dpToPx(context, 1));
        int lastRow = data.rows.size() - 1;
        table.addView(createTableRow(context, data.header, data.alignments, widths,
                true, lastRow < 0, stroke));
        for (int index = 0; index <= lastRow; index++) {
            table.addView(createTableRow(context, data.rows.get(index), data.alignments, widths,
                    false, index == lastRow, stroke));
        }
        scroll.addView(table, new android.widget.HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return shell;
    }

    private static android.widget.TableRow createTableRow(Context context, java.util.List<String> values,
                                                            java.util.List<MarkdownTableParser.Alignment> alignments,
                                                            int[] widths, boolean header,
                                                            boolean lastRow, int stroke) {
        android.widget.TableRow row = new android.widget.TableRow(context);
        row.setGravity(Gravity.TOP);
        int fill = resolve(context, header ? R.color.surface_tonal : R.color.surface_card);
        int gridLine = resolve(context, R.color.divider);
        for (int column = 0; column < widths.length; column++) {
            TextView cell = new TextView(context);
            String value = column < values.size() ? values.get(column) : "";
            // A cell holding only "---" / "___" / "***" is a leftover border
            // marker: Markwon would draw it as a thematic break, i.e. a stray
            // horizontal line inside the table.
            if (value.trim().matches("[-_*]{3,}")) value = "";
            cell.setTextColor(resolve(context, header ? R.color.text_primary : R.color.text_secondary));
            cell.setTextSize(header ? 14f : 13.5f);
            cell.setTypeface(Typeface.DEFAULT, header ? Typeface.BOLD : Typeface.NORMAL);
            cell.setGravity(tableGravity(column < alignments.size()
                    ? alignments.get(column) : MarkdownTableParser.Alignment.START));
            cell.setPadding(dpToPx(context, 12), dpToPx(context, 10),
                    dpToPx(context, 12), dpToPx(context, 10));
            cell.setLineSpacing(dpToPx(context, 2), 1f);
            cell.setSingleLine(false);
            cell.setMaxLines(10);
            cell.setMaxWidth(widths[column]);
            cell.setTextIsSelectable(true);
            cell.setBackground(cellBackground(fill, gridLine, stroke,
                    column < widths.length - 1, !lastRow));
            render(context, cell, value);
            android.widget.TableRow.LayoutParams params = new android.widget.TableRow.LayoutParams(
                    widths[column], ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(cell, params);
        }
        return row;
    }

    /**
     * Каждая ячейка рисует только правую и нижнюю линию сетки. Полная обводка
     * ячейки давала двойную толщину внутренних линий против одинарных внешних —
     * сетка выглядела кривой. Наружные края закрывает рамка самой карточки.
     */
    private static android.graphics.drawable.Drawable cellBackground(int fill, int gridLine,
                                                                     int stroke, boolean rightEdge,
                                                                     boolean bottomEdge) {
        if (!rightEdge && !bottomEdge) {
            return new android.graphics.drawable.ColorDrawable(fill);
        }
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{
                        new android.graphics.drawable.ColorDrawable(gridLine),
                        new android.graphics.drawable.ColorDrawable(fill)
                });
        layers.setLayerInset(1, 0, 0, rightEdge ? stroke : 0, bottomEdge ? stroke : 0);
        return layers;
    }

    private static int[] tableColumnWidths(Context context, MarkdownTableParser.Table data,
                                           int viewportWidth) {
        int columns = data.header.size();
        int[] widths = new int[columns];
        int total = 0;
        for (int column = 0; column < columns; column++) {
            int longest = visibleCellLength(data.header.get(column));
            for (java.util.List<String> row : data.rows) {
                if (column < row.size()) longest = Math.max(longest, visibleCellLength(row.get(column)));
            }
            int dp = Math.max(96, Math.min(240, 32 + longest * 7));
            widths[column] = dpToPx(context, dp);
            total += widths[column];
        }
        // A narrow table must span the whole card. Padding the TableLayout with
        // setMinimumWidth() only widened the container: the cells kept their
        // measured width, so the header tint stopped mid-card and left a bare
        // strip on the right.
        if (total > 0 && total < viewportWidth) {
            int extra = viewportWidth - total;
            int distributed = 0;
            for (int column = 0; column < columns; column++) {
                int share = column == columns - 1
                        ? extra - distributed
                        : (int) ((long) extra * widths[column] / total);
                widths[column] += share;
                distributed += share;
            }
        }
        return widths;
    }

    private static int visibleCellLength(String value) {
        if (value == null) return 0;
        String plain = value.replaceAll("[`*_~#]", "").replaceAll("<br\\s*/?>", " ");
        int longest = 0;
        for (String line : plain.split("\\R", -1)) {
            longest = Math.max(longest, line.codePointCount(0, line.length()));
        }
        return longest;
    }

    private static int tableGravity(MarkdownTableParser.Alignment alignment) {
        if (alignment == MarkdownTableParser.Alignment.CENTER) return Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        if (alignment == MarkdownTableParser.Alignment.END) return Gravity.TOP | Gravity.END;
        return Gravity.TOP | Gravity.START;
    }

    /**
     * Отрендерить markdown в TextView (старый метод для совместимости)
     */
    public static void render(Context context, TextView textView, String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            Telemoji.apply(context, textView, "");
            configureInteractiveText(context, textView);
            return;
        }
        
        // Препроцессинг
        markdown = preprocessMarkdown(markdown);

        // Инлайн-LaTeX ($...$ / \(...\)) рендерим сами: маскируем формулы
        // плейсхолдерами, гоняем текст через Markwon, затем вставляем
        // JLatexMathDrawable как ImageSpan на месте плейсхолдера. Инлайн-парсер
        // Markwon на практике роняет часть формул — этот путь детерминирован.
        java.util.List<String> inlineMath = new java.util.ArrayList<>();
        java.util.List<String> codeMath = new java.util.ArrayList<>();
        java.util.List<String> bareMath = new java.util.ArrayList<>();
        String masked = maskInlineLatex(markdown, inlineMath, codeMath, bareMath);
        if (!inlineMath.isEmpty() || !codeMath.isEmpty() || !bareMath.isEmpty()) {
            app.multiai.mobile.utils.AiDebugLog.log("LaTeX mask: " + inlineMath.size()
                    + " $-formula(s) " + inlineMath + ", " + codeMath.size()
                    + " code-formula(s), " + bareMath.size() + " bare " + bareMath);
        } else if (containsInlineLatex(markdown)) {
            app.multiai.mobile.utils.AiDebugLog.log(
                    "LaTeX MASK MISS (regex gap): " + preview(markdown));
        }

        Markwon markwon = getInstance(context);
        String cacheKey = themeSignature + '\u0000' + markdown;
        Spanned rendered;
        synchronized (renderedCache) {
            rendered = renderedCache.get(cacheKey);
        }
        if (rendered == null) {
            Spanned base = markwon.toMarkdown(masked);
            rendered = (inlineMath.isEmpty() && codeMath.isEmpty() && bareMath.isEmpty()) ? base
                    : attachInlineLatex(context, base, inlineMath, codeMath, bareMath);
            synchronized (renderedCache) {
                renderedCache.put(cacheKey, rendered);
            }
        }
        // Telemoji preserves Markdown spans. Add URL/path interactions after
        // that copy so cached Markwon output remains immutable and recycler
        // rows never inherit spans from an earlier message.
        Telemoji.apply(context, textView, rendered);
        configureInteractiveText(context, textView);
    }

    /**
     * Инлайн-математика вне код-спанов: group(1) = $...$, group(2) = \(...\).
     * Переносы строк внутри $...$ запрещены, чтобы одна незакрытая формула
     * не сожрала половину сообщения.
     */
    private static final Pattern INLINE_MATH_MASK_PATTERN = Pattern.compile(
            "(?<!\\\\)\\$(?!\\$)([^$\\r\\n]+?)(?<!\\$)\\$(?!\\$)|\\\\\\(([\\s\\S]+?)\\\\\\)");

    /** Формула в бэктиках: содержит хоть одну LaTeX-команду (\sqrt, \frac, …). */
    private static final Pattern LATEX_COMMAND = Pattern.compile("\\\\[a-zA-Z]+");

    /** «Голая» формула в тексте: команда + brace-группа, опционально =5, >0 и т.п. */
    private static final Pattern BARE_LATEX_PATTERN = Pattern.compile(
            "\\\\[a-zA-Z]+(?:\\{[^{}\\r\\n]*\\})+((?:\\s*[=<>+\\-*/]\\s*[0-9.,]+)*)");

    /** Fenced-код защищаем от латех-маскинга — \command там это код. */
    private static final Pattern FENCED_CODE_PATTERN = Pattern.compile("```[\\s\\S]*?```");

    private static boolean hasLatexInCode(String markdown) {
        Matcher codeMatcher = INLINE_CODE_PATTERN.matcher(markdown);
        while (codeMatcher.find()) {
            String inner = codeMatcher.group(1);
            if (inner != null && LATEX_COMMAND.matcher(inner).find()) return true;
        }
        return false;
    }

    /**
     * Заменяет формулы на плейсхолдеры: $...$ / \(...\) → \uFFFD, бэктики с
     * LaTeX-содержимым (например `\sqrt{x+3}=5`) → \uFFFC, «голая» формула в
     * тексте (\sqrt{x+3}=5) → \uFFFE. Юзер пишет формулы всеми тремя способами.
     * Обычный инлайн- и fenced-код защищаются плейсхолдерами и не маскируется.
     */
    private static String maskInlineLatex(String markdown, java.util.List<String> outDollar,
                                          java.util.List<String> outCode,
                                          java.util.List<String> outBare) {
        if (!containsInlineLatex(markdown) && !hasLatexInCode(markdown)
                && !BARE_LATEX_PATTERN.matcher(markdown).find()) return markdown;

        // 0. Fenced-код: внутри него \command — это код, не формула
        java.util.List<String> fences = new java.util.ArrayList<>();
        StringBuffer fencedBuf = new StringBuffer();
        Matcher fenceMatcher = FENCED_CODE_PATTERN.matcher(markdown);
        while (fenceMatcher.find()) {
            fences.add(fenceMatcher.group());
            fenceMatcher.appendReplacement(fencedBuf,
                    Matcher.quoteReplacement("\u0002F" + (fences.size() - 1) + "\u0002"));
        }
        fenceMatcher.appendTail(fencedBuf);
        String work = fencedBuf.toString();

        // 1. Инлайн-код: обычный защищаем, LaTeX-содержимое превращаем в формулу
        java.util.List<String> codes = new java.util.ArrayList<>();
        StringBuffer protectedBuf = new StringBuffer();
        Matcher codeMatcher = INLINE_CODE_PATTERN.matcher(work);
        while (codeMatcher.find()) {
            String inner = codeMatcher.group(1);
            if (inner != null && LATEX_COMMAND.matcher(inner).find()) {
                outCode.add(inner);
                codeMatcher.appendReplacement(protectedBuf, Matcher.quoteReplacement("\uFFFC"));
            } else {
                codes.add(codeMatcher.group(0));
                codeMatcher.appendReplacement(protectedBuf,
                        Matcher.quoteReplacement("\u0001C" + (codes.size() - 1) + "\u0001"));
            }
        }
        codeMatcher.appendTail(protectedBuf);
        work = protectedBuf.toString();

        // 2. $...$ и \(...\)
        StringBuffer sb = new StringBuffer();
        Matcher m = INLINE_MATH_MASK_PATTERN.matcher(work);
        while (m.find()) {
            String latex = m.group(1) != null ? m.group(1) : m.group(2);
            if (latex == null || latex.trim().isEmpty()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            outDollar.add(latex);
            m.appendReplacement(sb, Matcher.quoteReplacement("\uFFFD"));
        }
        m.appendTail(sb);
        work = sb.toString();

        // 3. «Голая» формула в тексте
        StringBuffer bareBuf = new StringBuffer();
        Matcher bare = BARE_LATEX_PATTERN.matcher(work);
        while (bare.find()) {
            outBare.add(bare.group());
            bare.appendReplacement(bareBuf, Matcher.quoteReplacement("\uFFFE"));
        }
        bare.appendTail(bareBuf);
        work = bareBuf.toString();

        // 4. Восстанавливаем защищённый код
        for (int i = 0; i < codes.size(); i++) {
            work = work.replace("\u0001C" + i + "\u0001", codes.get(i));
        }
        for (int i = 0; i < fences.size(); i++) {
            work = work.replace("\u0002F" + i + "\u0002", fences.get(i));
        }
        return work;
    }

    /** Вставляет отрендеренные формулы на места плейсхолдеров. */
    private static Spanned attachInlineLatex(Context context, Spanned base,
                                             java.util.List<String> dollarList,
                                             java.util.List<String> codeList,
                                             java.util.List<String> bareList) {
        SpannableStringBuilder builder = new SpannableStringBuilder(base);
        float sizePx = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16f,
                context.getResources().getDisplayMetrics());
        int textColor = resolve(context, R.color.text_primary);
        int padding = Math.max(1, Math.round(2 * context.getResources().getDisplayMetrics().density));
        // Код-формулы (\uFFFC), затем $-формулы (\uFFFD), затем голые (\uFFFE)
        int pos = attachLatexList(builder, codeList, "\uFFFC", sizePx, textColor, padding, 0);
        pos = attachLatexList(builder, dollarList, "\uFFFD", sizePx, textColor, padding, pos);
        attachLatexList(builder, bareList, "\uFFFE", sizePx, textColor, padding, pos);
        return builder;
    }

    private static int attachLatexList(SpannableStringBuilder builder,
                                       java.util.List<String> latexList, String placeholder,
                                       float sizePx, int textColor, int padding, int searchFrom) {
        for (String latex : latexList) {
            int pos = builder.toString().indexOf(placeholder, searchFrom);
            if (pos < 0) break;
            searchFrom = pos + 1;
            // Модели/источники иногда отдают удвоенный бэкслеш (\\sqrt) —
            // перед рендером нормализуем к одиночному
            String tex = latex;
            if (tex.contains("\\\\")) {
                tex = tex.replaceAll("\\\\\\\\(?=[a-zA-Z])", "\\\\");
            }
            try {
                ru.noties.jlatexmath.JLatexMathDrawable drawable =
                        ru.noties.jlatexmath.JLatexMathDrawable.builder(tex)
                                .textSize(sizePx)
                                .color(textColor)
                                .padding(padding)
                                .build();
                builder.setSpan(new android.text.style.ImageSpan(drawable,
                                android.text.style.ImageSpan.ALIGN_BASELINE),
                        pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Throwable t) {
                // Битая формула — возвращаем исходный текст
                app.multiai.mobile.utils.AiDebugLog.log(
                        "LaTeX render FAILED for '" + tex + "': " + t);
                String raw = "$" + latex + "$";
                builder.replace(pos, pos + 1, raw);
                searchFrom = pos + raw.length();
            }
        }
        return searchFrom;
    }

    private static String preview(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() > 160 ? flat.substring(0, 160) + "…" : flat;
    }

    /**
     * Enables links without using the stock LinkMovementMethod for every touch.
     * RecyclerView keeps normal vertical scrolling because only a tap directly
     * on a ClickableSpan is consumed.
     *
     * Selection + clickable spans в одной TextView через MovementMethod не
     * работают: LinkMovementMethod гасит лонг-тап, а focusableInTouchMode
     * съедает первый тап по ссылке. Поэтому MovementMethod нет вообще: тапы
     * по спанам ловит {@link SpanTapListener}, лонг-тап остаётся системному
     * выделению через setTextIsSelectable(true).
     */
    private static void configureInteractiveText(Context context, TextView textView) {
        textView.setAutoLinkMask(0);
        CharSequence text = textView.getText();
        if (!(text instanceof Spannable)) {
            textView.setMovementMethod(null);
            textView.setLinksClickable(false);
            textView.setTextIsSelectable(true);
            return;
        }

        Spannable content = (Spannable) text;
        // Markwon handles Markdown links; Linkify covers bare source URLs.
        android.text.util.Linkify.addLinks(content, android.text.util.Linkify.WEB_URLS);
        pruneFileLikeLinkSpans(content);
        addProjectFilePathSpans(context, content);
        textView.setLinksClickable(false);
        textView.setMovementMethod(null);
        // O24: выделение слов в ответе AI должно быть видимым (как в Telegram),
        // поэтому вместо прозрачного highlight — акцентный цвет из темы.
        textView.setHighlightColor(resolveSelectionHighlight(context));
        textView.setTextIsSelectable(true);
        SpanTapListener.attach(textView);
    }

    /** Accent-tinted, semi-transparent selection color that works on both themes. */
    private static int resolveSelectionHighlight(Context context) {
        int base = resolve(context, R.color.accent);
        return androidx.core.graphics.ColorUtils.setAlphaComponent(base, 66);
    }

    private static void addProjectFilePathSpans(Context context, Spannable content) {
        int linkColor = resolve(context, R.color.syntax_keyword);

        // Linkify считает "MainActivity.java" доменом с TLD ".java" и вешает
        // URLSpan только на имя файла внутри пути — из-за этого путь выглядел
        // частично «ссылкой» и открывал браузер вместо редактора. Голые
        // имена файлов без схемы ссылками не являются — снимаем такие спаны.
        pruneFileUrlSpans(content);

        // Пути в инлайн-коде: Markwon уже снял бэктики и покрасил ран
        // monospace'ом, поэтому INLINE_CODE_PATTERN здесь бесполезен —
        // ищем пути среди monospace-отрезков.
        addMonospacePathSpans(context, content, linkColor);

        Matcher path = PROJECT_PATH_PATTERN.matcher(content);
        while (path.find()) {
            addProjectFilePathSpan(content, path.start(), path.end(), path.group(), linkColor);
        }
    }

    /** `path/File.java:57` внутри инлайн-кода → кликабельная ссылка в редактор. */
    private static void addMonospacePathSpans(Context context, Spannable content, int linkColor) {
        TypefaceSpan[] spans = content.getSpans(0, content.length(), TypefaceSpan.class);
        int found = 0;
        for (TypefaceSpan span : spans) {
            if (!"monospace".equals(span.getFamily())) continue;
            int start = content.getSpanStart(span);
            int end = content.getSpanEnd(span);
            if (start < 0 || end <= start) continue;
            String text = content.subSequence(start, end).toString().trim();
            if (!looksLikeProjectPath(text)) continue;
            if (overlapsClickableSpan(content, start, end)) continue;
            content.setSpan(new ProjectFilePathSpan(text, linkColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            found++;
        }
        if (found > 0) {
            app.multiai.mobile.utils.AiDebugLog.log("Path spans (inline code): " + found);
        }
    }

    /** Путь: есть слэш, последний сегмент — имя файла с расширением, без пробелов и схем. */
    private static boolean looksLikeProjectPath(String text) {
        if (text == null || text.isEmpty()) return false;
        if (text.contains("://") || text.contains(" ") || text.contains("`")) return false;
        int slash = text.lastIndexOf('/');
        if (slash <= 0 || slash == text.length() - 1) return false;
        String fileName = text.substring(slash + 1);
        int colon = fileName.indexOf(':');
        if (colon >= 0) fileName = fileName.substring(0, colon);
        return fileName.matches("[A-Za-z0-9._-]+\\.[A-Za-z0-9]{1,10}");
    }

    private static final Pattern FILE_LIKE_NAME = Pattern.compile(
            "(?i)^[\\w./\\\\-]*\\w+\\.(java|kt|kts|xml|gradle|json|txt|md|py|cpp|c|h|hpp|sh|bat|yaml|yml|html|css|js|ts|dart|go|rs|rb|php|swift|properties|toml)$");

    /** Удаляет URLSpan'ы, которые Linkify поставил на имена файлов без схемы. */
    private static void pruneFileUrlSpans(Spannable content) {
        android.text.style.URLSpan[] spans = content.getSpans(
                0, content.length(), android.text.style.URLSpan.class);
        int pruned = 0;
        for (android.text.style.URLSpan span : spans) {
            int start = content.getSpanStart(span);
            int end = content.getSpanEnd(span);
            if (start < 0 || end <= start) continue;
            String text = content.subSequence(start, end).toString().trim();
            if (text.startsWith("http://") || text.startsWith("https://")
                    || text.startsWith("www.") || text.contains("://")) continue;
            if (FILE_LIKE_NAME.matcher(text).matches()) {
                content.removeSpan(span);
                pruned++;
            }
        }
        if (pruned > 0) {
            app.multiai.mobile.utils.AiDebugLog.log("Pruned file-like URLSpans: " + pruned);
        }
    }

    /**
     * Голое имя файла без пути ("MainActivity.java") — НЕ ссылка вовсе:
     * никакой раскраски, никакого браузера, никакого поиска по проекту.
     * Linkify и Markwon-Linkify вешают на такие токены URLSpan/ClickableLinkSpan
     * с мусорным "http://…" — снимаем их, текст остаётся обычным.
     */
    private static void pruneFileLikeLinkSpans(Spannable content) {
        pruneFileUrlSpans(content);
        android.text.style.ClickableSpan[] spans = content.getSpans(
                0, content.length(), android.text.style.ClickableSpan.class);
        int pruned = 0;
        for (android.text.style.ClickableSpan span : spans) {
            if (span instanceof ProjectFilePathSpan) continue; // явный путь — легитимно
            int start = content.getSpanStart(span);
            int end = content.getSpanEnd(span);
            if (start < 0 || end <= start) continue;
            String text = content.subSequence(start, end).toString().trim();
            if (text.isEmpty() || text.contains("/") || text.contains(":")) continue;
            if (!FILE_LIKE_NAME.matcher(text).matches()) continue;
            content.removeSpan(span);
            pruned++;
        }
        if (pruned > 0) {
            app.multiai.mobile.utils.AiDebugLog.log(
                    "Pruned bare-filename link spans: " + pruned);
        }
    }

    private static void addProjectFilePathSpan(Spannable content, int start, int end,
                                               String rawPath, int linkColor) {
        // «путь/файл.java:57» — хвост ":число" это номер строки, не часть пути
        String candidate = rawPath.trim();
        int line = -1;
        java.util.regex.Matcher lineMatcher =
                java.util.regex.Pattern.compile("^(.*):([0-9]{1,5})$").matcher(candidate);
        if (lineMatcher.matches()) {
            candidate = lineMatcher.group(1);
            try {
                line = Integer.parseInt(lineMatcher.group(2));
            } catch (NumberFormatException ignored) {}
        }
        String path = normalizeProjectRelativePath(candidate);
        if (path.isEmpty() || overlapsClickableSpan(content, start, end)) return;
        content.setSpan(new ProjectFilePathSpan(line > 0 ? path + ":" + line : path, linkColor),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static boolean overlapsClickableSpan(Spannable content, int start, int end) {
        return content.getSpans(start, Math.max(start + 1, end), ClickableSpan.class).length > 0;
    }

    /** Rejects URIs, absolute paths and traversal; only a selected-project path may open. */
    private static String normalizeProjectRelativePath(String rawPath) {
        if (rawPath == null) return "";
        String candidate = rawPath.trim().replace('\\', '/');
        while (candidate.startsWith("./")) candidate = candidate.substring(2);
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.contains("://")
                || candidate.indexOf(':') >= 0) return "";
        String[] parts = candidate.split("/");
        if (parts.length < 2) return "";
        StringBuilder normalized = new StringBuilder();
        for (String value : parts) {
            String part = value == null ? "" : value.trim();
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.indexOf('?') >= 0 || part.indexOf('#') >= 0) return "";
            if (normalized.length() > 0) normalized.append('/');
            normalized.append(part);
        }
        return normalized.toString();
    }

    private static void openProjectFile(Context context, String projectRelativePath) {
        openProjectFile(context, projectRelativePath, -1);
    }

    private static final java.util.concurrent.ExecutorService PATH_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean PATH_OPENING =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final android.os.Handler PATH_MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private static void openProjectFile(Context context, String projectRelativePath, int lineNumber) {
        String path = normalizeProjectRelativePath(projectRelativePath);
        if (path.isEmpty() || !PATH_OPENING.compareAndSet(false, true)) return;
        java.lang.ref.WeakReference<Context> target = new java.lang.ref.WeakReference<>(context);
        Context application = context.getApplicationContext();
        PATH_EXECUTOR.execute(() -> {
            Intent intent = resolveProjectFile(application, path, lineNumber);
            PATH_MAIN.post(() -> {
                PATH_OPENING.set(false);
                Context current = target.get();
                if (current == null || (current instanceof Activity
                        && (((Activity) current).isFinishing() || ((Activity) current).isDestroyed()))) return;
                if (intent == null) {
                    showInteractionError(current, current.getString(R.string.scr_file_not_found));
                    return;
                }
                try {
                    if (!(current instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    current.startActivity(intent);
                } catch (RuntimeException e) {
                    showInteractionError(current, current.getString(R.string.scr_file_not_found));
                }
            });
        });
    }

    /** Resolves only the explicit path; never scans unrelated project directories. */
    private static Intent resolveProjectFile(Context context, String projectRelativePath, int lineNumber) {
        String rawTreeUri = context.getSharedPreferences("multiaiide", Context.MODE_PRIVATE)
                .getString("project_folder_uri", "");
        if (rawTreeUri == null || rawTreeUri.trim().isEmpty()) {
            app.multiai.mobile.utils.AiDebugLog.log("Path open FAILED: no project folder");
            return null;
        }
        try {
            DocumentFile file = DocumentFile.fromTreeUri(context, Uri.parse(rawTreeUri));
            if (file == null || !file.exists()) {
                app.multiai.mobile.utils.AiDebugLog.log("Path open FAILED: project tree unavailable");
                return null;
            }
            for (String part : projectRelativePath.split("/")) {
                file = file.findFile(part);
                if (file == null || !file.exists()) {
                    app.multiai.mobile.utils.AiDebugLog.log(
                            "Path open FAILED: segment not found: " + part);
                    return null;
                }
            }
            if (!file.isFile()) return null;
            String fileName = file.getName();
            Intent editor = new Intent(context, app.multiai.mobile.ui.EditorActivity.class);
            if (!(context instanceof Activity)) editor.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            editor.putExtra("file_uri", file.getUri().toString());
            editor.putExtra("file_name", fileName == null ? projectRelativePath : fileName);
            editor.putExtra("file_path", projectRelativePath);
            editor.putExtra("project_folder_uri", rawTreeUri);
            if (lineNumber > 0) editor.putExtra("line_number", lineNumber);
            return editor;
        } catch (Exception e) {
            app.multiai.mobile.utils.AiDebugLog.log("Path open ERROR: " + e);
            return null;
        }
    }

    private static void openExternalLink(Context context, String url) {
        String target = url == null ? "" : url.trim();
        // "example.com" без схемы: ACTION_VIEW без схемы падает молча —
        // добавляем https сами
        if (!target.isEmpty() && !target.contains("://")) {
            target = "https://" + target;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            app.multiai.mobile.utils.AiDebugLog.log("Link open OK: " + target);
        } catch (Exception e) {
            app.multiai.mobile.utils.AiDebugLog.log("Link open FAILED: " + target + " -> " + e);
            showInteractionError(context, "Не удалось открыть ссылку");
        }
    }

    private static void showInteractionError(Context context, String message) {
        if (context instanceof Activity) {
            ModernToast.show((Activity) context, message);
        } else {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    private static final class ProjectFilePathSpan extends ClickableSpan {
        private final String projectRelativePath;
        private final int lineNumber;
        private final int linkColor;

        ProjectFilePathSpan(String projectRelativePath, int linkColor) {
            // Parse :line suffix if present
            String path = projectRelativePath;
            int line = -1;
            int colonIdx = projectRelativePath.lastIndexOf(':');
            if (colonIdx > 0) {
                try {
                    line = Integer.parseInt(projectRelativePath.substring(colonIdx + 1));
                    path = projectRelativePath.substring(0, colonIdx);
                } catch (NumberFormatException ignored) {}
            }
            this.projectRelativePath = path;
            this.lineNumber = line;
            this.linkColor = linkColor;
        }

        @Override
        public void onClick(@NonNull View widget) {
            openProjectFile(widget.getContext(), projectRelativePath, lineNumber);
        }

        @Override
        public void updateDrawState(@NonNull android.text.TextPaint ds) {
            super.updateDrawState(ds);
            ds.setColor(linkColor);
            ds.setUnderlineText(true);
        }
    }

    /**
     * Тап по ClickableSpan без LinkMovementMethod. ACTION_DOWN всегда
     * отдаётся вью (press, лонг-тап, выделение), клик срабатывает только
     * если был тап ровно по спану и выделение не активно.
     */
    private static final class SpanTapListener implements View.OnTouchListener {
        private final TextView view;
        private float downX;
        private float downY;
        private ClickableSpan pressedSpan;
        private boolean moved;
        private boolean longPressed;
        private final Runnable longPress;

        static void attach(TextView view) {
            view.setOnTouchListener(new SpanTapListener(view));
        }

        private SpanTapListener(TextView view) {
            this.view = view;
            longPress = () -> {
                if (pressedSpan != null && !moved) {
                    longPressed = true;
                    view.performLongClick();
                }
            };
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                view.removeCallbacks(longPress);
                pressedSpan = null;
                if (view.hasSelection()) return false;
                downX = event.getX();
                downY = event.getY();
                moved = false;
                longPressed = false;
                pressedSpan = findSpan(event);
                if (pressedSpan == null) return false;
                view.postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());
                return true;
            }
            if (pressedSpan == null) return false;
            if (Math.hypot(event.getX() - downX, event.getY() - downY)
                    > android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop()) {
                moved = true;
                view.removeCallbacks(longPress);
            }
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                view.removeCallbacks(longPress);
                pressedSpan = null;
                v.setPressed(false);
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                view.removeCallbacks(longPress);
                ClickableSpan span = pressedSpan;
                pressedSpan = null;
                v.setPressed(false);
                if (moved || longPressed || view.hasSelection()
                        || event.getEventTime() - event.getDownTime()
                        >= android.view.ViewConfiguration.getLongPressTimeout()) return true;
                CharSequence text = view.getText();
                if (!(text instanceof Spanned) || ((Spanned) text).getSpanStart(span) < 0) return true;
                AiDebugLog.log("Tap UP: click " + span.getClass().getSimpleName());
                if (span instanceof android.text.style.URLSpan) {
                    openExternalLink(v.getContext(), ((android.text.style.URLSpan) span).getURL());
                } else {
                    span.onClick(v);
                }
            }
            return true;
        }

        private ClickableSpan findSpan(MotionEvent event) {
            return findSpanAt(event.getX(), event.getY());
        }

        /** Поиск спана по координатам (в системе координат вью). */
        private ClickableSpan findSpanAt(float evX, float evY) {
            CharSequence text = view.getText();
            Layout layout = view.getLayout();
            if (!(text instanceof Spanned) || layout == null) return null;
            float x = evX - view.getTotalPaddingLeft() + view.getScrollX();
            float y = evY - view.getTotalPaddingTop() + view.getScrollY();
            float tolerance = 6 * view.getResources().getDisplayMetrics().density;
            Spanned spanned = (Spanned) text;
            ClickableSpan nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            android.graphics.Path path = new android.graphics.Path();
            android.graphics.Region region = new android.graphics.Region();
            android.graphics.Region clip = new android.graphics.Region(
                    -1, -1, layout.getWidth() + 1, layout.getHeight() + 1);
            android.graphics.Rect rect = new android.graphics.Rect();
            // Selection geometry follows rendered glyphs, including bidi runs,
            // wrapped links and ReplacementSpans; character-distance guesses do not.
            for (ClickableSpan span : spanned.getSpans(0, spanned.length(), ClickableSpan.class)) {
                int start = spanned.getSpanStart(span);
                int end = spanned.getSpanEnd(span);
                if (start < 0 || end <= start) continue;
                path.reset();
                layout.getSelectionPath(start, end, path);
                region.setPath(path, clip);
                android.graphics.RegionIterator iterator = new android.graphics.RegionIterator(region);
                while (iterator.next(rect)) {
                    float dx = Math.max(rect.left - x, Math.max(0, x - rect.right));
                    float dy = Math.max(rect.top - y, Math.max(0, y - rect.bottom));
                    double distance = Math.hypot(dx, dy);
                    if (distance == 0) return span;
                    if (distance <= tolerance && distance < nearestDistance) {
                        nearest = span;
                        nearestDistance = distance;
                    }
                }
            }
            return nearest;
        }
    }
    
    /** Render display math locally; no WebView, JavaScript or CDN is involved. */
    private static View createMathView(Context context, String latex, boolean displayMode) {
        TextView textView = new TextView(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(0, dpToPx(context, 4), 0, dpToPx(context, 4));
        textView.setLayoutParams(lp);
        textView.setTextColor(resolve(context, R.color.text_primary));
        textView.setTextSize(16);
        textView.setLineSpacing(dpToPx(context, 4), 1f);
        getInstance(context).setMarkdown(textView,
                displayMode ? "$$\n" + latex + "\n$$" : "$" + latex + "$");
        return textView;
    }

    public static boolean containsInlineLatex(String text) {
        return java.util.regex.Pattern.compile(
                "(?<!\\$)\\$(?!\\$).+?(?<!\\$)\\$(?!\\$)|\\\\\\(.+?\\\\\\)",
                java.util.regex.Pattern.DOTALL).matcher(text).find();
    }

    /**
     * Деградация LaTeX-формул до обычного текста, чтобы битая формула
     * не уронила весь рендер. Display-формулы превращаются в инлайн.
     */
    private static String degradeLatex(String markdown) {
        String degraded = markdown
                .replaceAll("(?s)\\$\\$\\s*(.*?)\\s*\\$\\$", "`$1`")
                .replaceAll("(?s)\\\\\\[(.*?)\\\\\\]", "`$1`")
                .replaceAll("(?s)\\\\\\((.*?)\\\\\\)", "`$1`")
                .replaceAll("(?<!\\$)\\$(?!\\$)([^$\\n]+?)(?<!\\$)\\$(?!\\$)", "`$1`");
        return degraded;
    }

    /**
     * Рендерит markdown в один TextView, при ошибке деградирует LaTeX до текста.
     * Используется для пользовательских сообщений и reasoning — там формулы
     * приходят от AI и могут быть частично сломаны.
     */
    public static void renderSafe(Context context, TextView textView, String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            render(context, textView, markdown);
            return;
        }
        try {
            render(context, textView, markdown);
        } catch (Throwable t) {
            app.multiai.mobile.utils.AiDebugLog.log(
                    "renderSafe degraded to text: " + t + " | " + preview(markdown));
            try {
                render(context, textView, degradeLatex(markdown));
            } catch (Throwable ignored) {
                textView.setText(markdown);
            }
        }
    }

    /** Render markdown and inline math through the local Markwon pipeline. */
    private static View createInlineMathMarkdownView(Context context, String markdown) {
        TextView textView = new TextView(context);
        textView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        textView.setTextColor(resolve(context, R.color.text_primary));
        textView.setTextSize(16);
        textView.setLineSpacing(dpToPx(context, 4), 1f);
        try {
            getInstance(context).setMarkdown(textView, markdown);
        } catch (Throwable t) {
            getInstance(context).setMarkdown(textView, degradeLatex(markdown));
        }
        // Apply Telemoji after Markwon has set the text.
        CharSequence current = textView.getText();
        if (current != null) {
            Telemoji.apply(context, textView, current);
        }
        configureInteractiveText(context, textView);
        return textView;
    }

    /**
     * Создать view для кодблока с кнопкой копирования и номерами строк
     */
    private static View createCodeBlockView(Context context, String language, String code) {
        // Outer container with rounded corners
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        containerLp.setMargins(0, dpToPx(context, 8), 0, dpToPx(context, 8));
        container.setLayoutParams(containerLp);
        container.setClipToOutline(true);

        // Rounded background (code surface, theme-aware)
        android.graphics.drawable.GradientDrawable containerBg = new android.graphics.drawable.GradientDrawable();
        containerBg.setColor(resolve(context, R.color.code_bg));
        containerBg.setCornerRadius(dpToPx(context, 12));
        container.setBackground(containerBg);

        // Header with language + copy button (slightly elevated code surface)
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        header.setBackgroundColor(resolve(context, R.color.surface_elevated));
        header.setPadding(dpToPx(context, 14), dpToPx(context, 10), dpToPx(context, 10), dpToPx(context, 10));
        header.setGravity(Gravity.CENTER_VERTICAL);

        // Language badge
        TextView langText = new TextView(context);
        langText.setText(language.isEmpty() ? "text" : language.toLowerCase());
        langText.setTextColor(resolve(context, R.color.text_secondary));
        langText.setTextSize(12);
        langText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        langText.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
        ));
        header.addView(langText);

        // Copy icon button (no text)
        android.widget.ImageView copyBtn = new android.widget.ImageView(context);
        int copyIconId = context.getResources().getIdentifier("ic_copy_code", "drawable", context.getPackageName());
        int checkIconId = context.getResources().getIdentifier("ic_check_circle", "drawable", context.getPackageName());
        copyBtn.setImageResource(copyIconId);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
            dpToPx(context, 32), dpToPx(context, 32)
        );
        copyBtn.setLayoutParams(copyLp);
        copyBtn.setPadding(dpToPx(context, 6), dpToPx(context, 6), dpToPx(context, 6), dpToPx(context, 6));
        copyBtn.setClickable(true);
        copyBtn.setFocusable(true);
        copyBtn.setBackground(app.multiai.mobile.ui.kit.UiKit.rippleRounded(context,
                16, context.getColor(app.multiai.mobile.R.color.ripple)));
        copyBtn.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("code", code);
            clipboard.setPrimaryClip(clip);
            copyBtn.setImageResource(checkIconId);
            copyBtn.postDelayed(() -> copyBtn.setImageResource(copyIconId), 2000);
        });
        header.addView(copyBtn);
        container.addView(header);

        // HorizontalScrollView for wide code
        android.widget.HorizontalScrollView hScroll = new android.widget.HorizontalScrollView(context);
        hScroll.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        hScroll.setHorizontalScrollBarEnabled(false);
        hScroll.setFillViewport(true);

        // Code text — clean, no line numbers
        TextView codeText = new TextView(context);
        codeText.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        codeText.setTextColor(resolve(context, R.color.code_text));
        codeText.setTextSize(13);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setPadding(dpToPx(context, 16), dpToPx(context, 14), dpToPx(context, 16), dpToPx(context, 14));
        codeText.setLineSpacing(0, 1.4f);

        // Syntax highlighting via Markwon
        Markwon markwon = getInstance(context);
        String codeBlockMarkdown = "```" + language + "\n" + code + "\n```";
        markwon.setMarkdown(codeText, codeBlockMarkdown);

        hScroll.addView(codeText);
        container.addView(hScroll);

        return container;
    }
    
    /**
     * Парсим markdown на части: текст, кодблоки и Mermaid диаграммы
     */
    private static class ContentPart {
        boolean isCode;
        boolean isMermaid;
        boolean isMath;
        boolean isDisplayMath; // true for $$ (block), false for $ (inline)
        String content;
        String language;
        ContentPart(boolean isCode, boolean isMermaid, String content, String language) {
            this.isCode = isCode;
            this.isMermaid = isMermaid;
            this.isMath = false;
            this.isDisplayMath = false;
            this.content = content;
            this.language = language;
        }

        static ContentPart math(String latex, boolean display) {
            ContentPart p = new ContentPart(false, false, latex, "");
            p.isMath = true;
            p.isDisplayMath = display;
            return p;
        }
    }
    
    private static java.util.List<ContentPart> parseContentParts(String markdown) {
        java.util.List<ContentPart> parts = new java.util.ArrayList<>();
        
        // Отдельно выделяем только код и display-формулы. Inline-LaTeX
        // остаётся в текстовом потоке и рисуется Markwon'ом в одном TextView.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "```(\\w*)\\n([\\s\\S]*?)```" +          // group 1,2: fenced code
            "|\\$\\$([\\s\\S]*?)\\$\\$" +             // group 3: display math $$...$$
            "|\\\\\\[([\\s\\S]*?)\\\\\\]",             // group 4: display math \[...\]
            java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = pattern.matcher(markdown);
        
        int lastEnd = 0;
        while (matcher.find()) {
            // Text before this match
            if (matcher.start() > lastEnd) {
                String text = markdown.substring(lastEnd, matcher.start()).trim();
                if (!text.isEmpty()) {
                    // Split inline $...$ math from text
                    addTextWithInlineMath(parts, text);
                }
            }
            
            if (matcher.group(1) != null || matcher.group(2) != null) {
                // Fenced code block
                String language = matcher.group(1);
                String code = matcher.group(2);
                if ("mermaid".equalsIgnoreCase(language)) {
                    parts.add(new ContentPart(false, true, code, "mermaid"));
                } else {
                    parts.add(new ContentPart(true, false, code, language != null ? language : ""));
                }
            } else if (matcher.group(3) != null) {
                // Display math $$...$$
                parts.add(ContentPart.math(matcher.group(3).trim(), true));
            } else if (matcher.group(4) != null) {
                // Display math \[...\]
                parts.add(ContentPart.math(matcher.group(4).trim(), true));
            }
            
            lastEnd = matcher.end();
        }
        
        // Text after last match
        if (lastEnd < markdown.length()) {
            String text = markdown.substring(lastEnd).trim();
            if (!text.isEmpty()) {
                addTextWithInlineMath(parts, text);
            }
        }
        
        if (parts.isEmpty() && !markdown.isEmpty()) {
            parts.add(new ContentPart(false, false, markdown, ""));
        }
        
        return parts;
    }
    
    /**
     * Inline-LaTeX остаётся в общем текстовом потоке. Markwon рисует его
     * inline span'ом, поэтому фраза переносится как единый текст.
     */
    private static void addTextWithInlineMath(java.util.List<ContentPart> parts, String text) {
        parts.add(new ContentPart(false, false, text, ""));
    }
    
    /**
     * Препроцессинг markdown
     */
    private static String preprocessMarkdown(String markdown) {
        // Добавляем эмодзи к заголовкам чек-листов
        markdown = preprocessChecklistHeaders(markdown);
        
        return markdown;
    }

    /**
     * 🔥 Фиксим bold и italic - добавляем пробелы где нужно
     * ВАЖНО: используем \\p{L} для Unicode букв (работает на всех Android)
     */
    private static String fixBoldItalic(String markdown) {
        // Сначала защищаем code blocks от изменений
        java.util.List<String> codeBlocks = new java.util.ArrayList<>();
        java.util.regex.Pattern codePattern = java.util.regex.Pattern.compile("```[\\s\\S]*?```|`[^`]+`");
        java.util.regex.Matcher codeMatcher = codePattern.matcher(markdown);
        
        while (codeMatcher.find()) {
            codeBlocks.add(codeMatcher.group());
        }
        
        // Заменяем code blocks на плейсхолдеры
        String processed = markdown;
        for (int i = 0; i < codeBlocks.size(); i++) {
            processed = processed.replace(codeBlocks.get(i), "___CODE_BLOCK_" + i + "___");
        }
        
        // 🔥 Фиксим **текст** - добавляем пробелы ВОКРУГ всего блока
        // Используем \\p{L} для Unicode букв (кириллица, латиница и т.д.)
        processed = processed.replaceAll("([\\p{L}\\p{N}])\\*\\*([^\\*]+)\\*\\*([\\p{L}\\p{N}])", "$1 **$2** $3");
        processed = processed.replaceAll("([\\p{L}\\p{N}])\\*\\*([^\\*]+)\\*\\*", "$1 **$2**");  // Пробел перед
        processed = processed.replaceAll("\\*\\*([^\\*]+)\\*\\*([\\p{L}\\p{N}])", "**$1** $2");  // Пробел после
        
        // 🔥 Фиксим *текст* - добавляем пробелы ВОКРУГ всего блока (но не трогаем **)
        processed = processed.replaceAll("([\\p{L}\\p{N}])\\*([^\\*]+)\\*([\\p{L}\\p{N}])", "$1 *$2* $3");
        processed = processed.replaceAll("([\\p{L}\\p{N}])\\*([^\\*]+)\\*", "$1 *$2*");  // Пробел перед
        processed = processed.replaceAll("\\*([^\\*]+)\\*([\\p{L}\\p{N}])", "*$1* $2");  // Пробел после
        
        // 🔥 Фиксим __текст__
        processed = processed.replaceAll("([\\p{L}\\p{N}])__([^_]+)__([\\p{L}\\p{N}])", "$1 __$2__ $3");
        processed = processed.replaceAll("([\\p{L}\\p{N}])__([^_]+)__", "$1 __$2__");
        processed = processed.replaceAll("__([^_]+)__([\\p{L}\\p{N}])", "__$1__ $2");
        
        // 🔥 Фиксим _текст_
        processed = processed.replaceAll("([\\p{L}\\p{N}])_([^_]+)_([\\p{L}\\p{N}])", "$1 _$2_ $3");
        processed = processed.replaceAll("([\\p{L}\\p{N}])_([^_]+)_", "$1 _$2_");
        processed = processed.replaceAll("_([^_]+)_([\\p{L}\\p{N}])", "_$1_ $2");
        
        // Возвращаем code blocks обратно
        for (int i = 0; i < codeBlocks.size(); i++) {
            processed = processed.replace("___CODE_BLOCK_" + i + "___", codeBlocks.get(i));
        }
        
        return processed;
    }
    
    /**
     * 🔥 Фиксим LaTeX math notation - конвертируем в читаемый текст
     */
    private static String fixMathNotation(String markdown) {
        // $$ блоки (display math) → обрамляем в code block
        markdown = markdown.replaceAll("\\$\\$([^$]+)\\$\\$", "`$1`");
        
        // $ inline math $ → обрамляем в code
        markdown = markdown.replaceAll("\\$([^$]+)\\$", "`$1`");
        
        // Заменяем LaTeX команды на Unicode символы
        markdown = markdown.replace("\\neq", "≠");
        markdown = markdown.replace("\\leq", "≤");
        markdown = markdown.replace("\\geq", "≥");
        markdown = markdown.replace("\\approx", "≈");
        markdown = markdown.replace("\\times", "×");
        markdown = markdown.replace("\\div", "÷");
        markdown = markdown.replace("\\pm", "±");
        markdown = markdown.replace("\\infty", "∞");
        markdown = markdown.replace("\\pi", "π");
        markdown = markdown.replace("\\alpha", "α");
        markdown = markdown.replace("\\beta", "β");
        markdown = markdown.replace("\\gamma", "γ");
        markdown = markdown.replace("\\delta", "δ");
        markdown = markdown.replace("\\theta", "θ");
        markdown = markdown.replace("\\lambda", "λ");
        markdown = markdown.replace("\\mu", "μ");
        markdown = markdown.replace("\\sigma", "σ");
        markdown = markdown.replace("\\omega", "ω");
        markdown = markdown.replace("\\sum", "Σ");
        markdown = markdown.replace("\\prod", "Π");
        markdown = markdown.replace("\\int", "∫");
        markdown = markdown.replace("\\sqrt", "√");
        markdown = markdown.replace("\\partial", "∂");
        markdown = markdown.replace("\\nabla", "∇");
        markdown = markdown.replace("\\in", "∈");
        markdown = markdown.replace("\\notin", "∉");
        markdown = markdown.replace("\\subset", "⊂");
        markdown = markdown.replace("\\supset", "⊃");
        markdown = markdown.replace("\\cup", "∪");
        markdown = markdown.replace("\\cap", "∩");
        markdown = markdown.replace("\\emptyset", "∅");
        markdown = markdown.replace("\\forall", "∀");
        markdown = markdown.replace("\\exists", "∃");
        markdown = markdown.replace("\\neg", "¬");
        markdown = markdown.replace("\\land", "∧");
        markdown = markdown.replace("\\lor", "∨");
        markdown = markdown.replace("\\rightarrow", "→");
        markdown = markdown.replace("\\leftarrow", "←");
        markdown = markdown.replace("\\leftrightarrow", "↔");
        markdown = markdown.replace("\\Rightarrow", "⇒");
        markdown = markdown.replace("\\Leftarrow", "⇐");
        markdown = markdown.replace("\\Leftrightarrow", "⇔");
        
        // Фиксим степени: x^2 → x²
        markdown = markdown.replaceAll("\\^2\\b", "²");
        markdown = markdown.replaceAll("\\^3\\b", "³");
        
        return markdown;
    }
    
    /**
     * Препроцессинг: добавляем эмодзи к заголовкам чек-листов
     */
    private static String preprocessChecklistHeaders(String markdown) {
        String[] lines = markdown.split("\n");
        StringBuilder result = new StringBuilder();
        
        for (String line : lines) {
            if (line.trim().startsWith("###") && 
                (line.toLowerCase().contains("список") || line.toLowerCase().contains("задач"))) {
                
                String title = line.trim().substring(3).trim();

                result.append("### ").append(title).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * Resolve a semantic color resource token to an int for the current theme.
     */
    private static int resolve(Context context, int colorRes) {
        return androidx.core.content.ContextCompat.getColor(context, colorRes);
    }

    /**
     * Convert an ARGB color int to an uppercase #RRGGBB CSS hex string (no alpha)
     * for use inside WebView/HTML payloads.
     */
    private static String colorHex(int color) {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF & color);
    }

    /**
     * Build a theme-aware Prism4j syntax-highlight theme that reads the app's
     * semantic code/syntax color tokens, so code blocks render correctly in both
     * light and dark mode instead of always using the Darkula (dark) palette.
     */
    private static Prism4jTheme createTheme(Context context) {
        return new ThemeAwarePrism4jTheme(
            resolve(context, R.color.code_bg),
            resolve(context, R.color.code_text),
            resolve(context, R.color.syntax_comment),
            resolve(context, R.color.syntax_string),
            resolve(context, R.color.syntax_keyword),
            resolve(context, R.color.syntax_literal),
            resolve(context, R.color.syntax_number)
        );
    }

    /**
     * Custom Prism4j theme backed by the app's semantic syntax tokens.
     * Implements {@link Prism4jTheme} directly (rather than extending
     * {@code Prism4jThemeBase}) because the base's {@code ColorHashMap} and
     * {@code add()} are protected and only usable from within the
     * {@code io.noties.markwon.syntax} package. A flat {@code HashMap} plus
     * {@link ForegroundColorSpan} keeps everything in our own package.
     */
    private static final class ThemeAwarePrism4jTheme implements Prism4jTheme {
        private final int background;
        private final int textColor;
        private final java.util.HashMap<String, Integer> colors = new java.util.HashMap<>();

        ThemeAwarePrism4jTheme(int background, int textColor,
                               int comment, int string, int keyword,
                               int literal, int number) {
            this.background = background;
            this.textColor = textColor;
            put(comment, "comment", "prolog", "doctype", "cdata");
            put(literal, "annotation");
            put(keyword, "attr-value", "changed", "function", "inserted");
            put(number, "entity", "number", "symbol");
            put(keyword, "tag", "builtin", "deleted");
            put(literal, "constant", "variable");
            put(string, "string", "char");
            put(keyword, "keyword", "selector", "important", "atrule");
            put(literal, "operator", "punctuation", "attr-name");
            put(keyword, "property");
            put(literal, "regex");
            put(keyword, "url");
            // Fall-through: any unmapped type renders in the base text color.
        }

        private void put(int color, String... types) {
            for (String t : types) colors.put(t, color);
        }

        @Override
        public int background() {
            return background;
        }

        @Override
        public int textColor() {
            return textColor;
        }

        @Override
        public void apply(@NonNull String language, @NonNull Syntax syntax,
                          @NonNull SpannableStringBuilder builder, int start, int end) {
            Integer col = colors.get(syntax.type());
            if (col == null) col = textColor;
            builder.setSpan(new ForegroundColorSpan(col), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * Конвертировать dp в px
     */
    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    /**
     * Кликабельная ссылка
     */
    private static class ClickableLinkSpan extends ClickableSpan {
        private final String url;
        private final int linkColor;

        public ClickableLinkSpan(String url, int linkColor) {
            this.url = url;
            this.linkColor = linkColor;
        }

        @Override
        public void onClick(@NonNull View widget) {
            // Check if this is a project file reference like "path/File.java:57"
            String path = normalizeProjectRelativePath(url);
            if (!path.isEmpty()) {
                openProjectFile(widget.getContext(), path);
                return;
            }
            // Проверяем: имя файла с номером строки ("AchievementManager.java:57")
            int colonIdx = url.lastIndexOf(':');
            if (colonIdx > 0) {
                String beforeColon = url.substring(0, colonIdx);
                String lineStr = url.substring(colonIdx + 1);
                try {
                    int line = Integer.parseInt(lineStr);
                    if (beforeColon.contains("/")) {
                        // Явный путь — открываем редактор
                        openProjectFile(widget.getContext(), beforeColon, line);
                        return;
                    }
                    // Голое имя файла — не ссылка и не сайт: ничего не открываем
                    showInteractionError(widget.getContext(),
                            widget.getContext().getString(R.string.scr_file_not_found));
                    return;
                } catch (NumberFormatException ignored) {
                    // Не число после ':' — внешняя ссылка
                }
            }
            // Голое имя файла как URL — не сайт и не путь: ничего не открываем
            if (!url.contains("://") && !url.startsWith("www.")
                    && FILE_LIKE_NAME.matcher(url).matches()) {
                showInteractionError(widget.getContext(),
                        widget.getContext().getString(R.string.scr_file_not_found));
                return;
            }
            openExternalLink(widget.getContext(), url);
        }

        @Override
        public void updateDrawState(@NonNull android.text.TextPaint ds) {
            super.updateDrawState(ds);
            ds.setColor(linkColor);
            ds.setUnderlineText(true);
        }
    }

}