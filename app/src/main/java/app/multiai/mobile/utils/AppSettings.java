package app.multiai.mobile.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки приложения
 */
public class AppSettings {
    private static final String PREFS_NAME = "multiaiide_settings";
    private static final String KEY_STREAMING = "streaming_enabled";
    private static final String KEY_MODE = "ai_mode";
    private static final String KEY_USE_TOOLS = "use_tools";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_CHAT_MODE = "chat_mode"; // "chat" or "project"
    private static final String KEY_TTS_PROVIDER = "tts_provider"; // "edge" (old) or "teratts" (new neural)
    private static final String KEY_TTS_VOICE = "tts_voice"; // voice id/name

    // ── Personalization ──
    public static final String KEY_PERS_TONE = "pers_tone"; // default|professional|friendly|candid|whimsical|efficient|sarcastic
    public static final String KEY_PERS_TRAITS = "pers_traits"; // CSV: warmer,excited,less_headers,more_emoji + custom
    public static final String KEY_PERS_CUSTOM_TRAITS = "pers_custom_traits"; // CSV of user-added
    public static final String KEY_PERS_INSTRUCTIONS = "pers_instructions"; // text field
    public static final String KEY_PERS_WEB_SEARCH = "pers_web_search"; // bool

    // ── Memory prefs ──
    public static final String KEY_MEM_REFERENCE = "mem_reference"; // bool — ссылаться на память
    public static final String KEY_MEM_CHAT_HISTORY = "mem_chat_history"; // bool — использовать журналы
    public static final String KEY_MEM_NICKNAME = "mem_nickname";
    public static final String KEY_MEM_PROFESSION = "mem_profession";
    public static final String KEY_MEM_ABOUT = "mem_about";

    // ── Appearance ──
    public static final String KEY_THEME = "theme"; // system|light|dark
    public static final String KEY_ACCENT = "accent"; // default|blue|green|yellow|pink|orange

    // ── Data controls ──
    public static final String KEY_IMPROVE_MODEL = "improve_model";

    // ── General ──
    public static final String KEY_LANGUAGE = "language"; // ru|en

    // ── Security ──
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";        // bool — биометрия при входе
    private static final String KEY_SCREENSHOT_BLOCK = "screenshot_block_enabled";  // bool — FLAG_SECURE
    private static final String KEY_SHAKE_TO_REPORT = "shake_to_report_enabled";    // bool — встряхивание → баг-репорт

    // ── General ──
    private static final String KEY_SHOW_SEGMENTED_MODE = "show_segmented_mode";   // bool — segmented pill in top bar

    // ── Emoji style ──
    // "system" (default) | "static" (Telemoji WebP) | "animated" (Telemoji Lottie)
    public static final String KEY_EMOJI_MODE = "emoji_mode";
    public static final String EMOJI_MODE_SYSTEM = "system";
    public static final String EMOJI_MODE_STATIC = "static";
    public static final String EMOJI_MODE_ANIMATED = "animated";

    // ── Workspace (main-screen setting shown in settings) ──
    // stored as chat_mode key above: "chat" = Личная, "project" = Рабочая

    private final SharedPreferences prefs;
    private final Context context;

