package app.multiai.mobile.utils;

import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;

/**
 * Единый формат ссылки тех-поддержки для обоих режимов:
 * {@code https://t.me/multiai_info?text=<urlencoded шаблон с кодом и логом ошибки>}.
 *
 * <p>Telegram не принимает слишком длинные URL (> 2-4 KB), поэтому лог обрезается.</p>
 */
public final class SupportLinkBuilder {

    public static final String SUPPORT_CHAT = "https://t.me/multiai_info";

    // Telegram URL limit is fuzzy; keep total encoded payload well under 1.5 KB.
    private static final int MAX_LOG_CHARS = 600;

    private SupportLinkBuilder() {}

    /** Ссылка с зашитым текстом «Здравствуйте! у меня возникла ошибка <CODE>, помогите! ...». */
    public static String build(String errorCode) {
        return build(errorCode, null);
    }

    /** Ссылка с кодом ошибки + логом. */
    public static String build(String errorCode, String errorLog) {
        String code = errorCode != null && !errorCode.isEmpty() ? errorCode : "UNKNOWN";
        StringBuilder text = new StringBuilder();
        text.append("Здравствуйте! у меня возникла ошибка ").append(code).append(", помогите!");
        if (errorLog != null && !errorLog.isEmpty()) {
            String clipped = errorLog;
            if (clipped.length() > MAX_LOG_CHARS) {
                clipped = clipped.substring(0, MAX_LOG_CHARS) + "...";
            }
            text.append("\nВот логи ошибки:\n\"").append(clipped).append("\"");
        }
        try {
            return SUPPORT_CHAT + "?text=" + URLEncoder.encode(text.toString(), "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }
}
