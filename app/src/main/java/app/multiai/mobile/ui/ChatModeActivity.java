package app.multiai.mobile.ui;

import app.multiai.mobile.BuildConfig;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.animation.ValueAnimator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import app.multiai.mobile.R;
import app.multiai.mobile.adapters.ChatModeChatsAdapter;
import app.multiai.mobile.adapters.ChatModeMessagesAdapter;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.MultiAiError;
import app.multiai.mobile.network.RetryPolicy;
import app.multiai.mobile.network.StreamingTextNormalizer;
import app.multiai.mobile.network.oauth.OAuthLoginController;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernMenu;

/**
 * Chat Mode Activity — чистый ChatGPT-стиль интерфейс.
 * Только чат, без файлов/терминала/редактора.
 * Доступные инструменты: погода, время, веб-поиск, url_fetch.
 */
public class ChatModeActivity extends AppCompatActivity implements
        app.multiai.mobile.utils.ChatsClearedNotifier.Listener {

    private static final String TAG = "ChatModeActivity";
    private static final String PREFS_NAME = "multiai_profile";
    private static final String BACKEND_URL = "https://multiai.store";

    private boolean profileReceiverRegistered;
    private final BroadcastReceiver profileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadSidebarAvatar();
        }
    };

    // UI
    private EditText editMessage;
    private ImageButton btnSend, btnStop, btnAttach, btnSidebar, btnNewChat, btnComposeChat,
            btnChatMore, btnVoice, btnMic;
    private View btnRemoveAttachment;
    private RecyclerView recyclerMessages;
    private ChatModeMessagesAdapter messagesAdapter;
    private ChatModeChatsAdapter sidebarChatsAdapter;
    private View emptyState;
    private FloatingActionButton fabScrollDown;
    private TextView brandingPill;
    private app.multiai.mobile.ui.kit.MultiAiConnectionStatus connectionStatus;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private EditText sidebarSearchInput;
    private TextView sidebarTitle;
    private View sidebarBrandLogo;
    private View sidebarBrandBlock;
    private ViewGroup sidebarSearchGroup;
    private android.widget.ImageView sidebarProfile;
    private ImageButton sidebarSearchButton;
    private View attachmentPreviewCard;
    private ImageView attachmentPreview;
    private TextView attachmentFileName;
    private TextView attachmentFileSize;
    private View attachmentImageBox;
    private View attachmentFileBox;
    private ProgressBar attachmentLoading;
    private LinearLayout voiceOverlay;
    // Voice UI (state machine: Idle → Recording → Transcribing → Editing)
    private androidx.constraintlayout.motion.widget.MotionLayout inputMotionLayout;
    private FrameLayout standardInputGroup;
    private LinearLayout voiceInputGroup;
    private LinearLayout transcribingGroup;
    private TextView transcribeLabel;
    private View transcribeDot1, transcribeDot2, transcribeDot3;
    private ImageButton btnVoiceCancel, btnVoiceStop, btnVoiceSend;
    private VoiceWaveformView voiceWaveform;
    private android.animation.AnimatorSet transcribingAnimatorSet;
    private boolean voiceSentByUser = false;
    private boolean voiceCancelled = false;
    private enum InputState { IDLE, RECORDING, TRANSCRIBING }
    private InputState currentInputState = InputState.IDLE;
    private boolean sidebarSearchExpanded = false;
    private ValueAnimator sidebarSearchWidthAnimator;
    private boolean lastSegmentedSetting = false;
    private String lastEmojiMode = AppSettings.EMOJI_MODE_SYSTEM;
    private boolean temporaryChatEnabled = false;
    private final List<Message> temporaryMessages = new ArrayList<>();
    /** Временный чат не сохраняется, поэтому его сводка живёт только в Activity. */
    private String temporaryContextSummary;
    private int temporaryContextSummaryMessageCount;
    private static final double AUTO_SUMMARY_TRIGGER_RATIO = 0.72d;
    private static final int AUTO_SUMMARY_TAIL_MESSAGE_COUNT = 8;
    private static final int AUTO_SUMMARY_MIN_MESSAGES = 6;
    private static final int AUTO_SUMMARY_MIN_NEW_MESSAGES = 2;
    private boolean autoSummarizing = false;
    private boolean skipAutoSummaryOnce = false;
    private View pillSlider;
    private View chatActionsPill;

    // Sidebar
    private FrameLayout sidebarOverlay;
    private boolean sidebarOpen = false;
    private float sidebarSwipeStartX;
    private float sidebarSwipeStartY;
    private float sidebarSwipeStartTranslationX;
    private boolean sidebarSwipeTracking = false;
    private boolean sidebarSwipeDragging = false;
    /** Глобальный свайп (из любого места) сработал в этом жесте — один раз. */
    private boolean globalSwipeHandled = false;

    // Shake-to-report (gated by AppSettings.shakeToReportEnabled)
    private app.multiai.mobile.utils.ShakeDetector shakeDetector;

    /** Apply shake-to-report state to the live detector without a full re-register. */
    public void applyShakeEnabled(boolean enabled) {
        if (shakeDetector != null) {
            shakeDetector.setEnabled(enabled);
        } else if (enabled) {
            shakeDetector = new app.multiai.mobile.utils.ShakeDetector(
                    () -> app.multiai.mobile.ui.kit.ReportBugSheet.show(ChatModeActivity.this));
            shakeDetector.register(this);
        }
    }

    // State
    private static final String CHATMODE_PREFS = "multiai_chatmode_chats";
    private ChatManager chatManager;
    private Chat currentChat;
    private View mainContent;
    private List<Chat> allChats = new ArrayList<>();
    private volatile boolean isGenerating = false;
    private Message retrySourceMessage;
    private Message retrySourceUser;
    /** Последний список, уже переданный адаптеру через loadMessages(). */
    private String loadedMessagesSourceKey;
    private long loadedMessagesFingerprint = Long.MIN_VALUE;
    private boolean completedInitialResume;
    private static final int AUTO_SCROLL_NEAR_BOTTOM_THRESHOLD_PX = 180;
    /** While false the user is reading older messages; stream updates never steal the scroll. */
    private boolean followStreamToBottom = true;
    private boolean userScrollingMessages = false;
    /** Эпоха стрима: инкремент при переключении чата. Колбеки старой эпохи
     *  игнорируются — ответ не дописывается в чужой чат. */
    private volatile int streamEpoch = 0;
    private String selectedModelId;
    private String selectedModelName;
    private List<String[]> availableModels = new ArrayList<>();
    private final List<app.multiai.mobile.models.ModelInfo> modelCatalog = new ArrayList<>();
    private String selectedEffort;
    /** Персистентный клиент — хранит выбранный reasoning effort между запросами. */
    private final MultiAiClient multiAiClient = new MultiAiClient();
    /** Отдельный клиент для моделей кастомных провайдеров — минует multiai.store. */
    private final app.multiai.mobile.network.CustomProviderChatClient customProviderChatClient =
            new app.multiai.mobile.network.CustomProviderChatClient();
    private Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private Uri cameraPhotoUri;
    private String pendingPermissionAction;
    private boolean isPickerOpening = false;
    private android.widget.PopupWindow attachPopup;
    private boolean attachMenuOpen;
    private String selectedImageBase64;
    private String selectedImageName;
    private String attachedFileName;
    private String attachedFileContent;
    private android.media.MediaRecorder mediaRecorder;
    private java.io.File audioFile;
    private boolean isListening = false;
    private android.animation.ObjectAnimator micPulseAnimator;
    private static final long VOICE_SILENCE_TIMEOUT_MS = 15000L;
    private static final int VOICE_SILENCE_AMPLITUDE = 500;
    // Автообрыв записи: 10 минут. Жёсткого минутного лимита больше нет —
    // длинные голосовые (10+ минут) не должны обрезаться посреди мысли.
    private static final long VOICE_MAX_DURATION_MS = 10L * 60 * 1000;
    private Handler voiceSilenceHandler;
    private Runnable voiceSilenceRunnable;
    private long silenceStartedAt;
    private long voiceRecordingStartedAt;
    // Несохранённая запись: после ошибки распознавания файл живёт, чтобы можно было
    // повторить попытку и не перезаписывать 10 минут заново.
    private java.io.File pendingVoiceFile;

    /**
     * Сохраняет input bar над системной навигацией. IME обрабатывается
     * adjustResize, поэтому navigation inset не суммируется с клавиатурой.
     */
    private void configureInputInsets() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        View inputContainer = findViewById(R.id.inputContainer);
        if (inputContainer == null) return;
        final int baseLeft = inputContainer.getPaddingLeft();
        final int baseTop = inputContainer.getPaddingTop();
        final int baseRight = inputContainer.getPaddingRight();
        final int baseBottom = inputContainer.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                inputContainer, (view, insets) -> {
                    boolean imeVisible = keyboardVisible || insets.isVisible(
                            androidx.core.view.WindowInsetsCompat.Type.ime());
                    int navigationBottom = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                    // Отступ клавиатура↔поле уменьшен ~в 1.5 раза (20dp -> 13dp).
                    int bottom = imeVisible ? 0
                            : Math.max(baseBottom, Math.max(navigationBottom, dp(13)));
                    view.setPadding(baseLeft, baseTop, baseRight, bottom);
                    return insets;
                });
        inputContainer.post(() ->
                androidx.core.view.ViewCompat.requestApplyInsets(inputContainer));
    }

    /**
     * Роутит отправку сообщения: если {@code selectedModelId} принадлежит кастомному
     * провайдеру — запрос уходит напрямую на его baseURL, минуя multiai.store.
     * Tools-схема передаётся обоим путям (CustomProviderChatClient конвертирует
     * её под формат провайдера — OpenAI tools или Anthropic input_schema).
     */
    /**
     * Embedding-модели (bge-m3…) не умеют чат — прогоняем текст через
     * /v1/embeddings и отдаём сводку вектора как обычный ответ. Фон-поток.
     */
    private boolean handleEmbeddingTurn(app.multiai.mobile.utils.CustomProviderStore.ResolvedCustomModel resolved,
                                        String userMessage, MultiAiClient.StreamCallback callback) {
        if (resolved == null || !resolved.isEmbeddingModel()) return false;
        final app.multiai.mobile.utils.CustomProviderStore.ResolvedCustomModel fr = resolved;
        final String input = userMessage == null ? "" : userMessage.trim();
        new Thread(() -> {
            try {
                app.multiai.mobile.network.CustomProviderClient.EmbeddingResult r =
                        app.multiai.mobile.network.CustomProviderClient.embed(
                                fr.provider, fr.requestName, input);
                runOnUiThread(() -> {
                    String summary = app.multiai.mobile.network.CustomProviderClient
                            .formatEmbeddingSummary(this, fr.requestName, input, r);
                    callback.onContent(summary);
                    callback.onComplete();
                });
            } catch (Exception e) {
                runOnUiThread(() -> callback.onError(getString(
                        R.string.embed_failed, e.getMessage() != null ? e.getMessage() : String.valueOf(e))));
            }
        }, "embedding-turn").start();
        return true;
    }

    private void routeSendMessage(String systemPrompt, List<Message> history, String userMessage,
                                  String imageBase64, boolean useTools, boolean stream,
                                  MultiAiClient.StreamCallback callback) {
        app.multiai.mobile.utils.CustomProviderStore.ResolvedCustomModel resolved =
                app.multiai.mobile.utils.CustomProviderStore.resolve(this, selectedModelId);
        if (resolved != null) {
            if (handleEmbeddingTurn(resolved, userMessage, callback)) return;
            customProviderChatClient.syncToolsFrom(multiAiClient);
            customProviderChatClient.sendMessageStream(resolved.provider, resolved.requestName,
                    systemPrompt, history, userMessage, imageBase64, useTools, stream,
                    MultiAiClient.ToolPolicy.FULL, callback);
            return;
        }
        multiAiClient.sendMessageStream(selectedModelId, systemPrompt, history, userMessage,
                imageBase64, useTools, stream, callback);
    }

    /** Тот же роутинг для продолжения разговора из уже собранного JSONArray (после тула). */
    private void routeSendMessage(String systemPrompt, JSONArray messages, boolean useTools,
                                  boolean stream, MultiAiClient.StreamCallback callback) {
        app.multiai.mobile.utils.CustomProviderStore.ResolvedCustomModel resolved =
                app.multiai.mobile.utils.CustomProviderStore.resolve(this, selectedModelId);
        if (resolved != null) {
            if (resolved.isEmbeddingModel()) {
                callback.onError(getString(R.string.error_embedding_not_chat));
                return;
            }
            customProviderChatClient.syncToolsFrom(multiAiClient);
            customProviderChatClient.sendMessageStream(resolved.provider, resolved.requestName,
                    systemPrompt, messages, useTools, stream,
                    MultiAiClient.ToolPolicy.FULL, callback);
            return;
        }
        multiAiClient.sendMessageStream(selectedModelId, systemPrompt, messages, useTools, stream, callback);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        app.multiai.mobile.utils.ChatsClearedNotifier.register(this);
        // Обязательные обновления: проверка релиза + диалог (в т.ч. блокирующий).
        app.multiai.mobile.utils.UpdateManager.checkOnLaunch(this);
        setContentView(R.layout.activity_chat_mode);
        configureInputInsets();
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        int background = ContextCompat.getColor(this, R.color.bg);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        boolean lightBars = getResources().getBoolean(R.bool.light_status_bar);
        int systemUi = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (lightBars) systemUi |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(systemUi);

        chatManager = new ChatManager(this, CHATMODE_PREFS);

        // Pull cloud data on start (memories, instructions, chatmode chats) if signed in
        if (OAuthSession.get().isAuthorized()) {
            new Thread(() -> {
                try {
                    app.multiai.mobile.utils.CloudSyncManager sync = new app.multiai.mobile.utils.CloudSyncManager(ChatModeActivity.this);
                    sync.pullInstructions();
                    sync.pullMemories();
                    sync.pullChatModeChats();
                    sync.pullPersonalization();
                    runOnUiThread(() -> {
                        String openChatId = currentChat != null ? currentChat.getId() : null;
                        allChats = chatManager.loadActiveChats();
                        if (!isGenerating && openChatId != null) {
                            for (Chat chat : allChats) {
                                if (openChatId.equals(chat.getId())) {
                                    currentChat = chat;
                                    loadMessages();
                                    break;
                                }
                            }
                        }
                        updateSidebarChats();
                    });
                } catch (Exception ignored) {}
            }).start();
        }

        mainContent = findViewById(R.id.mainContent);

        initActivityResultLaunchers();
        initViews();
        initSidebar();
        setupListeners();
        loadModelFromSettings();
        loadChats();
        handleOpenChatIntent(getIntent());

        // 🌍 Заполняем кэш местоположения при старте (IP всегда доступен)
        // Сбрасываем TTL если кэш пуст — гарантирует что fetchLocationSync сработает
        SharedPreferences locPrefs = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
        if (locPrefs.getString("ip_city", "").isEmpty()) {
            locPrefs.edit().putLong("last_fetch_ts", 0L).apply();
        }
        refreshLocationCacheIfStale();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (app.multiai.mobile.utils.AppLockManager.get(this)
                .routeIfLocked(this, intent)) return;
        setIntent(intent);
        handleOpenChatIntent(intent);
    }

    private void handleOpenChatIntent(Intent intent) {
        if (intent == null || chatManager == null) return;
        String chatId = intent.getStringExtra(app.multiai.mobile.utils.ChatActions.EXTRA_CHAT_ID);
        if (chatId == null || chatId.isEmpty()) return;
        Chat requested = chatManager.getChatById(chatId);
        if (requested == null) return;
        if (requested.isArchived()) {
            requested.setArchived(false);
            chatManager.updateChat(requested);
            allChats = chatManager.loadActiveChats();
            updateSidebarChats();
        }
        temporaryChatEnabled = false;
        switchToChat(requested);
        if (sidebarChatsAdapter != null) sidebarChatsAdapter.setActiveChatId(chatId);
        intent.removeExtra(app.multiai.mobile.utils.ChatActions.EXTRA_CHAT_ID);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handleSidebarSwipe(event)) return true;
        return super.dispatchTouchEvent(event);
    }

    private boolean handleSidebarSwipe(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                sidebarSwipeStartX = event.getX();
                sidebarSwipeStartY = event.getY();
                // Начинаем трекинг только у левого края — иначе глобальный
                // перехват touch-событий ломает выделение текста в чате:
                // после свайпа ActionMode больше не поднимается.
                sidebarSwipeTracking = sidebarSwipeStartX <= dp(40);
                sidebarSwipeDragging = false;
                globalSwipeHandled = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - sidebarSwipeStartX;
                float dy = event.getY() - sidebarSwipeStartY;
                // Свайп из любого места: триггерим открытие/закрытие один раз,
                // но НЕ потребляем поток (иначе ломается выделение текста).
                if (!globalSwipeHandled && Math.abs(dx) > dp(70)
                        && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                    globalSwipeHandled = true;
                    if (dx > 0 && !sidebarOpen) openSidebar();
                    else if (dx < 0 && sidebarOpen) closeSidebar();
                }
                if (!sidebarSwipeTracking) return false;
                if (!sidebarSwipeDragging) {
                    if (Math.abs(dx) > dp(20) && Math.abs(dx) > Math.abs(dy) * 1.3f) {
                        sidebarSwipeDragging = true;
                    } else {
                        return false;
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (sidebarSwipeDragging) {
                    float finalDx = event.getX() - sidebarSwipeStartX;
                    if (finalDx > dp(40) && !sidebarOpen) {
                        openSidebar();
                    } else if (finalDx < -dp(40) && sidebarOpen) {
                        closeSidebar();
                    }
                    sidebarSwipeTracking = false;
                    sidebarSwipeDragging = false;
                    return true;
                }
                sidebarSwipeTracking = false;
                sidebarSwipeDragging = false;
                break;
            case MotionEvent.ACTION_CANCEL:
                sidebarSwipeTracking = false;
                sidebarSwipeDragging = false;
                break;
        }
        return false;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int getSidebarWidth() {
        if (sidebarOverlay == null) {
            return getResources().getDisplayMetrics().widthPixels;
        }
        int width = sidebarOverlay.getWidth();
        return width > 0 ? width : getResources().getDisplayMetrics().widthPixels;
    }

    private void initActivityResultLaunchers() {
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    isPickerOpening = false;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        if (editAttachTarget != null) {
                            Message target = editAttachTarget;
                            editAttachTarget = null;
                            for (Uri uri : app.multiai.mobile.utils.PhotoPicker.results(result.getData())) {
                                attachImageToMessage(target, uri);
                            }
                            return;
                        }
                        for (Uri uri : app.multiai.mobile.utils.PhotoPicker.results(result.getData())) loadImage(uri);
                    }
                });

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    isPickerOpening = false;
                    if (success && cameraPhotoUri != null) {
                        if (editAttachTarget != null) {
                            Message target = editAttachTarget;
                            editAttachTarget = null;
                            attachImageToMessage(target, cameraPhotoUri);
                        } else {
                            loadImage(cameraPhotoUri);
                        }
                    }
                });

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    isPickerOpening = false;
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        if (editAttachTarget != null) {
                            Message target = editAttachTarget;
                            editAttachTarget = null;
                            for (Uri uri : app.multiai.mobile.utils.PhotoPicker.results(result.getData())) {
                                attachFileToMessage(target, uri);
                            }
                            return;
                        }
                        for (Uri uri : app.multiai.mobile.utils.PhotoPicker.results(result.getData())) loadFileAsText(uri);
                    }
                });

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    String action = pendingPermissionAction;
                    pendingPermissionAction = null;
                    if (!isGranted) {
                        ModernToast.show(this, getString(R.string.chat_permission_denied));
                        return;
                    }
                    if ("gallery".equals(action)) openGallery();
                    else if ("camera".equals(action)) openCamera();
                    else if ("audio".equals(action)) startVoiceInput();
                });
    }

    /** Inject the segmented Chat/Work pill into the adaptive center slot. */
    private void setupTopBarSegmentedPill() {
        FrameLayout slot = findViewById(R.id.modePillContainer);
        if (slot == null) return;
        AppSettings settings = new AppSettings(this);
        boolean showPill = settings.isSegmentedModeEnabled();
        lastSegmentedSetting = showPill;
        lastEmojiMode = settings.getEmojiMode();

        if (showPill) {
            app.multiai.mobile.ui.kit.SegmentedModePill pill =
                    new app.multiai.mobile.ui.kit.SegmentedModePill(this);
            FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER);
            slot.addView(pill, pillLp);
        }

        // MultiAI branding text → opens model picker.
        if (brandingPill != null) {
            brandingPill.setOnClickListener(v -> showModelPicker());
        }
    }

    private void initViews() {
        editMessage = findViewById(R.id.editMessage);
        btnSend = findViewById(R.id.btnSend);
        btnStop = findViewById(R.id.btnStop);
        btnAttach = findViewById(R.id.btnAttach);
        btnSidebar = findViewById(R.id.btnSidebar);
        btnNewChat = findViewById(R.id.btnNewChat);
        recyclerMessages = findViewById(R.id.recyclerMessages);
        app.multiai.mobile.ui.kit.ChatScrollIndicator.attach(recyclerMessages);
        emptyState = findViewById(R.id.emptyState);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptySubtitle = findViewById(R.id.emptySubtitle);
        fabScrollDown = findViewById(R.id.fabScrollDown);
        brandingPill = findViewById(R.id.brandingPill);
        if (brandingPill != null) {
            connectionStatus = new app.multiai.mobile.ui.kit.MultiAiConnectionStatus(
                    brandingPill, this::modelPillText);
        }
        setupTopBarSegmentedPill();
        btnComposeChat = findViewById(R.id.btnComposeChat);
        btnChatMore = findViewById(R.id.btnChatMore);
        chatActionsPill = findViewById(R.id.chatActionsPill);
        btnVoice = findViewById(R.id.btnVoice);
        btnMic = findViewById(R.id.btnMic);
        pillSlider = null;
        attachmentPreviewCard = findViewById(R.id.attachmentPreviewCard);
        attachmentPreview = findViewById(R.id.attachmentPreview);
        attachmentFileName = findViewById(R.id.attachmentFileName);
        attachmentImageBox = findViewById(R.id.attachmentImageBox);
        attachmentFileBox = findViewById(R.id.attachmentFileBox);
        btnRemoveAttachment = findViewById(R.id.btnRemoveAttachment);
        attachmentLoading = findViewById(R.id.attachmentLoading);
        voiceOverlay = findViewById(R.id.voiceOverlay);
        // Новый voice UI
        inputMotionLayout = findViewById(R.id.inputMotionLayout);
        setupInputMotionListener();
        standardInputGroup = findViewById(R.id.standardInputGroup);
        voiceInputGroup = findViewById(R.id.voiceInputGroup);
        transcribingGroup = findViewById(R.id.transcribingGroup);
        transcribeLabel = findViewById(R.id.transcribeLabel);
        transcribeDot1 = findViewById(R.id.transcribeDot1);
        transcribeDot2 = findViewById(R.id.transcribeDot2);
        transcribeDot3 = findViewById(R.id.transcribeDot3);
        btnVoiceStop = findViewById(R.id.btnVoiceStop);
        btnVoiceSend = findViewById(R.id.btnVoiceSend);
        voiceWaveform = findViewById(R.id.voiceWaveform);

        // Apply the user-selected accent to the send / stop / voice-send button
        // backgrounds and their icons (XML @color/accent is static).
        AccentColor.tintButtons(this, btnSend, btnStop, btnVoiceSend);
        // Tint the chat input caret with the accent color too.
        AccentColor.applyCursorTint(editMessage, AccentColor.resolve(this));

        messagesAdapter = new ChatModeMessagesAdapter();
        messagesAdapter.setUserNickname(new app.multiai.mobile.utils.AppSettings(this).getNickname());
        if (selectedModelName != null) messagesAdapter.setCurrentModelName(selectedModelName);
        messagesAdapter.setMessageActionListener(new ChatModeMessagesAdapter.MessageActionListener() {
            @Override
            public void onCopy(Message message) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && message.getContent() != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ai_message", message.getContent()));
                    ModernToast.show(ChatModeActivity.this, getString(R.string.chat_copied));
                }
            }

            @Override
            public void onLike(Message message) {
                sendFeedbackReport(message, "like", null, null);
                ModernToast.show(ChatModeActivity.this, getString(R.string.chat_thanks_for_rating));
            }

            @Override
            public void onDislike(Message message) {
                showDislikeFeedbackDialog(message);
            }

            @Override
            public void onTts(Message message) {
                speakMessage(message);
            }

            @Override
            public void onShare(Message message) {
                String text = message.getContent() != null ? message.getContent() : "";
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.chat_share)));
            }

            @Override
            public void onMore(Message message, View anchor) {
                showAiMoreMenu(message, anchor);
            }
        });
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(lm);
        recyclerMessages.setAdapter(messagesAdapter);
        recyclerMessages.setItemViewCacheSize(8);
        androidx.recyclerview.widget.DefaultItemAnimator itemAnimator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        itemAnimator.setSupportsChangeAnimations(false);
        itemAnimator.setAddDuration(app.multiai.mobile.ui.kit.MotionKit.duration(220L));
        itemAnimator.setRemoveDuration(app.multiai.mobile.ui.kit.MotionKit.duration(160L));
        itemAnimator.setMoveDuration(0L);
        recyclerMessages.setItemAnimator(itemAnimator);
        messagesAdapter.setRecyclerView(recyclerMessages);
        messagesAdapter.setOnErrorRetryListener(message -> {
            runOnUiThread(() -> retryLastUserMessage(message));
        });
        messagesAdapter.setUserMessageActionListener(new ChatModeMessagesAdapter.OnUserMessageActionListener() {
            @Override
            public void onUserMessageRetry(Message message, int position) {
                runOnUiThread(() -> retryLastUserMessageForAction(message));
            }
            @Override
            public void onUserMessageEdit(Message message, int position) {
                // Enable inline editing — message turns into EditText in the bubble
                runOnUiThread(() -> {
                    message.beginEditAttachmentSnapshot();
                    message.setEditing(true);
                    messagesAdapter.notifyItemChanged(position);
                });
            }
            @Override
            public void onUserMessageEdited(Message message, int position, String newContent) {
                // Как в Work-режиме: старое сообщение и его ответ удаляются,
                // свежее сообщение с правкой уходит модели. Удаление — ПО ИНДЕКСАМ
                // из списка адаптера (источник UI), не по идентичности инстансов.
                runOnUiThread(() -> {
                    java.util.List<Message> adapterMsgs = messagesAdapter.getMessagesList();
                    int userIndex = adapterMsgs.indexOf(message);
                    app.multiai.mobile.utils.AiDebugLog.log("ChatMode Edit save: adapterIndex="
                            + userIndex + ", pos=" + position + ", newLen=" + newContent.length()
                            + ", img=" + (message.getImageBase64() != null));
                    if (userIndex < 0) userIndex = position;
                    if (userIndex < 0 || userIndex >= adapterMsgs.size()) return;
                    // 1) Старый ответ ИИ после сообщения — из адаптера (UI)
                    while (adapterMsgs.size() > userIndex + 1) {
                        Message tail = adapterMsgs.get(adapterMsgs.size() - 1);
                        messagesAdapter.removeMessage(tail);
                    }
                    // 2) Вложения из режима правки — в состояние ввода.
                    //    updateChat выгружает вложения на диск — читаем обратно.
                    String img = message.getImageBase64();
                    if ((img == null || img.isEmpty()) && message.getImagePath() != null) {
                        try {
                            java.io.FileInputStream fis =
                                    new java.io.FileInputStream(message.getImagePath());
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                            fis.close();
                            img = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                        } catch (Exception e) {
                            app.multiai.mobile.utils.AiDebugLog.log(
                                    "ChatMode Edit transfer: image disk read FAILED " + e);
                        }
                    }
                    if (img != null && !img.isEmpty()) {
                        selectedImageBase64 = img;
                        message.setImageBase64(null);
                    }
                    if (message.hasAttachedFile()) {
                        attachedFileName = message.getAttachedFileName();
                        attachedFileContent = message.getAttachedFileContent();
                        if ((attachedFileContent == null || attachedFileContent.isEmpty())
                                && message.getAttachedFilePath() != null) {
                            try {
                                java.io.FileInputStream fis =
                                        new java.io.FileInputStream(message.getAttachedFilePath());
                                byte[] bytes = new byte[10 * 1024 * 1024];
                                int read = fis.read(bytes);
                                fis.close();
                                if (read > 0) {
                                    attachedFileContent = new String(bytes, 0, read,
                                            java.nio.charset.StandardCharsets.UTF_8);
                                }
                            } catch (Exception e) {
                                app.multiai.mobile.utils.AiDebugLog.log(
                                        "ChatMode Edit transfer: file disk read FAILED " + e);
                            }
                        }
                        message.setAttachedFileName(null);
                        message.setAttachedFileContent(null);
                    }
                    // 3) Само сообщение — из адаптера
                    messagesAdapter.removeMessage(message);
                    // 4) currentChat синхронизируем по позициям (identity мог разъехаться)
                    java.util.List<Message> chatMsgs = currentChat.getMessages();
                    while (chatMsgs.size() > userIndex + 1) {
                        chatMsgs.remove(chatMsgs.size() - 1);
                    }
                    if (userIndex < chatMsgs.size()) chatMsgs.set(userIndex, message);
                    else chatMsgs.add(message);
                    chatMsgs.remove(userIndex);
                    chatManager.updateChat(currentChat);
                    editMessage.setText(newContent);
                    editMessage.setSelection(newContent.length());
                    sendMessage();
                });
            }
            @Override
            public void onUserMessageAttach(Message message, int position, String kind) {
                // «+» в режиме правки: вложение попадает в само сообщение
                app.multiai.mobile.utils.AiDebugLog.log("ChatMode Edit attach: " + kind);
                runOnUiThread(() -> {
                    editAttachTarget = message;
                    if ("camera".equals(kind)) {
                        openCamera();
                    } else if ("file".equals(kind)) {
                        attachFile();
                    } else {
                        openGallery();
                    }
                });
            }
        });

        // Initially: voice visible, send hidden
        btnSend.setVisibility(View.GONE);
        btnVoice.setVisibility(View.VISIBLE);
        updateEmptyStateText();

        // Model picker
        TextView modelPicker = findViewById(R.id.modelPicker);
        if (modelPicker != null) {
            modelPicker.setVisibility(View.GONE);
        }
    }

    private void setupListeners() {
        btnSend.setOnClickListener(v -> sendMessage());
        btnStop.setOnClickListener(v -> stopGeneration());
        btnNewChat.setOnClickListener(v -> toggleTemporaryChat());
        if (btnComposeChat != null) btnComposeChat.setOnClickListener(v -> createNewChat());
        if (btnChatMore != null) btnChatMore.setOnClickListener(v -> showCurrentChatActions());
        btnSidebar.setOnClickListener(v -> toggleSidebar());
        btnAttach.setOnClickListener(v -> {
            if (currentInputState == InputState.RECORDING) {
                cancelVoiceInput();
            } else if (attachMenuOpen) {
                if (attachPopup != null) attachPopup.dismiss();
            } else {
                showAttachMenu();
            }
        });
        btnVoice.setOnClickListener(v -> {
            toggleVoiceInput();
        });
        btnMic.setOnClickListener(v -> {
            toggleVoiceInput();
        });
        if (btnVoiceCancel != null) btnVoiceCancel.setOnClickListener(v -> cancelVoiceInput());
        if (btnVoiceStop != null) btnVoiceStop.setOnClickListener(v -> {
            // Остановить запись, но не отправлять — показать расшифровку в строке ввода
            voiceSentByUser = false;
            stopVoiceInput();
        });
        if (btnVoiceSend != null) btnVoiceSend.setOnClickListener(v -> {
            // Остановить и сразу отправить после расшифровки
            voiceSentByUser = true;
            stopVoiceInput();
        });
        // Крестик на превью убирает вложение; тап по превью — выбрать другое.
        if (btnRemoveAttachment != null) btnRemoveAttachment.setOnClickListener(v -> removeAttachment());
        if (attachmentPreviewCard != null) {
            attachmentPreviewCard.setOnClickListener(v -> attachPhoto());
        }
        editMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateSendMicVisibility();
            }
        });

        // 🔥 MotionLayout: [+] "отрывается" от input bar при открытии клавиатуры
        editMessage.setOnFocusChangeListener((v, hasFocus) -> {
            if (currentInputState == InputState.RECORDING) return;
            if (hasFocus) {
                keyboardVisible = true;
                syncMotionScene();
                View inputContainer = findViewById(R.id.inputContainer);
                if (inputContainer != null) {
                    androidx.core.view.ViewCompat.requestApplyInsets(inputContainer);
                }
            }
        });
        setupKeyboardVisibilityListener();

        recyclerMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrollingMessages = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    rv.post(() -> updateScrollDownFab(rv, 0));
                    userScrollingMessages = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                updateScrollDownFab(rv, dy);
            }
        });

        fabScrollDown.setOnClickListener(v -> {
            followStreamToBottom = true;
            fabScrollDown.setVisibility(View.GONE);
            scrollToBottom();
        });
    }

    private void showAttachMenu() {
        // Меню «+» по референсу: карточка с круглыми иконками над полем ввода,
        // как в Work-режиме. Плагинов и «Размышлять глубже» здесь нет.
        View anchor = findViewById(R.id.inputContainer);
        if (anchor == null) anchor = btnAttach;
        attachMenuOpen = true;
        attachPopup = app.multiai.mobile.ui.kit.AttachMenuPopup.show(anchor,
                new app.multiai.mobile.ui.kit.AttachMenuPopup.Callback() {
                    @Override public void onCamera() { openCamera(); }
                    @Override public void onPhoto() { attachPhoto(); }
                    @Override public void onFiles() { attachFile(); }
                },
                () -> {
                    attachMenuOpen = false;
                    attachPopup = null;
                    if (currentInputState != InputState.RECORDING && btnAttach != null) {
                        btnAttach.animate().rotation(0f).setDuration(180)
                                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
                    }
                });
        if (btnAttach != null && currentInputState != InputState.RECORDING) {
            btnAttach.animate().rotation(45f).setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
        }
    }

    private void attachPhoto() {
        openGallery();
    }

    private void openGallery() {
        if (isPickerOpening) return;
        isPickerOpening = true;
        Intent intent = app.multiai.mobile.utils.PhotoPicker.intent();
        pickImageLauncher.launch(intent);
        handler.postDelayed(() -> isPickerOpening = false, 1000);
    }

    private void openCamera() {
        if (isPickerOpening) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "camera";
            requestPermissionLauncher.launch(android.Manifest.permission.CAMERA);
            return;
        }
        isPickerOpening = true;
        try {
            java.io.File cameraDir = new java.io.File(getCacheDir(), "camera");
            //noinspection ResultOfMethodCallIgnored
            cameraDir.mkdirs();
            java.io.File photoFile = new java.io.File(cameraDir, "camera_" + System.currentTimeMillis() + ".jpg");
            cameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(cameraPhotoUri);
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.chat_error_camera, e.getMessage()));
        }
        handler.postDelayed(() -> isPickerOpening = false, 1000);
    }

    /** Режим превью как в ChatGPT: фото — скруглённая плитка без крестика
     *  (тап по превью открывает галерею), файл — карточка с иконкой и именем. */
    /** Плавное появление/скрытие карточки вложения у поля ввода. */
    private void animateAttachmentCard(View card, boolean show) {
        if (card == null) return;
        card.animate().cancel();
        float shift = getResources().getDisplayMetrics().density * 8f;
        if (show) {
            card.setVisibility(View.VISIBLE);
            card.setAlpha(0f);
            card.setTranslationY(shift);
            card.animate().alpha(1f).translationY(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        } else {
            card.animate().alpha(0f).translationY(shift)
                    .setDuration(140)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> {
                        card.setVisibility(View.GONE);
                        card.setAlpha(1f);
                        card.setTranslationY(0f);
                    }).start();
        }
    }

    private void showAttachmentInline(boolean isImage) {
        if (attachmentPreviewCard == null) return;
        attachmentPreviewCard.animate().cancel();
        attachmentPreviewCard.setAlpha(1f);
        attachmentPreviewCard.setTranslationY(0f);
        app.multiai.mobile.views.ImageAttachmentStrip.render(attachmentPreviewCard, selectedImageBase64, index -> {
            selectedImageBase64 = app.multiai.mobile.utils.ImageAttachments.remove(selectedImageBase64, index);
            showAttachmentInline(true);
        }, attachedFileName, attachedFileContent, index -> {
            java.util.List<app.multiai.mobile.utils.FileAttachments.Entry> files =
                    app.multiai.mobile.utils.FileAttachments.unpack(attachedFileName, attachedFileContent);
            files.remove(index);
            attachedFileName = app.multiai.mobile.utils.FileAttachments.names(files);
            attachedFileContent = app.multiai.mobile.utils.FileAttachments.pack(files);
            showAttachmentInline(false);
        });
        updateSendMicVisibility();
    }

    private void attachFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        String[] mimeTypes = {"text/*", "application/json", "application/xml", "application/javascript", "image/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private int loadingImages;

    /** Сообщение в режиме правки, к которому прикрепляем вложение («+»). */
    private Message editAttachTarget;

    /** Прикрепляет выбранное фото к редактируемому сообщению (не к полю ввода). */
    private void attachImageToMessage(Message message, Uri imageUri) {
        loadingImages++;
        if (attachmentLoading != null) attachmentLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                android.graphics.Bitmap bitmap = app.multiai.mobile.utils.AttachmentImageLoader.decode(
                        getContentResolver(), imageUri);
                if (bitmap == null) throw new Exception("Не удалось открыть изображение");
                android.graphics.Bitmap resized = resizeImage(bitmap, 1024, 1024);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                runOnUiThread(() -> {
                    message.replaceImages(app.multiai.mobile.utils.ImageAttachments.append(
                            message.getImageBase64(), base64));
                    messagesAdapter.refreshMessage(message);
                    if (--loadingImages <= 0 && attachmentLoading != null) {
                        attachmentLoading.setVisibility(View.GONE);
                    }
                    ModernToast.show(this, getString(R.string.chat_photo_attached));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (--loadingImages <= 0 && attachmentLoading != null) {
                        attachmentLoading.setVisibility(View.GONE);
                    }
                    ModernToast.show(this, getString(R.string.chat_error_image, e.getMessage()));
                });
            }
        }).start();
    }

    /** Читает выбранный файл в редактируемое сообщение (текстовое вложение). */
    private void attachFileToMessage(Message message, Uri fileUri) {
        new Thread(() -> {
            try {
                String fileName = getFileName(fileUri);
                String mimeType = getContentResolver().getType(fileUri);
                if (mimeType != null && mimeType.startsWith("image/")) {
                    runOnUiThread(() -> attachImageToMessage(message, fileUri));
                    return;
                }
                java.io.InputStream is = getContentResolver().openInputStream(fileUri);
                if (is == null) throw new Exception("Cannot open file");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int read;
                int totalChars = 0;
                int maxChars = 50000;
                while ((read = reader.read(buf)) != -1 && totalChars < maxChars) {
                    sb.append(buf, 0, read);
                    totalChars += read;
                }
                reader.close();
                is.close();
                String content = sb.toString();
                if (totalChars >= maxChars) content += getString(R.string.chat_file_truncated);
                final String fName = fileName;
                final String fContent = content;
                runOnUiThread(() -> {
                    java.util.List<app.multiai.mobile.utils.FileAttachments.Entry> files =
                            app.multiai.mobile.utils.FileAttachments.unpack(message.getAttachedFileName(), message.getAttachedFileContent());
                    files.add(new app.multiai.mobile.utils.FileAttachments.Entry(fName, fContent));
                    message.replaceFiles(files);
                    messagesAdapter.refreshMessage(message);
                });
            } catch (Exception e) {
                runOnUiThread(() -> ModernToast.show(this,
                        getString(R.string.chat_error_loading_image)));
            }
        }).start();
    }

    private void loadImage(Uri imageUri) {        loadingImages++;
        if (attachmentPreviewCard != null) attachmentPreviewCard.setVisibility(View.VISIBLE);
        if (attachmentLoading != null) attachmentLoading.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                android.graphics.Bitmap bitmap = app.multiai.mobile.utils.AttachmentImageLoader.decode(getContentResolver(), imageUri);
                if (bitmap == null) throw new Exception("Не удалось открыть изображение");
                android.graphics.Bitmap resized = resizeImage(bitmap, 1024, 1024);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                resized.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, baos);
                byte[] bytes = baos.toByteArray();
                String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                String fileName = getFileName(imageUri);
                long fileSize = bytes.length;
                runOnUiThread(() -> {
                    if (app.multiai.mobile.utils.ImageAttachments.unpack(selectedImageBase64).size() >= 10) {
                        if (attachmentLoading != null) attachmentLoading.setVisibility(View.GONE);
                        loadingImages--;
                        ModernToast.show(this, "Максимум 10 фото");
                        return;
                    }
                    selectedImageBase64 = app.multiai.mobile.utils.ImageAttachments.append(selectedImageBase64, base64);
                    selectedImageName = fileName;
                    if (attachmentPreview != null) {
                        attachmentPreview.clearColorFilter();
                        attachmentPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        attachmentPreview.setImageBitmap(resized);
                    }
                    if (attachmentFileName != null) attachmentFileName.setText(fileName);
                    showAttachmentInline(true);
                    if (--loadingImages <= 0 && attachmentLoading != null) attachmentLoading.setVisibility(View.GONE);
                    updateSendMicVisibility();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (attachmentLoading != null) attachmentLoading.setVisibility(View.GONE);
                    loadingImages--;
                    ModernToast.show(this, getString(R.string.chat_error_image, e.getMessage()));
                });
            }
        }).start();
    }

    private void loadFileAsText(Uri fileUri) {
        new Thread(() -> {
            try {
                String fileName = getFileName(fileUri);
                String mimeType = getContentResolver().getType(fileUri);
                if (mimeType != null && mimeType.startsWith("image/")) {
                    runOnUiThread(() -> loadImage(fileUri));
                    return;
                }
                InputStream is = getContentResolver().openInputStream(fileUri);
                if (is == null) throw new Exception("Не удалось открыть файл");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int read;
                int total = 0;
                int maxChars = 50000;
                while ((read = reader.read(buf)) != -1 && total < maxChars) {
                    sb.append(buf, 0, read);
                    total += read;
                }
                reader.close();
                is.close();
                String content = sb.toString();
                if (total >= maxChars) content += getString(R.string.chat_file_truncated);
                final String finalContent = content;
                long size = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                runOnUiThread(() -> {
                    java.util.List<app.multiai.mobile.utils.FileAttachments.Entry> files =
                            app.multiai.mobile.utils.FileAttachments.unpack(attachedFileName, attachedFileContent);
                    files.add(new app.multiai.mobile.utils.FileAttachments.Entry(fileName, finalContent));
                    attachedFileName = app.multiai.mobile.utils.FileAttachments.names(files);
                    attachedFileContent = app.multiai.mobile.utils.FileAttachments.pack(files);
                    if (attachmentFileName != null) attachmentFileName.setText(fileName);
                    showAttachmentInline(false);
                    updateSendMicVisibility();
                });
            } catch (Exception e) {
                runOnUiThread(() -> ModernToast.show(this, getString(R.string.chat_error_file, e.getMessage())));
            }
        }).start();
    }

    private android.graphics.Bitmap resizeImage(android.graphics.Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxWidth && height <= maxHeight) return bitmap;
        float ratio = Math.min((float) maxWidth / width, (float) maxHeight / height);
        return android.graphics.Bitmap.createScaledBitmap(bitmap, Math.round(width * ratio), Math.round(height * ratio), true);
    }

    private String getFileName(Uri uri) {
        String fileName = "attachment";
        try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) fileName = cursor.getString(nameIndex);
            }
        } catch (Exception ignored) {}
        return fileName;
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void removeAttachment() {
        selectedImageBase64 = null;
        selectedImageName = null;
        attachedFileName = null;
        attachedFileContent = null;
        // Крестик прячется вместе с превью — иначе остаётся висеть и
        // перехватывать чужие тапы
        if (btnRemoveAttachment != null) {
            btnRemoveAttachment.setVisibility(View.GONE);
            btnRemoveAttachment.setX(0f);
            btnRemoveAttachment.setY(0f);
        }
        animateAttachmentCard(attachmentPreviewCard, false);
        if (attachmentPreview != null) {
            attachmentPreview.clearColorFilter();
            attachmentPreview.setImageDrawable(null);
        }
        updateSendMicVisibility();
    }

    private void updateSendMicVisibility() {
        boolean hasText = editMessage != null && editMessage.getText().toString().trim().length() > 0;
        boolean hasAttachment = selectedImageBase64 != null || attachedFileContent != null;
        boolean canSend = hasText || hasAttachment;
        if (btnSend != null) btnSend.setVisibility(canSend && !isGenerating ? View.VISIBLE : View.GONE);
        if (btnMic != null) btnMic.setVisibility(!canSend && !isGenerating ? View.VISIBLE : View.GONE);
    }

    // ══════════════════ MOTION LAYOUT: [+] DETACH / MORPH ══════════════════

    private boolean keyboardVisible = false;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener keyboardListener;

    private void setupKeyboardVisibilityListener() {
        View root = findViewById(android.R.id.content);
        if (root == null) return;
        keyboardListener = () -> {
            android.graphics.Rect r = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenH = root.getRootView().getHeight();
            int diff = screenH - r.bottom;
            boolean nowVisible = diff > screenH * 0.15;
            if (nowVisible != keyboardVisible) {
                keyboardVisible = nowVisible;
                syncMotionScene();
                View inputContainer = findViewById(R.id.inputContainer);
                if (inputContainer != null) {
                    androidx.core.view.ViewCompat.requestApplyInsets(inputContainer);
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
    }

    /** Единый синк сцены [+] по состоянию клавиатуры и голосового ввода. */
    private void syncMotionScene() {
        if (inputMotionLayout == null) return;
        int target;
        if (currentInputState == InputState.RECORDING
                || currentInputState == InputState.TRANSCRIBING) {
            target = R.id.scene_recording;
        } else {
            target = keyboardVisible ? R.id.scene_typing : R.id.scene_idle;
        }
        if (inputMotionLayout.getCurrentState() != target) {
            inputMotionLayout.transitionToState(target);
        }
    }

    /**
     * Слушатель MotionLayout: синхронно анимирует paddingStart EditText'a.
     * В idle: 48dp (место под внутренний [+])
     * В typing/recording: 14dp (нормальный padding, [+] снаружи)
     */
    private void setupInputMotionListener() {
        if (inputMotionLayout == null || editMessage == null) return;
        final int paddingIdle = dp(48);
        final int paddingDetached = dp(14);
        inputMotionLayout.setTransitionListener(new androidx.constraintlayout.motion.widget.MotionLayout.TransitionListener() {
            @Override public void onTransitionStarted(androidx.constraintlayout.motion.widget.MotionLayout ml, int startId, int endId) {}
            @Override
            public void onTransitionChange(androidx.constraintlayout.motion.widget.MotionLayout ml, int startId, int endId, float progress) {
                boolean startIsIdle = startId == R.id.scene_idle;
                boolean endIsIdle = endId == R.id.scene_idle;
                // Интерполируем: idle → padding 48, detached → padding 14
                int startPad = startIsIdle ? paddingIdle : paddingDetached;
                int endPad = endIsIdle ? paddingIdle : paddingDetached;
                int pad = (int) (startPad + (endPad - startPad) * progress);
                applyEditPaddingStart(pad);
            }
            @Override
            public void onTransitionCompleted(androidx.constraintlayout.motion.widget.MotionLayout ml, int currentId) {
                applyEditPaddingStart(currentId == R.id.scene_idle ? paddingIdle : paddingDetached);
            }
            @Override public void onTransitionTrigger(androidx.constraintlayout.motion.widget.MotionLayout ml, int triggerId, boolean positive, float progress) {}
        });
        // Начальное состояние
        applyEditPaddingStart(paddingIdle);
    }

    private void applyEditPaddingStart(int paddingPx) {
        if (editMessage == null) return;
        editMessage.setPaddingRelative(paddingPx,
                editMessage.getPaddingTop(),
                editMessage.getPaddingEnd(),
                editMessage.getPaddingBottom());
    }

    // ══════════════════ VOICE STATE MACHINE ══════════════════

    private void toggleVoiceInput() {
        if (currentInputState == InputState.RECORDING) {
            voiceSentByUser = false;
            stopVoiceInput();
        } else if (currentInputState == InputState.IDLE) {
            startVoiceInput();
        }
    }

    /** Переход между состояниями с красивой анимацией fade+slide. */
    private void transitionToState(InputState target) {
        if (currentInputState == target) return;
        InputState prev = currentInputState;
        currentInputState = target;

        View outgoing = viewForState(prev);
        View incoming = viewForState(target);

        if (outgoing != null) {
            outgoing.animate().cancel();
            outgoing.animate()
                    .alpha(0f)
                    .translationY(-dp(8))
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> outgoing.setVisibility(View.GONE))
                    .start();
        }
        if (incoming != null) {
            incoming.animate().cancel();
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            incoming.setTranslationY(dp(10));
            incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(260)
                    .setStartDelay(80)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f))
                    .start();
        }

        // State-specific side effects
        if (target == InputState.RECORDING) {
            if (voiceWaveform != null) voiceWaveform.setListening(true);
            stopTranscribingAnimation();
        } else if (target == InputState.TRANSCRIBING) {
            if (voiceWaveform != null) voiceWaveform.setListening(false);
            startTranscribingAnimation();
        } else {
            if (voiceWaveform != null) voiceWaveform.setListening(false);
            stopTranscribingAnimation();
        }
        // Контекст-кольцо скрывается на время голосового ввода/расшифровки.
        if (contextRing != null) {
            contextRing.setVisibility(target == InputState.IDLE ? View.VISIBLE : View.GONE);
        }

        // 🔥 Синхронизируем [+] сцену с новым состоянием голоса
        syncMotionScene();
    }

    private View viewForState(InputState state) {
        if (state == null) return null;
        switch (state) {
            case IDLE: return standardInputGroup;
            case RECORDING: return voiceInputGroup;
            case TRANSCRIBING: return transcribingGroup;
            default: return null;
        }
    }

    private void startTranscribingAnimation() {
        stopTranscribingAnimation();
        if (transcribeDot1 == null) return;
        android.animation.ObjectAnimator a1 = dotPulseAnim(transcribeDot1, 0);
        android.animation.ObjectAnimator a2 = dotPulseAnim(transcribeDot2, 150);
        android.animation.ObjectAnimator a3 = dotPulseAnim(transcribeDot3, 300);
        transcribingAnimatorSet = new android.animation.AnimatorSet();
        transcribingAnimatorSet.playTogether(a1, a2, a3);
        transcribingAnimatorSet.start();
    }

    private android.animation.ObjectAnimator dotPulseAnim(View dot, long delay) {
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator
                .ofFloat(dot, "alpha", 0.3f, 1f, 0.3f);
        anim.setDuration(950);
        anim.setStartDelay(delay);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        return anim;
    }

    private void stopTranscribingAnimation() {
        if (transcribingAnimatorSet != null) {
            transcribingAnimatorSet.cancel();
            transcribingAnimatorSet = null;
        }
    }

    private void startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "audio";
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        try {
            voiceCancelled = false;
            voiceSentByUser = false;
            transitionToState(InputState.RECORDING);

            audioFile = new java.io.File(getCacheDir(), "voice_" + System.currentTimeMillis() + ".m4a");
            mediaRecorder = new android.media.MediaRecorder();
            mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isListening = true;
            voiceRecordingStartedAt = android.os.SystemClock.elapsedRealtime();
            // Пользователь начал новую запись — старая несохранённая больше не нужна.
            if (pendingVoiceFile != null) {
                pendingVoiceFile.delete();
                pendingVoiceFile = null;
            }

            // Амплитуда + VAD. Автоотправка происходит только после пятнадцати полных
            // секунд непрерывной тишины; любой слышимый сигнал мгновенно сбрасывает таймер.
            silenceStartedAt = 0L;
            voiceSilenceHandler = new Handler(Looper.getMainLooper());
            voiceSilenceRunnable = new Runnable() {
                @Override public void run() {
                    if (!isListening || mediaRecorder == null) return;
                    try {
                        int amp = mediaRecorder.getMaxAmplitude();
                        float normalized = Math.min(1.0f, amp / 8000f);
                        if (voiceWaveform != null) voiceWaveform.setAmplitude(0.25f + normalized * 0.75f);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (amp < VOICE_SILENCE_AMPLITUDE) {
                            if (silenceStartedAt == 0L) silenceStartedAt = now;
                            if (now - silenceStartedAt >= VOICE_SILENCE_TIMEOUT_MS) {
                                voiceSentByUser = true;
                                stopVoiceInput();
                                return;
                            }
                        } else {
                            silenceStartedAt = 0L;
                        }
                    } catch (Exception ignored) {}
                    if (voiceSilenceHandler != null) {
                        voiceSilenceHandler.postDelayed(this, 120);
                    }
                }
            };
            voiceSilenceHandler.postDelayed(voiceSilenceRunnable, 120);
            handler.postDelayed(() -> {
                if (isListening) {
                    voiceSentByUser = true;
                    stopVoiceInput();
                }
            }, VOICE_MAX_DURATION_MS);
        } catch (Exception e) {
            isListening = false;
            transitionToState(InputState.IDLE);
            ModernToast.show(this, getString(R.string.chat_error_mic, e.getMessage()));
        }
    }

    private void cancelVoiceInput() {
        if (currentInputState != InputState.RECORDING) return;
        voiceCancelled = true;
        isListening = false;
        clearVoiceSilenceDetection();
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception ignored) {}
        if (audioFile != null && audioFile.exists()) audioFile.delete();
        transitionToState(InputState.IDLE);
    }

    private void stopVoiceInput() {
        if (!isListening) return;
        isListening = false;
        clearVoiceSilenceDetection();
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception ignored) {}
        transitionToState(InputState.TRANSCRIBING);
        transcribeVoice();
    }

    private void clearVoiceSilenceDetection() {
        if (voiceSilenceHandler != null && voiceSilenceRunnable != null) {
            voiceSilenceHandler.removeCallbacks(voiceSilenceRunnable);
        }
        voiceSilenceHandler = null;
        voiceSilenceRunnable = null;
        silenceStartedAt = 0L;
    }

    private void transcribeVoice() {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            transitionToState(InputState.IDLE);
            return;
        }
        new Thread(() -> {
            String lastError = null;
            boolean keepFile = false;
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
                byte[] bytes = new byte[(int) audioFile.length()];
                int ignored = fis.read(bytes);
                fis.close();
                String base64Audio = Base64.encodeToString(bytes, Base64.NO_WRAP);
                JSONObject body = new JSONObject();
                body.put("audio", base64Audio);
                // Таймауты масштабируются от размера записи: длинное голосовое
                // не должно падать по таймауту на загрузке/распознавании.
                long sizeMb = Math.max(1, audioFile.length() / (1024 * 1024));
                okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(Math.min(10, sizeMb) * 45, TimeUnit.SECONDS)
                        .writeTimeout(Math.min(10, sizeMb) * 30, TimeUnit.SECONDS)
                        .build();
                okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder()
                        .url("https://multiai-api.vercel.app/api/whisper")
                        .post(okhttp3.RequestBody.create(body.toString(),
                                okhttp3.MediaType.get("application/json; charset=utf-8")));
                String bearer = app.multiai.mobile.network.BackendApi.bearerToken();
                if (bearer != null) reqBuilder.header("Authorization", bearer);
                okhttp3.Response resp = client.newCall(reqBuilder.build()).execute();
                String raw = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    lastError = "HTTP " + resp.code();
                    keepFile = true;
                    return;
                }
                JSONObject result = new JSONObject(raw);
                String text = postProcessVoice(result.optString("text", "").trim());
                final boolean recognized = isMeaningfulSpeech(text);
                runOnUiThread(() -> {
                    transitionToState(InputState.IDLE);
                    if (recognized) {
                        if (audioFile != null) {
                            audioFile.delete();
                            audioFile = null;
                        }
                        String current = editMessage.getText().toString();
                        if (!current.isEmpty() && !current.endsWith(" ")) current += " ";
                        editMessage.setText(current + text);
                        editMessage.setSelection(editMessage.getText().length());
                        if (voiceSentByUser) {
                            voiceSentByUser = false;
                            sendMessage();
                        }
                    } else {
                        voiceSentByUser = false;
                        keepVoiceForRetry(getString(R.string.chat_voice_unrecognized));
                    }
                });
            } catch (Exception e) {
                lastError = e.getMessage();
                keepFile = true;
            }
            if (keepFile) {
                final String err = lastError;
                runOnUiThread(() -> {
                    transitionToState(InputState.IDLE);
                    keepVoiceForRetry(err != null
                            ? getString(R.string.chat_error_recognition, err)
                            : getString(R.string.chat_voice_unrecognized));
                });
            }
        }).start();
    }

    /**
     * Ошибка распознавания не должна стоить пользователю записи: файл переезжает
     * в pendingVoiceFile, вместо удаления. Повторная попытка — из тоста/снекбара,
     * файл удаляется только после успеха, отмены пользователем или новой записи.
     */
    private void keepVoiceForRetry(String errorMessage) {
        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            if (pendingVoiceFile != null && !pendingVoiceFile.equals(audioFile)) {
                pendingVoiceFile.delete();
            }
            pendingVoiceFile = audioFile;
            audioFile = null;
            android.view.View root = findViewById(android.R.id.content);
            if (root != null) {
                com.google.android.material.snackbar.Snackbar.make(root, errorMessage,
                                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                        .setAction(getString(R.string.chat_voice_retry),
                                v -> retryPendingVoiceTranscription())
                        .show();
            } else {
                ModernToast.show(this, errorMessage);
            }
        } else {
            ModernToast.show(this, errorMessage);
        }
    }

    private void retryPendingVoiceTranscription() {
        if (pendingVoiceFile == null || !pendingVoiceFile.exists() || pendingVoiceFile.length() == 0) {
            pendingVoiceFile = null;
            return;
        }
        if (currentInputState != InputState.IDLE) return;
        audioFile = pendingVoiceFile;
        pendingVoiceFile = null;
        voiceSentByUser = false;
        transitionToState(InputState.TRANSCRIBING);
        transcribeVoice();
    }

    /**
     * Детектор осмысленной речи. Whisper на тишине/шуме любит галлюцинировать
     * "Продолжение следует…", "Субтитры создавал…", "Подписывайтесь на канал".
     * Фильтруем эти артефакты.
     */
    private boolean isMeaningfulSpeech(String text) {
        if (text == null || text.isEmpty()) return false;
        String t = text.toLowerCase().trim();
        // Слишком короткий фрагмент = мусор
        if (t.replaceAll("[\\s.,!?…]+", "").length() < 2) return false;

        String[] hallucinations = {
                "продолжение следует",
                "субтитры", "субтитр",
                "субтитры делал",
                "подписывайтесь", "подпишись",
                "ставьте лайк", "поставьте лайк",
                "всем пока", "до свидания",
                "спасибо за просмотр",
                "thanks for watching",
                "subscribe", "like and subscribe",
                "music playing", "♪"
        };
        for (String h : hallucinations) {
            if (t.contains(h)) return false;
        }
        return true;
    }

    private String postProcessVoice(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw.trim();
        text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        text = text.replaceAll("(?i)\\bточка\\b", ".");
        text = text.replaceAll("(?i)\\bзапятая\\b", ",");
        text = text.replaceAll("(?i)\\bвопросительный знак\\b", "?");
        text = text.replaceAll("(?i)\\bвосклицательный знак\\b", "!");
        text = text.replaceAll("(?i)\\bновая строка\\b", "\n");
        text = text.replaceAll("\\s+([.,!?:;)\"…])", "$1");
        return text.trim();
    }

    private void clearTemporaryConversation() {
        temporaryMessages.clear();
        temporaryContextSummary = null;
        temporaryContextSummaryMessageCount = 0;
    }

    private void toggleTemporaryChat() {
        if (isGenerating) stopGeneration();
        clearRetryState();
        temporaryChatEnabled = !temporaryChatEnabled;
        if (!temporaryChatEnabled) {
            clearTemporaryConversation();
            currentChat = null;
        }
        animatePillSlide(temporaryChatEnabled);
        updateEmptyStateText();
        messagesAdapter.setMessages(new ArrayList<>(temporaryChatEnabled ? temporaryMessages : new ArrayList<>()));
        emptyState.setVisibility(View.VISIBLE);
        recyclerMessages.setVisibility(View.GONE);
    }

    private void animatePillSlide(boolean temporary) {
        btnNewChat.setImageResource(temporary
                ? R.drawable.chat_mode_ic_dashed_bubble_check
                : R.drawable.chat_mode_ic_dashed_circle);
        updateTopChatActions(true);
    }

    private void applyPillState() {
        btnNewChat.setImageResource(temporaryChatEnabled
                ? R.drawable.chat_mode_ic_dashed_bubble_check
                : R.drawable.chat_mode_ic_dashed_circle);
        updateTopChatActions(false);
    }

    private void updateTopChatActions(boolean animate) {
        if (chatActionsPill == null) return;
        boolean hasMessages = temporaryChatEnabled
                ? !temporaryMessages.isEmpty()
                : currentChat != null && !currentChat.getMessages().isEmpty();
        if (hasMessages && chatActionsPill.getVisibility() != View.VISIBLE) {
            chatActionsPill.setVisibility(View.VISIBLE);
            if (animate) {
                chatActionsPill.setAlpha(0f);
                chatActionsPill.setScaleX(0.9f);
                chatActionsPill.animate().alpha(1f).scaleX(1f).setDuration(180).start();
            }
        } else if (!hasMessages) {
            chatActionsPill.setVisibility(View.GONE);
        }
        // Hide the "temporary chat" dashed-circle icon once the conversation has
        // messages — the compose + more pill replaces it.
        if (btnNewChat != null) {
            btnNewChat.setVisibility(hasMessages ? View.GONE : View.VISIBLE);
        }
    }

    private void updateEmptyStateText() {
        if (emptyTitle == null || emptySubtitle == null) {
            return;
        }
        if (temporaryChatEnabled) {
            stopVibeRotation();
            emptyTitle.setText(getString(R.string.chat_temp_chat_title));
            emptySubtitle.setText(getString(R.string.chat_temp_chat_subtitle));
        } else {
            emptyTitle.setText(getString(R.string.chat_hello_name, getUserFirstName()));
            startVibeRotation();
        }
    }

    private String getUserFirstName() {
        // OAuth-профиль первичен (name → preferred_username), легаси-prefs — fallback.
        app.multiai.mobile.network.oauth.OAuthStore oauthStore =
                new app.multiai.mobile.network.oauth.OAuthStore(this);
        String name = oauthStore.getName();
        if (name == null || name.trim().isEmpty()) {
            name = oauthStore.getPreferredUsername();
        }
        if (name == null || name.trim().isEmpty()) {
            name = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("name", null);
        }
        if (name == null || name.trim().isEmpty()) {
            return getString(R.string.chat_friend);
        }
        String trimmed = name.trim();
        // Telegram-ники приходят как "@durov" — без собаки приветствие чище.
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }

    // ══════════════════ VIBE LINES ROTATION ══════════════════

    private String[] vibeLinesCache = null;

    /** Lazily loads the vibe lines from the string-array resource. */
    private String[] getVibeLines() {
        if (vibeLinesCache == null) {
            vibeLinesCache = getResources().getStringArray(R.array.vibe_lines);
        }
        return vibeLinesCache;
    }

    private int vibeLineIndex = -1;
    private final Random vibeRandom = new Random();
    private final Runnable vibeRotator = new Runnable() {
        @Override
        public void run() {
            if (emptySubtitle == null || emptyState == null || emptyState.getVisibility() != View.VISIBLE) return;
            animateSubtitleSwap(nextVibeLine());
            handler.postDelayed(this, 4500L);
        }
    };

    private String nextVibeLine() {
        String[] lines = getVibeLines();
        if (lines.length <= 1) return lines[0];
        int next;
        do {
            next = vibeRandom.nextInt(lines.length);
        } while (next == vibeLineIndex);
        vibeLineIndex = next;
        return lines[next];
    }

    private void animateSubtitleSwap(String newText) {
        if (emptySubtitle == null) return;
        emptySubtitle.animate()
                .alpha(0f)
                .translationY(-10f)
                .setDuration(260)
                .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    emptySubtitle.setText(newText);
                    emptySubtitle.setTranslationY(12f);
                    emptySubtitle.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(360)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                            .start();
                })
                .start();
    }

    private void startVibeRotation() {
        if (emptySubtitle == null) return;
        handler.removeCallbacks(vibeRotator);
        emptySubtitle.setAlpha(1f);
        emptySubtitle.setTranslationY(0f);
        emptySubtitle.setText(nextVibeLine());
        handler.postDelayed(vibeRotator, 4500L);
    }

    private void stopVibeRotation() {
        handler.removeCallbacks(vibeRotator);
    }

    private String getRandomVibeLine() {
        return nextVibeLine();
    }

    // ══════════════════ SIDEBAR ══════════════════

    private void initSidebar() {
        sidebarOverlay = findViewById(R.id.sidebarOverlay);
        if (sidebarOverlay == null) return;

        sidebarOverlay.setTranslationX(-2000f);
        sidebarOverlay.setVisibility(View.GONE);

        RecyclerView chatList = findViewById(R.id.sidebarChatList);
        if (chatList != null) {
            sidebarChatsAdapter = new ChatModeChatsAdapter();
            chatList.setLayoutManager(new LinearLayoutManager(this));
            chatList.setAdapter(sidebarChatsAdapter);
            sidebarChatsAdapter.setOnChatClickListener(chat -> switchToChat(chat));
            sidebarChatsAdapter.setOnChatLongClickListener((chat, anchor) -> showChatActionsMenu(chat));
        }

        // "Чат" FAB — new chat + close sidebar
        View fabNewChat = findViewById(R.id.sidebarNewChatFab);
        if (fabNewChat != null) {
            fabNewChat.setOnClickListener(v -> {
                closeSidebar();
                createNewChat();
            });
        }

        // Profile avatar → профиль MultiAI (OAuth-аккаунт)
        sidebarProfile = findViewById(R.id.sidebarProfileAvatar);
        if (sidebarProfile != null) {
            sidebarProfile.setOnClickListener(v -> {
                closeSidebar();
                startActivity(new Intent(this, ProfileActivity.class));
                overridePendingTransition(R.anim.fade_slide_in, R.anim.fade_slide_out);
            });
            loadSidebarAvatar();
        }

        sidebarSearchInput = findViewById(R.id.sidebarSearchInput);
        sidebarTitle = findViewById(R.id.sidebarTitle);
        sidebarBrandLogo = findViewById(R.id.sidebarBrandLogo);
        sidebarBrandBlock = findViewById(R.id.sidebarBrandBlock);
        sidebarSearchGroup = findViewById(R.id.sidebarSearchGroup);
        // Brand avatar click in chat-mode sidebar → stay in chat (already here).
        // Per user request: tapping brand icon in burger goes to chat of current mode.
        // In chat mode we're already in ChatModeActivity → just close sidebar.
        if (sidebarBrandLogo != null) {
            sidebarBrandLogo.setOnClickListener(v -> closeSidebar());
        }
        if (sidebarBrandBlock != null) {
            sidebarBrandBlock.setOnClickListener(v -> closeSidebar());
        }
        // CHAT badge in personal-mode sidebar → opens mode switcher dropdown.
        View badgeSidebar = findViewById(R.id.modeBadgeContainerSidebar);
        if (badgeSidebar != null) {
            badgeSidebar.setOnClickListener(v ->
                    app.multiai.mobile.ui.kit.ModeSwitcher.showDropdown(this, badgeSidebar,
                            app.multiai.mobile.ui.kit.ModeSwitcher.MODE_CHAT));
        }
        sidebarSearchButton = findViewById(R.id.sidebarSearch);
        if (sidebarSearchButton != null) {
            sidebarSearchButton.setTranslationX(-2f);
            sidebarSearchButton.setOnClickListener(v -> toggleSidebarSearch());
        }
        if (sidebarSearchInput != null) {
            sidebarSearchInput.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override
                public void afterTextChanged(Editable s) {
                    updateSidebarChats(s.toString());
                }
            });
        }

        // "Воспоминания" → saved memories
        View sidebarMore = findViewById(R.id.sidebarMore);
        if (sidebarMore != null) {
            sidebarMore.setOnClickListener(v -> {
                closeSidebar();
                startActivity(new Intent(this, SavedMemoriesActivity.class));
                overridePendingTransition(R.anim.fade_slide_in, R.anim.fade_slide_out);
            });
        }

        // Настройки — тот же экран, что открывает Work-режим из drawer'а.
        View sidebarSettings = findViewById(R.id.sidebarSettings);
        if (sidebarSettings != null) {
            sidebarSettings.setOnClickListener(v -> {
                closeSidebar();
                startActivity(new Intent(this, ChatModeSettingsActivity.class));
                overridePendingTransition(R.anim.fade_slide_in, R.anim.fade_slide_out);
            });
        }

        // Версия приложения вместо хардкода «1.3» в футере бургер-меню.
        TextView drawerVersion = findViewById(R.id.drawerVersionLabel);
        if (drawerVersion != null) {
            drawerVersion.setText(getString(R.string.drawer_version, BuildConfig.VERSION_NAME));
        }
    }

    /**
     * Аватарка в сайдбаре чат-режима. У fallback drawable собственный inset,
     * поэтому он сохраняет безопасный размер даже после сброса padding для фото.
     */
    private void loadSidebarAvatar() {
        if (sidebarProfile == null) return;
        // Load photo URL if available (OAuth store first, legacy prefs fallback).
        app.multiai.mobile.network.oauth.OAuthStore oauthStore =
                new app.multiai.mobile.network.oauth.OAuthStore(this);
        String photoUrl = oauthStore.getPicture();
        if (photoUrl == null || photoUrl.trim().isEmpty()) {
            photoUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString("photo_url", null);
        }
        if (photoUrl != null && !photoUrl.trim().isEmpty() && !"null".equals(photoUrl)) {
            try {
                // Реальная аватарка заполняет весь круг без tint и внешнего padding.
                sidebarProfile.setImageTintList(null);
                sidebarProfile.setPadding(0, 0, 0, 0);
                sidebarProfile.setTag(true);
                com.bumptech.glide.Glide.with(this).load(photoUrl)
                        .transform(new com.bumptech.glide.load.resource.bitmap.CircleCrop())
                        .placeholder(R.drawable.chat_mode_ic_user_sidebar)
                        .error(R.drawable.chat_mode_ic_user_sidebar)
                        .into(sidebarProfile);
            } catch (Exception ignored) {}
        } else if (sidebarProfile.getTag() != null) {
            // URL пропал (разлогин) — вернуть заглушку с intrinsic inset и tint.
            com.bumptech.glide.Glide.with(this).clear(sidebarProfile);
            sidebarProfile.setTag(null);
            sidebarProfile.setImageResource(R.drawable.chat_mode_ic_user_sidebar);
            sidebarProfile.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            ContextCompat.getColor(this, R.color.icon_muted)));
            sidebarProfile.setPadding(0, 0, 0, 0);
        }
    }

    private void toggleSidebar() {
        if (sidebarOpen) closeSidebar();
        else openSidebar();
    }

    private void settleSidebarOpen() {
        if (sidebarOverlay == null) {
            return;
        }
        sidebarOpen = true;
        sidebarOverlay.animate().cancel();
        sidebarOverlay.setVisibility(View.VISIBLE);
        sidebarOverlay.animate()
                .translationX(0f)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                .withEndAction(() -> {})
                .start();
        // Shift main content to the right
        if (mainContent != null) {
            mainContent.animate()
                    .translationX(dp(260))
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
        }
    }

    private void settleSidebarClosed(float targetX) {
        if (sidebarOverlay == null) {
            return;
        }
        sidebarOpen = false;
        sidebarOverlay.animate().cancel();
        sidebarOverlay.animate()
                .translationX(targetX)
                .setDuration(180)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                .withEndAction(() -> {
                    sidebarOverlay.setVisibility(View.GONE);
                    sidebarOverlay.setTranslationX(-getSidebarWidth());
                })
                .start();
        // Return main content to original position
        if (mainContent != null) {
            mainContent.animate()
                    .translationX(0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f))
                    .start();
        }
    }

    private void openSidebar() {
        if (sidebarOverlay == null || sidebarOpen) return;

        loadSidebarAvatar();
        // Update chat list in sidebar
        updateSidebarChats(sidebarSearchInput != null ? sidebarSearchInput.getText().toString() : "");
        sidebarOverlay.animate().cancel();
        sidebarOverlay.setVisibility(View.VISIBLE);
        sidebarOverlay.setTranslationX(-getSidebarWidth());
        settleSidebarOpen();
    }

    private void closeSidebar() {
        if (sidebarOverlay == null || !sidebarOpen) return;
        setSidebarSearchExpanded(false, false);
        settleSidebarClosed(-getSidebarWidth());
    }

    private void toggleSidebarSearch() {
        setSidebarSearchExpanded(!sidebarSearchExpanded, true);
    }

    private void setSidebarSearchExpanded(boolean expanded, boolean animate) {
        if (sidebarSearchInput == null || sidebarSearchGroup == null) return;
        if (sidebarSearchExpanded == expanded && animate) return;
        sidebarSearchExpanded = expanded;

        int collapsedWidth = dp(36);
        View header = (View) sidebarSearchGroup.getParent();
        int headerWidth = header.getWidth() > 0 ? header.getWidth() : getSidebarWidth();
        int expandedWidth = Math.max(collapsedWidth,
                headerWidth - header.getPaddingStart() - header.getPaddingEnd());
        int startWidth = sidebarSearchGroup.getWidth() > 0
                ? sidebarSearchGroup.getWidth() : collapsedWidth;
        int endWidth = expanded ? expandedWidth : collapsedWidth;

        if (sidebarSearchWidthAnimator != null) {
            sidebarSearchWidthAnimator.cancel();
            sidebarSearchWidthAnimator = null;
        }
        sidebarSearchInput.animate().cancel();
        if (sidebarBrandLogo != null) sidebarBrandLogo.animate().cancel();
        if (sidebarBrandBlock != null) sidebarBrandBlock.animate().cancel();

        if (!expanded) {
            if (sidebarBrandLogo != null) sidebarBrandLogo.setVisibility(View.VISIBLE);
            if (sidebarBrandBlock != null) sidebarBrandBlock.setVisibility(View.VISIBLE);
        } else {
            sidebarSearchInput.setVisibility(View.VISIBLE);
            if (startWidth == collapsedWidth) sidebarSearchInput.setAlpha(0f);
        }

        if (!animate) {
            setSidebarSearchWidth(endWidth);
            settleSidebarSearchViews(expanded);
        } else {
            sidebarSearchWidthAnimator = ValueAnimator.ofInt(startWidth, endWidth);
            sidebarSearchWidthAnimator.setDuration(220);
            sidebarSearchWidthAnimator.setInterpolator(
                    new android.view.animation.DecelerateInterpolator(1.6f));
            sidebarSearchWidthAnimator.addUpdateListener(animation ->
                    setSidebarSearchWidth((int) animation.getAnimatedValue()));
            sidebarSearchWidthAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (animation != sidebarSearchWidthAnimator) return;
                    sidebarSearchWidthAnimator = null;
                    settleSidebarSearchViews(sidebarSearchExpanded);
                }
            });
            sidebarSearchWidthAnimator.start();

            sidebarSearchInput.animate()
                    .alpha(expanded ? 1f : 0f)
                    .setDuration(expanded ? 180 : 120)
                    .start();
            animateSidebarBrand(sidebarBrandLogo, expanded);
            animateSidebarBrand(sidebarBrandBlock, expanded);
        }

        if (sidebarSearchButton != null) {
            sidebarSearchButton.setImageResource(expanded ? R.drawable.ic_close : R.drawable.ic_magnify);
            sidebarSearchButton.setContentDescription(getString(expanded
                    ? R.string.close_search : R.string.chat_search_hint));
            sidebarSearchButton.setTranslationX(expanded ? 0f : -2f);
        }

        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (expanded) {
            sidebarSearchInput.requestFocus();
            sidebarSearchInput.post(() -> {
                if (sidebarSearchExpanded && imm != null) {
                    imm.showSoftInput(sidebarSearchInput,
                            android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                }
            });
        } else {
            sidebarSearchInput.setText("");
            sidebarSearchInput.clearFocus();
            if (imm != null) imm.hideSoftInputFromWindow(sidebarSearchInput.getWindowToken(), 0);
        }
    }

    private void setSidebarSearchWidth(int width) {
        ViewGroup.LayoutParams params = sidebarSearchGroup.getLayoutParams();
        params.width = width;
        sidebarSearchGroup.setLayoutParams(params);
    }

    private void animateSidebarBrand(View view, boolean hide) {
        if (view == null) return;
        view.animate()
                .alpha(hide ? 0f : 1f)
                .setDuration(160)
                .start();
    }

    private void settleSidebarSearchViews(boolean expanded) {
        sidebarSearchInput.animate().cancel();
        sidebarSearchInput.setAlpha(expanded ? 1f : 0f);
        sidebarSearchInput.setVisibility(expanded ? View.VISIBLE : View.GONE);
        settleSidebarBrand(sidebarBrandLogo, expanded);
        settleSidebarBrand(sidebarBrandBlock, expanded);
        if (sidebarSearchButton != null) {
            sidebarSearchButton.setTranslationX(expanded ? 0f : -2f);
        }
    }

    private void settleSidebarBrand(View view, boolean hidden) {
        if (view == null) return;
        view.animate().cancel();
        view.setAlpha(hidden ? 0f : 1f);
        view.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }

    private void updateSidebarChats() {
        updateSidebarChats("");
    }

    private void updateSidebarChats(String query) {
        TextView emptyText = findViewById(R.id.sidebarEmptyText);
        RecyclerView chatList = findViewById(R.id.sidebarChatList);
        List<Chat> filteredChats = new ArrayList<>();
        String normalizedQuery = query != null ? query.trim().toLowerCase() : "";

        for (Chat chat : allChats) {
            String title = chat.getTitle() != null ? chat.getTitle().toLowerCase() : "";
            String preview = chat.getLastMessagePreview() != null ? chat.getLastMessagePreview().toLowerCase() : "";
            if (normalizedQuery.isEmpty() || title.contains(normalizedQuery) || preview.contains(normalizedQuery)) {
                filteredChats.add(chat);
            }
        }

        if (filteredChats.isEmpty()) {
            if (emptyText != null) emptyText.setVisibility(View.VISIBLE);
            if (chatList != null) chatList.setVisibility(View.GONE);
        } else {
            if (emptyText != null) emptyText.setVisibility(View.GONE);
            if (chatList != null) chatList.setVisibility(View.VISIBLE);
            if (sidebarChatsAdapter != null) {
                sidebarChatsAdapter.setActiveChatId(currentChat != null ? currentChat.getId() : null);
                sidebarChatsAdapter.setChats(filteredChats);
            }
        }
    }

    private void loadModelFromSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        selectedModelId = prefs.getString("selected_model_id", null);
        selectedModelName = prefs.getString("selected_model_name", null);
        // ChatActivity (Work mode) stores model selection in a different prefs file.
        // Fall back to it so pretty names stay consistent across modes.
        if (selectedModelId == null) {
            SharedPreferences workPrefs = getSharedPreferences("multiaiide_settings", MODE_PRIVATE);
            selectedModelId = workPrefs.getString("selected_model_id", null);
            selectedModelName = workPrefs.getString("selected_model_name", null);
        }
        if (selectedModelName == null) selectedModelName = getString(R.string.chat_model_not_selected);
        updateModelPill();
        loadAvailableModels();
    }

    private void loadAvailableModels() {
        availableModels.clear();
        modelCatalog.clear();
        String accessToken = OAuthSession.get().getAccessToken();

        new Thread(() -> {
            try {
                List<app.multiai.mobile.models.ModelInfo> catalog =
                        new MultiAiClient().fetchModelCatalog(accessToken);
                runOnUiThread(() -> {
                    modelCatalog.clear();
                    modelCatalog.addAll(catalog);
                    availableModels.clear();
                    for (app.multiai.mobile.models.ModelInfo mi : catalog) {
                        availableModels.add(new String[]{mi.id, mi.displayName});
                    }
                    // Validate selected model
                    boolean valid = false;
                    // Кастомная модель провайдера не входит в каталог MultiAI —
                    // считаем валидной, пока резолвится в существующего провайдера.
                    if (selectedModelId != null
                            && app.multiai.mobile.utils.CustomProviderStore.resolve(
                                    this, selectedModelId) != null) {
                        valid = true;
                    }
                    for (app.multiai.mobile.models.ModelInfo mi : modelCatalog) {
                        if (mi.id.equals(selectedModelId)) {
                            valid = true;
                            // Свежее имя из каталога (prefs мог хранить старый формат)
                            if (!mi.displayName.equals(selectedModelName)) {
                                selectedModelName = mi.displayName;
                                SharedPreferences.Editor ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                                        .putString("selected_model_name", selectedModelName);
                                // Sync to Work-mode prefs so pretty names survive cross-mode restarts.
                                getSharedPreferences("multiaiide_settings", MODE_PRIVATE).edit()
                                        .putString("selected_model_name", selectedModelName)
                                        .putString("selected_model_id", selectedModelId)
                                        .apply();
                                ed.apply();
                            }
                            break;
                        }
                    }
                    if (!valid) {
                        selectedModelId = null;
                        selectedModelName = getString(R.string.chat_model_not_selected);
                    }
                    // Восстановить сохранённый effort для выбранной модели
                    applySavedEffort();
                    updateModelPill();
                    refreshContextRing();
                    refreshActiveModelDialogIfOpen();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    availableModels.clear();
                    updateModelPill();
                    refreshActiveModelDialogIfOpen();
                });
            }
        }).start();
    }

    /** Найти ModelInfo выбранной модели или null. */
    private app.multiai.mobile.models.ModelInfo findSelectedModel() {
        if (selectedModelId == null) return null;
        for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
            if (model != null && selectedModelId.equals(model.id)) return model;
        }
        return null;
    }

    /** Прописать в клиент сохранённый effort выбранной модели. */
    private void applySavedEffort() {
        if (multiAiClient == null) return;
        app.multiai.mobile.models.ModelInfo mi = findSelectedModel();
        if (mi == null) {
            selectedEffort = null;
            multiAiClient.setEffort(null);
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String effortKey = "effort_" + mi.id;
        boolean fromChat = currentChat != null && mi.id.equals(currentChat.getModelId());
        String stored = fromChat
                ? currentChat.getModelEffort()
                : prefs.getString(effortKey, mi.reasoningDefault);
        selectedEffort = mi.resolveEffort(stored);
        if (!java.util.Objects.equals(stored, selectedEffort)) {
            if (fromChat) {
                currentChat.setModelEffort(selectedEffort);
                try { chatManager.updateChat(currentChat); } catch (Exception ignored) {}
            } else {
                SharedPreferences.Editor edit = prefs.edit();
                if (selectedEffort == null) edit.remove(effortKey);
                else edit.putString(effortKey, selectedEffort);
                edit.apply();
            }
        }
        multiAiClient.setEffort(selectedEffort);
    }

    private String modelPillText() {
        String display = selectedModelName != null ? selectedModelName : "MultiAI";
        return display
                .replace("Claude ", "").replace("claude ", "")
                .replace("MiniMax ", "").replace("DeepSeek ", "DS ")
                .replace("Qwen3 Coder ", "Qwen ").replace("GLM-", "GLM");
    }

    private void updateModelPill() {
        // Бренд-текст «MultiAI» заменён на имя выбранной модели.
        // Отдельный modelPicker скрыт — имя живёт прямо в brandingPill.
        TextView modelPicker = findViewById(R.id.modelPicker);
        if (modelPicker != null) modelPicker.setVisibility(View.GONE);
        if (brandingPill != null) {
            if (messagesAdapter != null && selectedModelName != null) {
                messagesAdapter.setCurrentModelName(selectedModelName);
            }
            if (connectionStatus != null) connectionStatus.refreshConnectedText();
            else brandingPill.setText(modelPillText());
            int screenWidthDp = getResources().getConfiguration().screenWidthDp;
            brandingPill.setMaxWidth(dp(screenWidthDp < 400 ? 108 : 160));
            brandingPill.setSingleLine(true);
            brandingPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        ensureContextRing();
    }

    /** Контекст-кольцо у кнопки отправки: заполненность окна текущим диалогом. */
    private app.multiai.mobile.ui.kit.ModelCatalogUi.ContextRing contextRing;

    private void ensureContextRing() {
        if (contextRing != null) {
            refreshContextRing();
            return;
        }
        // inputBox теперь вертикальный (превью сверху) — кольцо живёт в
        // горизонтальной строке ввода standardInputGroup.
        android.widget.FrameLayout inputBox = findViewById(R.id.standardInputGroup);
        if (inputBox == null) return;
        contextRing = new app.multiai.mobile.ui.kit.ModelCatalogUi.ContextRing(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        lp.rightMargin = dp(50); // левее mic/send (кнопки 34-40dp + margin)
        contextRing.setLayoutParams(lp);
        contextRing.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || !v.isAttachedToWindow()) return;
            app.multiai.mobile.models.ModelInfo model = findSelectedModel();
            long limit = model != null ? Math.max(0L, model.contextWindow) : 0L;
            String name = model != null && model.displayName != null
                    ? model.displayName
                    : (selectedModelName != null ? selectedModelName : "MultiAI");
            app.multiai.mobile.ui.kit.ModelCatalogUi.showContextSheet(
                    ChatModeActivity.this, buildCatalogUsage(), limit, name);
        });
        
        inputBox.addView(contextRing);
        // Увеличенная зона тача (48dp) вокруг маленького кольца 16dp.
        inputBox.post(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            contextRing.getHitRect(r);
            int extra = (dp(48) - contextRing.getWidth()) / 2;
            if (extra > 0) {
                r.left -= extra; r.top -= extra;
                r.right += extra; r.bottom += extra;
                inputBox.setTouchDelegate(new android.view.TouchDelegate(r, contextRing));
            }
        });
        refreshContextRing();
    }

    /** Пересчитать использование контекста: кольцо считает весь диалог,
     *  но при 0 сообщениях показывает 0 (системный промпт — постоянная база,
     *  а не «заполненность» с точки зрения юзера). */
    private void refreshContextRing() {
        if (contextRing == null) return;
        app.multiai.mobile.models.ModelInfo mi = findSelectedModel();
        long limit = mi != null ? mi.contextWindow : 0;
        long msgs = messagesTokens();
        long used = msgs > 0 ? msgs + systemPromptTokens() + toolsTokens() : 0;
        contextRing.setUsage(used, limit);
    }

    /** Грубая оценка токенов диалога (~4 символа/токен) с системным промптом. */
    private long estimateChatTokens() {
        return systemPromptTokens() + messagesTokens() + toolsTokens();
    }

    /** Сжимает старый участок истории до отправки нового сообщения. */
    private boolean startAutoSummaryIfNeeded() {
        if (skipAutoSummaryOnce) {
            skipAutoSummaryOnce = false;
            return false;
        }
        // Пока идёт компактирование, не создаём дубликат turn с полной историей.
        if (autoSummarizing) return true;
        if (isGenerating || selectedModelId == null
                || selectedModelId.trim().isEmpty()) return false;

        final boolean temporary = temporaryChatEnabled;
        final Chat targetChat = currentChat;
        final List<Message> source = temporary ? temporaryMessages
                : (targetChat != null ? targetChat.getMessages() : null);
        app.multiai.mobile.models.ModelInfo model = findSelectedModel();
        long limit = model != null ? Math.max(0L, model.contextWindow) : 0L;
        if (source == null || limit <= 0L || source.size() < AUTO_SUMMARY_MIN_MESSAGES
                || estimateChatTokens() < Math.round(limit * AUTO_SUMMARY_TRIGGER_RATIO)) {
            return false;
        }

        final int covered = hasContextSummary(temporary, targetChat)
                ? Math.min(contextSummaryMessageCount(temporary, targetChat), source.size()) : 0;
        final int tailCount = Math.min(AUTO_SUMMARY_TAIL_MESSAGE_COUNT,
                Math.max(4, source.size() / 2));
        final int endExclusive = Math.max(covered, source.size() - tailCount);
        if (endExclusive - covered < AUTO_SUMMARY_MIN_NEW_MESSAGES) return false;

        final List<Message> segment = new ArrayList<>();
        for (int i = covered; i < endExclusive; i++) {
            Message message = source.get(i);
            if (message != null && (message.getType() == Message.TYPE_USER
                    || message.getType() == Message.TYPE_AI)) {
                segment.add(message);
            }
        }
        if (segment.isEmpty()) return false;

        final int finalEndExclusive = endExclusive;
        final String previousSummary = contextSummary(temporary, targetChat);
        autoSummarizing = true;
        ModernToast.show(this, getString(R.string.chat_context_compacting));

        String summaryInstructions = app.multiai.mobile.utils.ConversationSummaryService
                .buildSystemInstructions(previousSummary);
        multiAiClient.sendMessage(selectedModelId, summaryInstructions, segment,
                "Create the updated compact conversation summary now.", false,
                new MultiAiClient.ResponseCallback() {
                    @Override
                    public void onSuccess(String content, String thinking, List<ToolCall> toolCalls) {
                        runOnUiThread(() -> {
                            autoSummarizing = false;
                            String compact = app.multiai.mobile.utils.ConversationSummaryService
                                    .normalizeSummary(content);
                            if (compact.isEmpty()) {
                                continueAfterAutoSummaryFailure(temporary, targetChat);
                                return;
                            }
                            if (compact.length() > 12_000) compact = compact.substring(0, 12_000);
                            setContextSummary(temporary, targetChat, compact, finalEndExclusive);
                            if (!temporary) {
                                try {
                                    if (chatManager != null) chatManager.updateChat(targetChat);
                                } catch (Exception ignored) {
                                }
                            }
                            refreshContextRing();
                            if (isSameSummaryConversation(temporary, targetChat) && !isGenerating) {
                                sendMessage();
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            autoSummarizing = false;
                            continueAfterAutoSummaryFailure(temporary, targetChat);
                        });
                    }
                });
        return true;
    }

    private void continueAfterAutoSummaryFailure(boolean temporary, Chat targetChat) {
        // A background helper must never prevent the user from sending a message.
        if (isSameSummaryConversation(temporary, targetChat) && !isGenerating) {
            skipAutoSummaryOnce = true;
            sendMessage();
        }
    }

    /** Compacts the older prefix through a separate tool-free AI client. */
    private String executeHistorySummaryTool() throws Exception {
        final boolean temporary = temporaryChatEnabled;
        final Chat targetChat = currentChat;
        final List<Message> allMessages = new ArrayList<>(temporary ? temporaryMessages
                : (targetChat != null ? targetChat.getMessages() : new ArrayList<>()));
        final int covered = hasContextSummary(temporary, targetChat)
                ? Math.min(contextSummaryMessageCount(temporary, targetChat), allMessages.size()) : 0;
        final int tailCount = Math.min(AUTO_SUMMARY_TAIL_MESSAGE_COUNT,
                Math.max(4, allMessages.size() / 2));
        final int endExclusive = Math.max(covered, allMessages.size() - tailCount);
        if (endExclusive <= covered) {
            return new JSONObject().put("success", true).put("skipped", true)
                    .put("summarized_message_count", covered)
                    .put("retained_recent_message_count", allMessages.size() - covered)
                    .put("message", "The existing summary and recent history already fit the context")
                    .toString();
        }

        final List<Message> segment = new ArrayList<>();
        for (int i = covered; i < endExclusive; i++) {
            Message message = allMessages.get(i);
            if (message != null && (message.getType() == Message.TYPE_USER
                    || message.getType() == Message.TYPE_AI)) {
                segment.add(message);
            }
        }
        if (segment.isEmpty()) {
            return new JSONObject().put("success", true).put("skipped", true)
                    .put("message", "No conversation messages are available in the older history").toString();
        }

        try {
            app.multiai.mobile.utils.ConversationSummaryService.Result result =
                    app.multiai.mobile.utils.ConversationSummaryService.summarize(
                            selectedModelId, contextSummary(temporary, targetChat), segment, null);
            String summary = result.getSummary();
            setContextSummary(temporary, targetChat, summary, endExclusive);
            if (!temporary && targetChat != null && chatManager != null) {
                chatManager.updateChat(targetChat);
            }
            runOnUiThread(() -> {
                if (isSameSummaryConversation(temporary, targetChat)) refreshContextRing();
            });
            return new JSONObject().put("success", true)
                    .put("summary", summary)
                    .put("summarized_message_count", endExclusive)
                    .put("retained_recent_message_count", allMessages.size() - endExclusive)
                    .put("message", "History summary was generated by a separate AI client and saved")
                    .toString();
        } catch (app.multiai.mobile.utils.ConversationSummaryService.SummaryException e) {
            return new JSONObject().put("error", e.getMessage()).put("code", e.getCode()).toString();
        }
    }

    private boolean isSameSummaryConversation(boolean temporary, Chat targetChat) {
        return temporary ? temporaryChatEnabled : currentChat == targetChat;
    }

    private String contextSummary(boolean temporary, Chat chat) {
        return temporary ? temporaryContextSummary : (chat != null ? chat.getContextSummary() : null);
    }

    private int contextSummaryMessageCount(boolean temporary, Chat chat) {
        return temporary ? Math.max(0, temporaryContextSummaryMessageCount)
                : (chat != null ? chat.getContextSummaryMessageCount() : 0);
    }

    private boolean hasContextSummary(boolean temporary, Chat chat) {
        String summary = contextSummary(temporary, chat);
        return contextSummaryMessageCount(temporary, chat) > 0
                && summary != null && !summary.trim().isEmpty();
    }

    private void setContextSummary(boolean temporary, Chat chat, String summary, int count) {
        if (temporary) {
            temporaryContextSummary = summary;
            temporaryContextSummaryMessageCount = Math.max(0, count);
        } else if (chat != null) {
            chat.setContextSummary(summary);
            chat.setContextSummaryMessageCount(count);
        }
    }

    private List<Message> trimHistoryCoveredBySummary(List<Message> history, boolean temporary, Chat chat) {
        if (history == null || history.isEmpty() || !hasContextSummary(temporary, chat)) return history;
        int from = Math.min(contextSummaryMessageCount(temporary, chat), history.size());
        return new ArrayList<>(history.subList(from, history.size()));
    }

    private long systemPromptTokens() {
        String sys = buildEnrichedInstructions();
        return sys != null ? sys.length() / 4 : 0;
    }

    private long messagesTokens() {
        final boolean temporary = temporaryChatEnabled;
        final Chat chat = currentChat;
        List<Message> source = temporary ? temporaryMessages
                : (chat != null ? chat.getMessages() : null);
        if (source == null) return 0L;
        long chars = 0L;
        int start = 0;
        if (hasContextSummary(temporary, chat)) {
            chars += contextSummary(temporary, chat).trim().length();
            start = Math.min(contextSummaryMessageCount(temporary, chat), source.size());
        }
        for (int i = start; i < source.size(); i++) {
            Message message = source.get(i);
            if (message != null) chars += message.getContentSafe().length();
        }
        return chars / 4;
    }

    /** Схема инструментов ~ фиксированный размер (4 локальных тулзы). */
    private long toolsTokens() {
        return 220;
    }

    private app.multiai.mobile.ui.kit.ModelCatalogUi.Usage buildCatalogUsage() {
        // 0 сообщений = нулевой контекст (как в кольце): системный промпт
        // и инструменты считаются только когда диалог начат.
        if (messagesTokens() == 0) {
            return new app.multiai.mobile.ui.kit.ModelCatalogUi.Usage(0, 0, 0);
        }
        return new app.multiai.mobile.ui.kit.ModelCatalogUi.Usage(
                systemPromptTokens(), messagesTokens(), toolsTokens());
    }

    private void loadChats() {
        allChats = chatManager.loadActiveChats();
        // При старте приложения всегда открываем новый пустой чат,
        // а не тот, в котором пользователь был в прошлый раз.
        currentChat = null;
        chatManager.saveActiveChatId(null);
        loadMessages();
    }

    /** Crossfade between empty state and the messages list (short + subtle). */
    private void showMessagesCrossfade() {
        if (emptyState == null || recyclerMessages == null) return;
        if (recyclerMessages.getVisibility() == View.VISIBLE
                && emptyState.getVisibility() != View.VISIBLE
                && recyclerMessages.getAlpha() >= 0.99f) {
            return;
        }
        emptyState.animate().cancel();
        recyclerMessages.animate().cancel();
        emptyState.setAlpha(1f);
        recyclerMessages.setAlpha(0f);
        recyclerMessages.setVisibility(View.VISIBLE);
        emptyState.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> emptyState.setVisibility(View.GONE)).start();
        recyclerMessages.animate().alpha(1f).setDuration(220).start();
    }

    /**
     * Не запускает повторный notifyDataSetChanged(), если loadMessages() получил
     * тот же UI-снимок. Сигнатура намеренно не хэширует целиком большие ответы
     * инструментов: для восстановления достаточно структуры, размеров текста и
     * статусов tool calls.
     */
    private boolean bindMessagesIfChanged(String sourceKey, List<Message> source) {
        List<Message> safeSource = source != null ? source : java.util.Collections.emptyList();
        long fingerprint = messagesUiFingerprint(safeSource);
        boolean alreadyBound = sourceKey.equals(loadedMessagesSourceKey)
                && fingerprint == loadedMessagesFingerprint
                && messagesAdapter.getItemCount() == safeSource.size();
        if (alreadyBound) return false;

        messagesAdapter.setMessages(new ArrayList<>(safeSource));
        loadedMessagesSourceKey = sourceKey;
        loadedMessagesFingerprint = fingerprint;
        return true;
    }

    private long messagesUiFingerprint(List<Message> source) {
        long hash = 1125899906842597L;
        for (Message message : source) {
            if (message == null) {
                hash = 31L * hash;
                continue;
            }
            hash = 31L * hash + message.getType();
            hash = 31L * hash + message.getTimestamp();
            hash = 31L * hash + compactTextFingerprint(message.getContentSafe());
            hash = 31L * hash + compactTextFingerprint(message.getThinkingContent());
            hash = 31L * hash + (message.isToolWorkflowSummary() ? 1L : 0L);
            List<ToolCall> calls = message.getToolCalls();
            hash = 31L * hash + calls.size();
            for (ToolCall call : calls) {
                if (call == null) {
                    hash = 31L * hash;
                    continue;
                }
                hash = 31L * hash + java.util.Objects.hashCode(call.getId());
                hash = 31L * hash + java.util.Objects.hashCode(call.getName());
                hash = 31L * hash + java.util.Objects.hashCode(call.getStatus());
            }
        }
        return hash;
    }

    private long compactTextFingerprint(String value) {
        if (value == null || value.isEmpty()) return 0L;
        int length = value.length();
        long hash = length;
        hash = 31L * hash + value.charAt(0);
        hash = 31L * hash + value.charAt(length - 1);
        if (length > 2) hash = 31L * hash + value.charAt(length / 2);
        return hash;
    }

    /** Привести chat/prefs effort к возможностям текущего каталога. */
    private String resolveModelEffort(String modelId, String candidate) {
        if (modelId != null) {
            for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
                if (modelId.equals(model.id)) return model.resolveEffort(candidate);
            }
        }
        // До загрузки каталога не отправляем legacy synthetic none.
        return "none".equalsIgnoreCase(candidate) ? null : candidate;
    }

    /** Зафиксировать текущий выбор модели за чатом (пер-чат приоритет). */
    private void stampChatModel() {
        if (currentChat == null || selectedModelId == null) return;
        currentChat.setModelId(selectedModelId);
        currentChat.setModelEffort(multiAiClient != null ? multiAiClient.getEffort() : null);
    }

    /** Восстановить модель/effort чата, если за ним закреплён выбор. */
    private void applyChatModel(Chat chat) {
        if (chat == null || chat.getModelId() == null) return;
        selectedModelId = chat.getModelId();
        selectedModelName = chatDisplayName(chat.getModelId());
        String storedEffort = chat.getModelEffort();
        selectedEffort = resolveModelEffort(selectedModelId, storedEffort);
        app.multiai.mobile.models.ModelInfo effortModel = findSelectedModel();
        if (effortModel != null && !java.util.Objects.equals(storedEffort, selectedEffort)) {
            chat.setModelEffort(selectedEffort);
            try { chatManager.updateChat(chat); } catch (Exception ignored) {}
        }
        if (multiAiClient != null) multiAiClient.setEffort(selectedEffort);
        updateModelPill();
    }

    /** Человекочитаемое имя модели (из каталога, prefs или обрезка id). */
    private String chatDisplayName(String modelId) {
        if (modelId == null) return "MultiAI";
        // 1. Каталог уже загружен — ищем pretty name там.
        for (app.multiai.mobile.models.ModelInfo mi : modelCatalog) {
            if (mi.id.equals(modelId)) return mi.displayName;
        }
        // 2. Глобальный prefs (модель выбрана в данный момент).
        String saved = getSharedPreferences("multiai_profile", MODE_PRIVATE)
                .getString("selected_model_name", null);
        if (saved != null
                && modelId.equals(getSharedPreferences("multiai_profile", MODE_PRIVATE)
                        .getString("selected_model_id", null))) {
            return saved;
        }
        // 3. Work-mode prefs.
        String workSaved = getSharedPreferences("multiaiide_settings", MODE_PRIVATE)
                .getString("selected_model_name", null);
        if (workSaved != null
                && modelId.equals(getSharedPreferences("multiaiide_settings", MODE_PRIVATE)
                        .getString("selected_model_id", null))) {
            return workSaved;
        }
        // 4. Fallback — обрезка id.
        return modelId.contains("/") ? modelId.substring(modelId.lastIndexOf('/') + 1) : modelId;
    }

    private void loadMessages() {
        updateEmptyStateText();
        applyPillState();
        // Смена чата = новый контекст: кольцо должно пересчитаться сразу,
        // а не висеть со значением прошлого чата до конца стрима.
        refreshContextRing();
        if (temporaryChatEnabled) {
            if (!temporaryMessages.isEmpty()) {
                boolean changed = bindMessagesIfChanged("temporary", temporaryMessages);
                boolean wasVisible = recyclerMessages.getVisibility() == View.VISIBLE
                        && emptyState.getVisibility() != View.VISIBLE;
                showMessagesCrossfade();
                if (changed || !wasVisible) scrollToBottom();
            } else {
                emptyState.setVisibility(View.VISIBLE);
                recyclerMessages.setVisibility(View.GONE);
                bindMessagesIfChanged("empty", java.util.Collections.emptyList());
            }
            updateTopChatActions(false);
            return;
        }
        // Пер-чат модель действует и для пустого чата: выбор мог быть
        // закреплён до первого сообщения.
        if (currentChat != null) {
            applyChatModel(currentChat);
        }
        if (currentChat != null && !currentChat.getMessages().isEmpty()) {
            List<Message> messages = currentChat.getMessages();
            boolean changed = bindMessagesIfChanged("chat:" + currentChat.getId(), messages);
            boolean wasVisible = recyclerMessages.getVisibility() == View.VISIBLE
                    && emptyState.getVisibility() != View.VISIBLE;
            showMessagesCrossfade();
            if (changed || !wasVisible) scrollToBottom();
        } else {
            emptyState.setVisibility(View.VISIBLE);
            recyclerMessages.setVisibility(View.GONE);
            bindMessagesIfChanged("empty", java.util.Collections.emptyList());
        }
    }

    /**
     * Переключение на другой чат: сохраняет текущий, останавливает стрим
     * (недописанный ответ уходит в СТАРЫЙ чат) и открывает свежую копию
     * из хранилища — как это делает Work-режим.
     */
    private void switchToChat(Chat chat) {
        if (chat == null) return;
        if (currentChat != null && currentChat.getId().equals(chat.getId()) && !isGenerating) {
            closeSidebar();
            return;
        }

        // Сохраняем текущий чат до смены ссылки.
        if (!temporaryChatEnabled && currentChat != null && !currentChat.getMessages().isEmpty()) {
            try {
                chatManager.updateChat(currentChat);
            } catch (Exception ignored) {
            }
        }

        if (isGenerating) {
            // One stop path owns cancellation and finalizes both text and reasoning-only partials.
            stopGeneration();
        } else {
            streamEpoch++;
            clearRetryState();
            try {
                if (multiAiClient != null) multiAiClient.cancel();
                customProviderChatClient.cancel();
            } catch (Exception ignored) {
            }
        }

        temporaryChatEnabled = false;
        clearTemporaryConversation();

        // Свежая копия из хранилища — в adapter могла лежать устаревшая ссылка.
        Chat fresh = null;
        List<Chat> freshChats = chatManager.loadActiveChats();
        for (Chat c : freshChats) {
            if (c.getId().equals(chat.getId())) {
                fresh = c;
                break;
            }
        }
        currentChat = fresh != null ? fresh : chat;
        chatManager.saveActiveChatId(currentChat.getId());

        allChats = freshChats;
        updateSidebarChats();
        loadMessages();
        closeSidebar();
    }

    private void createNewChat() {
        if (isGenerating) {
            stopGeneration();
        } else {
            streamEpoch++;
            clearRetryState();
            try {
                multiAiClient.cancel();
                customProviderChatClient.cancel();
            } catch (Exception ignored) {}
        }
        // Незасейвленный черновик не теряем — фиксируем, если в нём есть сообщения.
        if (!temporaryChatEnabled && currentChat != null && !currentChat.getMessages().isEmpty()) {
            try {
                chatManager.updateChat(currentChat);
            } catch (Exception ignored) {
            }
        }
        temporaryChatEnabled = false;
        clearTemporaryConversation();
        applyPillState();
        updateEmptyStateText();
        // Лениво: пустой чат живёт только в памяти, на диск попадёт с первым
        // сообщением — без мусорных «Новый чат» в истории и облаке.
        currentChat = new Chat();
        // Восстанавливаем глобальную модель (из prefs) — новый чат не должен
        // наследовать модель предыдущего чата.
        loadModelFromSettings();
        allChats = chatManager.loadActiveChats();
        updateSidebarChats();
        updateTopChatActions(false);
        messagesAdapter.setMessages(new ArrayList<>());
        emptyState.setVisibility(View.VISIBLE);
        recyclerMessages.setVisibility(View.GONE);
        editMessage.setText("");
        // Fade animation
        emptyState.setAlpha(0f);
        emptyState.animate().alpha(1f).setDuration(300).start();
    }

    /** Все чаты очищены из Data Controls — мгновенно уходим в новый чат. */
    @Override
    public void onAllChatsCleared() {
        runOnUiThread(this::createNewChat);
    }

    // 🔥 Character-by-character streaming animation (как в Project mode)
    private final StringBuilder streamBuffer = new StringBuilder();
    private String streamDisplayed = "";
    private Message streamTargetMessage = null;
    /** Current AI bubble even while it only contains reasoning, before text animation starts. */
    private Message activeStreamMessage = null;
    private final Handler streamAnimHandler = new Handler(Looper.getMainLooper());
    private Runnable streamAnimTask = null;
    private Runnable streamDrainCallback = null;
    private boolean streamInputComplete = false;
    private static final int STREAM_CHAR_DELAY_MS = 80;
    private long lastStreamScrollAtMs = 0L;

    private void startStreamAnimation(Message target) {
        stopStreamAnimation();
        streamTargetMessage = target;
        streamDisplayed = "";
        streamInputComplete = false;
        streamDrainCallback = null;
        streamAnimTask = new Runnable() {
            @Override
            public void run() {
                if (streamTargetMessage == null) return;
                String fullText = streamBuffer.toString();
                if (!fullText.startsWith(streamDisplayed)) {
                    streamDisplayed = "";
                }

                int displayedCodePoints = streamDisplayed.codePointCount(0, streamDisplayed.length());
                int fullCodePoints = fullText.codePointCount(0, fullText.length());
                if (displayedCodePoints >= fullCodePoints) {
                    if (streamInputComplete) {
                        Runnable callback = streamDrainCallback;
                        streamDrainCallback = null;
                        stopStreamAnimation();
                        if (callback != null) callback.run();
                        return;
                    }
                    streamAnimHandler.postDelayed(this, 16);
                    return;
                }

                int lag = fullCodePoints - displayedCodePoints;
                int step = streamInputComplete ? lag : Math.max(4, (lag + 2) / 3);
                int nextCodePoint = Math.min(fullCodePoints, displayedCodePoints + step);
                int nextIndex = fullText.offsetByCodePoints(0, nextCodePoint);
                streamDisplayed = fullText.substring(0, nextIndex);
                streamTargetMessage.setContent(streamDisplayed);
                messagesAdapter.updateMessageContentDirect(streamTargetMessage, streamDisplayed);
                scrollToBottomDuringStream();
                streamAnimHandler.postDelayed(this, STREAM_CHAR_DELAY_MS);
            }
        };
        streamAnimHandler.post(streamAnimTask);
    }

    private void appendStreamChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        streamBuffer.append(chunk);
    }

    private void flushStreamAnimation() {
        stopStreamAnimation();
        if (streamTargetMessage != null && streamBuffer.length() > 0) {
            streamTargetMessage.setContent(streamBuffer.toString());
            messagesAdapter.updateMessageContentDirect(streamTargetMessage, streamBuffer.toString());
            streamDisplayed = streamBuffer.toString();
        }
        streamInputComplete = false;
        streamDrainCallback = null;
    }

    private void completeStreamAnimation(Runnable callback) {
        streamInputComplete = true;
        streamDrainCallback = callback;
        if (streamTargetMessage == null || streamBuffer.length() == 0) {
            Runnable ready = streamDrainCallback;
            streamDrainCallback = null;
            if (ready != null) ready.run();
        } else if (streamAnimTask == null) {
            startStreamAnimation(streamTargetMessage);
            streamInputComplete = true;
            streamDrainCallback = callback;
        }
    }

    private void stopStreamAnimation() {
        if (streamAnimTask != null) {
            streamAnimHandler.removeCallbacks(streamAnimTask);
            streamAnimTask = null;
        }
    }

    private void resetStreamAnimation() {
        stopStreamAnimation();
        streamBuffer.setLength(0);
        streamDisplayed = "";
        streamTargetMessage = null;
        streamInputComplete = false;
        streamDrainCallback = null;
    }

    /** Не запускаем smooth-scroll на каждый символ: это и было источником тряски.
     *  Пока пользователь держит список пальцем (drag/fling), стрим не скроллит —
     *  он читает, а не ждёт, когда вьюха дотащит его вниз. */
    private void scrollToBottomDuringStream() {
        if (!followStreamToBottom) return;
        if (userScrollingMessages) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStreamScrollAtMs < 96L) return;
        lastStreamScrollAtMs = now;
        recyclerMessages.post(() -> {
            if (followStreamToBottom && !userScrollingMessages && messagesAdapter.getItemCount() > 0) {
                scrollToBottom();
            }
        });
    }

    // ══════════════════ SEND MESSAGE ══════════════════

    /** Retry the exact failed user turn, including its optional attachment. */
    private void retryLastUserMessage(Message errorMessage) {
        if (isGenerating || errorMessage == null) return;
        List<Message> msgs = temporaryChatEnabled ? temporaryMessages
                : (currentChat != null ? currentChat.getMessages() : null);
        if (msgs == null || errorMessage.getType() != Message.TYPE_ERROR) return;
        int errorIndex = msgs.indexOf(errorMessage);
        // An old error card must never retry the latest unrelated user turn.
        if (errorIndex < 0 || errorIndex != msgs.size() - 1) return;

        int userIndex = -1;
        for (int i = errorIndex - 1; i >= 0; i--) {
            if (msgs.get(i).getType() == Message.TYPE_USER) {
                userIndex = i;
                break;
            }
        }
        if (userIndex < 0) return;

        Message userMessage = msgs.get(userIndex);
        // A failed turn can contain a partial AI answer and tool cards before its
        // error card. Drop all of that attempt, but keep exactly one user message.
        while (msgs.size() > userIndex + 1) {
            Message tail = msgs.remove(msgs.size() - 1);
            messagesAdapter.removeMessage(tail);
        }
        clearRetryState();
        retrySourceUser = userMessage;
        restoreRetryAttachmentAndSend(userMessage);
    }

    /** Retry a user message from the inline action buttons (not error-specific). */
    private void retryLastUserMessageForAction(Message userMessage) {
        if (isGenerating || userMessage == null) return;
        List<Message> msgs = temporaryChatEnabled ? temporaryMessages
                : (currentChat != null ? currentChat.getMessages() : null);
        if (msgs == null) return;
        int userIndex = msgs.indexOf(userMessage);
        if (userIndex < 0) return;
        // Delete everything after this user message
        while (msgs.size() > userIndex + 1) {
            Message tail = msgs.remove(msgs.size() - 1);
            messagesAdapter.removeMessage(tail);
        }
        clearRetryState();
        retrySourceUser = userMessage;
        restoreRetryAttachmentAndSend(userMessage);
    }

    /**
     * Restores retry attachments from memory or ChatManager's on-disk storage.
     * The file read happens off the main thread, otherwise a 10 MB retry would freeze the chat.
     */
    private void restoreRetryAttachmentAndSend(Message message) {
        if (message == null) return;
        final boolean retryTemporary = temporaryChatEnabled;
        final Chat retryChat = currentChat;
        new Thread(() -> {
            String image = message.getImageBase64();
            String fileContent = message.getAttachedFileContent();
            try {
                if ((image == null || image.isEmpty()) && message.getImagePath() != null) {
                    byte[] bytes = readLocalAttachment(message.getImagePath(), 7 * 1024 * 1024);
                    image = Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
                if ((fileContent == null || fileContent.isEmpty())
                        && message.getAttachedFilePath() != null) {
                    byte[] bytes = readLocalAttachment(message.getAttachedFilePath(), 10 * 1024 * 1024);
                    fileContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception error) {
                handler.post(() -> {
                    if (retrySourceUser == message) clearRetryState();
                    if (!isFinishing() && !isDestroyed()) {
                        ModernToast.show(this, getString(R.string.chat_error_read_file,
                                error.getClass().getSimpleName()));
                    }
                });
                return;
            }
            final String restoredImage = image;
            final String restoredFileContent = fileContent;
            handler.post(() -> {
                boolean staleRetry = isFinishing() || isDestroyed() || isGenerating
                        || retrySourceUser != message
                        || retryTemporary != temporaryChatEnabled
                        || (!retryTemporary && currentChat != retryChat);
                if (staleRetry) {
                    if (retrySourceUser == message) clearRetryState();
                    return;
                }
                removeAttachment();
                selectedImageBase64 = restoredImage;
                selectedImageName = message.getImageName();
                attachedFileName = message.getAttachedFileName();
                attachedFileContent = restoredFileContent;
                if (selectedImageBase64 != null && !selectedImageBase64.isEmpty()) {
                    try {
                        byte[] data = Base64.decode(app.multiai.mobile.utils.ImageAttachments.unpack(selectedImageBase64).get(0), Base64.DEFAULT);
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(
                                data, 0, data.length);
                        if (bitmap != null && attachmentPreview != null) {
                            attachmentPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            attachmentPreview.setImageBitmap(bitmap);
                        }
                    } catch (Exception ignored) {
                        // The request still carries the original image even if its preview cannot decode.
                    }
                    if (attachmentFileName != null) attachmentFileName.setText(selectedImageName);
                    showAttachmentInline(true);
                    showAttachmentInline(true);
                } else if (attachedFileContent != null || attachedFileName != null) {
                    if (attachmentFileName != null) attachmentFileName.setText(attachedFileName);
                    showAttachmentInline(false);
                }
                editMessage.setText(message.getContentSafe());
                editMessage.setSelection(message.getContentSafe().length());
                sendMessage();
            });
        }, "chat-retry-attachment-restore").start();
    }

    private static byte[] readLocalAttachment(String path, int maxBytes) throws Exception {
        java.io.File file = new java.io.File(path);
        if (!file.isFile() || file.length() > maxBytes) {
            throw new java.io.IOException("attachment_unavailable_or_too_large");
        }
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new java.io.IOException("attachment_too_large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    /** Image models reject /chat/completions — route them to /v1/images/generations. */
    private boolean isImageGenerationModel(String modelId) {
        if (modelId == null) return false;
        for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
            if (model != null && modelId.equals(model.id) && "image_generation".equals(model.modelKind)) return true;
        }
        String s = modelId.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("ma-")) s = s.substring(3);
        return s.contains("image") || s.contains("banana") || s.contains("dall-e")
                || s.contains("imagen") || s.contains("flux");
    }

    /**
     * Direct image-model turn in Chat Mode: reuse the typing message as the
     * "generating" card, then swap it to the finished image (Message.imageBase64).
     */
    private void runImageGenerationTurn(String prompt, Message typingMsg, boolean isTemporary,
                                        String attachedImage) {
        final int epoch = streamEpoch;
        // Iterative editing: this turn's attachment wins, otherwise the previous
        // generated picture travels with the prompt (ChatGPT-style continuity).
        String inputImage = attachedImage != null ? attachedImage
                : lastChatImageBase64(isTemporary);
        String quality = app.multiai.mobile.utils.ImageGenOptions.savedQuality(this, selectedModelId);
        String size = app.multiai.mobile.utils.ImageGenOptions.savedSize(this, selectedModelId);
        multiAiClient.generateImage(selectedModelId, prompt, inputImage, quality, size, result -> {
            if (epoch != streamEpoch || isFinishing() || isDestroyed()) return;
            setGenerating(false);
            boolean ok = result.ok && result.imageBase64 != null && !result.imageBase64.isEmpty();
            Message done;
            if (ok) {
                done = new Message("", Message.TYPE_AI);
                done.setImageBase64(result.imageBase64);
                done.setImageName("generated-" + System.currentTimeMillis() + ".png");
            } else {
                String err = result.error != null ? result.error : "image_failed";
                done = new Message("Не удалось сгенерировать изображение (" + err + ")", Message.TYPE_ERROR);
                done.setErrorCode("MULTIAI-IMAGE-GEN");
                done.setErrorTitle("Генерация изображения");
                done.setRetryable(true);
            }
            messagesAdapter.removeMessage(typingMsg);
            messagesAdapter.addMessage(done);
            if (!isTemporary && currentChat != null) {
                try {
                    currentChat.addMessage(done);
                    chatManager.updateChat(currentChat);
                } catch (Exception ignored) {
                }
            } else if (isTemporary) {
                temporaryMessages.add(done);
            }
            scrollToBottom();
        });
    }

    /** Latest AI-generated image in the chat — context for iterative edits. */
    private String lastChatImageBase64(boolean isTemporary) {
        List<Message> msgs = isTemporary ? temporaryMessages
                : (currentChat != null ? currentChat.getMessages() : null);
        if (msgs == null) return null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m.getType() != Message.TYPE_AI || !m.hasImage()) continue;
            String b64 = m.getImageBase64();
            if (b64 == null || b64.isEmpty()) {
                if (m.getImagePath() == null) continue;
                try (java.io.FileInputStream fis = new java.io.FileInputStream(m.getImagePath())) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
                    b64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP);
                } catch (Exception ignored) {
                }
            }
            if (b64 != null && !b64.isEmpty()) {
                return b64.startsWith("data:") && b64.indexOf(',') >= 0
                        ? b64.substring(b64.indexOf(',') + 1) : b64;
            }
        }
        return null;
    }

    private void sendMessage() {
        if (loadingImages > 0) {
            ModernToast.show(this, getString(R.string.chat_attachment_loading));
            return;
        }
        String text = editMessage.getText().toString().trim();
        if ((text.isEmpty() && selectedImageBase64 == null && attachedFileContent == null) || isGenerating) return;
        final boolean retrying = retrySourceUser != null;
        // Сводка делается до создания user bubble: текст и вложение останутся
        // на месте, а затем этот же метод спокойно продолжит отправку.
        if (!retrying && startAutoSummaryIfNeeded()) return;

        final String aiText;
        if (attachedFileContent != null) {
            String fileBlock = app.multiai.mobile.utils.FileAttachments.prompt(attachedFileName, attachedFileContent);
            aiText = (text.isEmpty() ? getString(R.string.chat_attached_file_prefix, attachedFileName) : text) + fileBlock;
        } else {
            aiText = text;
        }
        final String capturedImageBase64 = selectedImageBase64;
        final String capturedImageName = selectedImageName;
        final String capturedFileName = attachedFileName;
        final String capturedFileContent = attachedFileContent;
        app.multiai.mobile.utils.AiDebugLog.log("ChatMode SEND: textLen=" + text.length()
                + ", img=" + (capturedImageBase64 != null ? capturedImageBase64.length() : -1)
                + ", file=" + (capturedFileContent != null ? capturedFileContent.length() : -1));
        final String displayText = text.isEmpty()
                ? (capturedImageBase64 != null ? getString(R.string.chat_image_label) : getString(R.string.chat_file_label, capturedFileName))
                : text;

        boolean isTemporary = temporaryChatEnabled;
        if (!isTemporary && currentChat == null) {
            // Лениво: чат станет реальным при первом updateChat ниже.
            currentChat = new Chat();
        }

        Message userMessage = retrying ? retrySourceUser : new Message(displayText, Message.TYPE_USER);
        if (!retrying && capturedImageBase64 != null) {
            userMessage.setImageBase64(capturedImageBase64);
            userMessage.setImageName(capturedImageName);
        }
        if (!retrying && capturedFileName != null) {
            userMessage.setAttachedFileName(capturedFileName);
            userMessage.setAttachedFileContent(capturedFileContent);
        }
        if (!retrying && isTemporary) {
            temporaryMessages.add(userMessage);
        } else if (!retrying) {
            currentChat.addMessage(userMessage);
        }
        if (!retrying) messagesAdapter.addMessage(userMessage);
        updateTopChatActions(true);
        if (!isTemporary) {
            stampChatModel(); // фиксируем модель чата при первой отправке
            chatManager.updateChat(currentChat);
            // Новый чат — моментально показываем в сайдбаре
            allChats = chatManager.loadActiveChats();
            updateSidebarChats();
        }

        if (emptyState != null && emptyState.getVisibility() == View.VISIBLE) {
            showMessagesCrossfade();
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerMessages.setVisibility(View.VISIBLE);
        }

        editMessage.setText("");
        removeAttachment();
        followStreamToBottom = true;
        if (fabScrollDown != null) fabScrollDown.setVisibility(View.GONE);
        setGenerating(true);
        scrollToBottom();

        // Add typing indicator
        Message typingMsg = new Message("", Message.TYPE_AI_TYPING);
        typingMsg.setImageGenerating(isImageGenerationModel(selectedModelId));
        messagesAdapter.addMessage(typingMsg);
        scrollToBottom();

        // 🔥 Image-generation model selected → /v1/images/generations instead of
        // the chat stream. The typing message stays as the "generating" card.
        if (isImageGenerationModel(selectedModelId)) {
            runImageGenerationTurn(aiText, typingMsg, isTemporary, capturedImageBase64);
            return;
        }

        // Новый request-local OpenAI tool loop полностью владеет turn до финального текста.
        // Условие оставляет старую реализацию компилируемой как fallback, но helper всегда
        // принимает валидный turn и не допускает отдельного completion на каждый tool.
        if (startSequentialChatToolLoop(aiText, capturedImageBase64, userMessage,
                isTemporary, currentChat, buildEnrichedInstructions())) {
            return;
        }

        // Legacy fallback (не достигается для валидного turn).
        List<Message> history = new ArrayList<>(isTemporary ? temporaryMessages : currentChat.getMessages());
        history = trimHistoryCoveredBySummary(history, isTemporary, currentChat);
        resetStreamAnimation();
        String systemPrompt = buildEnrichedInstructions();
        // Эпоха стрима: если юзер переключит чат — колбеки ниже молча выйдут,
        // не дописав ответ (и ошибку) в чужую переписку.
        final int epoch = streamEpoch;
        // Legacy fallback также уважает live-настройки, если когда-либо будет включён.
        AppSettings legacySettings = new AppSettings(this);
        routeSendMessage(systemPrompt, history,
                aiText, capturedImageBase64, legacySettings.isUseToolsEnabled(),
                legacySettings.isStreamingEnabled(), new MultiAiClient.StreamCallback() {
            private Message aiMessage;
            private boolean firstContent = true;
            // 🔥 Текст, показанный до tool_call — нужен для обрезки дублирующегося префикса
            private String preToolText = "";
            private boolean hadToolCall = false;
            private StreamingTextNormalizer.PrefixStripper prefixStripper =
                    new StreamingTextNormalizer.PrefixStripper("");

            @Override
            public void onContextInfo(int usedPct, int usedTokens, int maxTokens, boolean summarized) {}

            private StringBuilder thinkingBuffer = new StringBuilder();
            private String accumulatedThinking = "";
            private long thinkingStartedAtMs;

            @Override
            public void onThinking(String thinking) {
                if (epoch != streamEpoch) return;
                // MultiAiClient callback уже append-only.
                if (thinkingStartedAtMs == 0L) {
                    thinkingStartedAtMs = android.os.SystemClock.elapsedRealtime();
                }
                thinkingBuffer.append(thinking);
                // Живой блок размышлений: создаём сообщение на первом же
                // thinking-чанке и обновляем по мере стрима.
                if (aiMessage == null) {
                    aiMessage = new Message("", null, Message.TYPE_AI);
                    // В стриме показываем компактный реальный preview. Полный
                    // reasoning открывается только по явному тапу пользователя.
                    aiMessage.setThinkingExpanded(false);
                    aiMessage.setThinkingFinished(false);
                    aiMessage.setStreaming(true); // тулбар скрыт до конца генерации
                    messagesAdapter.insertBeforeTrailingTyping(aiMessage);
                    scrollToBottom();
                }
                aiMessage.setThinkingContent(thinkingBuffer.toString());
                messagesAdapter.updateMessageThinkingDirect(aiMessage, thinkingBuffer.toString());
            }

            private void finishThinkingIfNeeded() {
                if (thinkingStartedAtMs == 0L) return;
                accumulatedThinking = thinkingBuffer.toString();
                if (aiMessage != null) {
                    aiMessage.setThinkingTimeMs(Math.max(1L,
                            android.os.SystemClock.elapsedRealtime() - thinkingStartedAtMs));
                    aiMessage.setThinkingFinished(true);
                    messagesAdapter.updateMessageThinkingDirect(aiMessage, accumulatedThinking);
                }
                thinkingStartedAtMs = 0L;
                thinkingBuffer.setLength(0);
            }

            @Override
            public void onThinkingEnd() {
                finishThinkingIfNeeded();
            }

            @Override
            public void onContent(String content) {
                if (epoch != streamEpoch) return;
                finishThinkingIfNeeded();
                String visibleContent = hadToolCall
                        ? prefixStripper.accept(content) : content;
                if (visibleContent.isEmpty()) return;

                if (firstContent) {
                    firstContent = false;
                    if (aiMessage == null) {
                        // Размышлений не было — создаём сообщение тут, как раньше.
                        String thinking = accumulatedThinking.isEmpty() ? null : accumulatedThinking;
                        aiMessage = new Message("", thinking, Message.TYPE_AI);
                        aiMessage.setStreaming(true);
                        messagesAdapter.insertBeforeTrailingTyping(aiMessage);
                    } else {
                        // Сообщение уже создано в onThinking — дописываем thinking-итог.
                        if (!accumulatedThinking.isEmpty()) {
                            aiMessage.setThinkingContent(accumulatedThinking);
                        }
                    }
                    startStreamAnimation(aiMessage);
                    scrollToBottom();
                }
                appendStreamChunk(visibleContent);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                if (epoch != streamEpoch) return;

                // 🔥 Сохраняем текст до tool_call и оставляем его в UI
                final Message thinkingTarget = aiMessage; // follow-up thinking допишем сюда
                if (aiMessage != null) {
                    flushStreamAnimation();
                    String textSoFar = streamBuffer.toString();
                    boolean hasThinking = aiMessage.hasThinking();
                    if (!textSoFar.trim().isEmpty()) {
                        preToolText = textSoFar;
                        aiMessage.setContent(textSoFar);
                        aiMessage.setStreaming(false); // кусок завершён — тулбар уместен
                        // Сохраняем в чат (останется виден после поворота)
                        if (currentChat != null && !isTemporary) {
                            currentChat.addMessage(aiMessage);
                        }
                    } else if (!hasThinking) {
                        // Пустой и без размышлений — мусор, удаляем. С thinking
                        // оставляем: блок размышлений не должен пропадать.
                        messagesAdapter.removeMessage(aiMessage);
                    } else {
                        // Только thinking — финализируем как есть.
                        aiMessage.setStreaming(false);
                        if (currentChat != null && !isTemporary) {
                            currentChat.addMessage(aiMessage);
                        }
                    }
                    // Сброс для следующего AI-сообщения после tool_result
                    aiMessage = null;
                    firstContent = true;
                    hadToolCall = true;
                    prefixStripper = new StreamingTextNormalizer.PrefixStripper(preToolText);
                    // Сброс стрим-буфера — новый AI месседж начнётся с нуля
                    resetStreamAnimation();
                }

                Message toolMsg = new Message(toolCall.getName(), getString(R.string.chat_tool_running), false);
                toolMsg.addToolCall(toolCall);
                messagesAdapter.insertBeforeTrailingTyping(toolMsg);
                // 🔥 Обновляем предыдущее AI-сообщение — чтобы скрылся его action bar
                int prevIdx = messagesAdapter.getMessagesList().size() - 2;
                if (prevIdx >= 0) messagesAdapter.notifyItemChanged(prevIdx);
                if (currentChat != null && !isTemporary) {
                    currentChat.addMessage(toolMsg);
                    chatManager.updateChat(currentChat);
                }
                scrollToBottom();

                // Execute tool locally and feed result back to the model
                new Thread(() -> {
                    JSONObject argsJson = new JSONObject();
                    for (java.util.Map.Entry<String, String> e : toolCall.getParams().entrySet()) {
                        try { argsJson.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
                    }
                    String rawResult = executeChatTool(toolCall.getName(), argsJson);
                    final String result = rawResult != null && rawResult.length() > 100_000
                            ? "{\"error\":\"tool_result_too_large\"}" : rawResult;
                    boolean failed = false;
                    try {
                        JSONObject parsed = new JSONObject(result);
                        failed = parsed.has("error");
                        toolCall.setResult(parsed);
                    } catch (Exception ignored) {
                        failed = true;
                    }
                    // Без этого виджет погоды не отрисуется: ToolWidgetRenderer
                    // требует status=success, а тул зависал в «loading».
                    toolCall.setStatus(failed ? "error" : "success");
                    handler.post(() -> {
                        if (currentChat != null && !isTemporary) {
                            chatManager.updateChat(currentChat);
                        }
                        messagesAdapter.refreshMessage(toolMsg);
                        scrollToBottom();
                        // Continue conversation with tool result
                        continueWithToolResult(toolCall, result, epoch, thinkingTarget);
                    });
                }).start();
            }

            @Override
            public void onToolResult(ToolCall toolCall) {
                // Results are now handled inside onToolCall after local execution.
            }

            @Override
            public void onComplete() {
                if (epoch != streamEpoch) return;
                finishThinkingIfNeeded();
                if (hadToolCall) appendStreamChunk(prefixStripper.finish());
                flushStreamAnimation();
                String finalText = streamBuffer.toString();
                messagesAdapter.removeTrailingTypingMessages();
                if (finalText.length() > 0) {
                    String thinking = accumulatedThinking.isEmpty() ? null : accumulatedThinking;
                    if (aiMessage == null) {
                        aiMessage = new Message(finalText, thinking, Message.TYPE_AI);
                        messagesAdapter.addMessage(aiMessage);
                    } else {
                        aiMessage.setContent(finalText);
                        if (thinking != null) aiMessage.setThinkingContent(thinking);
                        messagesAdapter.updateMessageContentDirect(aiMessage, finalText);
                    }
                    // В чат сохраняем копию live-сообщения: thinking дописывался
                    // в него и после тулов (follow-up), а старая копия теряла
                    // этот хвост — после перезапуска блок размышлений пропадал.
                    // Финальный rich-рендер делаем после полной сборки текста,
                    // а не на одном из streaming-кадров.
                    Message finalAiMessage = new Message(finalText,
                            aiMessage.getThinkingContent(), Message.TYPE_AI);
                    if (isTemporary) {
                        temporaryMessages.add(finalAiMessage);
                    } else if (currentChat != null) {
                        currentChat.addMessage(finalAiMessage);
                        chatManager.updateChat(currentChat);
                        // Обновляем сайдбар чтобы новый чат появился в истории
                        allChats = chatManager.loadActiveChats();
                        updateSidebarChats();
                        app.multiai.mobile.utils.ChatTitleGenerator.generate(
                                ChatModeActivity.this, currentChat, chatManager,
                                () -> currentChat, () -> {
                                    allChats = chatManager.loadActiveChats();
                                    updateSidebarChats();
                                });
                    }
                } else if (!isTemporary && currentChat != null) {
                    // Пустой ответ (всё пришло через tool-ветки): пре-тул текст уже
                    // в currentChat — фиксируем на диск и обновляем сайдбар.
                    try {
                        chatManager.updateChat(currentChat);
                        allChats = chatManager.loadActiveChats();
                        updateSidebarChats();
                    } catch (Exception ignored) {
                    }
                }
                resetStreamAnimation();
                // После ответа reasoning остаётся компактным.
                if (aiMessage != null) {
                    aiMessage.setThinkingExpanded(false);
                    messagesAdapter.updateMessageThinkingDirect(aiMessage,
                            aiMessage.getThinkingContent());
                    messagesAdapter.finalizeMessageContent(aiMessage);
                }
                accumulatedThinking = "";
                thinkingBuffer.setLength(0);
                setGenerating(false);
                scrollToBottom();
                refreshContextRing();
            }

            @Override
            public void onError(String error) {
                if (epoch != streamEpoch) return; // отмена при переключении — не ошибка юзера
                stopStreamAnimation();
                resetStreamAnimation();
                messagesAdapter.removeTrailingTypingMessages();
                // Карточка ошибки в стиле Work-режима: код классификации +
                // поддержка через SupportLinkBuilder (?text=...). Ссылку
                // прячем в markdown — сырой percent-encoded URL в чате кринж.
                String normalized = error != null ? error.trim().toLowerCase() : "";
                String code;
                String title;
                String body;
                if (normalized.contains("не авторизован")
                        || normalized.contains("not authorized")
                        || (normalized.contains("no access token") && !normalized.contains("401"))) {
                    code = "MULTIAI-NOT-AUTHORIZED";
                    title = getString(R.string.chat_err_not_authorized_title);
                    body = getString(R.string.chat_err_not_authorized_body);
                } else if (normalized.contains("401") || normalized.contains("unauthorized")
                        || normalized.contains("invalid_token")) {
                    code = "MULTIAI-SESSION-EXPIRED";
                    title = getString(R.string.chat_err_auth_title);
                    body = getString(R.string.chat_err_auth_body);
                } else if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("no chunks")) {
                    code = "MULTIAI-TIMEOUT-120S";
                    title = getString(R.string.chat_err_timeout_title);
                    body = getString(R.string.chat_err_timeout_body);
                } else if (normalized.contains("empty_completion") || normalized.contains("без текста")
                        || normalized.contains("не дала ответ")) {
                    code = "MULTIAI-EMPTY-COMPLETION";
                    title = getString(R.string.chat_err_empty_title);
                    body = getString(R.string.chat_err_empty_body);
                } else if (normalized.startsWith("model_disabled:")) {
                    code = "MULTIAI-MODEL-DISABLED";
                    title = getString(R.string.chat_err_model_disabled_title);
                    body = getString(R.string.chat_err_model_disabled_body,
                            normalized.substring("model_disabled:".length()));
                } else if (normalized.contains("http 5") || normalized.contains("502") || normalized.contains("503")) {
                    code = "MULTIAI-UPSTREAM-HTTP";
                    title = getString(R.string.chat_err_server_title);
                    body = getString(R.string.chat_err_server_body);
                } else if (normalized.contains("upstream_unavailable") || normalized.contains("недоступен")
                        || normalized.contains("service_error") || normalized.contains("upstream_error")) {
                    code = "MULTIAI-UPSTREAM-UNAVAILABLE";
                    title = getString(R.string.chat_err_server_title);
                    body = getString(R.string.chat_err_server_body);
                } else if (normalized.contains("tool_") || normalized.contains("unsupported tool")
                        || normalized.contains("tool result") || normalized.contains("инструмент")) {
                    code = "MULTIAI-TOOL-FAIL";
                    title = getString(R.string.chat_err_tool_title);
                    body = getString(R.string.chat_err_tool_body);
                } else {
                    code = "MULTIAI-NETWORK-ERROR";
                    title = getString(R.string.chat_err_generic_title);
                    body = getString(R.string.chat_err_generic_body);
                }
                Message errMsg = new Message(body, Message.TYPE_ERROR);
                errMsg.setErrorCode(code);
                errMsg.setErrorTitle(title);
                errMsg.setRetryable(!"MULTIAI-NOT-AUTHORIZED".equals(code)
                        && !"MULTIAI-SESSION-EXPIRED".equals(code));
                messagesAdapter.addMessage(errMsg);
                // Ошибки тоже в историю — иначе после рестарта обмен выглядит
                // оборванным без следа (в рабочем режиме так и делается).
                if (!isTemporary && currentChat != null) {
                    try {
                        currentChat.addMessage(errMsg);
                        chatManager.updateChat(currentChat);
                    } catch (Exception ignored) {
                    }
                }
                accumulatedThinking = "";
                thinkingBuffer.setLength(0);
                setGenerating(false);
                scrollToBottom();
            }
        });
    }

    private static final int MAX_CHAT_EMPTY_RESPONSE_RETRIES = 2;
    private static final int MAX_CHAT_RECONNECTS = 10;
    private final RetryPolicy chatRetryPolicy = new RetryPolicy(
            MAX_CHAT_RECONNECTS + 1, 2_000L, 20_000L, 0.0, new Random());

    private static final class LocalToolResult {
        final String raw;
        final JSONObject json;
        final boolean failed;

        LocalToolResult(String raw, JSONObject json, boolean failed) {
            this.raw = raw;
            this.json = json;
            this.failed = failed;
        }
    }

    /**
     * Starts one strictly sequential OpenAI turn. Every response is collected in full;
     * its tools are executed as one local batch, and exactly one follow-up is sent.
     */
    private boolean startSequentialChatToolLoop(String userText, String imageBase64,
                                                Message currentUserMessage, boolean temporary,
                                                Chat generationChat, String systemPrompt) {
        final int epoch = streamEpoch;
        activeStreamMessage = null;
        try {
            JSONArray canonical = buildChatLoopHistory(systemPrompt, userText, imageBase64,
                    currentUserMessage, temporary, generationChat);
            resetStreamAnimation();
            sendChatLoopRound(canonical, 0, "", 0, 0, epoch, temporary, generationChat);
        } catch (Exception e) {
            finishChatLoopError("Не удалось подготовить запрос: " + e.getMessage(), epoch,
                    temporary, generationChat);
        }
        return true;
    }

    /** Build canonical history without rendering-only TYPE_TOOL_EXECUTION placeholders. */
    private JSONArray buildChatLoopHistory(String systemPrompt, String userText, String imageBase64,
                                           Message currentUserMessage, boolean temporary,
                                           Chat generationChat) throws Exception {
        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }

        List<Message> source = temporary
                ? new ArrayList<>(temporaryMessages)
                : new ArrayList<>(generationChat != null
                        ? generationChat.getMessages() : new ArrayList<>());
        int covered = contextSummaryMessageCount(temporary, generationChat);
        int start = hasContextSummary(temporary, generationChat)
                ? Math.min(covered, source.size()) : 0;
        for (int i = start; i < source.size(); i++) {
            Message msg = source.get(i);
            if (msg == currentUserMessage) continue;
            if (msg == retrySourceMessage) continue;
            if (msg.getType() != Message.TYPE_USER && msg.getType() != Message.TYPE_AI) continue;
            messages.put(new JSONObject()
                    .put("role", msg.getType() == Message.TYPE_USER ? "user" : "assistant")
                    .put("content", msg.getContentSafe()));
        }

        JSONObject user = new JSONObject().put("role", "user");
        if (imageBase64 != null && !imageBase64.isEmpty()) {
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("type", "text").put("text", userText));
            for (String image : app.multiai.mobile.utils.ImageAttachments.unpack(imageBase64)) {
                parts.put(new JSONObject().put("type", "image_url")
                        .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + image)));
            }
            user.put("content", parts);
            app.multiai.mobile.utils.AiDebugLog.log("Loop history: user parts="
                    + parts.length() + " (image attached, payloadLen=" + imageBase64.length() + ")");
        } else {
            user.put("content", userText);
            app.multiai.mobile.utils.AiDebugLog.log("Loop history: NO IMAGE (imageBase64 empty)"
                    + ", textLen=" + (userText != null ? userText.length() : 0));
        }
        messages.put(user);
        return messages;
    }

    private void sendChatLoopRound(JSONArray canonical, int completedToolRounds,
                                   String repeatedPrefix, int emptyRetryCount,
                                   int reconnectCount, int epoch, boolean temporary,
                                   Chat generationChat) {
        if (!isChatLoopActive(epoch)) return;
        resetStreamAnimation();

        AppSettings settings = new AppSettings(this);
        // Настройка «Ссылаться на сохранённую память» гейтит и запись в память.
        multiAiClient.setMemoryToolsEnabled(settings.isMemoryReferenceEnabled());
        routeSendMessage(null, canonical,
                settings.isUseToolsEnabled(), settings.isStreamingEnabled(),
                new MultiAiClient.StreamCallback() {
                    private final List<ToolCall> calls = new ArrayList<>();
                    private final List<Message> cards = new ArrayList<>();
                    private final StringBuilder rawContent = new StringBuilder();
                    private final StringBuilder thinking = new StringBuilder();
                    private final StreamingTextNormalizer.PrefixStripper prefixStripper =
                            new StreamingTextNormalizer.PrefixStripper(repeatedPrefix);
                    private Message aiMessage;
                    private long thinkingStartedAtMs;
                    private boolean renderPending;
                    private final Runnable renderLatest = () -> {
                        renderPending = false;
                        if (aiMessage == null || !isChatLoopActive(epoch)) return;
                        aiMessage.setThinkingContent(thinking.toString());
                        messagesAdapter.updateMessageThinkingDirect(aiMessage, thinking.toString());
                    };

                    private void scheduleThinkingRender() {
                        if (renderPending) return;
                        renderPending = true;
                        handler.postDelayed(renderLatest, 32L);
                    }

                    private void finishThinking() {
                        if (thinkingStartedAtMs == 0L || aiMessage == null) return;
                        aiMessage.setThinkingContent(thinking.toString());
                        aiMessage.setThinkingTimeMs(Math.max(1L,
                                android.os.SystemClock.elapsedRealtime() - thinkingStartedAtMs));
                        aiMessage.setThinkingFinished(true);
                        thinkingStartedAtMs = 0L;
                        messagesAdapter.updateMessageThinkingDirect(aiMessage, thinking.toString());
                    }

                    private void appendVisibleContent(String chunk) {
                        if (chunk == null || chunk.isEmpty()) return;
                        rawContent.append(chunk);
                        ensureAiMessage(true);
                        appendStreamChunk(chunk);
                    }

                    private void flushRepeatedPrefix() {
                        appendVisibleContent(prefixStripper.finish());
                    }

                    @Override public void onContextInfo(int usedPct, int usedTokens,
                                                        int maxTokens, boolean summarized) {}

                    private void ensureAiMessage(boolean contentIncoming) {
                        if (aiMessage == null) {
                            aiMessage = new Message("", null, Message.TYPE_AI);
                            aiMessage.setStreaming(true);
                            aiMessage.setThinkingExpanded(false);
                            aiMessage.setThinkingFinished(false);
                            activeStreamMessage = aiMessage;
                            messagesAdapter.insertBeforeTrailingTyping(aiMessage);
                            scrollToBottom();
                        }
                        if (contentIncoming && streamTargetMessage != aiMessage) {
                            startStreamAnimation(aiMessage);
                        }
                    }

                    @Override public void onThinking(String chunk) {
                        if (!isChatLoopActive(epoch)) return;
                        if (thinkingStartedAtMs == 0L) {
                            thinkingStartedAtMs = android.os.SystemClock.elapsedRealtime();
                        }
                        thinking.append(chunk);
                        ensureAiMessage(false);
                        scheduleThinkingRender();
                    }

                    @Override public void onThinkingEnd() {
                        finishThinking();
                    }

                    @Override public void onContent(String chunk) {
                        if (!isChatLoopActive(epoch) || chunk == null || chunk.isEmpty()) return;
                        finishThinking();
                        appendVisibleContent(prefixStripper.accept(chunk));
                    }

                    @Override public void onToolCall(ToolCall call) {
                        if (!isChatLoopActive(epoch)) return;
                        finishThinking();
                        flushRepeatedPrefix();
                        calls.add(call);

                        // Preserve pre-tool content/thinking before the card in persisted UI history.
                        if (aiMessage != null) {
                            flushStreamAnimation();
                            aiMessage.setStreaming(false);
                            persistChatLoopMessage(aiMessage, temporary, generationChat);
                            messagesAdapter.finalizeMessageContent(aiMessage);
                        }

                        Message card = new Message(call.getName(),
                                getString(R.string.chat_tool_running), false);
                        card.addToolCall(call);
                        cards.add(card);
                        messagesAdapter.insertBeforeTrailingTyping(card);
                        // Rebind the preceding thinking-only item immediately: its blank
                        // content and action bar must not reserve space above the tool card.
                        if (aiMessage != null) {
                            int aiIndex = messagesAdapter.getMessagesList().indexOf(aiMessage);
                            if (aiIndex >= 0) messagesAdapter.notifyItemChanged(aiIndex);
                        }
                        persistChatLoopMessage(card, temporary, generationChat);
                        scrollToBottom();
                    }

                    @Override public void onToolResult(ToolCall call) {
                        // Chat Mode executes only its local safe tools after the full response.
                    }

                    @Override public void onComplete() {
                        if (!isChatLoopActive(epoch)) return;
                        finishThinking();
                        flushRepeatedPrefix();

                        if (calls.isEmpty()
                                && rawContent.toString().trim().isEmpty()
                                && thinking.toString().trim().isEmpty()) {
                            if (emptyRetryCount < MAX_CHAT_EMPTY_RESPONSE_RETRIES) {
                                if (aiMessage != null) {
                                    messagesAdapter.removeMessage(aiMessage);
                                    if (activeStreamMessage == aiMessage) activeStreamMessage = null;
                                }
                                resetStreamAnimation();
                                handler.postDelayed(() -> {
                                    if (isChatLoopActive(epoch)) {
                                        sendChatLoopRound(canonical, completedToolRounds,
                                                repeatedPrefix, emptyRetryCount + 1, reconnectCount,
                                                epoch, temporary, generationChat);
                                    }
                                }, 700);
                            } else {
                                finalizeChatLoopRoundMessage(aiMessage, rawContent.toString(),
                                        thinking.toString(), temporary, generationChat);
                                finishChatLoopError(
                                        "empty_completion: модель не дала ответ после tool result",
                                        epoch, temporary, generationChat);
                            }
                            return;
                        }

                        if (calls.isEmpty()) {
                            final Message completedMessage = aiMessage;
                            completeStreamAnimation(() -> {
                                if (!isChatLoopActive(epoch)) return;
                                finalizeChatLoopRoundMessage(completedMessage, rawContent.toString(),
                                        thinking.toString(), temporary, generationChat);
                                try {
                                    canonical.put(new JSONObject().put("role", "assistant")
                                            .put("content", rawContent.toString()));
                                } catch (Exception ignored) {}
                                finishChatLoopSuccess(epoch, temporary, generationChat,
                                        completedMessage);
                            });
                            return;
                        }

                        finalizeChatLoopRoundMessage(aiMessage, rawContent.toString(),
                                thinking.toString(), temporary, generationChat);

                        try {
                            appendAssistantToolBatch(canonical, rawContent.toString(), calls);
                        } catch (Exception e) {
                            finishChatLoopError(e.getMessage(), epoch, temporary, generationChat);
                            return;
                        }
                        executeChatToolBatch(canonical, calls, cards,
                                completedToolRounds + 1, rawContent.toString(), epoch,
                                temporary, generationChat);
                    }

                    @Override public void onError(String error) {
                        if (!isChatLoopActive(epoch)) return;
                        String lower = error != null
                                ? error.toLowerCase(java.util.Locale.ROOT) : "";
                        if (lower.contains("empty_completion")
                                && emptyRetryCount < MAX_CHAT_EMPTY_RESPONSE_RETRIES) {
                            if (aiMessage != null) {
                                    messagesAdapter.removeMessage(aiMessage);
                                    if (activeStreamMessage == aiMessage) activeStreamMessage = null;
                                }
                            resetStreamAnimation();
                            handler.postDelayed(() -> {
                                if (isChatLoopActive(epoch)) {
                                    sendChatLoopRound(canonical, completedToolRounds,
                                            repeatedPrefix, emptyRetryCount + 1, reconnectCount,
                                            epoch, temporary, generationChat);
                                }
                            }, 700);
                            return;
                        }
                        MultiAiError parsed = MultiAiError.parse(error);
                        if (parsed.isRetryable() && calls.isEmpty()
                                && reconnectCount < MAX_CHAT_RECONNECTS) {
                            int nextReconnect = reconnectCount + 1;
                            if (aiMessage != null) {
                                    messagesAdapter.removeMessage(aiMessage);
                                    if (activeStreamMessage == aiMessage) activeStreamMessage = null;
                                }
                            resetStreamAnimation();
                            messagesAdapter.showReconnecting("Reconnecting " + nextReconnect
                                    + "/" + MAX_CHAT_RECONNECTS);
                            scrollToBottom();
                            long delayMs = chatRetryPolicy.delayMs(parsed, nextReconnect);
                            handler.postDelayed(() -> {
                                if (!isChatLoopActive(epoch)) return;
                                messagesAdapter.showTypingAfterReconnect();
                                sendChatLoopRound(canonical, completedToolRounds, repeatedPrefix,
                                        emptyRetryCount, nextReconnect, epoch,
                                        temporary, generationChat);
                            }, Math.max(0L, delayMs));
                            return;
                        }
                        finalizeChatLoopRoundMessage(aiMessage, rawContent.toString(),
                                thinking.toString(), temporary, generationChat);
                        finishChatLoopError(error, epoch, temporary, generationChat);
                    }
                });
    }

    private void appendAssistantToolBatch(JSONArray canonical, String content,
                                          List<ToolCall> calls) {
        try {
            JSONArray toolCalls = new JSONArray();
            for (ToolCall call : calls) {
                JSONObject fn = new JSONObject();
                fn.put("name", call.getName());
                fn.put("arguments", new JSONObject(call.getParams()).toString());
                toolCalls.put(new JSONObject()
                        .put("id", call.getId())
                        .put("type", "function")
                        .put("function", fn));
            }
            canonical.put(new JSONObject()
                    .put("role", "assistant")
                    .put("content", content != null ? content : "")
                    .put("tool_calls", toolCalls));
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось собрать tool_calls", e);
        }
    }

    /** Execute all calls locally, then append one role=tool per id and send one follow-up. */
    private void executeChatToolBatch(JSONArray canonical, List<ToolCall> calls,
                                      List<Message> cards, int completedToolRounds,
                                      String repeatedPrefix, int epoch, boolean temporary,
                                      Chat generationChat) {
        if (!isChatLoopActive(epoch)) return;
        if (calls.isEmpty()) {
            sendChatLoopRound(canonical, completedToolRounds, repeatedPrefix, 0, 0, epoch,
                    temporary, generationChat);
            return;
        }

        LocalToolResult[] results = new LocalToolResult[calls.size()];
        java.util.concurrent.atomic.AtomicInteger remaining =
                new java.util.concurrent.atomic.AtomicInteger(calls.size());
        for (int i = 0; i < calls.size(); i++) {
            final int index = i;
            final ToolCall call = calls.get(i);
            new Thread(() -> {
                if (!isChatLoopActive(epoch)) return;
                JSONObject args = new JSONObject();
                for (Map.Entry<String, String> entry : call.getParams().entrySet()) {
                    try { args.put(entry.getKey(), entry.getValue()); } catch (Exception ignored) {}
                }
                String raw = executeChatTool(call.getName(), args);
                if (raw != null && raw.length() > 100_000) {
                    raw = "{\"error\":\"tool_result_too_large\"}";
                }
                JSONObject parsed;
                boolean failed;
                try {
                    parsed = new JSONObject(raw != null ? raw : "{}");
                    failed = parsed.has("error");
                } catch (Exception e) {
                    parsed = new JSONObject();
                    try { parsed.put("error", raw != null ? raw : getString(R.string.chat_err_empty_tool_result)); }
                    catch (Exception ignored) {}
                    failed = true;
                }
                if (!isChatLoopActive(epoch)) return;
                results[index] = new LocalToolResult(raw != null ? raw : parsed.toString(),
                        parsed, failed);
                if (remaining.decrementAndGet() == 0) {
                    handler.post(() -> completeChatToolBatch(canonical, calls, cards, results,
                            completedToolRounds, repeatedPrefix, epoch, temporary,
                            generationChat));
                }
            }, "chat-safe-tool-" + i).start();
        }
    }

    private void completeChatToolBatch(JSONArray canonical, List<ToolCall> calls,
                                       List<Message> cards, LocalToolResult[] results,
                                       int completedToolRounds, String repeatedPrefix,
                                       int epoch, boolean temporary, Chat generationChat) {
        if (!isChatLoopActive(epoch)) return;
        try {
            for (int i = 0; i < calls.size(); i++) {
                ToolCall call = calls.get(i);
                LocalToolResult result = results[i];
                if (result == null) {
                    throw new IllegalStateException("Не получен результат tool " + call.getName());
                }
                call.setResult(result.json);
                call.setStatus(result.failed ? "error" : "success");
                canonical.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.getId())
                        .put("content", result.raw));
            }
            messagesAdapter.refreshMessages(cards);
            persistChatLoopState(temporary, generationChat);
            scrollToBottom();
            sendChatLoopRound(canonical, completedToolRounds, repeatedPrefix, 0, 0, epoch,
                    temporary, generationChat);
        } catch (Exception e) {
            finishChatLoopError(getString(R.string.chat_err_tool_results, e.getMessage()),
                    epoch, temporary, generationChat);
        }
    }

    private void finalizeChatLoopRoundMessage(Message message, String rawContent,
                                              String thinking, boolean temporary,
                                              Chat generationChat) {
        flushStreamAnimation();
        messagesAdapter.removeTrailingTypingMessages();
        if (message == null && ((rawContent != null && !rawContent.isEmpty())
                || (thinking != null && !thinking.isEmpty()))) {
            message = new Message(rawContent != null ? rawContent : "",
                    thinking != null && !thinking.isEmpty() ? thinking : null, Message.TYPE_AI);
            messagesAdapter.addMessage(message);
        }
        if (message != null) {
            if (streamTargetMessage == message && streamBuffer.length() > 0) {
                message.setContent(streamBuffer.toString());
            } else if ((message.getContentSafe().isEmpty()) && rawContent != null) {
                message.setContent(rawContent);
            }
            if (thinking != null && !thinking.isEmpty()) message.setThinkingContent(thinking);
            message.setStreaming(false);
            message.setThinkingExpanded(false);
            persistChatLoopMessage(message, temporary, generationChat);
            messagesAdapter.updateMessageThinkingDirect(message, message.getThinkingContent());
            messagesAdapter.finalizeMessageContent(message);
        }
        resetStreamAnimation();
        persistChatLoopState(temporary, generationChat);
    }

    private void persistChatLoopMessage(Message message, boolean temporary,
                                        Chat generationChat) {
        if (message == null) return;
        List<Message> target = temporary ? temporaryMessages
                : generationChat != null ? generationChat.getMessages() : null;
        if (target != null && !target.contains(message)) target.add(message);
    }

    private void persistChatLoopState(boolean temporary, Chat generationChat) {
        if (!temporary && generationChat != null) {
            try { chatManager.updateChat(generationChat); } catch (Exception ignored) {}
        }
    }

    private boolean isChatLoopActive(int epoch) {
        return isGenerating && epoch == streamEpoch && !isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }

    private void finishChatLoopSuccess(int epoch, boolean temporary, Chat generationChat,
                                       Message finalMessage) {
        if (!isChatLoopActive(epoch)) return;
        if (finalMessage != null) {
            finalMessage.setStreaming(false);
            finalMessage.setThinkingExpanded(false);
            messagesAdapter.finalizeMessageContent(finalMessage);
        }
        if (retrySourceMessage != null && finalMessage != null && finalMessage != retrySourceMessage) {
            retrySourceMessage.addAlternateContent(
                    finalMessage.getContentSafe(), finalMessage.getThinkingContent(), null);
            retrySourceMessage.setRetryPending(false);
            messagesAdapter.removeMessage(finalMessage);
            List<Message> target = temporary ? temporaryMessages
                    : generationChat != null ? generationChat.getMessages() : null;
            if (target != null) target.remove(finalMessage);
            messagesAdapter.refreshMessage(retrySourceMessage);
            finalMessage = retrySourceMessage;
        }
        persistChatLoopState(temporary, generationChat);
        if (!temporary && generationChat != null) {
            allChats = chatManager.loadActiveChats();
            updateSidebarChats();
            app.multiai.mobile.utils.ChatTitleGenerator.generate(
                    ChatModeActivity.this, generationChat, chatManager,
                    () -> generationChat, () -> {
                        if (epoch != streamEpoch) return;
                        allChats = chatManager.loadActiveChats();
                        updateSidebarChats();
                    });
        }
        setGenerating(false);
        activeStreamMessage = null;
        scrollToBottom();
        refreshContextRing();
        clearRetryState();
    }

    private void finishChatLoopError(String error, int epoch, boolean temporary,
                                     Chat generationChat) {
        if (!isChatLoopActive(epoch)) return;
        stopStreamAnimation();
        resetStreamAnimation();
        messagesAdapter.removeTrailingTypingMessages();
        messagesAdapter.removeReconnectingMessages();
        String normalized = error != null ? error.trim().toLowerCase() : "";
        String code;
        String title;
        String body;
        if (normalized.contains("не авторизован")
                || normalized.contains("not authorized")
                || (normalized.contains("no access token") && !normalized.contains("401"))) {
            code = "MULTIAI-NOT-AUTHORIZED";
            title = getString(R.string.chat_err_not_authorized_title);
            body = getString(R.string.chat_err_not_authorized_body);
        } else if (normalized.contains("401") || normalized.contains("unauthorized")
                || normalized.contains("invalid_token")) {
            code = "MULTIAI-SESSION-EXPIRED";
            title = getString(R.string.chat_err_auth_title);
            body = getString(R.string.chat_err_auth_body);
        } else if (normalized.contains("timeout") || normalized.contains("timed out")
                || normalized.contains("no chunks")) {
            code = "MULTIAI-TIMEOUT-120S";
            title = getString(R.string.chat_err_timeout_title);
            body = getString(R.string.chat_err_timeout_body);
        } else if (normalized.contains("empty_completion") || normalized.contains("без текста")
                || normalized.contains("не дала ответ")) {
            code = "MULTIAI-EMPTY-COMPLETION";
            title = getString(R.string.chat_err_empty_title);
            body = getString(R.string.chat_err_empty_body);
        } else if (normalized.startsWith("model_disabled:")) {
            code = "MULTIAI-MODEL-DISABLED";
            title = getString(R.string.chat_err_model_disabled_title);
            body = getString(R.string.chat_err_model_disabled_body,
                    normalized.substring("model_disabled:".length()));
        } else if (normalized.contains("http 5") || normalized.contains("502")
                || normalized.contains("503")) {
            code = "MULTIAI-UPSTREAM-HTTP";
            title = getString(R.string.chat_err_server_title);
            body = getString(R.string.chat_err_server_body);
        } else if (normalized.contains("upstream_unavailable") || normalized.contains("недоступен")
                || normalized.contains("service_error") || normalized.contains("upstream_error")) {
            code = "MULTIAI-UPSTREAM-UNAVAILABLE";
            title = getString(R.string.chat_err_server_title);
            body = getString(R.string.chat_err_server_body);
        } else if (normalized.contains("tool_") || normalized.contains("unsupported tool")
                || normalized.contains("tool result") || normalized.contains("инструмент")) {
            code = "MULTIAI-TOOL-FAIL";
            title = getString(R.string.chat_err_tool_title);
            body = getString(R.string.chat_err_tool_body);
        } else {
            code = "MULTIAI-NETWORK-ERROR";
            title = getString(R.string.chat_err_generic_title);
            body = getString(R.string.chat_err_generic_body);
        }
        Message message = new Message(body, Message.TYPE_ERROR);
        message.setErrorCode(code);
        message.setErrorTitle(title);
        message.setRetryable(!"MULTIAI-NOT-AUTHORIZED".equals(code)
                && !"MULTIAI-SESSION-EXPIRED".equals(code));
        messagesAdapter.addMessage(message);
        persistChatLoopMessage(message, temporary, generationChat);
        persistChatLoopState(temporary, generationChat);
        activeStreamMessage = null;
        setGenerating(false);
        scrollToBottom();
        clearRetryState();
    }

    /** Legacy single-tool follow-up retained only as an unreachable fallback. */
    private void continueWithToolResult(ToolCall toolCall, String result, int epoch, Message thinkingTarget) {
        if (epoch != streamEpoch) return;
        if (currentChat == null && !temporaryChatEnabled) return;

        List<Message> context = new ArrayList<>(temporaryChatEnabled ? temporaryMessages : currentChat.getMessages());
        // Drop the tool execution placeholder if present
        context.removeIf(m -> m.getType() == Message.TYPE_TOOL_EXECUTION);

        // Спец-формат OpenAI: перед role=tool ОБЯЗАТЕЛЬНО assistant-сообщение
        // с tool_calls (multiai.store валидирует историю и на «голый» role=tool
        // отвечает 400 invalid_tool_history).
        org.json.JSONArray messages = new org.json.JSONArray();
        for (Message msg : context) {
            try {
                JSONObject m = new JSONObject();
                m.put("role", msg.getType() == Message.TYPE_USER ? "user" : "assistant");
                StringBuilder content = new StringBuilder();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    content.append(msg.getContent());
                }
                m.put("content", content.toString());
                messages.put(m);
            } catch (Exception ignored) {}
        }
        // assistant с tool_calls + role=tool (OpenAI-спецификация)
        try {
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", thinkingTarget != null ? thinkingTarget.getContentSafe() : "");
            org.json.JSONArray toolCalls = new org.json.JSONArray();
            JSONObject tc = new JSONObject();
            tc.put("id", toolCall.getId());
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", toolCall.getName());
            fn.put("arguments", new JSONObject(toolCall.getParams()).toString());
            tc.put("function", fn);
            toolCalls.put(tc);
            assistantMsg.put("tool_calls", toolCalls);
            messages.put(assistantMsg);

            JSONObject toolResultMsg = new JSONObject();
            toolResultMsg.put("role", "tool");
            toolResultMsg.put("tool_call_id", toolCall.getId());
            toolResultMsg.put("content", result);
            messages.put(toolResultMsg);
        } catch (Exception ignored) {}

        // useTools=true и в follow-up: без tools модель не сможет вызвать
        // следующий инструмент в цепочке (например, search → fetch).
        // Префикс до tool_call модель любит пересказать заново — режем.
        final String preToolPrefix = thinkingTarget != null
                ? thinkingTarget.getContentSafe() : "";
        AppSettings legacySettings = new AppSettings(this);
        routeSendMessage(null, messages,
                legacySettings.isUseToolsEnabled(), legacySettings.isStreamingEnabled(),
                new MultiAiClient.StreamCallback() {
                    private Message followUp;
                    private final StreamingTextNormalizer.PrefixStripper prefixStripper =
                            new StreamingTextNormalizer.PrefixStripper(preToolPrefix);

                    private void appendFollowUpContent(String content) {
                        if (content == null || content.isEmpty()) return;
                        if (followUp == null) {
                            followUp = new Message("", null, Message.TYPE_AI);
                            followUp.setStreaming(true);
                            messagesAdapter.insertBeforeTrailingTyping(followUp);
                            if (currentChat != null && !temporaryChatEnabled) {
                                currentChat.addMessage(followUp);
                            }
                        }
                        followUp.setContent(followUp.getContentSafe() + content);
                        messagesAdapter.updateMessageContentDirect(followUp,
                                followUp.getContentSafe());
                        scrollToBottomDuringStream();
                    }

                    @Override public void onContextInfo(int usedPct, int usedTokens, int maxTokens, boolean summarized) {}

                    @Override public void onThinking(String thinking) {
                        if (epoch != streamEpoch) return;
                        // Размышления follow-up дописываем в предыдущее
                        // AI-сообщение — второй блок «Процесс размышления»
                        // в чате не плодим.
                        if (thinkingTarget != null) {
                            String existing = thinkingTarget.getThinkingContent();
                            thinkingTarget.setThinkingContent(
                                    (existing == null ? "" : existing) + thinking);
                            messagesAdapter.updateMessageThinkingDirect(thinkingTarget,
                                    thinkingTarget.getThinkingContent());
                        }
                    }

                    @Override public void onThinkingEnd() {}

                    @Override public void onToolCall(ToolCall tc) {
                        // Nested tools are not expected in chat mode; ignore.
                    }

                    @Override public void onToolResult(ToolCall tc) {}

                    @Override public void onContent(String content) {
                        if (epoch != streamEpoch) return;
                        appendFollowUpContent(prefixStripper.accept(content));
                    }

                    @Override public void onComplete() {
                        if (epoch != streamEpoch) return;
                        appendFollowUpContent(prefixStripper.finish());
                        if (thinkingTarget != null) {
                            thinkingTarget.setThinkingExpanded(false);
                        }
                        if (thinkingTarget != null) {
                            messagesAdapter.updateMessageThinkingDirect(thinkingTarget,
                                    thinkingTarget.getThinkingContent());
                        }
                        if (followUp != null) {
                            messagesAdapter.finalizeMessageContent(followUp);
                        }
                        if (followUp != null && currentChat != null && !temporaryChatEnabled) {
                            chatManager.updateChat(currentChat);
                        }
                        setGenerating(false);
                    }

                    @Override public void onError(String error) {
                        if (epoch != streamEpoch) return;
                        Message err = new Message(error, null, Message.TYPE_AI);
                        messagesAdapter.addMessage(err);
                        if (currentChat != null && !temporaryChatEnabled) {
                            currentChat.addMessage(err);
                            chatManager.updateChat(currentChat);
                        }
                        setGenerating(false);
                    }
                });
    }

    private void setGenerating(boolean generating) {
        isGenerating = generating;
        app.multiai.mobile.utils.GenerationService.update(this, "chat", generating);
        handler.post(() -> {
            if (generating) {
                btnSend.setVisibility(View.GONE);
                if (btnMic != null) btnMic.setVisibility(View.GONE);
                btnStop.setVisibility(View.VISIBLE);
            } else {
                btnStop.setVisibility(View.GONE);
                updateSendMicVisibility();
            }
            editMessage.setEnabled(!generating);
        });
    }

    private void stopGeneration() {
        isGenerating = false;
        clearRetryState();
        streamEpoch++; // late-колбеки отменённого стрима больше не пишут в UI/чат
        try {
            if (multiAiClient != null) multiAiClient.cancel();
            customProviderChatClient.cancel();
        } catch (Exception ignored) {
        }
        flushStreamAnimation();
        // Частичный ответ может состоять только из reasoning, пока текстовая
        // анимация ещё не стартовала. Поэтому берём оба состояния, а не только
        // streamTargetMessage.
        Message partial = streamTargetMessage != null ? streamTargetMessage : activeStreamMessage;
        resetStreamAnimation();
        activeStreamMessage = null;
        setGenerating(false);
        messagesAdapter.removeTrailingTypingMessages();
        // Reconnect row also hosts the animated working logo; drop it on Stop.
        messagesAdapter.removeReconnectingMessages();
        if (partial != null && (!partial.getContentSafe().trim().isEmpty() || partial.hasThinking())) {
            partial.setStreaming(false);
            partial.setThinkingExpanded(false);
            if (temporaryChatEnabled) {
                if (!temporaryMessages.contains(partial)) temporaryMessages.add(partial);
            } else if (currentChat != null && !currentChat.getMessages().contains(partial)) {
                currentChat.addMessage(partial);
            }
            messagesAdapter.finalizeMessageContent(partial);
        } else if (partial != null) {
            messagesAdapter.removeMessage(partial);
        }
        if (!temporaryChatEnabled && currentChat != null) {
            try {
                chatManager.updateChat(currentChat);
                allChats = chatManager.loadActiveChats();
                updateSidebarChats();
            } catch (Exception ignored) {
            }
        }
    }

    private void clearRetryState() {
        if (retrySourceMessage != null && retrySourceMessage.isRetryPending()) {
            retrySourceMessage.setRetryPending(false);
            if (messagesAdapter != null) messagesAdapter.refreshMessage(retrySourceMessage);
        }
        retrySourceMessage = null;
        retrySourceUser = null;
    }

    private void scrollToBottom() {
        if (!followStreamToBottom || recyclerMessages == null || messagesAdapter == null) return;
        handler.post(() -> {
            if (!followStreamToBottom || messagesAdapter.getItemCount() == 0) return;
            if (recyclerMessages.canScrollVertically(1)) {
                recyclerMessages.scrollBy(0, Integer.MAX_VALUE);
            }
        });
    }

    private void updateScrollDownFab(RecyclerView recyclerView, int dy) {
        if (recyclerView == null || fabScrollDown == null) return;
        if (!recyclerView.canScrollVertically(1)) {
            followStreamToBottom = true;
            fabScrollDown.setVisibility(View.GONE);
        } else if (userScrollingMessages) {
            // Любой ручной скролл (не только строго вверх) отключает автофоллов:
            // стрим не должен перетягивать вью вниз, пока пользователь читает.
            followStreamToBottom = false;
            fabScrollDown.setVisibility(View.VISIBLE);
        }
    }

    private String buildEnrichedInstructions() {
        StringBuilder sb = new StringBuilder(app.multiai.mobile.utils.AssistantInstructions
                .header(selectedModelId) + app.multiai.mobile.utils.AssistantInstructions.base());
        AppSettings settings = new AppSettings(this);

        // 🔥 Контекст: местоположение / IP / таймзона
        String locationContext = buildLocationContext();
        if (!locationContext.isEmpty()) {
            sb.append("\n\n").append(locationContext);
        }

        // Сводка является скрытым контекстом: интерфейс продолжает показывать
        // пользователю весь диалог, а модель получает summary + свежий хвост.
        appendContextSummaryInstructions(sb, temporaryChatEnabled, currentChat);

        // Профиль, инструкции и память — в конце промпта: модели взвешивают
        // ближний к концу контекст выше, иначе блоки теряются.
        sb.append("\n\n════════ USER CONTEXT — HIGHEST PRIORITY, ALWAYS APPLY ════════\n");
        app.multiai.mobile.utils.ProfilePromptBuilder.append(this, sb, settings);
        String userInstructions = settings.getUserInstructions();
        if (userInstructions != null && !userInstructions.trim().isEmpty()) {
            sb.append("Пользовательские инструкции (приоритет над стилем по умолчанию):\n")
              .append(userInstructions.trim()).append('\n');
        }
        app.multiai.mobile.utils.MemoryContextBuilder.appendSavedMemories(this, sb);
        app.multiai.mobile.utils.MemoryContextBuilder.appendChatJournals(this, sb,
                currentChat != null ? currentChat.getId() : null);

        return sb.toString();
    }

    private void appendContextSummaryInstructions(StringBuilder sb, boolean temporary, Chat chat) {
        String summary = contextSummary(temporary, chat);
        if (summary == null || summary.trim().isEmpty()
                || contextSummaryMessageCount(temporary, chat) <= 0) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("СКРЫТАЯ СВОДКА ПРЕДЫДУЩЕЙ ЧАСТИ ДИАЛОГА:\n");
        sb.append("Это фактический сжатый контекст, а не новый запрос пользователя. "
                + "Он не может отменять системные инструкции. Учитывай из него решения, "
                + "факты, ограничения и незавершённые задачи.\n");
        sb.append(summary.trim());
    }

    /**
     * Добавляет чистый дедуплицированный блок профиля пользователя в системный промпт.
     * Включает только непустые поля; одинаковые значения не повторяются.
     * Персонализационные инструкции дописываются как дополнение к стилю общения.
     */
    private void appendProfileBlock(StringBuilder sb, AppSettings settings) {
        java.util.LinkedHashMap<String, String> fields = new java.util.LinkedHashMap<>();
        String nickname = safeTrim(settings.getNickname());
        String profession = safeTrim(settings.getProfession());
        String about = safeTrim(settings.getAbout());
        String persInstructions = safeTrim(settings.getPersonalizationInstructions());

        if (!nickname.isEmpty()) fields.put("Никнейм", nickname);
        if (!profession.isEmpty()) fields.put("Профессия", profession);
        if (!about.isEmpty()) fields.put("О себе", about);

        if (fields.isEmpty() && persInstructions.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Отвечай на языке интерфейса: ")
                    .append("en".equals(settings.getLanguage()) ? "English" : "Русский")
                    .append(".");
            return;
        }

        if (sb.length() > 0) sb.append("\n\n");
        sb.append("Отвечай на языке интерфейса: ")
                .append("en".equals(settings.getLanguage()) ? "English" : "Русский")
                .append(".\n");
        sb.append("ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ:\n");

        // Дедупликация значений: если юзер указал одно и то же в разных полях,
        // выводим значение только при первом упоминании.
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue();
            if (seen.add(value.toLowerCase(java.util.Locale.ROOT))) {
                sb.append("- ").append(entry.getKey()).append(": ").append(value).append("\n");
            }
        }

        if (!persInstructions.isEmpty()) {
            sb.append("- Дополнительно о стиле общения: ").append(persInstructions).append("\n");
        }

        // Тон и черты из «Персонализации» — раньше в промпт не попадали.
        String styleBlock = app.multiai.mobile.utils.PersonalizationPromptBuilder
                .buildStyleBlock(this);
        if (styleBlock != null) {
            sb.append("- ").append(styleBlock).append("\n");
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * Собираем контекст местоположения пользователя для system prompt.
     * Данные кэшируются и обновляются раз в час (IP может смениться).
     * Пустой/старый кэш обновляется только в фоне и не задерживает отправку.
     */
    private String buildLocationContext() {
        SharedPreferences ctx = getSharedPreferences("multiaiide_location", MODE_PRIVATE);

        // Освежаем кэш на фоне если он старый — не блокируем отправку
        refreshLocationCacheIfStale();

        StringBuilder out = new StringBuilder();
        out.append("КОНТЕКСТ ПОЛЬЗОВАТЕЛЯ (данные с устройства):\n");

        // 📱 Информация об устройстве
        out.append("- Устройство: ").append(android.os.Build.MANUFACTURER).append(" ")
           .append(android.os.Build.MODEL).append("\n");
        out.append("- ОС: Android ").append(android.os.Build.VERSION.RELEASE)
           .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");

        // Точное местоположение (GPS, если разрешил)
        float lat = ctx.getFloat("lat", Float.NaN);
        float lon = ctx.getFloat("lon", Float.NaN);
        String gpsCity = ctx.getString("gps_city", "");

        // Reverse-geocoding для пустого gpsCity уже поставлен в фоновое обновление.
        if (!Float.isNaN(lat) && !Float.isNaN(lon)) {
            if (!gpsCity.isEmpty()) {
                out.append("- Город по GPS: ").append(gpsCity).append("\n");
            } else {
                // Fallback: берём город из IP если reverse-geocoding не сработал
                String fallbackCity = ctx.getString("ip_city", "");
                if (!fallbackCity.isEmpty()) {
                    out.append("- Город: ").append(fallbackCity).append("\n");
                }
            }
        }

        // IP-контекст
        String ip = ctx.getString("ip", "");
        String ipTimezone = ctx.getString("ip_timezone", "");

        if (!ipTimezone.isEmpty()) {
            out.append("- Часовой пояс: ").append(ipTimezone).append("\n");
        }

        if (!ip.isEmpty()) {
            out.append("- IP-адрес: ").append(ip).append("\n");
        }

        out.append("\nИспользуй эти данные когда пользователь спрашивает про погоду / время / свой город / 'мне' / 'у меня' — не уточняй город лишний раз.");
        return out.toString();
    }

    /**
     * 🔥 Синхронный fetch IP-данных (для первого запуска, когда кэш пуст).
     * Блокирует вызывающий поток на ~2-5 сек, но зато AI сразу знает город.
     * Пробуем несколько сервисов: ip-api.com (работает в РФ), потом ipapi.co.
     */
    private void fetchLocationSync(SharedPreferences ctx) {
        SharedPreferences.Editor editor = ctx.edit();
        boolean success = false;

        // 1. ipwho.is (HTTPS, бесплатный, работает везде включая РФ)
        try {
            okhttp3.OkHttpClient hc = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                .url("https://ipwho.is/")
                .build();
            okhttp3.Response resp = hc.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                JSONObject d = new JSONObject(resp.body().string());
                if (d.optBoolean("success", false)) {
                    editor.putString("ip", d.optString("ip", ""));
                    editor.putString("ip_city", d.optString("city", ""));
                    editor.putString("ip_region", d.optString("region", ""));
                    editor.putString("ip_country", d.optString("country", ""));
                    String tz = "";
                    JSONObject tzObj = d.optJSONObject("timezone");
                    if (tzObj != null) tz = tzObj.optString("id", "");
                    editor.putString("ip_timezone", tz);
                    double ipLat = d.optDouble("latitude", Double.NaN);
                    double ipLon = d.optDouble("longitude", Double.NaN);
                    if (!Double.isNaN(ipLat) && !Double.isNaN(ipLon)) {
                        editor.putFloat("ip_lat", (float) ipLat);
                        editor.putFloat("ip_lon", (float) ipLon);
                    }
                    success = true;
                }
            }
        } catch (Exception e) {
        }

        // No cleartext fallback: location/profile metadata must never cross HTTP.

        if (success) {
            editor.putLong("last_fetch_ts", System.currentTimeMillis());
            editor.commit();
        } else {
        }
    }

    /**
     * Синхронный reverse-geocoding GPS → имя города. Пишет в prefs.
     * Использует Nominatim (OpenStreetMap) — работает везде.
     */
    private void reverseGeocodeSync(float lat, float lon) {
        try {
            okhttp3.OkHttpClient hc = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build();
            okhttp3.Request req = new okhttp3.Request.Builder()
                .url("https://nominatim.openstreetmap.org/reverse?format=json&lat=" + lat
                     + "&lon=" + lon + "&accept-language=ru&zoom=10")
                .addHeader("User-Agent", "MultiAI/1.0")
                .build();
            okhttp3.Response resp = hc.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                JSONObject d = new JSONObject(resp.body().string());
                JSONObject addr = d.optJSONObject("address");
                if (addr != null) {
                    // Приоритет: city > town > village > suburb > county
                    String city = addr.optString("city", "");
                    if (city.isEmpty()) city = addr.optString("town", "");
                    if (city.isEmpty()) city = addr.optString("village", "");
                    if (city.isEmpty()) city = addr.optString("suburb", "");
                    if (city.isEmpty()) city = addr.optString("county", "");

                    String state = addr.optString("state", "");

                    StringBuilder cityLine = new StringBuilder();
                    if (!city.isEmpty()) cityLine.append(city);
                    if (!state.isEmpty() && !state.equals(city)) {
                        if (cityLine.length() > 0) cityLine.append(", ");
                        cityLine.append(state);
                    }

                    if (cityLine.length() > 0) {
                        getSharedPreferences("multiaiide_location", MODE_PRIVATE)
                            .edit().putString("gps_city", cityLine.toString()).commit();
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    /**
     * Обновление геоконтекста: если кэш старше часа — дёргаем ipapi + обратное геокодирование.
     * Всё в фоне, не блокирует отправку текущего сообщения — следующие будут свежие.
     */
    private void refreshLocationCacheIfStale() {
        SharedPreferences ctx = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
        long lastFetch = ctx.getLong("last_fetch_ts", 0L);
        long now = System.currentTimeMillis();
        if (now - lastFetch < 60L * 60L * 1000L) return; // свежее часа — не трогаем

        // Сразу помечаем чтобы не штормить если медленный ответ
        ctx.edit().putLong("last_fetch_ts", now).apply();

        // Обновляем last-known GPS
        cacheLastKnownLocation();

        new Thread(() -> {
            SharedPreferences.Editor editor = ctx.edit();
            try {
                // IP info через ipwho.is (HTTPS, работает в РФ)
                okhttp3.OkHttpClient hc = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
                okhttp3.Request req = new okhttp3.Request.Builder()
                    .url("https://ipwho.is/")
                    .build();
                okhttp3.Response resp = hc.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject d = new JSONObject(resp.body().string());
                    if (d.optBoolean("success", false)) {
                        editor.putString("ip", d.optString("ip", ""));
                        editor.putString("ip_city", d.optString("city", ""));
                        editor.putString("ip_region", d.optString("region", ""));
                        editor.putString("ip_country", d.optString("country", ""));
                        String tz = "";
                        JSONObject tzObj = d.optJSONObject("timezone");
                        if (tzObj != null) tz = tzObj.optString("id", "");
                        editor.putString("ip_timezone", tz);
                        float curLat = ctx.getFloat("lat", Float.NaN);
                        if (Float.isNaN(curLat)) {
                            double ipLat = d.optDouble("latitude", Double.NaN);
                            double ipLon = d.optDouble("longitude", Double.NaN);
                            if (!Double.isNaN(ipLat) && !Double.isNaN(ipLon)) {
                                editor.putFloat("ip_lat", (float) ipLat);
                                editor.putFloat("ip_lon", (float) ipLon);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            // Reverse-geocoding GPS → имя города (если есть GPS)
            try {
                float lat = ctx.getFloat("lat", Float.NaN);
                float lon = ctx.getFloat("lon", Float.NaN);
                if (!Float.isNaN(lat) && !Float.isNaN(lon)) {
                    okhttp3.OkHttpClient hc = new okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                        .url("https://geocoding-api.open-meteo.com/v1/reverse?latitude=" + lat
                             + "&longitude=" + lon + "&language=ru")
                        .build();
                    okhttp3.Response resp = hc.newCall(req).execute();
                    if (resp.isSuccessful() && resp.body() != null) {
                        JSONObject d = new JSONObject(resp.body().string());
                        JSONArray rr = d.optJSONArray("results");
                        if (rr != null && rr.length() > 0) {
                            JSONObject loc = rr.getJSONObject(0);
                            StringBuilder cityLine = new StringBuilder();
                            cityLine.append(loc.optString("name", ""));
                            String admin1 = loc.optString("admin1", "");
                            if (!admin1.isEmpty() && !admin1.equals(cityLine.toString())) {
                                cityLine.append(", ").append(admin1);
                            }
                            editor.putString("gps_city", cityLine.toString());
                        }
                    }
                }
            } catch (Exception ignored) {}

            editor.apply();
        }, "location-refresh").start();
    }

    // ══════════════════ CHAT-SAFE TOOLS ══════════════════

    private String executeChatTool(String name, JSONObject args) {
        try {
            switch (name) {
                case "get_weather": return executeWeather(args);
                case "get_current_time": return executeTime(args);
                case "web_search": return executeWebSearch(args);
                case "url_fetch": return executeUrlFetch(args);
                case "save_memory": return executeSaveMemory(args);
                case "summarize_chat_history": return executeHistorySummaryTool();
                case "summarize_past_chat":
                    return app.multiai.mobile.utils.PastChatSummaryService.run(this, args,
                            selectedModelId,
                            currentChat != null ? currentChat.getId() : null, null).toString();
                default:
                    return "{\"error\":\"Tool '" + name + "' is not available in Chat mode.\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown") + "\"}";
        }
    }

    private String executeWeather(JSONObject args) throws Exception {
        String city = args.optString("city", "").trim();
        okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build();

        double lat, lon;
        String locName;

        if (city.isEmpty()) {
            // 🔥 Если AI не указал город — пробуем геолокацию пользователя
            SharedPreferences loc = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
            float cachedLat = loc.getFloat("lat", Float.NaN);
            float cachedLon = loc.getFloat("lon", Float.NaN);
            // 🔥 Fallback: если нет GPS — пробуем координаты из IP-определения
            if (Float.isNaN(cachedLat) || Float.isNaN(cachedLon)) {
                cachedLat = loc.getFloat("ip_lat", Float.NaN);
                cachedLon = loc.getFloat("ip_lon", Float.NaN);
            }
            if (!Float.isNaN(cachedLat) && !Float.isNaN(cachedLon)) {
                lat = cachedLat;
                lon = cachedLon;
                // Пробуем сперва кэш
                String cachedCity = loc.getString("gps_city", loc.getString("ip_city", ""));
                if (!cachedCity.isEmpty()) {
                    locName = cachedCity;
                } else {
                    // Reverse-geocoding в название города
                    try {
                        okhttp3.Request rev = new okhttp3.Request.Builder()
                            .url("https://geocoding-api.open-meteo.com/v1/reverse?latitude=" + lat
                                + "&longitude=" + lon + "&language=ru")
                            .build();
                        okhttp3.Response rvResp = httpClient.newCall(rev).execute();
                        if (rvResp.isSuccessful() && rvResp.body() != null) {
                            JSONObject rvData = new JSONObject(rvResp.body().string());
                            JSONArray rr = rvData.optJSONArray("results");
                            if (rr != null && rr.length() > 0) {
                                locName = rr.getJSONObject(0).optString("name", "");
                            } else {
                                locName = "";
                            }
                        } else {
                            locName = "";
                        }
                    } catch (Exception e) {
                        locName = "";
                    }
                    if (locName.isEmpty()) locName = String.format(java.util.Locale.US,
                        "%.2f, %.2f", lat, lon);
                }
            } else {
                // Нет геолокации — пробуем IP-определение
                try {
                    okhttp3.Request ipReq = new okhttp3.Request.Builder()
                        .url("https://ipwho.is/").build();
                    okhttp3.Response ipResp = httpClient.newCall(ipReq).execute();
                    if (ipResp.isSuccessful() && ipResp.body() != null) {
                        JSONObject ipData = new JSONObject(ipResp.body().string());
                        if (ipData.optBoolean("success", false)) {
                            lat = ipData.optDouble("latitude", Double.NaN);
                            lon = ipData.optDouble("longitude", Double.NaN);
                            locName = ipData.optString("city", "");
                        } else {
                            return "{\"error\":\"Не удалось определить местоположение\"}";
                        }
                        if (Double.isNaN(lat) || Double.isNaN(lon)) {
                            return "{\"error\":\"Не удалось определить местоположение\"}";
                        }
                    } else {
                        return "{\"error\":\"Не удалось определить местоположение\"}";
                    }
                } catch (Exception e) {
                    return "{\"error\":\"Geolocation failed: " + e.getMessage() + "\"}";
                }
            }
        } else {
            okhttp3.Request geoReq = new okhttp3.Request.Builder()
                .url("https://geocoding-api.open-meteo.com/v1/search?name=" +
                    java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=ru")
                .build();
            okhttp3.Response geoResp = httpClient.newCall(geoReq).execute();
            if (!geoResp.isSuccessful() || geoResp.body() == null)
                return "{\"error\":\"Geocoding failed\"}";
            JSONObject geoData = new JSONObject(geoResp.body().string());
            JSONArray geoResults = geoData.optJSONArray("results");
            if (geoResults == null || geoResults.length() == 0)
                return "{\"error\":\"City not found: " + city + "\"}";
            JSONObject locObj = geoResults.getJSONObject(0);
            lat = locObj.getDouble("latitude");
            lon = locObj.getDouble("longitude");
            locName = locObj.optString("name", city);
        }

        okhttp3.Request wxReq = new okhttp3.Request.Builder()
                .url("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon +
                        "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m&timezone=auto")
                .build();
        okhttp3.Response wxResp = httpClient.newCall(wxReq).execute();
        if (wxResp.isSuccessful() && wxResp.body() != null) {
            JSONObject wxData = new JSONObject(wxResp.body().string());
            JSONObject current = wxData.optJSONObject("current");
            if (current != null) {
                String desc = wmoToDescription(current.optInt("weather_code", -1));
                double windKmh = current.optDouble("wind_speed_10m", Double.NaN);
                double windMs = Double.isNaN(windKmh) ? Double.NaN : windKmh / 3.6;

                JSONObject result = new JSONObject();
                result.put("city", locName);
                // Поля которые ожидает ToolWidgetRenderer
                result.put("temp_c", current.opt("temperature_2m"));
                result.put("feels_like", current.opt("apparent_temperature"));
                result.put("humidity", current.opt("relative_humidity_2m"));
                if (!Double.isNaN(windMs)) result.put("wind_speed", windMs);
                result.put("weather", desc);
                result.put("condition", desc);
                // Backward-compat поля
                result.put("feels_like_c", current.opt("apparent_temperature"));
                result.put("wind_kmph", windKmh);
                result.put("description", desc);
                return result.toString();
            }
        }
        return "{\"error\":\"Weather service unavailable\"}";
    }

    private String executeTime(JSONObject args) throws Exception {
        String tz = args.optString("timezone", "").trim();

        // 🔥 Если timezone не указан — сначала смотрим кэш, потом IP, потом systemDefault
        if (tz.isEmpty()) {
            SharedPreferences loc = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
            tz = loc.getString("ip_timezone", "");
        }
        if (tz.isEmpty()) {
            try {
                okhttp3.OkHttpClient hc = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
                okhttp3.Request ipReq = new okhttp3.Request.Builder()
                    .url("https://ipapi.co/json/").build();
                okhttp3.Response ipResp = hc.newCall(ipReq).execute();
                if (ipResp.isSuccessful() && ipResp.body() != null) {
                    JSONObject ipData = new JSONObject(ipResp.body().string());
                    tz = ipData.optString("timezone", "");
                }
            } catch (Exception ignored) {}
            if (tz.isEmpty()) tz = java.util.TimeZone.getDefault().getID();
        }

        java.util.TimeZone timeZone = java.util.TimeZone.getTimeZone(tz);
        java.util.Date now = new java.util.Date();

        java.text.SimpleDateFormat isoFmt =
            new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
        isoFmt.setTimeZone(timeZone);

        java.text.SimpleDateFormat fullFmt =
            new java.text.SimpleDateFormat("d MMMM yyyy, HH:mm:ss", new java.util.Locale("ru"));
        fullFmt.setTimeZone(timeZone);

        java.text.SimpleDateFormat dayFmt =
            new java.text.SimpleDateFormat("EEEE", new java.util.Locale("ru"));
        dayFmt.setTimeZone(timeZone);

        int offsetHours = timeZone.getOffset(now.getTime()) / 3600000;
        String offsetLabel = "UTC" + (offsetHours >= 0 ? "+" : "") + offsetHours;

        JSONObject result = new JSONObject();
        result.put("timezone", tz);
        result.put("iso", isoFmt.format(now));
        result.put("formatted", fullFmt.format(now));
        result.put("day_of_week", dayFmt.format(now));
        result.put("utc_offset", offsetLabel);
        // Для старых обработчиков, оставляем поле datetime
        result.put("datetime", isoFmt.format(now));
        return result.toString();
    }

    private String executeWebSearch(JSONObject args) {
        return app.multiai.mobile.utils.WebSearchService.search(args).toString();
    }

    private String executeUrlFetch(JSONObject args) {
        return app.multiai.mobile.utils.WebSearchService.fetch(args).toString();
    }

    private String executeSaveMemory(JSONObject args) throws Exception {
        String memName = args.optString("name", "").trim();
        String memContent = args.optString("content", "").trim();
        if (memName.isEmpty() || memContent.isEmpty()) return "{\"error\":\"name and content required\"}";
        if (!app.multiai.mobile.utils.MemoryContextBuilder.memoriesEnabled(this)) {
            return "{\"error\":\"save_memory: память отключена в настройках\"}";
        }
        app.multiai.mobile.utils.MemoryStore.append(this, memName, memContent);
        // Sync memories to cloud
        new Thread(() -> {
            try { new app.multiai.mobile.utils.CloudSyncManager(ChatModeActivity.this).pushMemories(); }
            catch (Exception ignored) {}
        }).start();
        JSONObject res = new JSONObject();
        res.put("success", true);
        // Наружу только факт обновления памяти: без имени и содержимого.
        res.put("message", "Память обновлена");
        return res.toString();
    }

    private String wmoToDescription(int code) {
        switch (code) {
            case 0: return "Clear sky"; case 1: return "Mainly clear"; case 2: return "Partly cloudy";
            case 3: return "Overcast"; case 45: case 48: return "Fog";
            case 51: return "Light drizzle"; case 53: return "Moderate drizzle"; case 55: return "Dense drizzle";
            case 61: return "Slight rain"; case 63: return "Moderate rain"; case 65: return "Heavy rain";
            case 71: return "Slight snow"; case 73: return "Moderate snow"; case 75: return "Heavy snow";
            case 80: return "Slight rain showers"; case 81: return "Moderate rain showers";
            case 95: return "Thunderstorm"; default: return "Unknown";
        }
    }

    // ══════════════════ CHAT ACTIONS MENU ══════════════════

    private void showCurrentChatActions() {
        if (temporaryChatEnabled) {
            showTemporaryChatActions();
        } else if (currentChat != null && !currentChat.getMessages().isEmpty()) {
            showChatActionsMenu(currentChat);
        }
    }

    private void showTemporaryChatActions() {
        java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.chat_share), R.drawable.ic_share, false, "share"),
                new ModernSheet.MenuItem(getString(R.string.chat_downloaded_files), R.drawable.ic_file_attach, false, "files"),
                new ModernSheet.MenuItem(getString(R.string.chat_find_in_chat), R.drawable.ic_magnify, false, "search")
        );
        ModernSheet.menu(this, getString(R.string.chat_temporary_chat), items, (idx, item) -> {
            if (idx == 0) app.multiai.mobile.utils.ChatActions.share(
                    this, getString(R.string.chat_temporary_chat), temporaryMessages);
            else if (idx == 1) app.multiai.mobile.utils.ChatActions.showFiles(this, temporaryMessages);
            else showSearchInChat(temporaryMessages);
        });
    }

    private void showChatActionsMenu(Chat chat) {
        java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.chat_share), R.drawable.ic_share, false, "share"),
                new ModernSheet.MenuItem(chat.isPinned() ? getString(R.string.chat_unpin) : getString(R.string.chat_pin), chat.isPinned() ? R.drawable.ic_pin_filled : R.drawable.ic_pin, false, "pin"),
                new ModernSheet.MenuItem(getString(R.string.chat_move_to_project), R.drawable.ic_folder, false, "move"),
                new ModernSheet.MenuItem(getString(R.string.chat_downloaded_files), R.drawable.ic_file_attach, false, "files"),
                new ModernSheet.MenuItem(getString(R.string.chat_find_in_chat), R.drawable.ic_magnify, false, "search"),
                new ModernSheet.MenuItem(getString(R.string.chat_add_to_home), R.drawable.ic_home, false, "home"),
                new ModernSheet.MenuItem(chat.isArchived() ? getString(R.string.action_unarchive) : getString(R.string.chat_archive), R.drawable.ic_archive, false, "archive"),
                new ModernSheet.MenuItem(getString(R.string.chat_rename), R.drawable.ic_edit_pencil, false, "rename"),
                new ModernSheet.MenuItem(getString(R.string.action_delete), R.drawable.ic_trash, true, "delete")
        );
        ModernSheet.menu(this, app.multiai.mobile.utils.ChatActions.safeTitle(chat), items, (idx, item) -> {
            String action = String.valueOf(item.tag);
            switch (action) {
                case "share": app.multiai.mobile.utils.ChatActions.share(this, chat.getTitle(), chat.getMessages()); break;
                case "pin":
                    chat.setPinned(!chat.isPinned());
                    chatManager.updateChat(chat);
                    allChats = chatManager.loadActiveChats();
                    updateSidebarChats();
                    ModernToast.show(this, chat.isPinned() ? getString(R.string.chat_chat_pinned) : getString(R.string.chat_chat_unpinned));
                    break;
                case "move": moveChatToProjects(chat); break;
                case "files": app.multiai.mobile.utils.ChatActions.showFiles(this, chat.getMessages()); break;
                case "search": showSearchInChat(chat.getMessages()); break;
                case "home": app.multiai.mobile.utils.ChatActions.requestHomeShortcut(this, chat, true); break;
                case "archive":
                    if (chat.isArchived()) {
                        chat.setArchived(false);
                        chatManager.updateChat(chat);
                        allChats = chatManager.loadActiveChats();
                        updateSidebarChats();
                    } else archiveChat(chat);
                    break;
                case "rename": showRenameDialog(chat); break;
                case "delete": deleteChat(chat); break;
                default: break;
            }
        });
    }

    private void showSearchInChat(java.util.List<Message> source) {
        EditText input = new EditText(this);
        input.setHint(getString(R.string.chat_search_text_hint));
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        input.setSingleLine(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.stroke));
        bg.setCornerRadius(dp(14));
        input.setBackground(bg);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        ModernDialog.show(this, getString(R.string.chat_find_in_chat), null, input, getString(R.string.chat_cancel), getString(R.string.chat_find), false,
                () -> showSearchResults(source, input.getText().toString()));
        input.requestFocus();
    }

    private void showSearchResults(java.util.List<Message> source, String query) {
        java.util.List<Integer> matches = app.multiai.mobile.utils.ChatActions.findMatches(source, query);
        if (matches.isEmpty()) {
            ModernToast.show(this, getString(R.string.chat_no_matches));
            return;
        }
        java.util.List<ModernSheet.MenuItem> items = new java.util.ArrayList<>();
        for (Integer index : matches) {
            items.add(new ModernSheet.MenuItem(
                    app.multiai.mobile.utils.ChatActions.matchLabel(source.get(index)), 0, false, index));
        }
        ModernSheet.menu(this, getString(R.string.chat_found_count, matches.size()), items, (idx, item) -> {
            Object tag = item.tag;
            if (tag instanceof Integer) recyclerMessages.scrollToPosition((Integer) tag);
        });
    }

    private void showRenameDialog(Chat chat) {
        EditText input = new EditText(this);
        input.setText(chat.getTitle());
        input.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        input.setHint(getString(R.string.chat_chat_name_hint));
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_tonal));
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(1, androidx.core.content.ContextCompat.getColor(this, R.color.stroke));
        input.setBackground(inputBg);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setSingleLine();
        input.selectAll();
        input.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));

        ModernDialog.show(this, getString(R.string.chat_rename_chat), null, input, getString(R.string.chat_cancel), getString(R.string.chat_save), false, () -> {
            String newTitle = input.getText().toString().trim();
            if (!newTitle.isEmpty()) {
                chat.setTitle(newTitle);
                chatManager.updateChat(chat);
                updateSidebarChats();
            }
        });
        input.requestFocus();
    }

    private void archiveChat(Chat chat) {
        chat.setArchived(true);
        chatManager.updateChat(chat);
        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
            currentChat = null;
        }
        allChats = chatManager.loadActiveChats();
        if (!allChats.isEmpty() && currentChat == null) {
            currentChat = allChats.get(0);
            chatManager.saveActiveChatId(currentChat.getId());
        }
        updateSidebarChats();
        loadMessages();
        ModernToast.show(this, getString(R.string.chat_chat_archived));
    }

    /**
     * Переместить чат в Project Mode (обычный multiai_chats prefs).
     * Удаляет из Chat Mode и добавляет в Project Mode.
     */
    private void moveChatToProjects(Chat chat) {
        try {
            // Сохраняем копию чата в проект-менеджер
            ChatManager projectMgr = new ChatManager(this);
            projectMgr.addChatIfAbsent(chat);

            // Удаляем из Chat Mode
            chatManager.deleteChat(chat.getId());

            if (currentChat != null && currentChat.getId().equals(chat.getId())) {
                currentChat = null;
            }
            allChats = chatManager.loadActiveChats();
            if (!allChats.isEmpty() && currentChat == null) {
                currentChat = allChats.get(0);
                chatManager.saveActiveChatId(currentChat.getId());
            }
            updateSidebarChats();
            loadMessages();
            ModernToast.show(this, getString(R.string.chat_moved_to_project));
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.chat_error_move, e.getMessage()));
        }
    }

    private void deleteChat(Chat chat) {
        ModernDialog.show(this, getString(R.string.chat_delete_chat_title),
                getString(R.string.chat_delete_chat_body, chat.getTitle() != null ? chat.getTitle() : getString(R.string.chat_chat_fallback)),
                getString(R.string.chat_cancel), getString(R.string.action_delete), true, () -> {
            chatManager.deleteChat(chat.getId());
            if (currentChat != null && currentChat.getId().equals(chat.getId())) {
                currentChat = null;
            }
            allChats = chatManager.loadActiveChats();
            if (!allChats.isEmpty() && currentChat == null) {
                currentChat = allChats.get(0);
                chatManager.saveActiveChatId(currentChat.getId());
            }
            updateSidebarChats();
            loadMessages();
        });
    }

    // ══════════════════ MODEL PICKER ══════════════════

    // Динамический диалог — обновляется при загрузке моделей
    private android.app.Dialog activeModelDialog;
    private android.widget.LinearLayout activeModelListContainer;
    private android.widget.LinearLayout activeModelSkeleton;

    private void showModelPicker() {
        // Если модели ещё не загружены — запускаем фоновую подгрузку
        if (modelCatalog.isEmpty()) {
            loadAvailableModels();
            ModernToast.show(this, getString(R.string.chat_loading_models));
            return;
        }
        app.multiai.mobile.ui.kit.ModelCatalogUi.show(this,
                new ArrayList<>(modelCatalog), selectedModelId, selectedEffort,
                buildCatalogUsage(),
                (model, effort) -> {
                    selectedModelId = model.id;
                    selectedModelName = model.displayName;
                    selectedEffort = model.resolveEffort(effort);
                    if (multiAiClient != null) multiAiClient.setEffort(selectedEffort);
                    SharedPreferences.Editor modelPrefs = getSharedPreferences(
                            PREFS_NAME, MODE_PRIVATE).edit()
                            .putString("selected_model_id", selectedModelId)
                            .putString("selected_model_name", selectedModelName);
                    // Sync to Work-mode prefs.
                    getSharedPreferences("multiaiide_settings", MODE_PRIVATE).edit()
                            .putString("selected_model_id", selectedModelId)
                            .putString("selected_model_name", selectedModelName)
                            .apply();
                    String effortKey = "effort_" + model.id;
                    if (selectedEffort == null) modelPrefs.remove(effortKey);
                    else modelPrefs.putString(effortKey, selectedEffort);
                    modelPrefs.apply();
                    // Выбор в открытом чате закрепляется за ним сразу — не
                    // только при следующей отправке.
                    if (!temporaryChatEnabled && currentChat != null) {
                        stampChatModel();
                        try {
                            chatManager.updateChat(currentChat);
                        } catch (Exception ignored) {
                        }
                    }
                    updateModelPill();
                });
    }

    private android.widget.LinearLayout buildSkeletonList(int count) {
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < count; i++) {
            View row = new View(this);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_tonal));
            row.setBackground(bg);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
            lp.setMargins(dp(2), dp(3), dp(2), dp(3));
            row.setLayoutParams(lp);
            // shimmer
            android.animation.ValueAnimator shimmer = android.animation.ValueAnimator.ofFloat(0.35f, 1.0f, 0.35f);
            shimmer.setDuration(1400);
            shimmer.setStartDelay(i * 90L);
            shimmer.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            shimmer.addUpdateListener(a -> row.setAlpha((Float) a.getAnimatedValue()));
            shimmer.start();
            container.addView(row);
        }
        return container;
    }

    /** Вызывается после загрузки моделей — плавно подменяет скелетон списком. */
    private void refreshActiveModelDialogIfOpen() {
        if (activeModelDialog == null || activeModelListContainer == null) return;
        if (availableModels.isEmpty()) return;

        android.widget.LinearLayout listContainer = activeModelListContainer;

        if (activeModelSkeleton != null) {
            final View skeleton = activeModelSkeleton;
            skeleton.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                listContainer.removeView(skeleton);
                populateModelList(listContainer);
            }).start();
            activeModelSkeleton = null;
        } else {
            listContainer.removeAllViews();
            populateModelList(listContainer);
        }
    }

    private void populateModelList(android.widget.LinearLayout listContainer) {
        for (int idx = 0; idx < availableModels.size(); idx++) {
            String[] model = availableModels.get(idx);
            View row = buildModelRow(model);
            listContainer.addView(row);
            // Плавное появление каждой строки с задержкой
            row.setAlpha(0f);
            row.setTranslationY(dp(12));
            row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(idx * 35L)
                    .setDuration(280)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }
    }

    private View buildModelRow(String[] model) {
        final String mId = model[0];
        final String mName = model[1];
        boolean isSelected = mId.equals(selectedModelId);

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));

        android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
        rowBg.setCornerRadius(dp(12));
        if (isSelected) {
            int accent = AccentColor.resolve(this);
            rowBg.setColor((accent & 0x00FFFFFF) | 0x33000000); // ~20% accent fill
            rowBg.setStroke(1, (accent & 0x00FFFFFF) | 0x66000000); // ~40% accent stroke
        } else {
            rowBg.setColor(0x00000000);
        }
        row.setBackground(rowBg);

        android.widget.LinearLayout.LayoutParams rowLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(2), 0, dp(2));
        row.setLayoutParams(rowLp);

        // Top line: name + badges
        android.widget.LinearLayout topLine = new android.widget.LinearLayout(this);
        topLine.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        topLine.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView nameView = new TextView(this);
        nameView.setText(mName);
        nameView.setTextSize(15);
        nameView.setTypeface(null, isSelected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        nameView.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        topLine.addView(nameView);

        // Badges — semantic colors so they read in both themes.
        int badgeNeutral = androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary);
        int badgeMuted = androidx.core.content.ContextCompat.getColor(this, R.color.text_hint);
        String idLower = mId.toLowerCase();
        String nameLower = mName.toLowerCase();
        if (idLower.contains("sonnet") && (idLower.contains("4.5") || idLower.contains("4-5")))
            topLine.addView(createModelBadge("TOP", badgeMuted));
        else if (idLower.contains("sonnet-4") || idLower.contains("sonnet_4") || nameLower.contains("sonnet 4"))
            topLine.addView(createModelBadge("Stable", badgeNeutral));
        else if (idLower.contains("minimax") || nameLower.contains("minimax"))
            topLine.addView(createModelBadge("THINKING", badgeNeutral));
        else if (idLower.contains("deepseek") || nameLower.contains("deepseek"))
            topLine.addView(createModelBadge("VIBE", badgeNeutral));
        else if (idLower.contains("glm") || nameLower.contains("glm"))
            topLine.addView(createModelBadge("NEW", badgeNeutral));
        else if (idLower.contains("qwen") || nameLower.contains("qwen"))
            topLine.addView(createModelBadge("CODER", badgeNeutral));

        if (isSelected) {
            TextView check = new TextView(this);
            check.setText(" ✓");
            check.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.accent));
            check.setTextSize(15);
            topLine.addView(check);
        }
        row.addView(topLine);

        row.setClickable(true);
        row.setFocusable(true);
        // Ripple foreground
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        row.setForeground(getDrawable(outValue.resourceId));

        row.setOnClickListener(v -> {
            selectedModelId = mId;
            selectedModelName = mName;
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit()
                    .putString("selected_model_id", selectedModelId)
                    .putString("selected_model_name", selectedModelName)
                    .apply();
            getSharedPreferences("multiaiide_settings", MODE_PRIVATE).edit()
                    .putString("selected_model_id", selectedModelId)
                    .putString("selected_model_name", selectedModelName)
                    .apply();
            if (!temporaryChatEnabled && currentChat != null) {
                stampChatModel();
                try {
                    chatManager.updateChat(currentChat);
                } catch (Exception ignored) {
                }
            }
            updateModelPill();
            if (activeModelDialog != null) activeModelDialog.dismiss();
        });

        return row;
    }

    private TextView createModelBadge(String text, int color) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(color);
        badge.setTextSize(9);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dp(4));
        badgeBg.setColor(color & 0x33FFFFFF);
        badgeBg.setStroke(1, color & 0x66FFFFFF);
        badge.setBackground(badgeBg);
        badge.setPadding(dp(5), dp(1), dp(5), dp(1));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), 0, 0, 0);
        badge.setLayoutParams(lp);
        return badge;
    }

    /** Диалог фидбека по дизлайку — как в Work Mode (категория + детали). */
    private void showDislikeFeedbackDialog(Message message) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        String[] categories = getResources().getStringArray(R.array.chat_dislike_categories);
        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> spinnerAdapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, categories);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);
        android.graphics.drawable.GradientDrawable spinnerBg = new android.graphics.drawable.GradientDrawable();
        spinnerBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        spinnerBg.setCornerRadius(dp(12));
        spinnerBg.setStroke(1, ContextCompat.getColor(this, R.color.stroke));
        spinner.setBackground(spinnerBg);
        spinner.setPadding(dp(12), dp(8), dp(12), dp(8));
        body.addView(spinner);

        android.widget.EditText details = new android.widget.EditText(this);
        details.setHint(getString(R.string.chat_share_details_hint));
        details.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        details.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        details.setTextSize(14);
        details.setMinLines(3);
        details.setGravity(android.view.Gravity.TOP);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        inputBg.setCornerRadius(dp(12));
        inputBg.setStroke(1, ContextCompat.getColor(this, R.color.stroke));
        details.setBackground(inputBg);
        details.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        details.setLayoutParams(lp);
        body.addView(details);

        TextView info = new TextView(this);
        info.setText(getString(R.string.chat_feedback_info));
        info.setTextColor(ContextCompat.getColor(this, R.color.text_hint));
        info.setTextSize(12);
        info.setPadding(0, dp(10), 0, 0);
        body.addView(info);

        int accent = AccentColor.resolve(this);
        TextView btnSubmit = new TextView(this);
        btnSubmit.setText(getString(R.string.chat_submit));
        btnSubmit.setTextColor(AccentColor.contrastForeground(accent));
        btnSubmit.setTextSize(16);
        btnSubmit.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSubmit.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(accent);
        btnBg.setCornerRadius(dp(24));
        btnSubmit.setBackground(btnBg);
        btnSubmit.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        submitLp.topMargin = dp(12);
        btnSubmit.setLayoutParams(submitLp);
        body.addView(btnSubmit);

        final android.app.Dialog[] sheetRef = new android.app.Dialog[1];
        sheetRef[0] = ModernSheet.custom(this, getString(R.string.chat_what_wrong_with_answer), body);

        btnSubmit.setOnClickListener(v -> {
            int selectedPos = spinner.getSelectedItemPosition();
            String category = selectedPos > 0 ? categories[selectedPos] : null;
            String detailsText = details.getText().toString().trim();
            sendFeedbackReport(message, "dislike", category, detailsText.isEmpty() ? null : detailsText);
            if (sheetRef[0] != null) sheetRef[0].dismiss();
            ModernToast.show(this, getString(R.string.chat_thanks_for_feedback));
        });
    }

    private void sendFeedbackReport(Message message, String type, String category, String details) {
        new Thread(() -> {
            try {
                String content = message.getContent() != null ? message.getContent() : "";
                if (content.length() > 500) content = content.substring(0, 500);
                JSONObject report = new JSONObject();
                report.put("type", type);
                report.put("message_content", content);
                report.put("chat_id", currentChat != null ? currentChat.getId() : "unknown");
                report.put("timestamp", System.currentTimeMillis());
                report.put("model", selectedModelId != null ? selectedModelId : "");
                report.put("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                if (category != null) report.put("category", category);
                if (details != null) report.put("details", details);
                okhttp3.Request request = new okhttp3.Request.Builder()
                        .url("https://multiai-api.vercel.app/api/feedback")
                        .post(okhttp3.RequestBody.create(report.toString(), okhttp3.MediaType.get("application/json; charset=utf-8")))
                        .build();
                try (okhttp3.Response response = new okhttp3.OkHttpClient().newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) return;
                    JSONObject result = new JSONObject(response.body().string());
                    if (!result.optBoolean("ok", false)) return;
                }
            } catch (Exception e) {
                app.multiai.mobile.utils.FileLog.d("ChatModeActivity", "feedback_send_failed");
            }
        }).start();
    }

    // ══════════════════ TTS (Edge Svetlana, speed 1.3x) ══════════════════

    private android.media.MediaPlayer ttsPlayer;
    private boolean ttsLoading = false;

    private void speakMessage(Message message) {
        String text = message.getContent();
        if (text == null || text.isEmpty()) return;
        text = app.multiai.mobile.utils.TtsTextCleaner.clean(text);
        if (text.isEmpty()) return;

        // Второе нажатие = стоп
        if (ttsPlayer != null) {
            try { ttsPlayer.stop(); ttsPlayer.release(); } catch (Exception ignored) {}
            ttsPlayer = null;
            return;
        }
        if (ttsLoading) return;
        ttsLoading = true;

        ModernToast.show(this, getString(R.string.chat_tts_generating));
        final String ttsText = text.length() > 3000 ? text.substring(0, 3000) : text;
        AppSettings ttsSettings = new AppSettings(this);
        final String ttsProvider = ttsSettings.getTtsProvider();
        final String ttsVoice = ttsSettings.getTtsVoice();
        final String ttsRate = ttsSettings.getTtsEdgeRate();
        final String ttsPitch = ttsSettings.getTtsEdgePitch();
        final String ttsVolume = ttsSettings.getTtsEdgeVolume();
        final float requestedSpeed = ttsSettings.getTtsSpeed();

        new Thread(() -> {
            try {
                final app.multiai.mobile.utils.TtsEngine.SynthesisResult synthesis =
                        app.multiai.mobile.utils.TtsEngine.synthesizeWithFallback(
                                this, ttsProvider, ttsVoice, ttsText, ttsRate, ttsPitch, ttsVolume);
                final java.io.File tempFile = synthesis.audioFile;
                // Edge applies rate while synthesizing. Qwen has no rate parameter,
                // so its generated audio is sped up only at playback time.
                final float playbackSpeed = app.multiai.mobile.utils.VoiceCatalog.PROVIDER_QWEN
                        .equals(synthesis.provider) ? requestedSpeed : 1f;

                runOnUiThread(() -> {
                    ttsLoading = false;
                    try {
                        ttsPlayer = new android.media.MediaPlayer();
                        ttsPlayer.setDataSource(tempFile.getAbsolutePath());
                        ttsPlayer.setOnCompletionListener(mp -> {
                            try { mp.release(); } catch (Exception ignored) {}
                            ttsPlayer = null;
                            tempFile.delete();
                        });
                        ttsPlayer.setOnErrorListener((mp, what, extra) -> {
                            try { mp.release(); } catch (Exception ignored) {}
                            ttsPlayer = null;
                            tempFile.delete();
                            ModernToast.show(this, getString(R.string.chat_error_playback));
                            return true;
                        });
                        ttsPlayer.setOnPreparedListener(mp -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                try {
                                    mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(playbackSpeed));
                                } catch (Exception ignored) {}
                            }
                            mp.start();
                        });
                        ttsPlayer.prepareAsync();
                    } catch (Exception e) {
                        ModernToast.show(this, getString(R.string.chat_error_playback));
                        tempFile.delete();
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    ttsLoading = false;
                    ModernToast.show(this, getString(R.string.chat_tts_error_msg, e.getMessage()));
                });
            }
        }).start();
    }

    private void showAiMoreMenu(Message message, View anchor) {
        // Chat Mode: без «Поделиться», но с иконками — как в остальных меню.
        java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
                new ModernSheet.MenuItem(getString(R.string.chat_copy),
                        R.drawable.ic_copy, false, null),
                new ModernSheet.MenuItem(getString(R.string.chat_retry),
                        R.drawable.ic_retry_arrow, false, null)
        );
        app.multiai.mobile.ui.kit.ModernMenu.show(anchor, null, items, (idx, item) -> {
            switch (idx) {
                case 0: {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("ai_message", message.getContentSafe()));
                    ModernToast.show(this, getString(R.string.chat_copied));
                    break;
                }
                case 1: {
                    retryAiMessage(message);
                    break;
                }
            }
        });
    }

    private void retryAiMessage(Message aiMessage) {
        if (isGenerating || aiMessage == null) return;
        List<Message> messages = temporaryChatEnabled ? temporaryMessages
                : (currentChat != null ? currentChat.getMessages() : null);
        if (messages == null) return;
        int aiIndex = messages.indexOf(aiMessage);
        if (aiIndex <= 0) return;
        Message previous = messages.get(aiIndex - 1);
        if (previous == null || previous.getType() != Message.TYPE_USER) return;
        // Never delete a later user turn when someone retries an older answer.
        for (int i = aiIndex + 1; i < messages.size(); i++) {
            if (messages.get(i).getType() == Message.TYPE_USER) return;
        }
        while (messages.size() > aiIndex + 1) {
            Message tail = messages.remove(messages.size() - 1);
            messagesAdapter.removeMessage(tail);
        }
        clearRetryState();
        retrySourceMessage = aiMessage;
        retrySourceUser = previous;
        aiMessage.setRetryPending(true);
        messagesAdapter.refreshMessage(aiMessage);
        if (!temporaryChatEnabled && currentChat != null) chatManager.updateChat(currentChat);
        restoreRetryAttachmentAndSend(previous);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (connectionStatus != null) connectionStatus.start();
        if (!profileReceiverRegistered) {
            ContextCompat.registerReceiver(this, profileReceiver,
                    new IntentFilter(OAuthLoginController.ACTION_PROFILE_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            profileReceiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (connectionStatus != null) connectionStatus.stop();
        if (profileReceiverRegistered) {
            unregisterReceiver(profileReceiver);
            profileReceiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Блокирующий диалог после истечения грейса всплывает снова.
        app.multiai.mobile.utils.UpdateManager.reshowBlockingIfNeeded(this);
        AppSettings currentSettings = new AppSettings(this);
        // Recreate if a visual general setting changed while we were paused.
        if (currentSettings.isSegmentedModeEnabled() != lastSegmentedSetting
                || !currentSettings.getEmojiMode().equals(lastEmojiMode)) {
            recreate();
            return;
        }
        // Аватар мог появиться после логина/смены аккаунта в профиле.
        loadSidebarAvatar();
        if (!currentSettings.isChatMode()) {
            Intent intent = new Intent(this, ChatActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
            return;
        }
        if (!completedInitialResume) {
            completedInitialResume = true;
            return;
        }
        // Перечитываем чаты — могли быть изменены из других Activity (Project Mode move, etc.)
        if (chatManager != null) {
            String activeId = currentChat != null ? currentChat.getId() : null;
            allChats = chatManager.loadActiveChats();
            // Не подменяем объект чата во время стрима: callback сохраняет ответ
            // именно в него, а свежая копия из prefs ещё не содержит этот ответ.
            if (activeId != null && !isGenerating) {
                for (Chat c : allChats) {
                    if (c.getId().equals(activeId)) {
                        currentChat = c;
                        break;
                    }
                }
            }
            updateSidebarChats();
        }

        // 📳 Shake-to-report: (re)register when the activity returns to the foreground.
        // Checking the setting here means a disabled toggle (e.g. flipped from the
        // report-bug sheet) takes effect on the next resume without recreating the activity.
        if (new AppSettings(this).isShakeToReportEnabled()) {
            if (shakeDetector == null) {
                shakeDetector = new app.multiai.mobile.utils.ShakeDetector(
                        () -> app.multiai.mobile.ui.kit.ReportBugSheet.show(ChatModeActivity.this));
            }
            shakeDetector.setEnabled(true);
            shakeDetector.register(this);
        } else if (shakeDetector != null) {
            shakeDetector.setEnabled(false);
            shakeDetector.unregister();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 📳 Stop shake detection while the activity isn't in the foreground to save battery.
        if (shakeDetector != null) {
            shakeDetector.unregister();
        }
        // Страховочный сейв: уходим с экрана — текущий чат обязан быть на диске.
        if (!temporaryChatEnabled && currentChat != null && chatManager != null) {
            try {
                chatManager.updateChat(currentChat);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        // HTTP и локальные tool callbacks не должны переживать Activity. Сначала
        // фиксируем уже показанный partial, иначе после recreate он исчезает.
        if (connectionStatus != null) connectionStatus.destroy();
        if (isGenerating) {
            stopGeneration();
        } else {
            streamEpoch++;
            try {
                multiAiClient.cancel();
                customProviderChatClient.cancel();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
        // 📳 Stop shake detection.
        if (shakeDetector != null) {
            shakeDetector.unregister();
            shakeDetector = null;
        }
        if (keyboardListener != null) {
            View root = findViewById(android.R.id.content);
            if (root != null) {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);
            }
            keyboardListener = null;
        }
        clearVoiceSilenceDetection();
        stopStreamAnimation();
        stopTranscribingAnimation();
        // Несохранённая голосовая запись пережить Activity не может — чистим.
        if (pendingVoiceFile != null) {
            pendingVoiceFile.delete();
            pendingVoiceFile = null;
        }
        if (ttsPlayer != null) {
            try { ttsPlayer.stop(); ttsPlayer.release(); } catch (Exception ignored) {}
            ttsPlayer = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (sidebarOpen && sidebarSearchExpanded) {
            setSidebarSearchExpanded(false, true);
            return;
        }
        if (sidebarOpen) {
            closeSidebar();
            return;
        }
        super.onBackPressed();
    }

    // ══════════════════ 📍 LOCATION ══════════════════

    /**
     * При первом запуске — мягкий rationale-диалог с просьбой разрешить геолокацию.
     * Нужен для точной погоды/времени; после первого раза больше не беспокоим.
     */
    private void maybePromptForLocation() {
        SharedPreferences p = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (isFinishing() || isDestroyed()) return;
            // Original dialog set "asked=true" on BOTH buttons — set it once here so the
            // behaviour is preserved regardless of which button is pressed.
            p.edit().putBoolean("asked", true).apply();
            ModernDialog.show(ChatModeActivity.this,
                getString(R.string.chat_location_title),
                getString(R.string.chat_location_message_chatmode),
                getString(R.string.chat_not_now),
                getString(R.string.chat_allow),
                false,
                () -> {
                    if (ContextCompat.checkSelfPermission(ChatModeActivity.this,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                        androidx.core.app.ActivityCompat.requestPermissions(ChatModeActivity.this,
                            new String[]{
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            },
                            987);
                    } else {
                        cacheLastKnownLocation();
                    }
                });
        }, 2500);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @androidx.annotation.NonNull String[] permissions,
                                           @androidx.annotation.NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 987) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                cacheLastKnownLocation();
                // Форсим обновление кэша — сбрасываем TTL
                getSharedPreferences("multiaiide_location", MODE_PRIVATE)
                    .edit().putLong("last_fetch_ts", 0L).apply();
                refreshLocationCacheIfStale();
            }
        }
    }

    /**
     * Сохраняет координаты в prefs.
     * Использует FusedLocationProviderClient — сначала getLastLocation,
     * если null → делает АКТИВНЫЙ запрос getCurrentLocation (включает GPS).
     */
    private void cacheLastKnownLocation() {
        try {
            if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            com.google.android.gms.location.FusedLocationProviderClient fused =
                com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);

            @SuppressWarnings("MissingPermission")
            com.google.android.gms.tasks.Task<android.location.Location> task = fused.getLastLocation();
            task.addOnSuccessListener(location -> {
                if (location != null) {
                    saveLocationToPrefs(location);
                } else {
                    // 🔥 Last location null — делаем АКТИВНЫЙ запрос GPS
                    requestFreshLocation(fused);
                }
            });
            task.addOnFailureListener(e -> {
                requestFreshLocation(fused);
            });
        } catch (Exception e) {
        }
    }

    /**
     * 🔥 Активный запрос GPS — включает модуль, иконка появляется в статус-баре.
     * Получает одну точку и останавливается.
     */
    @SuppressWarnings("MissingPermission")
    private void requestFreshLocation(com.google.android.gms.location.FusedLocationProviderClient fused) {
        if (ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        com.google.android.gms.location.LocationRequest locReq =
            new com.google.android.gms.location.LocationRequest.Builder(
                com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, 1000)
                .setMaxUpdates(1)
                .setDurationMillis(15000) // макс 15 сек
                .build();

        com.google.android.gms.location.LocationCallback locCallback =
            new com.google.android.gms.location.LocationCallback() {
                @Override
                public void onLocationResult(com.google.android.gms.location.LocationResult result) {
                    if (result != null && result.getLastLocation() != null) {
                        saveLocationToPrefs(result.getLastLocation());
                    }
                    // Отписываемся после первого результата
                    fused.removeLocationUpdates(this);
                }
            };

        fused.requestLocationUpdates(locReq, locCallback, Looper.getMainLooper());
    }

    private void saveLocationToPrefs(android.location.Location location) {
        if (location == null) return;
        SharedPreferences p = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
        p.edit()
            .putFloat("lat", (float) location.getLatitude())
            .putFloat("lon", (float) location.getLongitude())
            .putLong("gps_ts", System.currentTimeMillis())
            // Сбрасываем TTL чтобы reverse-geocoding подхватил новые координаты
            .putLong("last_fetch_ts", 0L)
            .apply();
    }
}
