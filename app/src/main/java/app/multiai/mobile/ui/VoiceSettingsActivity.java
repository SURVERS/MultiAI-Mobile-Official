package app.multiai.mobile.ui;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import app.multiai.mobile.R;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.settings.SettingsUI;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.TtsEngine;
import app.multiai.mobile.utils.VoiceCatalog;

/**
 * Экран выбора голоса озвучки (ChatGPT-стайл).
 * Переключатель провайдера (Edge TTS / Qwen3 TTS) + список голосов с превью.
 */
public class VoiceSettingsActivity extends AppCompatActivity {

    private AppSettings settings;
    private String viewingProvider;   // какой провайдер сейчас показан в списке
    private LinearLayout listContainer;
    private TextView headerName;
    private TextView headerSubtitle;
    private TextView headerPreview;
    private TextView listenButton;

    private MediaPlayer previewPlayer;
    private File previewAudioFile;
    private boolean previewLoading;
    private String previewingId;      // id голоса, который сейчас грузится/играет

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        settings = new AppSettings(this);
        viewingProvider = settings.getTtsProvider();

        SettingsUI.Scaffold scaffold = SettingsUI.createScaffold(this, getString(R.string.voice_title), false);
        LinearLayout root = scaffold.content;
        root.setAlpha(0f);
        root.animate().alpha(1f).setDuration(220).start();