    public AppSettings(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Streaming включен/выключен
     */
    public boolean isStreamingEnabled() {
        return prefs.getBoolean(KEY_STREAMING, true); // По умолчанию включен
    }

    public void setStreamingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STREAMING, enabled).apply();
    }

    /**
     * Режим AI: "thinking" или "no_thinking"
     */
    public String getAiMode() {
        return prefs.getString(KEY_MODE, "thinking"); // По умолчанию thinking
    }

    public void setAiMode(String mode) {
        prefs.edit().putString(KEY_MODE, mode).apply();
    }

    /** convenience для тумблера «Показывать размышления». */
    public boolean isAiModeThinking() {
        return !"no_thinking".equals(getAiMode());
    }

    /**
     * Использовать инструменты (tools)
     */
    public boolean isUseToolsEnabled() {
        return prefs.getBoolean(KEY_USE_TOOLS, true); // По умолчанию включено
    }

    public void setUseToolsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_USE_TOOLS, enabled).apply();
    }

    /**
     * Режим приложения: "chat" (обычный чат) или "project" (с файловыми инструментами)
     */
    public String getChatMode() {
        return prefs.getString(KEY_CHAT_MODE, "project");
    }

    public void setChatMode(String mode) {
        prefs.edit().putString(KEY_CHAT_MODE, mode).apply();
    }

    public boolean isChatMode() {
        return "chat".equals(getChatMode());
    }

    /**
     * Пользовательские инструкции (дополнение к системному промпту на бэкенде)
     */
    public String getUserInstructions() {
        return prefs.getString(KEY_SYSTEM_PROMPT, "");
    }

    public void setUserInstructions(String instructions) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, instructions).apply();
        syncInstructionsToCloud();
    }

    /** Async push instructions to cloud */
    private void syncInstructionsToCloud() {
        new Thread(() -> {
            try {
                new CloudSyncManager(context).pushInstructions();
            } catch (Exception e) {
            }
        }).start();
    }

    // ══════════════════ TTS ══════════════════

    public static final String TTS_EDGE = "edge";           // Microsoft Edge TTS (через бэкенд multiai.store)
    public static final String TTS_QWEN = "qwen";           // Qwen3 TTS (напрямую через HF Gradio)
    private static final String KEY_TTS_SPEED = "tts_speed";
    private static final String KEY_TTS_PITCH_HZ = "tts_pitch_hz";
    private static final String KEY_TTS_VOLUME_OFFSET = "tts_volume_offset";
    private static final float TTS_SPEED_MIN = 0.5f;
    private static final float TTS_SPEED_MAX = 1.5f;
    private static final int TTS_PITCH_MIN_HZ = -50;
    private static final int TTS_PITCH_MAX_HZ = 50;
    private static final int TTS_VOLUME_MIN_OFFSET = -50;
    private static final int TTS_VOLUME_MAX_OFFSET = 50;
    // Устаревшие провайдеры — только для миграции старых настроек на edge.
    private static final String TTS_TERATTS = "teratts";
    private static final String TTS_OPENAI = "openai";

    /**
     * Какой TTS использовать для озвучки. По умолчанию edge (Svetlana —
     * стабильный, быстрый). Старые значения (teratts/openai) мигрируются на edge.
     */
    public String getTtsProvider() {
        String provider = prefs.getString(KEY_TTS_PROVIDER, TTS_EDGE);
        if (TTS_TERATTS.equals(provider) || TTS_OPENAI.equals(provider)) {
            prefs.edit().putString(KEY_TTS_PROVIDER, TTS_EDGE).apply();
            return TTS_EDGE;
        }
        return TTS_QWEN.equals(provider) ? TTS_QWEN : TTS_EDGE;
    }

    public void setTtsProvider(String provider) {
        String safe = TTS_QWEN.equals(provider) ? TTS_QWEN : TTS_EDGE;
        prefs.edit().putString(KEY_TTS_PROVIDER, safe).apply();
    }

    /** Выбранный голос. Для edge — имя вроде "ru-RU-SvetlanaNeural", для qwen — id голоса Qwen3. */
    public String getTtsVoice() {
        String provider = getTtsProvider();
        String defaultVoice = TTS_QWEN.equals(provider)
                ? VoiceCatalog.DEFAULT_QWEN_VOICE : VoiceCatalog.DEFAULT_EDGE_VOICE;
        String stored = prefs.getString(KEY_TTS_VOICE + "_" + provider, defaultVoice);
        // Голос мог быть удалён из каталога (напр. старый TeraTTS-id) — откат на дефолт.
        if (!VoiceCatalog.isValid(provider, stored)) return defaultVoice;
        return stored;
    }

    public void setTtsVoice(String voice) {
        prefs.edit().putString(KEY_TTS_VOICE + "_" + getTtsProvider(), voice).apply();
    }

    /** Скорость озвучки: 0.5x–1.5x. Edge получает rate, Qwen — скорость плеера. */
    public float getTtsSpeed() {
        return clampTtsSpeed(prefs.getFloat(KEY_TTS_SPEED, 1.3f));
    }

    public void setTtsSpeed(float speed) {
        prefs.edit().putFloat(KEY_TTS_SPEED, clampTtsSpeed(speed)).apply();
    }

    /** Высота голоса Edge TTS: от -50 до +50 Hz. */
    public int getTtsPitchHz() {
        return clampTtsInt(prefs.getInt(KEY_TTS_PITCH_HZ, 0),
                TTS_PITCH_MIN_HZ, TTS_PITCH_MAX_HZ);
    }

    public void setTtsPitchHz(int pitchHz) {
        prefs.edit().putInt(KEY_TTS_PITCH_HZ, clampTtsInt(pitchHz,
                TTS_PITCH_MIN_HZ, TTS_PITCH_MAX_HZ)).apply();
    }

    /** Громкость Edge TTS относительно обычной: от -50% до +50%. */
    public int getTtsVolumeOffsetPercent() {
        return clampTtsInt(prefs.getInt(KEY_TTS_VOLUME_OFFSET, 0),
                TTS_VOLUME_MIN_OFFSET, TTS_VOLUME_MAX_OFFSET);
    }

    public void setTtsVolumeOffsetPercent(int volumeOffset) {
        prefs.edit().putInt(KEY_TTS_VOLUME_OFFSET, clampTtsInt(volumeOffset,
                TTS_VOLUME_MIN_OFFSET, TTS_VOLUME_MAX_OFFSET)).apply();
    }

    public String getTtsEdgeRate() {
        return signedTtsValue(Math.round((getTtsSpeed() - 1f) * 100f), "%");
    }

    public String getTtsEdgePitch() {
        return signedTtsValue(getTtsPitchHz(), "Hz");
    }

    public String getTtsEdgeVolume() {
        return signedTtsValue(getTtsVolumeOffsetPercent(), "%");
    }

    private static float clampTtsSpeed(float value) {
        return Math.max(TTS_SPEED_MIN, Math.min(TTS_SPEED_MAX, value));
    }

    private static int clampTtsInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String signedTtsValue(int value, String suffix) {
        return (value >= 0 ? "+" : "") + value + suffix;
    }

    /**
     * Атомарно переключает провайдера и его голос. Так UI не может успеть
     * сохранить voice id в ключ предыдущего провайдера во время переключения.
     */
    public void setTtsSelection(String provider, String voice) {
        String safeProvider = TTS_QWEN.equals(provider) ? TTS_QWEN : TTS_EDGE;
        String defaultVoice = TTS_QWEN.equals(safeProvider)
                ? VoiceCatalog.DEFAULT_QWEN_VOICE : VoiceCatalog.DEFAULT_EDGE_VOICE;
        String safeVoice = VoiceCatalog.isValid(safeProvider, voice) ? voice : defaultVoice;
        prefs.edit()
                .putString(KEY_TTS_PROVIDER, safeProvider)
                .putString(KEY_TTS_VOICE + "_" + safeProvider, safeVoice)
                .apply();
    }

    // ══════════════════ PERSONALIZATION ══════════════════

    public String getPersonalizationTone() {
        return prefs.getString(KEY_PERS_TONE, "default");
    }

    public void setPersonalizationTone(String tone) {
        prefs.edit().putString(KEY_PERS_TONE, tone).apply();
        new CloudSyncManager(context).pushPersonalization();
    }

    /** Локальная запись без облачного пуша (для pull из облака). */
    public void setPersonalizationToneLocal(String tone) {
        prefs.edit().putString(KEY_PERS_TONE, tone).apply();
    }

    /**
     * Значение trait-а: "more", "default" или "less".
     * traitKey: warmth | excitement | headers | emoji (или кастомные)
     */
    public String getTraitLevel(String traitKey) {
        return prefs.getString("trait_" + traitKey, "default");
    }

    public void setTraitLevel(String traitKey, String level) {
        prefs.edit().putString("trait_" + traitKey, level).apply();
    }

    public void clearAllTraits() {
        android.content.SharedPreferences.Editor e = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("trait_")) e.remove(key);
        }
        e.apply();
    }

    /** Return CSV of selected traits (legacy shape) */
    public String getPersonalizationTraits() {
        return prefs.getString(KEY_PERS_TRAITS, "");
    }

    public void setPersonalizationTraits(String csv) {
        prefs.edit().putString(KEY_PERS_TRAITS, csv == null ? "" : csv).apply();
    }

    public String getPersonalizationCustomTraits() {
        return prefs.getString(KEY_PERS_CUSTOM_TRAITS, "");
    }

    public void setPersonalizationCustomTraits(String csv) {
        prefs.edit().putString(KEY_PERS_CUSTOM_TRAITS, csv == null ? "" : csv).apply();
    }

    public String getPersonalizationInstructions() {
        return prefs.getString(KEY_PERS_INSTRUCTIONS, "");
    }

    public void setPersonalizationInstructions(String text) {
        prefs.edit().putString(KEY_PERS_INSTRUCTIONS, text == null ? "" : text).apply();
        new CloudSyncManager(context).pushPersonalization();
    }

    /** Локальная запись без облачного пуша (для pull из облака). */
    public void setPersonalizationInstructionsLocal(String text) {
        prefs.edit().putString(KEY_PERS_INSTRUCTIONS, text == null ? "" : text).apply();
    }

    public boolean isWebSearchEnabled() {
        return prefs.getBoolean(KEY_PERS_WEB_SEARCH, true);
    }

    public void setWebSearchEnabled(boolean b) {
        prefs.edit().putBoolean(KEY_PERS_WEB_SEARCH, b).apply();
    }

    // ══════════════════ MEMORY ══════════════════

    public boolean isMemoryReferenceEnabled() {
        return prefs.getBoolean(KEY_MEM_REFERENCE, true);
    }

    public void setMemoryReferenceEnabled(boolean b) {
        prefs.edit().putBoolean(KEY_MEM_REFERENCE, b).apply();
    }

    public boolean isMemoryChatHistoryEnabled() {
        return prefs.getBoolean(KEY_MEM_CHAT_HISTORY, true);
    }

    public void setMemoryChatHistoryEnabled(boolean b) {
        prefs.edit().putBoolean(KEY_MEM_CHAT_HISTORY, b).apply();
    }

    public String getNickname() {
        return prefs.getString(KEY_MEM_NICKNAME, "");
    }

    public void setNickname(String s) {
        prefs.edit().putString(KEY_MEM_NICKNAME, s == null ? "" : s).apply();
        new CloudSyncManager(context).pushPersonalization();
    }

    /** Локальная запись без облачного пуша (для pull из облака). */
    public void setNicknameLocal(String s) {
        prefs.edit().putString(KEY_MEM_NICKNAME, s == null ? "" : s).apply();
    }

    public String getProfession() {
        return prefs.getString(KEY_MEM_PROFESSION, "");
    }

    public void setProfession(String s) {
        prefs.edit().putString(KEY_MEM_PROFESSION, s == null ? "" : s).apply();
        new CloudSyncManager(context).pushPersonalization();
    }

    /** Локальная запись без облачного пуша (для pull из облака). */
    public void setProfessionLocal(String s) {
        prefs.edit().putString(KEY_MEM_PROFESSION, s == null ? "" : s).apply();
    }

    public String getAbout() {
        return prefs.getString(KEY_MEM_ABOUT, "");
    }

    public void setAbout(String s) {
        prefs.edit().putString(KEY_MEM_ABOUT, s == null ? "" : s).apply();
        new CloudSyncManager(context).pushPersonalization();
    }

    /** Локальная запись без облачного пуша (для pull из облака). */
    public void setAboutLocal(String s) {
        prefs.edit().putString(KEY_MEM_ABOUT, s == null ? "" : s).apply();
    }

    // ══════════════════ APPEARANCE ══════════════════

    public String getTheme() {
        return prefs.getString(KEY_THEME, "system");
    }

    public void setTheme(String s) {
        prefs.edit().putString(KEY_THEME, s).apply();
    }

    public String getAccent() {
        return prefs.getString(KEY_ACCENT, "default");
    }

    public void setAccent(String s) {
        prefs.edit().putString(KEY_ACCENT, s).apply();
    }

    // ══════════════════ DATA ══════════════════

    public boolean isImproveModelEnabled() {
        return prefs.getBoolean(KEY_IMPROVE_MODEL, true);
    }

    public void setImproveModelEnabled(boolean b) {
        prefs.edit().putBoolean(KEY_IMPROVE_MODEL, b).apply();
    }

    // ══════════════════ LANGUAGE ══════════════════

    public String getLanguage() {
        return prefs.getString(KEY_LANGUAGE, "ru");
    }

    public void setLanguage(String s) {
        prefs.edit().putString(KEY_LANGUAGE, s).apply();
    }

    // ══════════════════ SECURITY ══════════════════

    /** Требовать биометрию (отпечаток / Face Unlock) при запуске приложения. */
    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }

    /** Блокировать скриншоты и запись экрана (FLAG_SECURE) внутри приложения. */
    public boolean isScreenshotBlockEnabled() {
        return prefs.getBoolean(KEY_SCREENSHOT_BLOCK, false);
    }

    public void setScreenshotBlockEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREENSHOT_BLOCK, enabled).apply();
    }

    /** Встряхивание телефона открывает диалог «Сообщить об ошибке». По умолчанию включено. */
    public boolean isShakeToReportEnabled() {
        return prefs.getBoolean(KEY_SHAKE_TO_REPORT, true);
    }

    public void setShakeToReportEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHAKE_TO_REPORT, enabled).apply();
    }

    /** Show the segmented Chat/Work mode pill in the top bar. Default: true. */
    public boolean isSegmentedModeEnabled() {
        return prefs.getBoolean(KEY_SHOW_SEGMENTED_MODE, true);
    }

    public void setSegmentedModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SHOW_SEGMENTED_MODE, enabled).apply();
    }

    // ══════════════════ EMOJI STYLE ══════════════════

    public String getEmojiMode() {
        return prefs.getString(KEY_EMOJI_MODE, EMOJI_MODE_SYSTEM);
    }

    public void setEmojiMode(String mode) {
        String safeMode = EMOJI_MODE_STATIC.equals(mode) || EMOJI_MODE_ANIMATED.equals(mode)
                ? mode : EMOJI_MODE_SYSTEM;
        prefs.edit().putString(KEY_EMOJI_MODE, safeMode).apply();
    }

    public boolean isEmojiModeSystem() {
        return EMOJI_MODE_SYSTEM.equals(getEmojiMode());
    }

    public boolean isEmojiModeStatic() {
        return EMOJI_MODE_STATIC.equals(getEmojiMode());
    }

    public boolean isEmojiModeAnimated() {
        return EMOJI_MODE_ANIMATED.equals(getEmojiMode());
    }
}
