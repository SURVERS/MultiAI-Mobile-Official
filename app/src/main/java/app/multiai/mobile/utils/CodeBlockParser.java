package app.multiai.mobile.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodeBlockParser {
    
    public static class CodeBlock {
        public String language;
        public String code;
        public int start;
        public int end;
        
        public CodeBlock(String language, String code, int start, int end) {
            this.language = language;
            this.code = code;
            this.start = start;
            this.end = end;
        }
    }
    
    /**
     * Парсит текст и находит все code blocks (```язык\nкод\n```)
     */
    public static List<CodeBlock> parseCodeBlocks(String text) {
        List<CodeBlock> blocks = new ArrayList<>();
        
        // Regex для ```язык\nкод\n```
        Pattern pattern = Pattern.compile("```(\\w+)?\\n([\\s\\S]*?)```", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(text);
        
        while (matcher.find()) {
            String language = matcher.group(1);
            String code = matcher.group(2);
            
            if (language == null || language.isEmpty()) {
                language = "CODE";
            } else {
                language = language.toUpperCase();
            }
            
            blocks.add(new CodeBlock(language, code, matcher.start(), matcher.end()));
        }
        
        return blocks;
    }
    
    /**
     * Создаёт View для code block с языком и кнопкой копирования
     */
    public static View createCodeBlockView(Context context, CodeBlock block) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackground(context.getDrawable(R.drawable.code_block_background));
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 8, 0, 8);
        container.setLayoutParams(params);
        
        // Header (язык + кнопка копирования)
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackground(context.getDrawable(R.drawable.code_header_background));
        header.setPadding(16, 12, 16, 12);
        
        // Язык
        TextView langText = new TextView(context);
        langText.setText(block.language);
        langText.setTextColor(androidx.core.content.ContextCompat.getColor(
                context, R.color.text_secondary));
        langText.setTextSize(13);
        langText.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams langParams = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        );
        langText.setLayoutParams(langParams);
        
        // Кнопка копирования
        ImageView copyBtn = new ImageView(context);
        copyBtn.setImageResource(R.drawable.ic_copy);
        copyBtn.setPadding(8, 8, 8, 8);
        copyBtn.setClickable(true);
        copyBtn.setFocusable(true);
        
        // Ripple effect
        int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
        android.content.res.TypedArray ta = context.obtainStyledAttributes(attrs);
        copyBtn.setBackground(ta.getDrawable(0));
        ta.recycle();
        
        copyBtn.setOnClickListener(v -> {
            // Копируем код
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("code", block.code);
            clipboard.setPrimaryClip(clip);
            
            // Меняем иконку на галочку
            copyBtn.setImageResource(R.drawable.ic_check);
            ModernToast.show((Activity) copyBtn.getContext(),
                    context.getString(R.string.kit_msg_copied));
            
            // Возвращаем иконку копирования через 2 секунды
            copyBtn.postDelayed(() -> {
                copyBtn.setImageResource(R.drawable.ic_copy);
            }, 2000);
        });
        
        header.addView(langText);
        header.addView(copyBtn);
        
        // Code content
        TextView codeText = new TextView(context);
        codeText.setText(block.code);
        codeText.setTextColor(androidx.core.content.ContextCompat.getColor(
                context, R.color.code_text));
        codeText.setTextSize(13);
        codeText.setTypeface(Typeface.MONOSPACE);
        codeText.setPadding(16, 12, 16, 12);
        codeText.setTextIsSelectable(true);
        
        container.addView(header);
        container.addView(codeText);
        
        return container;
    }
    
    /**
     * Заменяет code blocks в тексте на placeholder и возвращает текст без кода
     */
    public static String removeCodeBlocks(String text) {
        return text.replaceAll("```\\w*\\n[\\s\\S]*?```", "");
    }
}