        root.addView(buildHeader());
        root.addView(SettingsUI.sectionGap(this));
        root.addView(buildProviderSwitch());
        root.addView(SettingsUI.sectionGap(this));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listContainer);

        buildList();
        updateHeader();
    }

    // ─────────────────────────── HEADER ───────────────────────────

    private View buildHeader() {
        LinearLayout card = SettingsUI.card(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(SettingsUI.dp(this, 18), SettingsUI.dp(this, 18),
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 18));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top);

        ImageView avatar = new ImageView(this);
        int size = SettingsUI.dp(this, 64);
        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColors(new int[]{AccentColor.resolve(this), SettingsUI.colorSurfaceTonal(this)});
        avatarBg.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        avatarBg.setOrientation(GradientDrawable.Orientation.TL_BR);
        avatar.setBackground(avatarBg);
        avatar.setImageResource(R.drawable.ic_voice_wave);
        avatar.setColorFilter(AccentColor.contrastForeground(AccentColor.resolve(this)));
        avatar.setPadding(SettingsUI.dp(this, 18), SettingsUI.dp(this, 18),
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 18));
        avatar.setContentDescription(getString(R.string.voice_title));
        top.addView(avatar, new LinearLayout.LayoutParams(size, size));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams identityLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        identityLp.setMarginStart(SettingsUI.dp(this, 14));
        identity.setLayoutParams(identityLp);
        top.addView(identity);

        TextView eyebrow = new TextView(this);
        eyebrow.setText(getString(R.string.voice_subtitle));
        eyebrow.setTextColor(SettingsUI.colorSubtext(this));
        eyebrow.setTextSize(12);
        identity.addView(eyebrow);

        headerName = new TextView(this);
        headerName.setTextColor(SettingsUI.colorText(this));
        headerName.setTextSize(22);
        headerName.setTypeface(null, Typeface.BOLD);
        identity.addView(headerName);

        headerSubtitle = new TextView(this);
        headerSubtitle.setTextColor(SettingsUI.colorSubtext(this));
        headerSubtitle.setTextSize(13);
        identity.addView(headerSubtitle);

        headerPreview = new TextView(this);
        headerPreview.setTextColor(SettingsUI.colorSubtext(this));
        headerPreview.setTextSize(14);
        headerPreview.setMaxLines(3);
        GradientDrawable previewBg = new GradientDrawable();
        previewBg.setColor(SettingsUI.colorSurfaceTonal(this));
        previewBg.setCornerRadius(SettingsUI.dp(this, 14));
        headerPreview.setBackground(previewBg);
        headerPreview.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 11),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 11));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewLp.topMargin = SettingsUI.dp(this, 16);
        headerPreview.setLayoutParams(previewLp);
        card.addView(headerPreview);

        listenButton = new TextView(this);
        int accent = AccentColor.resolve(this);
        listenButton.setText(getString(R.string.voice_listen));
        listenButton.setTextColor(AccentColor.contrastForeground(accent));
        listenButton.setTextSize(14);
        listenButton.setTypeface(null, Typeface.BOLD);
        listenButton.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(accent);
        btnBg.setCornerRadius(SettingsUI.dp(this, 14));
        listenButton.setBackground(btnBg);
        listenButton.setPadding(SettingsUI.dp(this, 18), SettingsUI.dp(this, 12),
                SettingsUI.dp(this, 18), SettingsUI.dp(this, 12));
        LinearLayout.LayoutParams listenLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listenLp.topMargin = SettingsUI.dp(this, 10);
        listenButton.setLayoutParams(listenLp);
        listenButton.setOnClickListener(v -> {
            VoiceCatalog.Voice current = VoiceCatalog.find(
                    settings.getTtsProvider(), settings.getTtsVoice());
            if (current != null) previewVoice(current);
        });
        card.addView(listenButton);
        return card;
    }

    private void updateHeader() {
        VoiceCatalog.Voice current = VoiceCatalog.find(
                settings.getTtsProvider(), settings.getTtsVoice());
        if (current == null) {
            headerName.setText("—");
            headerSubtitle.setText("");
            if (headerPreview != null) headerPreview.setText("");
            return;
        }
        headerName.setText(current.displayName("en".equals(settings.getLanguage())));
        String prov = VoiceCatalog.providerName(current.provider);
        boolean english = "en".equals(settings.getLanguage());
        String sub = current.subtitle(english);
        headerSubtitle.setText(sub.isEmpty() ? prov : sub + " · " + prov);
        if (headerPreview != null) {
            headerPreview.setText(getString(R.string.voice_header_preview,
                    current.previewText(english)));
        }
        if (listenButton != null) {
            boolean currentPreview = current.id.equals(previewingId);
            listenButton.setText(currentPreview ? getString(R.string.voice_stop)
                    : getString(R.string.voice_listen));
        }
    }

    // ─────────────────────────── PROVIDER SWITCH ───────────────────────────

    private View buildProviderSwitch() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(SettingsUI.dp(this, 2), 0, SettingsUI.dp(this, 2), 0);
        row.addView(buildProviderPill(VoiceCatalog.providerName(VoiceCatalog.PROVIDER_EDGE),
                VoiceCatalog.PROVIDER_EDGE));
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(SettingsUI.dp(this, 10),
                ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(gap);
        row.addView(buildProviderPill(VoiceCatalog.providerName(VoiceCatalog.PROVIDER_QWEN),
                VoiceCatalog.PROVIDER_QWEN));
        return row;
    }

    private View buildProviderPill(String label, String provider) {
        boolean active = provider.equals(viewingProvider);
        TextView pill = new TextView(this);
        pill.setText(label);
        pill.setGravity(Gravity.CENTER);
        pill.setTextSize(14);
        pill.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        int accent = AccentColor.resolve(this);
        pill.setTextColor(active ? AccentColor.contrastForeground(accent) : SettingsUI.colorText(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(SettingsUI.dp(this, 22));
        bg.setColor(active ? accent : SettingsUI.colorSurfaceTonal(this));
        if (!active) bg.setStroke(SettingsUI.dp(this, 1), SettingsUI.colorStroke(this));
        pill.setBackground(bg);
        pill.setPadding(0, SettingsUI.dp(this, 12), 0, SettingsUI.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pill.setLayoutParams(lp);
        pill.setOnClickListener(v -> {
            if (provider.equals(viewingProvider)) return;
            viewingProvider = provider;
            // Пересобираем и переключатель (для активной подсветки), и список.
            rebuildAll();
        });
        return pill;
    }

    private void rebuildAll() {
        LinearLayout root = (LinearLayout) listContainer.getParent();
        root.removeAllViews();
        root.addView(buildHeader());
        root.addView(SettingsUI.sectionGap(this));
        root.addView(buildProviderSwitch());
        root.addView(SettingsUI.sectionGap(this));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);
        buildList();
        updateHeader();
    }

    // ─────────────────────────── VOICE LIST ───────────────────────────

    private interface VoiceSliderFormatter {
        String format(int progress);
    }

    private interface VoiceSliderSaver {
        void save(int progress);
    }

    private View buildVoiceControls() {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.addView(SettingsUI.sectionLabel(this, getString(R.string.voice_controls)));

        LinearLayout card = SettingsUI.card(this);
        float speed = settings.getTtsSpeed();
        card.addView(buildVoiceSlider(
                R.string.voice_speed,
                R.string.voice_speed_hint,
                10,
                Math.round((speed - 0.5f) * 10f),
                progress -> getString(R.string.voice_speed_value, 0.5f + progress / 10f),
                progress -> settings.setTtsSpeed(0.5f + progress / 10f)));

        if (VoiceCatalog.PROVIDER_EDGE.equals(settings.getTtsProvider())) {
            card.addView(SettingsUI.divider(this));
            int pitch = settings.getTtsPitchHz();
            card.addView(buildVoiceSlider(
                    R.string.voice_pitch,
                    R.string.voice_pitch_hint,
                    10,
                    (pitch + 50) / 10,
                    progress -> getString(R.string.voice_pitch_value, progress * 10 - 50),
                    progress -> settings.setTtsPitchHz(progress * 10 - 50)));

            card.addView(SettingsUI.divider(this));
            int volumeOffset = settings.getTtsVolumeOffsetPercent();
            card.addView(buildVoiceSlider(
                    R.string.voice_volume,
                    R.string.voice_volume_hint,
                    10,
                    (volumeOffset + 50) / 10,
                    progress -> getString(R.string.voice_volume_value, 50 + progress * 10),
                    progress -> settings.setTtsVolumeOffsetPercent(progress * 10 - 50)));
        }

        section.addView(card);
        return section;
    }

    private View buildVoiceSlider(int titleRes, int hintRes, int max, int progress,
                                  VoiceSliderFormatter formatter, VoiceSliderSaver saver) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(SettingsUI.dp(this, 16), SettingsUI.dp(this, 14),
                SettingsUI.dp(this, 16), SettingsUI.dp(this, 10));

        LinearLayout headline = new LinearLayout(this);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(titleRes);
        title.setTextColor(SettingsUI.colorText(this));
        title.setTextSize(15);
        title.setTypeface(null, Typeface.BOLD);
        headline.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setText(formatter.format(progress));
        value.setTextColor(AccentColor.resolve(this));
        value.setTextSize(14);
        value.setTypeface(null, Typeface.BOLD);
        headline.addView(value);
        row.addView(headline);

        TextView hint = new TextView(this);
        hint.setText(hintRes);
        hint.setTextColor(SettingsUI.colorSubtext(this));
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = SettingsUI.dp(this, 2);
        row.addView(hint, hintLp);

        android.widget.SeekBar slider = new android.widget.SeekBar(this);
        slider.setMax(max);
        slider.setProgress(progress);
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(
                AccentColor.resolve(this));
        slider.setProgressTintList(accent);
        slider.setThumbTintList(accent);
        slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(
                SettingsUI.colorStroke(this)));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sliderLp.topMargin = SettingsUI.dp(this, 2);
        row.addView(slider, sliderLp);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int valueProgress,
                                          boolean fromUser) {
                value.setText(formatter.format(valueProgress));
                if (fromUser) saver.save(valueProgress);
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {
            }
        });
        return row;
    }

    private void buildList() {
        listContainer.removeAllViews();
        listContainer.addView(buildVoiceControls());
        listContainer.addView(SettingsUI.spacer(this, 16));

        TextView label = SettingsUI.sectionLabel(this,
                getString(R.string.voice_provider_voices,
                        VoiceCatalog.providerName(viewingProvider)));
        listContainer.addView(label);
        LinearLayout.LayoutParams labelLp = (LinearLayout.LayoutParams) label.getLayoutParams();
        labelLp.bottomMargin = SettingsUI.dp(this, 8);
        label.setLayoutParams(labelLp);
        LinearLayout card = SettingsUI.card(this);
        java.util.List<VoiceCatalog.Voice> voices = VoiceCatalog.forProvider(viewingProvider);
        for (int i = 0; i < voices.size(); i++) {
            if (i > 0) card.addView(SettingsUI.divider(this));
            card.addView(buildVoiceRow(voices.get(i)));
        }
        listContainer.addView(card);
    }

    private View buildVoiceRow(VoiceCatalog.Voice voice) {
        boolean selected = voice.provider.equals(settings.getTtsProvider())
                && voice.id.equals(settings.getTtsVoice());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(SettingsUI.dp(this, 60));
        row.setPadding(SettingsUI.dp(this, 14), SettingsUI.dp(this, 10),
                SettingsUI.dp(this, 14), SettingsUI.dp(this, 10));
        int accent = AccentColor.resolve(this);
        int selectedFill = (accent & 0x00FFFFFF) | 0x1F000000;
        GradientDrawable rowSurface = new GradientDrawable();
        rowSurface.setColor(selected ? selectedFill : SettingsUI.colorCard(this));
        rowSurface.setCornerRadius(SettingsUI.dp(this, 14));
        if (selected) rowSurface.setStroke(SettingsUI.dp(this, 1), accent);
        android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                new int[][]{new int[]{}}, new int[]{SettingsUI.colorRipple(this)});
        row.setBackground(new android.graphics.drawable.RippleDrawable(csl, rowSurface, null));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> selectVoice(voice));

        // Кнопка превью (динамик).
        ImageView play = new ImageView(this);
        play.setImageResource(R.drawable.ic_volume);
        play.setColorFilter(previewingId != null && previewingId.equals(voice.id)
                ? AccentColor.resolve(this) : SettingsUI.colorMutedIcon(this));
        int pSize = SettingsUI.dp(this, 40);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(pSize, pSize);
        pLp.setMarginEnd(SettingsUI.dp(this, 6));
        play.setLayoutParams(pLp);
        play.setPadding(SettingsUI.dp(this, 8), SettingsUI.dp(this, 8),
                SettingsUI.dp(this, 8), SettingsUI.dp(this, 8));
        GradientDrawable playBg = new GradientDrawable();
        playBg.setShape(GradientDrawable.OVAL);
        playBg.setColor(previewingId != null && previewingId.equals(voice.id)
                ? ((accent & 0x00FFFFFF) | 0x33000000) : SettingsUI.colorSurfaceTonal(this));
        play.setBackground(new android.graphics.drawable.RippleDrawable(csl, playBg, null));
        play.setContentDescription(getString(R.string.voice_preview_content_description,
                voice.displayName("en".equals(settings.getLanguage()))));
        play.setClickable(true);
        play.setFocusable(true);
        play.setOnClickListener(v -> previewVoice(voice));
        row.addView(play);

        // Имя + подпись.
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(tLp);

        TextView name = new TextView(this);
        name.setText(voice.displayName("en".equals(settings.getLanguage())));
        name.setTextColor(SettingsUI.colorText(this));
        name.setTextSize(16);
        name.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        texts.addView(name);

        String subtitle = voice.subtitle("en".equals(settings.getLanguage()));
        if (!subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(subtitle);
            sub.setTextColor(SettingsUI.colorSubtext(this));
            sub.setTextSize(13);
            texts.addView(sub);
        }
        row.addView(texts);

        // Чекмарк выбранного.
        if (selected) {
            ImageView check = new ImageView(this);
            check.setImageResource(R.drawable.ic_check);
            check.setColorFilter(AccentColor.resolve(this));
            LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                    SettingsUI.dp(this, 22), SettingsUI.dp(this, 22));
            chLp.setMarginStart(SettingsUI.dp(this, 8));
            check.setLayoutParams(chLp);
            row.addView(check);
        }
        return row;
    }

    private void selectVoice(VoiceCatalog.Voice voice) {
        boolean wasSelected = voice.provider.equals(settings.getTtsProvider())
                && voice.id.equals(settings.getTtsVoice());
        stopPreview();
        settings.setTtsSelection(voice.provider, voice.id);
        ModernToast.show(this, getString(R.string.voice_selected,
                    voice.displayName("en".equals(settings.getLanguage()))));
        buildList();
        updateHeader();
        // Voice picker should make a choice audible immediately. Otherwise it
        // feels like every row has selected the same voice until a second tap.
        if (!wasSelected) previewVoice(voice);
    }

    // ─────────────────────────── PREVIEW ───────────────────────────

    private void previewVoice(VoiceCatalog.Voice voice) {
        // Повторный тап по играющему/грузящемуся голосу — стоп.
        if (previewingId != null && previewingId.equals(voice.id)) {
            stopPreview();
            buildList();
            updateHeader();
            return;
        }
        stopPreview();
        if (previewLoading) return;
        previewLoading = true;
        previewingId = voice.id;
        buildList();
        updateHeader();
        ModernToast.show(this, getString(R.string.voice_preview_generating));

        final String rate = settings.getTtsEdgeRate();
        final String pitch = settings.getTtsEdgePitch();
        final String volume = settings.getTtsEdgeVolume();
        final float requestedSpeed = settings.getTtsSpeed();
        new Thread(() -> {
            try {
                TtsEngine.SynthesisResult result = TtsEngine.synthesizeWithFallback(
                        this, voice.provider, voice.id,
                        voice.previewText("en".equals(settings.getLanguage())),
                        rate, pitch, volume);
                final float playbackSpeed = VoiceCatalog.PROVIDER_QWEN.equals(result.provider)
                        ? requestedSpeed : 1f;
                runOnUiThread(() -> {
                    previewLoading = false;
                    if (previewingId == null || !previewingId.equals(voice.id)) {
                        result.audioFile.delete();
                        return;
                    }
                    if (result.fallbackUsed) {
                        ModernToast.show(this, getString(R.string.voice_preview_fallback));
                    }
                    playPreview(result.audioFile, voice.id, playbackSpeed);
                });
            } catch (Exception e) {
                android.util.Log.w("VoiceSettings", "Preview generation failed", e);
                runOnUiThread(() -> {
                    previewLoading = false;
                    previewingId = null;
                    buildList();
                    updateHeader();
                    ModernToast.show(this, getString(R.string.voice_preview_error));
                });
            }
        }, "voice-preview").start();
    }

    private void playPreview(File audio, String voiceId, float playbackSpeed) {
        try {
            previewAudioFile = audio;
            previewPlayer = new MediaPlayer();
            previewPlayer.setDataSource(audio.getAbsolutePath());
            previewPlayer.setOnCompletionListener(mp -> finishPreviewPlayback());
            previewPlayer.setOnErrorListener((mp, what, extra) -> {
                android.util.Log.w("VoiceSettings", "Preview playback error " + what + "/" + extra);
                finishPreviewPlayback();
                ModernToast.show(this, getString(R.string.voice_preview_error));
                return true;
            });
            previewPlayer.setOnPreparedListener(mp -> {
                try {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(playbackSpeed));
                    }
                    mp.start();
                } catch (Exception e) {
                    android.util.Log.w("VoiceSettings", "Preview start failed", e);
                    finishPreviewPlayback();
                    ModernToast.show(this, getString(R.string.voice_preview_error));
                }
            });
            previewPlayer.prepareAsync();
        } catch (Exception e) {
            android.util.Log.w("VoiceSettings", "Preview setup failed", e);
            finishPreviewPlayback();
            ModernToast.show(this, getString(R.string.voice_preview_error));
        }
    }

    private void finishPreviewPlayback() {
        if (previewPlayer != null) {
            try { previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (previewAudioFile != null) {
            previewAudioFile.delete();
            previewAudioFile = null;
        }
        previewLoading = false;
        previewingId = null;
        buildList();
        updateHeader();
    }

    private void stopPreview() {
        if (previewPlayer != null) {
            try { previewPlayer.stop(); previewPlayer.release(); } catch (Exception ignored) {}
            previewPlayer = null;
        }
        if (previewAudioFile != null) {
            previewAudioFile.delete();
            previewAudioFile = null;
        }
        previewLoading = false;
        previewingId = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPreview();
    }
}
