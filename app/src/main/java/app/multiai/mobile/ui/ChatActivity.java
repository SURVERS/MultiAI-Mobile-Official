package app.multiai.mobile.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import android.content.SharedPreferences;

import app.multiai.mobile.R;
import app.multiai.mobile.adapters.ChatsAdapter;
import app.multiai.mobile.adapters.MessagesAdapter;
import app.multiai.mobile.models.Chat;
import app.multiai.mobile.models.Message;
import app.multiai.mobile.models.ToolCall;
import app.multiai.mobile.network.MultiAiClient;
import app.multiai.mobile.network.MultiAiError;
import app.multiai.mobile.network.RetryPolicy;
import app.multiai.mobile.network.StreamingTextNormalizer;
import app.multiai.mobile.network.oauth.OAuthSession;
import app.multiai.mobile.tools.OperationCancelToken;
import app.multiai.mobile.tools.WorkModeTools;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.utils.AccentColor;
import app.multiai.mobile.utils.AppSettings;
import app.multiai.mobile.utils.ChatActions;
import app.multiai.mobile.utils.ChatManager;
import app.multiai.mobile.utils.ChatTitleGenerator;
import app.multiai.mobile.utils.DrawerNavHelper;

/**
 * MultiAI Chat Activity с системой чатов, streaming, tool calls, thinking и изображениями
 */
public class ChatActivity extends AppCompatActivity {
    private static final String TAG = "ChatActivity";
    private static final int REQUEST_PERMISSION = 100;

    // Views
    private RecyclerView recyclerMessages;
    private RecyclerView recyclerChats;
    private EditText editMessage;
    private ImageButton buttonSend;
    private ImageButton buttonMenu;
    private ImageButton buttonAttachPhoto;
    private ImageButton buttonStop;
    private ImageButton buttonMic;
    private LinearLayout attachMenu;
    private LinearLayout emptyState;
    
    // 🎤 Voice input
    private android.speech.SpeechRecognizer speechRecognizer;
    private boolean isListening = false;
    private android.animation.ObjectAnimator micPulseAnimator;
    private DrawerLayout drawerLayout;
    private ImageButton btnNewChat;
    private boolean attachMenuOpen = false;
    private boolean isInForeground = true;
    private androidx.constraintlayout.motion.widget.MotionLayout inputMotionLayout;
    private boolean inputKeyboardVisible = false;
    private android.view.ViewTreeObserver.OnGlobalLayoutListener inputKeyboardListener;
    
    private com.google.android.material.floatingactionbutton.FloatingActionButton fabScrollDown;

    // Image preview views (inline-превью внутри inputBox)
    private View imagePreviewCard;
    private ImageView imagePreview;
    private TextView imageFileName;
    private TextView imageFileSize;
    private View imageLoadingOverlay;
    private View attachmentImageBox;
    private View attachmentFileBox;
    private View btnRemoveAttachment;

    /** ChatGPT-стиль: фото — скруглённая плитка, файл — карточка иконка+имя. */
    private void showAttachmentInline(boolean isImage) {
        if (attachmentImageBox != null) attachmentImageBox.setVisibility(isImage ? View.VISIBLE : View.GONE);
        if (attachmentFileBox != null) attachmentFileBox.setVisibility(isImage ? View.GONE : View.VISIBLE);
        if (imagePreviewCard != null) imagePreviewCard.setVisibility(View.VISIBLE);
        if (btnRemoveAttachment != null) {
            if (!isImage && attachmentFileBox != null) {
                attachmentFileBox.post(() -> {
                    btnRemoveAttachment.setX(Math.max(0f,
                            attachmentFileBox.getRight() - btnRemoveAttachment.getWidth() * 0.5f));
                    btnRemoveAttachment.setY(dpToPx(8));
                });
            } else {
                // The visual 24dp badge is centered inside the 48dp touch target.
                btnRemoveAttachment.setX(dpToPx(100));
                btnRemoveAttachment.setY(0f);
            }
        }
    }
    
    // Adapters
    private MessagesAdapter messagesAdapter;
    private ChatsAdapter chatsAdapter;
    private ChatsAdapter.OnChatClickListener chatsActionsListener;
    
    // Data
    private static MultiAiClient multiAiClient; // 🔥 Статический клиент для сохранения между поворотами
    private WorkModeTools workModeTools;
    private ChatManager chatManager;
    private Chat currentChat;
    private List<Chat> allChats;

    // State
    private boolean isGenerating = false;
    /** Идёт follow-up после tool-результата: контент основного стрима должен
     *  писаться в postToolMessage, а не теряться. */
    private boolean inToolFollowUp = false;
    private long thinkingStartTime = 0;
    private long turnStartTime = 0;
    private boolean turnUsedTools = false;
    private Handler thinkingTimerHandler;
    private Runnable thinkingTimerRunnable;
    // Live "Работает уже" workflow timer
    private Handler workflowTimerHandler;
    private Runnable workflowTimerRunnable;
    private String activityInstanceId = "";
    private int requestSeq = 0;
    private String activeRequestId = "";
    private String lastUserPrompt = "";
    private String lastFailedErrorMessage = "";
    private String activeErrorCode = "";
    private boolean isMultiAiMode = false;
    private Message activeErrorMessage = null;
    private Message lastFailedUserMessage = null;
    /** DeepSeek-style retry: keep the original assistant bubble and append a page. */
    private Message retrySourceMessage;
    private Message retrySourceUser;
    private View emptyStateLogo;
    private TextView textEmptyTyping;
    private final Handler emptyStateAnimationHandler = new Handler(Looper.getMainLooper());
    private List<String> emptyStatePhrases = new ArrayList<>();
    private int emptyStatePhraseIndex = 0;
    private int emptyStateVisibleChars = 0;
    private boolean emptyStateDeleting = false;
    private boolean emptyStateCursorVisible = true;
    private long emptyStateAnimationStep = 0L;
    private final Runnable emptyStateAnimationRunnable = new Runnable() {
        @Override
        public void run() {
            tickEmptyStateAnimation();
        }
    };
    private final Runnable emptyStateCursorRunnable = new Runnable() {
        @Override
        public void run() {
            tickEmptyStateCursor();
        }
    };
    
    // 🔥 Текущее сообщение и tool calls для streaming
    private Message currentMessage = null;
    private List<ToolCall> toolCalls = new ArrayList<>();
    private LinearLayout toolCallsContainer = null;
    private Message postToolMessage = null;
    private Message animationTargetMessage = null;
    private Message timerTargetMessage = null;
    private Message workflowTargetMessage = null; // "Работает уже" owner — stable across tool rounds
    private final Object toolResultLock = new Object();
    /** Calls/results belong only to the currently streaming assistant round. */
    private final java.util.LinkedHashMap<String, ToolCall> roundToolCalls = new java.util.LinkedHashMap<>();
    private final java.util.LinkedHashMap<String, JSONObject> roundToolResults = new java.util.LinkedHashMap<>();
    /** Exact RecyclerView message that owns each call in the active round. */
    private final java.util.LinkedHashMap<String, Message> roundToolOwners = new java.util.LinkedHashMap<>();
    private boolean roundModelStreamComplete = false;
    /** Tool calls wait until the SSE round closes, so Plan transition batches are preflighted atomically. */
    private boolean roundExecutionDispatched = false;
    private boolean roundBatchDispatched = false;
    private boolean toolResultBatchInFlight = false;
    private int toolResultBatchRetryCount = 0;
    private int toolRoundCount = 0;
    private int questionCount = 0;
    /** Invalidates callbacks from an earlier model-response attempt within the same turn. */
    private long toolRoundGeneration = 0L;
    private volatile long turnEpoch = 0L;
    private volatile boolean turnCancelled = false;
    /** Immutable Work Mode instructions for the current initial request and every tool follow-up. */
    private String turnSystemPrompt = "";
    /** Exact project-rule snapshot shared by this turn's prompt and workflow UI. */
    private app.multiai.mobile.utils.ProjectRulesManager.Snapshot turnProjectRules;
    /**
     * Правила проекта включаются в контекст ОДИН РАЗ на каждый контекст модели
     * (то есть на чат) и повторно только после суммаризации контекста — либо
     * если сами файлы правил или папка проекта изменились.
     *
     * <p>Ключ живёт в статической LRU-карте, поэтому пересоздание активити и
     * возврат к уже начатому чату НЕ вставляют весь AGENTS.md повторно. Раньше
     * ключ был один на процесс: новый чат — новый контекст модели — так и не
     * получал правил ни разу за всю сессию.</p>
     */
    private static final int RULES_SESSION_MEMORY = 32;
    private static final java.util.Map<String, String> rulesInjectedByChat =
            java.util.Collections.synchronizedMap(
                    new java.util.LinkedHashMap<String, String>(RULES_SESSION_MEMORY, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                java.util.Map.Entry<String, String> eldest) {
                            return size() > RULES_SESSION_MEMORY;
                        }
                    });
    private boolean pendingRulesReinject = false;
    private boolean turnIncludeRules = false;
    private JSONArray activeToolConversation = null;
    private static final int MAX_TOOL_RESULT_BATCH_RETRIES = 3;
    private static final long APPROVAL_TIMEOUT_SEC = 300L;
    /** Follow-up attempts already scheduled in this turn (0 = only the initial one). */
    private int followUpRetryCount = 0;
    /** Work Mode owns project actions, so its schema must not depend on Chat Mode's global tools toggle. */
    private boolean turnToolsEnabled = true;

    // ═══════════ Авто-суммаризация контекста ═══════════
    /** Начинаем сжимать заранее: остаётся запас для ответа модели. */
    private static final double AUTO_SUMMARY_TRIGGER_RATIO = 0.72d;
    /** Последние сообщения остаются дословными, чтобы не терять ближайший контекст. */
    private static final int AUTO_SUMMARY_TAIL_MESSAGE_COUNT = 8;
    private static final int AUTO_SUMMARY_MIN_MESSAGES = 6;
    private static final int AUTO_SUMMARY_MIN_NEW_MESSAGES = 2;
    private boolean autoSummarizing = false;
    /** После сетевой ошибки пропускаем ровно одну попытку, чтобы отправка не зависла. */
    private boolean skipAutoSummaryOnce = false;

    // ═══════════ Cooperative turn cancellation ═══════════
    // One OperationCancelToken per TURN (not per tool): covers the main
    // stream, every follow-up and every local tool execution, including
    // parallel tool calls of the same batch (they share this token).
    private volatile OperationCancelToken activeTurnToken = null;

    // ═══════════ Transient Plan Mode ═══════════
    // This is deliberately turn-scoped and is never controlled by persisted
    // Work Mode settings, so it cannot leak into another chat, app restart,
    // or unrelated project turn.
    private final Object planModeLock = new Object();
    private volatile boolean planModeActive = false;
    private volatile long planModeEpoch = -1L;
    private String planModePreviousAccess = "ask";
    private final java.util.List<PlanExitRequest> activePlanExitRequests =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private static final class PlanExitRequest {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean resolved = new AtomicBoolean(false);
        final AtomicReference<android.app.Dialog> dialog = new AtomicReference<>();
        volatile String reason = "dismissed";

        void resolve(String exitReason) {
            if (!resolved.compareAndSet(false, true)) return;
            reason = exitReason == null ? "dismissed" : exitReason;
            latch.countDown();
            android.app.Dialog current = dialog.get();
            if (current != null) {
                try { current.dismiss(); } catch (Exception ignored) {}
            }
        }
    }

    /** Cancel the turn token (if any) and stop in-flight local Work Mode work. */
    private void cancelActiveTurnWork(String reason) {
        OperationCancelToken token = activeTurnToken;
        activeTurnToken = null;
        if (token != null) token.cancel(reason);
        if (workModeTools != null) {
            try {
                workModeTools.cancelCurrentOperation(reason);
            } catch (Exception ignored) {
            }
        }
        try {
            app.multiai.mobile.utils.WorkModeAccess.releasePendingApprovals();
        } catch (Exception ignored) {
        }
        releasePendingPlanExitRequests();
        restorePlanMode("turn cancelled");
    }

    private boolean isPlanModeActiveForTurn(long epoch) {
        return planModeActive && planModeEpoch == epoch;
    }

    /** Starts persisted Plan access at the beginning of every new Work Mode turn. */
    private void prepareConfiguredPlanMode(long epoch) {
        if (!app.multiai.mobile.utils.WorkModeAccess.MODE_PLAN.equals(
                app.multiai.mobile.utils.WorkModeAccess.mode(this))) return;
        synchronized (planModeLock) {
            planModePreviousAccess = app.multiai.mobile.utils.WorkModeAccess.MODE_PLAN;
            planModeEpoch = epoch;
            planModeActive = true;
        }
    }

    private MultiAiClient.ToolPolicy toolPolicyForTurn(long epoch) {
        return isPlanModeActiveForTurn(epoch)
                ? MultiAiClient.ToolPolicy.PLAN_READ_ONLY
                : MultiAiClient.ToolPolicy.FULL;
    }

    /** Activate a Plan Mode session without mutating persisted Work Mode settings. */
    private JSONObject enterPlanMode(long epoch, JSONObject args) throws Exception {
        synchronized (planModeLock) {
            if (!isTurnActive(epoch)) {
                return planModeError("PLAN_TURN_INACTIVE", "Plan Mode belongs to an inactive turn");
            }
            if (!isPlanModeActiveForTurn(epoch)) {
                planModePreviousAccess = app.multiai.mobile.utils.WorkModeAccess.mode(this);
                planModeEpoch = epoch;
                planModeActive = true;
            }
        }
        // Every continuation receives a fresh immutable prompt snapshot. The
        // client schema is separately filtered per outgoing request.
        turnSystemPrompt = buildEnrichedInstructions(true);
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("plan_mode", "active");
        result.put("direct_file_writes_disabled", true);
        result.put("previous_access_mode", planModePreviousAccess);
        String reason = args != null ? args.optString("reason", "").trim() : "";
        if (!reason.isEmpty()) result.put("reason", reason);
        result.put("message", "Plan Mode is active: inspect the project, run project commands, then call present_plan.");
        return result;
    }

    private void restorePlanMode(String reason) {
        boolean changed;
        synchronized (planModeLock) {
            changed = planModeActive;
            planModeActive = false;
            planModeEpoch = -1L;
        }
        if (changed) {
            // The persisted mode never changed; rebuilding simply restores the
            // normal Work Mode contract for the current continuation.
            turnSystemPrompt = buildEnrichedInstructions(false);
        }
    }

    private JSONObject planModeError(String code, String message) throws Exception {
        return new JSONObject().put("success", false).put("error", message).put("code", code)
                .put("plan_mode", planModeActive ? "active" : "inactive");
    }

    private boolean isPlanReadOnlyTool(String name) {
        return "read_file".equals(name) || "list_dir".equals(name)
                || "search_grep".equals(name) || "find_files".equals(name)
                || "execute_command".equals(name) || "present_plan".equals(name);
    }

    private void releasePendingPlanExitRequests() {
        synchronized (activePlanExitRequests) {
            for (PlanExitRequest request : new java.util.ArrayList<>(activePlanExitRequests)) {
                if (request != null) request.resolve("cancelled");
            }
            activePlanExitRequests.clear();
        }
    }

    private void refreshToolCallOwner(ToolCall toolCall) {
        if (toolCall == null || messagesAdapter == null) return;
        Message owner;
        synchronized (toolResultLock) {
            owner = roundToolOwners.get(toolCall.getId());
        }
        if (owner != null) messagesAdapter.refreshMessage(owner);
    }

    // ═══════════ Retry policy ═══════════
    private static final int MAX_INITIAL_RECONNECTS = 10;
    private final RetryPolicy initialStreamRetryPolicy = new RetryPolicy(
            MAX_INITIAL_RECONNECTS + 1, 2_000L, 20_000L, 0.0, new java.util.Random());
    private final RetryPolicy followUpRetryPolicy = new RetryPolicy(10, 20_000L, 20_000L, 0.0, new java.util.Random());

    /**
     * Checkpoint captured before every tool-result follow-up request: deep
     * JSON copies of the exact payloads that produced the request. Lets the
     * Retry button re-issue ONLY the follow-up (side-effect tools are never
     * re-executed) after a terminal retryable failure.
     */
    private static final class FollowUpCheckpoint {
        final JSONArray toolResultsBatch; // deep copy
        final JSONArray requestMessages;  // deep copy — full exact payload
        final String preToolPrefix;
        final JSONArray activeToolConversation; // deep copy or null

        FollowUpCheckpoint(JSONArray toolResultsBatch, JSONArray requestMessages,
                           String preToolPrefix, JSONArray activeToolConversation) {
            this.toolResultsBatch = toolResultsBatch;
            this.requestMessages = requestMessages;
            this.preToolPrefix = preToolPrefix;
            this.activeToolConversation = activeToolConversation;
        }
    }

    /** Live checkpoint of the in-flight follow-up; null when none. */
    private FollowUpCheckpoint followUpCheckpoint = null;

    private static JSONArray deepCopy(JSONArray source) {
        if (source == null) return null;
        try {
            return new JSONArray(source.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private void clearFollowUpCheckpoint() {
        followUpCheckpoint = null;
    }

    /** Save a checkpoint: caller passes the live arrays, deep copies are stored. */
    private void saveFollowUpCheckpoint(JSONArray toolResultsBatch, JSONArray requestMessages,
                                        String preToolPrefix) {
        JSONArray batchCopy = deepCopy(toolResultsBatch);
        JSONArray messagesCopy = deepCopy(requestMessages);
        if (batchCopy == null || messagesCopy == null) {
            followUpCheckpoint = null;
            return;
        }
        followUpCheckpoint = new FollowUpCheckpoint(
                batchCopy, messagesCopy,
                preToolPrefix != null ? preToolPrefix : "",
                deepCopy(activeToolConversation));
    }
    
    // 🔥 ПОСИМВОЛЬНАЯ АНИМАЦИЯ (как у thinking!)
    private final StringBuilder contentBuffer = new StringBuilder();
    /** All visible AI content for the current Work turn, across model rounds. */
    private final StringBuilder workflowContentBuffer = new StringBuilder();
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private String displayedContent = "";
    private String displayedThinking = "";
    private Handler animationHandler = new Handler(Looper.getMainLooper());
    private Runnable contentAnimationTask;
    private long pendingSuccessfulFinishEpoch = -1L;
    private Runnable thinkingAnimationTask;
    /** Persists visible Work Mode progress without serializing on every token. */
    private static final long STREAM_PERSIST_INTERVAL_MS = 450L;
    private long lastStreamPersistAtMs = 0L;
    private Runnable pendingStreamPersistTask;
    private Chat pendingStreamPersistChat;
    private Message pendingStreamPersistMessage;
    // 🔥 ChatGPT-style: ~16ms (60 FPS), плавно и быстро
    private static final int CHAR_DELAY_MS = 16;
    private static final int CONTENT_CHAR_DELAY_MS = 16;
    private static final int AUTO_SCROLL_NEAR_BOTTOM_THRESHOLD_PX = 180;
    private boolean followStreamToBottom = true;
    private boolean userScrollingMessages = false;
    private long lastStreamingScrollAtMs = 0L;
    private boolean pendingTitleGeneration = false;
    private int contentRevealStartCp = 0;
    private int contentGhostStartCp = 0; // legacy field: now mirrors fade tail start
    
    // 🔥 Статическое хранилище для сохранения контента между поворотами экрана
    private static String pendingContent = null;
    private static String pendingThinking = null;
    private static boolean hasPendingMessage = false;
    
    // Image state
    private String selectedImageBase64 = null;
    private String selectedImageName = null;
    
    // File attachment state
    private String attachedFileName = null;
    private String attachedFileContent = null;
    private String pendingBranchContent = null;
    
    // Model selector
    private TextView btnModelSelector;
    private TextView brandingPill;
    private String selectedModelId;
    private String selectedModelName;
    private String selectedEffort;
    private List<String[]> availableModels = new ArrayList<>(); // [id, displayName, provider, enabled]
    private final List<app.multiai.mobile.models.ModelInfo> modelCatalog = new ArrayList<>();
    private static final String PREFS_NAME = "multiai_profile";
    private static final String BACKEND_URL = "https://multiai.store";
    
    // 🔔 Polling for announcements & model refresh
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private static final long POLL_INTERVAL_MS = 30_000; // 30 seconds
    private String lastAnnouncementHash = "";

    // Shake-to-report (gated by AppSettings.shakeToReportEnabled)
    private app.multiai.mobile.utils.ShakeDetector shakeDetector;

    private boolean lastSegmentedSetting = false;
    private String lastEmojiMode = AppSettings.EMOJI_MODE_SYSTEM;

    /** Apply shake-to-report state to the live detector without a full re-register. */
    public void applyShakeEnabled(boolean enabled) {
        if (shakeDetector != null) {
            shakeDetector.setEnabled(enabled);
        } else if (enabled) {
            shakeDetector = new app.multiai.mobile.utils.ShakeDetector(
                    () -> app.multiai.mobile.ui.kit.ReportBugSheet.show(ChatActivity.this));
            shakeDetector.register(this);
        }
    }
    
    // Activity result launchers
    private ActivityResultLauncher<Intent> pickImageLauncher;
    private ActivityResultLauncher<android.net.Uri> cameraLauncher;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private android.net.Uri cameraPhotoUri;
    private String pendingPermissionAction = null; // "gallery", "camera", "audio"
    private boolean isPickerOpening = false; // Debounce guard for camera/gallery double-open

    /**
     * Не даёт нижней части input bar уйти под gesture/3-button navigation.
     * adjustResize уже отвечает за IME, поэтому navigation inset добавляем
     * только при скрытой клавиатуре и всегда от исходного padding.
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
                    boolean imeVisible = inputKeyboardVisible || insets.isVisible(
                            androidx.core.view.WindowInsetsCompat.Type.ime());
                    int navigationBottom = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
                    // Keep the XML base gap while the keyboard is open. The old 8dp
                    // override made the capsule visibly touch Gboard's suggestion row.
                    int bottom = imeVisible ? baseBottom
                            : Math.max(baseBottom, Math.max(navigationBottom, dpToPx(20)));
                    view.setPadding(baseLeft, baseTop, baseRight, bottom);
                    return insets;
                });
        inputContainer.post(() ->
                androidx.core.view.ViewCompat.requestApplyInsets(inputContainer));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;

        // Redirect to ChatModeActivity if chat mode is active
        AppSettings chatModeCheck = new AppSettings(this);
        if (chatModeCheck.isChatMode()) {
            startActivity(new Intent(this, ChatModeActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_chat);
        configureInputInsets();
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        // Load empty-state typing phrases from resources (localized).
        String[] phraseArray = getResources().getStringArray(R.array.chat_empty_phrases);
        emptyStatePhrases = new ArrayList<>(phraseArray.length);
        for (String p : phraseArray) emptyStatePhrases.add(p);

        // 🔥 Запрашиваем разрешение на запись для логов (Android 9 и ниже)
        // 🔔 Запрашиваем разрешение на уведомления (Android 13+)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS")
                    != PackageManager.PERMISSION_GRANTED) {
                // Delay slightly so it doesn't conflict with other permission dialogs
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    requestPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS");
                }, 1500);
            }
        }
        
        // 🔔 Создаём канал уведомлений
        createNotificationChannel();
        
        activityInstanceId = "ACT-" + Integer.toHexString(System.identityHashCode(this));
        
        initActivityResultLaunchers();
        initViews();
        restoreGlobalModel();
        setupAdapters();
        setupDrawer();
        setupListeners();
        
        // 🔥 Создаем MultiAiClient только если его еще нет (для сохранения между поворотами)
        if (multiAiClient == null) {
            multiAiClient = new MultiAiClient();
        } else {
        }
        // ChatActivity is the Work/Project mode. The API schema is scoped here;
        // ChatModeActivity never enables the project filesystem/shell tools.
        multiAiClient.setWorkModeToolsEnabled(true);
        workModeTools = new WorkModeTools(this);
        workModeTools.setSubagentInvoker((presetId, task, context, timeoutSec, token) ->
                invokeWorkSubagent(presetId, task, context, timeoutSec, token));
        
        chatManager = new ChatManager(this);
        
        // Pull cloud data on start (memories, instructions, chats) if signed in.
        // Раньше проверялся legacy-ключ google_id, который никто не пишет —
        // из-за этого облачные чаты никогда не подтягивались.
        if (app.multiai.mobile.network.oauth.OAuthSession.get().isAuthorized()) {
            new Thread(() -> {
                try {
                    app.multiai.mobile.utils.CloudSyncManager sync =
                            new app.multiai.mobile.utils.CloudSyncManager(ChatActivity.this);
                    boolean ok = sync.pullAll();
                    sync.pullPersonalization();
                    if (ok) {
                        // Обновляем список БЕЗ смены активного чата: юзер мог
                        // уже тапнуть другой чат — pullAll не должен его откатывать.
                        runOnUiThread(() -> refreshChatList());
                    }
                } catch (Exception ignored) {}
            }).start();
        }
        
        // 📍 При первом запуске спрашиваем разрешение на геолокацию (для погоды)
        maybePromptForLocation();
        
        loadChats();

        // 📌 Handle chat id from a pinned home shortcut (or deep link).
        handleChatIdIntent(getIntent());

        // 🔔 Проверяем объявления от админа
        checkAnnouncement();
    }

    /**
     * Home-screen shortcuts open ChatActivity with {@link ChatActions#EXTRA_CHAT_ID}.
     * Because the launcher may reuse the existing task, handle the same extra in
     * {@link #onNewIntent} as well. We also honour {@link ChatActions#EXTRA_CHAT_MODE}
     * by redirecting to ChatModeActivity when the shortcut was created from chat mode.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (app.multiai.mobile.utils.AppLockManager.get(this)
                .routeIfLocked(this, intent)) return;
        setIntent(intent);
        handleChatIdIntent(intent);
    }

    private void handleChatIdIntent(Intent intent) {
        if (intent == null) return;
        // Shortcut created for Chat Mode must land in ChatModeActivity, not here.
        if (intent.getBooleanExtra(ChatActions.EXTRA_CHAT_MODE, false)) {
            if (new AppSettings(this).isChatMode()) {
                Intent redirect = new Intent(this, ChatModeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(ChatActions.EXTRA_CHAT_ID,
                                intent.getStringExtra(ChatActions.EXTRA_CHAT_ID));
                startActivity(redirect);
                return;
            }
        }
        String chatId = intent.getStringExtra(ChatActions.EXTRA_CHAT_ID);
        if (chatId == null || chatId.isEmpty()) return;
        // Consume the extra so configuration changes don't re-trigger navigation.
        intent.removeExtra(ChatActions.EXTRA_CHAT_ID);
        if (currentChat != null && currentChat.getId().equals(chatId)) return; // already there
        Chat target = chatManager.getChatById(chatId);
        if (target != null) {
            if (target.isArchived()) {
                target.setArchived(false);
                chatManager.updateChat(target);
            }
            switchToChat(target);
            if (drawerLayout != null) drawerLayout.closeDrawers();
        } else {
            ModernToast.show(this, getString(R.string.chat_chat_not_found));
        }
    }
    
    /**
     * Инициализация Activity Result Launchers
     */
    private void initActivityResultLaunchers() {
        // Launcher для выбора изображения
        pickImageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        loadImage(imageUri);
                    }
                }
            }
        );
        
        // Launcher для камеры
        cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success && cameraPhotoUri != null) {
                    loadImage(cameraPhotoUri);
                }
            }
        );

        // Launcher для файлов (документы)
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri fileUri = result.getData().getData();
                    if (fileUri != null) {
                        loadFileAsText(fileUri);
                    }
                }
            }
        );

        // Launcher для запроса разрешений — dispatches based on pendingPermissionAction
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                String action = pendingPermissionAction;
                pendingPermissionAction = null;
                if (isGranted) {
                    if ("gallery".equals(action)) openGallery();
                    else if ("camera".equals(action)) openCamera();
                    else if ("audio".equals(action)) startVoiceInput();
                } else {
                    ModernToast.show(this, getString(R.string.chat_permission_denied));
                }
            }
        );
    }

    private void initViews() {
        recyclerMessages = findViewById(R.id.recyclerMessages);
        editMessage = findViewById(R.id.editMessage);
        buttonSend = findViewById(R.id.buttonSend);
        buttonMenu = findViewById(R.id.buttonMenu);

        buttonAttachPhoto = findViewById(R.id.btnAttach);
        btnNewChat = findViewById(R.id.btnNewChat);
        // MultiAI branding text → opens model picker (acts like Chat Mode's model pill).
        brandingPill = findViewById(R.id.brandingPill);
        if (brandingPill != null) {
            brandingPill.setOnClickListener(v -> showModelSelector());
            int screenWidthDp = getResources().getConfiguration().screenWidthDp;
            brandingPill.setMaxWidth(dpToPx(screenWidthDp < 400 ? 108 : 160));
            brandingPill.setSingleLine(true);
            brandingPill.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        setupWorkTopBarSegmentedPill();
        inputMotionLayout = findViewById(R.id.inputMotionLayout);
        emptyState = findViewById(R.id.emptyState);
        emptyStateLogo = findViewById(R.id.emptyStateLogo);
        if (emptyStateLogo instanceof android.widget.ImageView) {
            int uiMode = getResources().getConfiguration().uiMode;
            boolean isLightTheme = (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    != android.content.res.Configuration.UI_MODE_NIGHT_YES;
            if (isLightTheme) {
                android.widget.ImageView logo = (android.widget.ImageView) emptyStateLogo;
                logo.setImageResource(R.drawable.ic_multiai_logo);
                logo.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ContextCompat.getColor(this, R.color.icon)));
            }
        }
        textEmptyTyping = findViewById(R.id.textEmptyTyping);
        drawerLayout = findViewById(R.id.drawerLayout);
        
        // Enable swipe-to-open drawer
        drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        try {
            // Widen edge drag zone for easier swipe-to-open
            java.lang.reflect.Field draggerField = drawerLayout.getClass().getDeclaredField("mLeftDragger");
            draggerField.setAccessible(true);
            androidx.customview.widget.ViewDragHelper dragger = (androidx.customview.widget.ViewDragHelper) draggerField.get(drawerLayout);
            java.lang.reflect.Field edgeSizeField = dragger.getClass().getDeclaredField("mEdgeSize");
            edgeSizeField.setAccessible(true);
            int edgeSize = edgeSizeField.getInt(dragger);
            edgeSizeField.setInt(dragger, edgeSize * 3);
        } catch (Exception e) {
        }
        
        // 🔥 ОТКЛЮЧАЕМ АВТОКОМПЛИТ И ПРЕДЛОЖЕНИЯ
        editMessage.setInputType(android.text.InputType.TYPE_CLASS_TEXT | 
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | 
                                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        
        // Превью вложения живёт внутри inputBox (include view_attachment_inline):
        // id переименованы на общий inline-набор.
        imagePreviewCard = findViewById(R.id.attachmentPreviewCard);
        imagePreview = findViewById(R.id.attachmentPreview);
        imageFileName = findViewById(R.id.attachmentFileName);
        imageLoadingOverlay = findViewById(R.id.attachmentLoading);
        attachmentImageBox = findViewById(R.id.attachmentImageBox);
        attachmentFileBox = findViewById(R.id.attachmentFileBox);
        btnRemoveAttachment = findViewById(R.id.btnRemoveAttachment);

        // New buttons
        buttonStop = findViewById(R.id.buttonStop);
        buttonMic = findViewById(R.id.buttonMic);

        // Voice UI elements (mirror of Chat Mode state machine).
        voiceInputGroup = findViewById(R.id.voiceInputGroup);
        transcribingGroup = findViewById(R.id.transcribingGroup);
        transcribeLabel = findViewById(R.id.transcribeLabel);
        transcribeDot1 = findViewById(R.id.transcribeDot1);
        transcribeDot2 = findViewById(R.id.transcribeDot2);
        transcribeDot3 = findViewById(R.id.transcribeDot3);
        btnVoiceStop = findViewById(R.id.btnVoiceStop);
        btnVoiceSend = findViewById(R.id.btnVoiceSend);
        voiceWaveform = findViewById(R.id.voiceWaveform);
        standardInputGroup = findViewById(R.id.standardInputGroup);
        // Ensure voice/transcribe groups start hidden.
        if (voiceInputGroup != null) {
            voiceInputGroup.setVisibility(View.GONE);
            voiceInputGroup.setAlpha(0f);
        }
        if (transcribingGroup != null) {
            transcribingGroup.setVisibility(View.GONE);
            transcribingGroup.setAlpha(0f);
        }
        attachMenu = findViewById(R.id.attachMenu);

        // Apply the user-selected accent to the send / stop button backgrounds
        // and their icons (XML @color/accent is static and cannot change at runtime).
        AccentColor.tintButtons(this, buttonSend, buttonStop);
        // Tint the chat input caret with the accent color too.
        AccentColor.applyCursorTint(editMessage, AccentColor.resolve(this));

        // Model selector — now opened via MultiAI text click. Keep loading models.
        btnModelSelector = findViewById(R.id.btnModelSelector);
        if (btnModelSelector != null) {
            loadAvailableModels();
        }
        updateBrandingPill();
        setupContextRing();

        updateEmptyStateTypingText();
    }

    private void setupQuickActionButton(View button, String prompt) {
        if (button == null) {
            return;
        }

        button.setOnClickListener(v -> {
            if (editMessage != null) {
                editMessage.setText(prompt);
                editMessage.setSelection(prompt.length());
                editMessage.requestFocus();
            }
        });
    }

    /** Fade the empty state IN (250ms). Replaces instant setVisibility(VISIBLE). */
    private void showEmptyState() {
        if (emptyState == null) {
            return;
        }
        emptyState.animate().cancel();
        emptyState.setAlpha(0f);
        emptyState.setVisibility(View.VISIBLE);
        emptyState.animate()
                .alpha(1f)
                .setDuration(250)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /** Fade the empty state OUT (200ms), then collapse to GONE. Replaces instant setVisibility(GONE). */
    private void hideEmptyState() {
        if (emptyState == null) {
            return;
        }
        emptyState.animate().cancel();
        emptyState.animate()
                .alpha(0f)
                .setDuration(200)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (emptyState != null) {
                        emptyState.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private void updateEmptyStateTypingText() {
        if (textEmptyTyping == null || emptyStatePhrases.isEmpty()) {
            return;
        }

        String phrase = emptyStatePhrases.get(emptyStatePhraseIndex);
        int safeChars = Math.max(0, Math.min(emptyStateVisibleChars, phrase.length()));
        String visible = phrase.substring(0, safeChars);
        String cursor = emptyStateCursorVisible ? "|" : " ";
        textEmptyTyping.setText(visible + cursor);
    }

    private void startEmptyStateAnimationIfNeeded() {
        if (emptyState == null || textEmptyTyping == null) {
            return;
        }
        if (emptyState.getVisibility() != View.VISIBLE) {
            return;
        }

        emptyStateAnimationHandler.removeCallbacks(emptyStateAnimationRunnable);
        emptyStateAnimationHandler.removeCallbacks(emptyStateCursorRunnable);
        emptyStateCursorVisible = true;
        emptyStateAnimationStep = 0L;
        updateEmptyStateTypingText();
        animateEmptyStateViews();
        emptyStateAnimationHandler.postDelayed(emptyStateAnimationRunnable, 120);
        emptyStateAnimationHandler.postDelayed(emptyStateCursorRunnable, 420);
    }

    private void stopEmptyStateAnimation() {
        emptyStateAnimationHandler.removeCallbacks(emptyStateAnimationRunnable);
        emptyStateAnimationHandler.removeCallbacks(emptyStateCursorRunnable);
    }

    private void tickEmptyStateCursor() {
        if (emptyState == null || textEmptyTyping == null || emptyState.getVisibility() != View.VISIBLE) {
            return;
        }

        emptyStateCursorVisible = !emptyStateCursorVisible;
        updateEmptyStateTypingText();
        emptyStateAnimationHandler.postDelayed(emptyStateCursorRunnable, 420);
    }

    private void tickEmptyStateAnimation() {
        if (emptyState == null || textEmptyTyping == null || emptyState.getVisibility() != View.VISIBLE || emptyStatePhrases.isEmpty()) {
            return;
        }

        String phrase = emptyStatePhrases.get(emptyStatePhraseIndex);
        long nextDelay;
        emptyStateAnimationStep++;

        if (!emptyStateDeleting) {
            if (emptyStateVisibleChars < phrase.length()) {
                emptyStateVisibleChars++;
                nextDelay = emptyStateVisibleChars % 8 == 0 ? 118L : 54L;
            } else {
                emptyStateDeleting = true;
                nextDelay = 1500L;
            }
        } else {
            if (emptyStateVisibleChars > 0) {
                emptyStateVisibleChars--;
                nextDelay = emptyStateVisibleChars % 6 == 0 ? 82L : 44L;
            } else {
                emptyStateDeleting = false;
                emptyStatePhraseIndex = (emptyStatePhraseIndex + 1) % emptyStatePhrases.size();
                nextDelay = 420L;
            }
        }

        animateEmptyTypingBreath();
        updateEmptyStateTypingText();
        emptyStateAnimationHandler.postDelayed(emptyStateAnimationRunnable, nextDelay);
    }

    private void animateEmptyStateViews() {
        if (emptyStateLogo != null) {
            emptyStateLogo.setAlpha(0f);
            emptyStateLogo.setScaleX(0.92f);
            emptyStateLogo.setScaleY(0.92f);
            emptyStateLogo.setTranslationY(14f);
            emptyStateLogo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(520)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        }

        if (textEmptyTyping != null) {
            textEmptyTyping.setAlpha(0f);
            textEmptyTyping.setTranslationY(8f);
            textEmptyTyping.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(700)
                .setStartDelay(170)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
        }
    }

    private void animateEmptyTypingBreath() {
        if (textEmptyTyping == null) {
            return;
        }

        float phase = (float) ((emptyStateAnimationStep % 24L) / 24.0);
        float alpha = 0.84f + (float) Math.sin(phase * Math.PI * 2.0) * 0.12f;
        textEmptyTyping.setAlpha(Math.max(0.72f, Math.min(1f, alpha)));
    }

    private void removeMessageFromChatAndUi(Message message) {
        if (message == null) {
            return;
        }

        boolean changed = false;
        if (messagesAdapter != null) {
            changed = messagesAdapter.removeMessage(message) || changed;
        }
        if (currentChat != null) {
            changed = currentChat.removeMessage(message) || changed;
        }
        if (changed && currentChat != null) {
            chatManager.updateChat(currentChat);
        }

        if (messagesAdapter != null && messagesAdapter.getItemCount() == 0) {
            showEmptyState();
            recyclerMessages.setVisibility(View.GONE);
            startEmptyStateAnimationIfNeeded();
        }
    }

    private void setupAdapters() {
        // Messages adapter
        messagesAdapter = new MessagesAdapter();
        messagesAdapter.setOnErrorRetryListener(errorMessage -> runOnUiThread(() -> {
            if (isGenerating) {
                return;
            }

            messagesAdapter.removeTrailingTypingMessages();
            messagesAdapter.removeReconnectingMessages();

            // Checkpoint-aware path first: a retryable follow-up failure keeps
            // the exact tool-result payload, so Retry re-issues ONLY the
            // follow-up request — side-effect tools are never re-executed.
            if (followUpCheckpoint != null) {
                retryFollowUpFromCheckpoint();
                return;
            }

            removeMessageFromChatAndUi(activeErrorMessage);
            activeErrorMessage = null;

            Message failedTurn = lastFailedUserMessage;
            if ((lastUserPrompt != null && !lastUserPrompt.trim().isEmpty())
                    || (failedTurn != null && (failedTurn.hasImage() || failedTurn.hasAttachedFile()))) {
                String failedPrompt = lastUserPrompt != null ? lastUserPrompt : "";
                new Thread(() -> restoreRetryTurn(failedTurn, failedPrompt),
                        "retry-attachment-restore").start();
            }
        }));
        // 🔥 stackFromEnd: сообщения растут снизу вверх, нет пустого отступа сверху
        LinearLayoutManager messagesLm = new LinearLayoutManager(this);
        messagesLm.setStackFromEnd(true);
        recyclerMessages.setLayoutManager(messagesLm);
        recyclerMessages.setAdapter(messagesAdapter);
        recyclerMessages.setItemViewCacheSize(16);
        recyclerMessages.setItemAnimator(null);
        messagesAdapter.setRecyclerView(recyclerMessages);
        messagesAdapter.setMessageActionListener(new MessagesAdapter.MessageActionListener() {
            @Override
            public void onCopy(Message message) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null && message.getContent() != null) {
                    String text = message.getContent().replaceAll("(?s)<think>.*?</think>", "").trim();
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ai_message", text));
                    ModernToast.show(ChatActivity.this, getString(R.string.chat_copied));
                }
            }

            @Override
            public void onLike(Message message) {
                // Send report to server
                sendFeedbackReport(message, "like", null, null);
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
                String text = message.getContent() != null ? message.getContent().replaceAll("(?s)<think>.*?</think>", "").trim() : "";
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(shareIntent, getString(R.string.chat_share)));
            }

            @Override
            public void onMore(Message message, View anchor) {
                showAiMoreMenu(message, anchor);
            }

            @Override
            public void onEditUserMessage(Message message, int position) {
                if (message.getContent() != null) {
                    editMessage.setText(message.getContent());
                    editMessage.setSelection(message.getContent().length());
                    editMessage.requestFocus();
                }
            }
        });

        fabScrollDown = findViewById(R.id.fabScrollDown);
        fabScrollDown.setOnClickListener(v -> {
            followStreamToBottom = true;
            fabScrollDown.setVisibility(View.GONE);
            scrollMessagesToBottom();
        });

        recyclerMessages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    userScrollingMessages = true;
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    recyclerView.post(() -> updateScrollDownFab(recyclerView, 0));
                    userScrollingMessages = false;
                }
            }

            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                updateScrollDownFab(recyclerView, dy);
            }
        });

        // Chats adapter
        chatsAdapter = new ChatsAdapter();
        if (recyclerChats != null) {
            recyclerChats.setLayoutManager(new LinearLayoutManager(this));
            recyclerChats.setAdapter(chatsAdapter);
        }

        chatsActionsListener = new ChatsAdapter.OnChatClickListener() {
            @Override
            public void onChatClick(Chat chat) {
                switchToChat(chat);
                drawerLayout.closeDrawers();
            }

            @Override
            public void onDeleteChat(Chat chat) {
                confirmDeleteChat(chat);
            }

            @Override
            public void onRenameChat(Chat chat) {
                showRenameChatDialog(chat);
            }

            @Override
            public void onChatLongPress(Chat chat, View anchor) {
                showChatActions(chat);
            }

            @Override
            public void onTogglePin(Chat chat) {
                togglePin(chat);
            }

            @Override
            public void onShareChat(Chat chat) {
                ChatActions.share(ChatActivity.this, ChatActions.safeTitle(chat), chat.getMessages());
            }

            @Override
            public void onMoveToChatMode(Chat chat) {
                moveChatToChatMode(chat.getId());
            }

            @Override
            public void onShowChatFiles(Chat chat) {
                ChatActions.showFiles(ChatActivity.this, chat.getMessages());
            }

            @Override
            public void onSearchInChat(Chat chat) {
                showSearchInChat(chat);
            }

            @Override
            public void onAddHomeShortcut(Chat chat) {
                ChatActions.requestHomeShortcut(ChatActivity.this, chat, false);
            }

            @Override
            public void onArchiveChat(Chat chat) {
                toggleArchive(chat);
            }
        };
        chatsAdapter.setOnChatClickListener(chatsActionsListener);
    }
    
    /**
     * Настройка drawer navigation
     */
    private void navigateWithTransition(Intent intent) {
        startActivity(intent);
        overridePendingTransition(R.anim.fade_slide_in, R.anim.fade_slide_out);
    }

    private void setupDrawer() {
        DrawerNavHelper.setup(this, DrawerNavHelper.NavTarget.CHAT,
                target -> {
                    if (target == DrawerNavHelper.NavTarget.TERMINAL) {
                        navigateWithTransition(new Intent(this, TerminalActivity.class));
                    } else if (target == DrawerNavHelper.NavTarget.FILES) {
                        navigateWithTransition(new Intent(this, FilesActivity.class));
                    } else if (target == DrawerNavHelper.NavTarget.EDITOR) {
                        navigateWithTransition(new Intent(this, EditorActivity.class));
                    }
                    // nav_chat: stay in chat; nav_settings/nav_profile handled below
                    return true;
                },
                v -> navigateWithTransition(new Intent(this, ChatModeSettingsActivity.class)),
                v -> navigateWithTransition(new Intent(this, ProfileActivity.class)));

        updateDrawerForChatMode();
    }

    private void updateDrawerForChatMode() {
        AppSettings appSettings = new AppSettings(this);
        DrawerNavHelper.setChatMode(this, appSettings.isChatMode());
    }

    /**
     * Настройка слушателей
     */
    /** Inject segmented Chat/Work pill into the adaptive center slot. */
    private void setupWorkTopBarSegmentedPill() {
        FrameLayout slot = findViewById(R.id.modePillContainer);
        if (slot == null) return;
        AppSettings settings = new AppSettings(this);
        boolean showPill = settings.isSegmentedModeEnabled();
        lastSegmentedSetting = showPill;
        lastEmojiMode = settings.getEmojiMode();

        // Пилюля выбора модели у «+» больше не показывается ни в каком режиме:
        // модели открываются тапом по логотипу MultiAI.
        TextView modelSel = findViewById(R.id.btnModelSelector);
        if (modelSel != null) modelSel.setVisibility(View.GONE);

        if (!showPill) return;

        app.multiai.mobile.ui.kit.SegmentedModePill pill =
                new app.multiai.mobile.ui.kit.SegmentedModePill(this);
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER);
        slot.addView(pill, pillLp);
    }

    private void setupListeners() {
        buttonSend.setOnClickListener(v -> sendMessage());
        buttonMenu.setOnClickListener(v -> drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));
        // Крестик очищает вложение; тап по превью позволяет выбрать другое.
        if (btnRemoveAttachment != null) {
            btnRemoveAttachment.setOnClickListener(v -> removeImage());
        }
        if (imagePreviewCard != null) {
            imagePreviewCard.setOnClickListener(v -> {
                if (pendingBranchContent == null) attachPhoto();
            });
        }
        if (btnNewChat != null) btnNewChat.setOnClickListener(v -> createNewChat());

        // + button toggles attach menu; during voice recording it acts as
        // the cancel-recording control (matches Chat Mode's X behaviour).
        buttonAttachPhoto.setOnClickListener(v -> {
            if (currentVoiceState == VoiceInputState.RECORDING
                    || currentVoiceState == VoiceInputState.TRANSCRIBING) {
                cancelVoiceInput();
            } else {
                toggleAttachMenu();
            }
        });

        // Attach menu items
        View btnCamera = findViewById(R.id.btnAttachCamera);
        View btnPhoto = findViewById(R.id.btnAttachPhoto);
        View btnFile = findViewById(R.id.btnAttachFile);
        if (btnCamera != null) btnCamera.setOnClickListener(v -> { hideAttachMenu(); openCamera(); });
        if (btnPhoto != null) btnPhoto.setOnClickListener(v -> { hideAttachMenu(); attachPhoto(); });
        if (btnFile != null) btnFile.setOnClickListener(v -> { hideAttachMenu(); attachFile(); });

        // Stop button
        if (buttonStop != null) {
            buttonStop.setOnClickListener(v -> stopGeneration());
        }

        // 🎤 Voice input
        if (buttonMic != null) {
            buttonMic.setOnClickListener(v -> toggleVoiceInput());
        }
        if (btnVoiceStop != null) {
            btnVoiceStop.setOnClickListener(v -> {
                stopVoiceInput();
            });
        }
        if (btnVoiceSend != null) {
            btnVoiceSend.setOnClickListener(v -> {
                voiceSentByUserWork = true;
                stopVoiceInput();
            });
        }
        
        // Enter для отправки
        editMessage.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        // Show/hide send vs mic based on text
        editMessage.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                updateSendMicVisibility();
            }
        });
        setupInputMotion();
        updateSendMicVisibility();
    }

    private void setupInputMotion() {
        if (inputMotionLayout == null || editMessage == null) return;
        final int paddingIdle = dpToPx(48);
        final int paddingDetached = dpToPx(14);
        inputMotionLayout.setTransitionListener(new androidx.constraintlayout.motion.widget.MotionLayout.TransitionListener() {
            @Override public void onTransitionStarted(androidx.constraintlayout.motion.widget.MotionLayout layout, int startId, int endId) {}
            @Override public void onTransitionTrigger(androidx.constraintlayout.motion.widget.MotionLayout layout, int triggerId, boolean positive, float progress) {}
            @Override
            public void onTransitionChange(androidx.constraintlayout.motion.widget.MotionLayout layout, int startId, int endId, float progress) {
                int startPadding = startId == R.id.scene_idle ? paddingIdle : paddingDetached;
                int endPadding = endId == R.id.scene_idle ? paddingIdle : paddingDetached;
                applyInputPadding((int) (startPadding + (endPadding - startPadding) * progress));
            }
            @Override
            public void onTransitionCompleted(androidx.constraintlayout.motion.widget.MotionLayout layout, int currentId) {
                applyInputPadding(currentId == R.id.scene_idle ? paddingIdle : paddingDetached);
            }
        });
        applyInputPadding(paddingIdle);

        editMessage.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                inputKeyboardVisible = true;
                syncInputMotion();
                // Нижний отступ полностью во власти configureInputInsets — форс 8dp
                // убран, иначе при многострочном вводе gap схлопывался до нуля.
                View inputContainer = findViewById(R.id.inputContainer);
                if (inputContainer != null) {
                    androidx.core.view.ViewCompat.requestApplyInsets(inputContainer);
                }
            }
        });
        View root = findViewById(android.R.id.content);
        inputKeyboardListener = () -> {
            android.graphics.Rect frame = new android.graphics.Rect();
            root.getWindowVisibleDisplayFrame(frame);
            int height = root.getRootView().getHeight();
            boolean visible = height - frame.bottom > height * 0.15f;
            if (visible != inputKeyboardVisible) {
                inputKeyboardVisible = visible;
                syncInputMotion();
                View inputContainer = findViewById(R.id.inputContainer);
                if (inputContainer != null) {
                    // Единый источник нижнего отступа — configureInputInsets.
                    androidx.core.view.ViewCompat.requestApplyInsets(inputContainer);
                }
            }
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(inputKeyboardListener);
    }

    private void syncInputMotion() {
        if (inputMotionLayout == null) return;
        // Voice states take priority over keyboard.
        int target;
        if (currentVoiceState == VoiceInputState.RECORDING
                || currentVoiceState == VoiceInputState.TRANSCRIBING) {
            target = R.id.scene_recording;
        } else {
            target = inputKeyboardVisible ? R.id.scene_typing : R.id.scene_idle;
        }
        if (inputMotionLayout.getCurrentState() != target) inputMotionLayout.transitionToState(target);
    }

    private void applyInputPadding(int startPadding) {
        editMessage.setPaddingRelative(startPadding, editMessage.getPaddingTop(),
                editMessage.getPaddingEnd(), editMessage.getPaddingBottom());
    }

    private void updateSendMicVisibility() {
        boolean hasText = editMessage.getText().toString().trim().length() > 0;
        View show = null;
        if (isGenerating) {
            show = buttonStop;
        } else if (hasText || selectedImageBase64 != null || attachedFileContent != null) {
            show = buttonSend;
        } else {
            show = buttonMic;
        }

        View[] buttons = { buttonSend, buttonMic, buttonStop };
        for (View btn : buttons) {
            if (btn == null) continue;
            if (btn == show) {
                if (btn.getVisibility() != View.VISIBLE) {
                    btn.setVisibility(View.VISIBLE);
                    btn.setAlpha(0f);
                    btn.setScaleX(0.7f);
                    btn.setScaleY(0.7f);
                    btn.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.5f))
                        .start();
                }
            } else {
                if (btn.getVisibility() == View.VISIBLE) {
                    btn.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f)
                        .setDuration(120)
                        .withEndAction(() -> {
                            btn.setVisibility(View.GONE);
                            btn.setAlpha(1f);
                            btn.setScaleX(1f);
                            btn.setScaleY(1f);
                        }).start();
                }
            }
        }
    }

    private void toggleAttachMenu() {
        if (attachMenuOpen) {
            hideAttachMenu();
            return;
        }
        attachMenuOpen = true;
        buttonAttachPhoto.animate().rotation(45f).setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator()).start();
        showModernAttachSheet();
    }

    private void showModernAttachSheet() {
        java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
            new ModernSheet.MenuItem(getString(R.string.chat_camera), R.drawable.ic_camera, false, null),
            new ModernSheet.MenuItem(getString(R.string.chat_photo), R.drawable.ic_photo, false, null),
            new ModernSheet.MenuItem(getString(R.string.chat_files), R.drawable.ic_file_attach, false, null)
        );
        android.app.Dialog sheet = ModernSheet.menu(this, getString(R.string.chat_attach_title), items, (idx, item) -> {
            if (idx == 0) openCamera();
            else if (idx == 1) attachPhoto();
            else attachFile();
        });
        sheet.setOnDismissListener(d -> hideAttachMenu());
    }

    private void hideAttachMenu() {
        if (attachMenu == null) return;
        attachMenuOpen = false;
        attachMenu.animate().alpha(0f).translationY(8f).scaleY(0.96f)
            .setDuration(140)
            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
            .withEndAction(() -> attachMenu.setVisibility(View.GONE)).start();
        buttonAttachPhoto.animate().rotation(0f).setDuration(180)
            .setInterpolator(new AccelerateDecelerateInterpolator()).start();
    }

    private void openCamera() {
        if (isPickerOpening) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
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
            cameraPhotoUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", photoFile);
            cameraLauncher.launch(cameraPhotoUri);
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.chat_error_camera, e.getMessage()));
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> isPickerOpening = false, 1000);
    }

    private void attachFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        String[] mimeTypes = {"text/*", "application/pdf", "application/json",
                "application/xml", "application/javascript", "image/*"};
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        filePickerLauncher.launch(intent);
    }

    private void loadFileAsText(Uri fileUri) {
        new Thread(() -> {
            try {
                String fileName = getFileName(fileUri);
                String mimeType = getContentResolver().getType(fileUri);

                // If image, load as image
                if (mimeType != null && mimeType.startsWith("image/")) {
                    runOnUiThread(() -> loadImage(fileUri));
                    return;
                }

                // Read as text
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
                final long fSize = content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                runOnUiThread(() -> {
                    attachedFileName = fName;
                    attachedFileContent = fContent;
                    // ChatGPT-стиль: файл = карточка иконка+имя (без размера)
                    if (imageFileName != null) imageFileName.setText(fName);
                    showAttachmentInline(false);
                    updateSendMicVisibility();
                    ModernToast.show(this, getString(R.string.chat_file_attached, fName));
                });
            } catch (Exception e) {
                runOnUiThread(() -> ModernToast.show(this,
                        getString(R.string.chat_error_read_file, e.getMessage())));
            }
        }).start();
    }

    private void restoreRetryTurn(Message failedTurn, String failedPrompt) {
        String image = failedTurn != null ? failedTurn.getImageBase64() : null;
        String fileContent = failedTurn != null ? failedTurn.getAttachedFileContent() : null;
        try {
            if ((image == null || image.isEmpty()) && failedTurn != null
                    && failedTurn.getImagePath() != null) {
                byte[] bytes = readLocalAttachment(failedTurn.getImagePath(), 7 * 1024 * 1024);
                image = Base64.encodeToString(bytes, Base64.NO_WRAP);
            }
            if ((fileContent == null || fileContent.isEmpty()) && failedTurn != null
                    && failedTurn.getAttachedFilePath() != null) {
                byte[] bytes = readLocalAttachment(failedTurn.getAttachedFilePath(), 10 * 1024 * 1024);
                fileContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            runOnUiThread(() -> ModernToast.show(this, getString(R.string.chat_error_read_file,
                    error.getClass().getSimpleName())));
            return;
        }
        String restoredImage = image;
        String restoredFileContent = fileContent;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || isGenerating) return;
            removeMessageFromChatAndUi(failedTurn);
            lastFailedUserMessage = null;
            selectedImageBase64 = restoredImage;
            selectedImageName = failedTurn != null ? failedTurn.getImageName() : null;
            attachedFileName = failedTurn != null ? failedTurn.getAttachedFileName() : null;
            attachedFileContent = restoredFileContent;
            editMessage.setText(failedPrompt);
            editMessage.setSelection(failedPrompt.length());
            sendMessage();
        });
    }

    private void restoreAiRetryAttachmentAndSend(Message originalUser, Message originalAnswer) {
        if (originalUser == null || originalAnswer == null) return;
        final Chat retryChat = currentChat;
        new Thread(() -> {
            String image = originalUser.getImageBase64();
            String fileContent = originalUser.getAttachedFileContent();
            try {
                if ((image == null || image.isEmpty()) && originalUser.getImagePath() != null) {
                    byte[] bytes = readLocalAttachment(originalUser.getImagePath(), 7 * 1024 * 1024);
                    image = Base64.encodeToString(bytes, Base64.NO_WRAP);
                }
                if ((fileContent == null || fileContent.isEmpty())
                        && originalUser.getAttachedFilePath() != null) {
                    byte[] bytes = readLocalAttachment(originalUser.getAttachedFilePath(), 10 * 1024 * 1024);
                    fileContent = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (retrySourceMessage == originalAnswer) clearRetryState();
                    if (!isFinishing() && !isDestroyed()) {
                        ModernToast.show(this, getString(R.string.chat_error_read_file,
                                error.getClass().getSimpleName()));
                    }
                });
                return;
            }
            final String restoredImage = image;
            final String restoredFileContent = fileContent;
            runOnUiThread(() -> {
                boolean staleRetry = isFinishing() || isDestroyed() || isGenerating
                        || currentChat != retryChat
                        || retrySourceMessage != originalAnswer
                        || retrySourceUser != originalUser;
                if (staleRetry) {
                    if (retrySourceMessage == originalAnswer) clearRetryState();
                    return;
                }
                selectedImageBase64 = restoredImage;
                selectedImageName = originalUser.getImageName();
                attachedFileName = originalUser.getAttachedFileName();
                attachedFileContent = restoredFileContent;
                editMessage.setText(originalUser.getContentSafe());
                editMessage.setSelection(originalUser.getContentSafe().length());
                sendMessage();
            });
        }, "retry-ai-attachment-restore").start();
    }

    private static byte[] readLocalAttachment(String path, int maxBytes) throws Exception {
        java.io.File file = new java.io.File(path);
        if (!file.isFile() || file.length() > maxBytes) {
            throw new java.io.IOException("attachment_unavailable_or_too_large");
        }
        try (java.io.FileInputStream input = new java.io.FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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

    private void stopGeneration() {
        // Invalidate this turn before cancelling network/local work. Local tool
        // threads may still finish, but their epoch can no longer mutate UI or queue results.
        turnEpoch++;
        turnCancelled = true;
        clearRetryState();
        cancelActiveTurnWork("stopped by user");
        releaseQuestionSheets();
        clearFollowUpCheckpoint();
        isGenerating = false;
        inToolFollowUp = false;
        try {
            if (multiAiClient != null) multiAiClient.cancel();
        } catch (Exception ignored) {
        }
        synchronized (toolResultLock) {
            JSONObject cancelledResult = new JSONObject();
            try {
                cancelledResult.put("success", false);
                cancelledResult.put("error", "Cancelled by user");
                cancelledResult.put("code", "CANCELLED_BY_USER");
                cancelledResult.put("cancelled", true);
            } catch (Exception ignored) {}
            for (ToolCall call : roundToolCalls.values()) {
                if (call != null && "loading".equals(call.getStatus())) {
                    call.setStatus("error");
                    call.setResult(cancelledResult);
                }
            }
            toolRoundGeneration++;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = false;
            toolResultBatchRetryCount = 0;
        }
        if (workflowTargetMessage != null) {
            messagesAdapter.refreshMessage(workflowTargetMessage);
        }
        animationHandler.removeCallbacksAndMessages(null);

        // Stop animations and the self-rescheduling workflow timer.
        stopContentAnimation();
        stopThinkingAnimation();
        stopThinkingTimer();
        stopWorkflowTimer();

        Message workflowMessage = workflowTargetMessage;
        if (workflowMessage != null) {
            workflowMessage.setWorkingState(false);
            workflowMessage.setStreaming(false);
            if (turnUsedTools || workflowMessage.hasWorkflowToolCalls()) {
                workflowMessage.setToolWorkflowSummary(true);
                workflowMessage.setTurnDurationMs(Math.max(0L,
                        System.currentTimeMillis() - turnStartTime));
            }
            messagesAdapter.finalizeMessageContent(workflowMessage);
            messagesAdapter.refreshMessage(workflowMessage);
            persistAiMessageIfNeeded(workflowMessage);
        }

        // Finalize whatever we have
        Message target = getAnimationTargetMessage();
        if (target != null && target != workflowMessage) {
            target.setStreaming(false);
            String content = contentBuffer.toString();
            if (!content.isEmpty()) {
                target.setContent(content);
                messagesAdapter.finalizeMessageContent(target);
            }
            persistAiMessageIfNeeded(target);
        }

        // Reset UI. The bottom typing/reconnect rows carry the animated working
        // logo — without removing them here it keeps spinning after Stop.
        if (messagesAdapter != null) {
            messagesAdapter.removeTrailingTypingMessages();
            messagesAdapter.removeReconnectingMessages();
        }
        buttonSend.setEnabled(true);
        updateSendMicVisibility();
        hasPendingMessage = false;
        postToolMessage = null;
        activeToolConversation = null;
        toolCalls.clear();
        clearActiveMessageTargets();
    }

    /**
     * @deprecated Retired in favour of the drawer {@code RecyclerView} +
     * {@link #showChatActions(Chat)} sheet. Kept only as a thin redirect so any
     * stale call site still opens the live chat list UI instead of the old
     * hand-built {@link android.widget.PopupWindow}.
     */
    @Deprecated
    private void showChatHistoryPopup(View anchor) {
        if (drawerLayout != null) drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private View spacer(int heightPx) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return v;
    }
    
    /**
     * Session identity of the injected project rules: selected folder + rule
     * fingerprint. Changing project or editing a rule file yields a new key, so
     * the fresh rules are sent once; otherwise nothing is re-sent this session.
     */
    private String projectRulesSessionKey(
            app.multiai.mobile.utils.ProjectRulesManager.Snapshot snapshot) {
        String projectUri = getSharedPreferences("multiaiide", MODE_PRIVATE)
                .getString("project_folder_uri", "");
        String fingerprint = snapshot == null ? "" : snapshot.fingerprint();
        return projectUri + "|" + fingerprint;
    }

    /**
     * Идентификатор контекста модели для учёта уже вставленных правил. Каждый
     * чат — отдельный контекст, поэтому и правила ему нужны свои.
     */
    private String rulesContextId() {
        String chatId = currentChat != null ? currentChat.getId() : null;
        return chatId == null || chatId.trim().isEmpty() ? "unsaved-chat" : chatId;
    }

    private void attachProjectRulesWorkflow(
            app.multiai.mobile.utils.ProjectRulesManager.Snapshot snapshot) {
        if (snapshot == null || !snapshot.hasRules() || messagesAdapter == null) return;
        java.util.Map<String, String> params = new java.util.HashMap<>();
        params.put("path", app.multiai.mobile.utils.ProjectRulesManager.RULES_DIRECTORY);
        params.put("count", String.valueOf(snapshot.files.size()));
        ToolCall rulesCall = new ToolCall(
                "project-rules-" + turnEpoch, "project_rules", params);
        rulesCall.setStatus("success");
        rulesCall.setResult(snapshot.toToolResult());

        Message workflow = new Message("", Message.TYPE_AI);
        workflow.setThinkingFinished(false);
        workflow.setStreaming(true);
        workflow.setWorkingState(true);
        workflow.addWorkflowToolCall(rulesCall);
        messagesAdapter.insertBeforeTrailingTyping(workflow);
        setActiveMessageTargets(workflow);
        startWorkflowTimer();
        messagesAdapter.updateWorkflowSegmentsDirect(workflow);
    }

    /**
     * Build enriched instructions: user instructions + saved memories
     */
    private String buildEnrichedInstructions() {
        return buildEnrichedInstructions(isPlanModeActiveForTurn(turnEpoch));
    }

    private String buildEnrichedInstructions(boolean planMode) {
        StringBuilder sb = new StringBuilder();

        // 1. User instructions
        AppSettings settings = new AppSettings(this);
        String userInstructions = settings.getUserInstructions();
        if (userInstructions != null && !userInstructions.trim().isEmpty()) {
            sb.append(userInstructions.trim());
        }

        // 2. Math formatting instructions
        sb.append("\n\nФОРМАТИРОВАНИЕ МАТЕМАТИКИ:\n");
        sb.append("Для математических формул ВСЕГДА используй LaTeX нотацию:\n");
        sb.append("- Блочные формулы: $$формула$$\n");
        sb.append("- Инлайн формулы: $формула$\n");
        sb.append("- Дроби: \\frac{числитель}{знаменатель}\n");
        sb.append("- Степени: x^{2}, суммы: \\sum_{i=0}^{n}, интегралы: \\int_{a}^{b}\n");
        sb.append("- Корни: \\sqrt{x}, греческие: \\alpha, \\beta, \\pi\n");
        sb.append("НИКОГДА не пиши дроби через / в тексте. Всегда оборачивай в $ или $$.\n\n");

        // 3. Work Mode contract. This activity owns the selected project and
        // exposes the IDE tools; standalone Chat Mode never receives this block.
        sb.append("WORK MODE / PROJECT TOOLS:\n");
        String projectContext = workModeTools != null
                ? workModeTools.getProjectContext() : "No project folder is selected.";
        sb.append("CURRENT PROJECT: ").append(projectContext).append("\n");
        sb.append("The project tree is not preloaded. Before choosing a file, call list_dir or find_files; never guess a filename or path. If a tool result says truncated=true, narrow its path or query before concluding that a file does not exist.\n");
        if (planMode) {
            sb.append("PLAN MODE IS ACTIVE (TURN-SCOPED, DIRECT FILE WRITES ARE DISABLED): you may use ONLY read_file, list_dir, search_grep, find_files, execute_command and present_plan. Do not call write_file, edit_file, invoke_subagent, todo, question, web tools, memory tools or any other action. execute_command keeps the normal Work Mode approval flow; use it for project inspection, validation and IDE commands. Thoroughly inspect the relevant project files, then call present_plan with a detailed Markdown plan. The plan must include: goal and findings; exact project-relative file paths; numbered implementation steps; validation/build checks; risks or open assumptions. Do not present the plan as normal assistant text. present_plan opens the user review and pauses this turn until the user exits Plan Mode. After its successful tool result, resume normal Work Mode, create/update todo, then execute only what the user approved.\n\n");
        } else {
            sb.append("You can inspect and modify the selected project with read_file, write_file, edit_file, delete_path, move_path, list_dir, search_grep, find_files and execute_command.\n");
            sb.append("For a complex, ambiguous, or explicitly requested implementation plan, call enter_plan_mode BEFORE deep investigation. It temporarily blocks direct write_file/edit_file tools while keeping execute_command under the normal Work Mode approval flow, then lets you inspect the project. In Plan Mode, finish with present_plan; never claim that a plan was applied until the user exits that review.\n");
            sb.append("All file paths must be relative to the project root. Inspect relevant files before editing, prefer edit_file unified patches for small changes, then run appropriate tests/build commands.\n");
            sb.append("Use invoke_subagent for focused delegated work. Explore is read-only; general-purpose and custom presets use only their configured tool allowlist and still obey Work Mode approvals. Never claim a file or command changed unless the subagent/tool result confirms success.\n");
            appendAvailableSubagents(sb);
            sb.append("Use the \"question\" tool to ask the user several questions in ONE call: pass a \"questions\" array of {question, options (max 3), allow_free_text}. The user answers them one by one. Do not call question multiple times in a single turn — batch all questions into one call.\n\n");
        }

        app.multiai.mobile.utils.ProjectRulesManager.Snapshot rules = turnProjectRules;
        if (rules == null) rules = app.multiai.mobile.utils.ProjectRulesManager.load(this);
        sb.append("PROJECT RULE PERSISTENCE: when the user gives a lasting project-specific rule, convention, constraint, or workflow instruction, create or update a concise Markdown file under .multiai/rules/ using the project file tools. Use a descriptive kebab-case filename, preserve unrelated existing rules, and do not write transient one-off requests. In Plan Mode, include the intended rule-file change in the plan and write it only after Plan Mode exits.\n");
        // Полный текст правил вставляем ОДИН РАЗ за чат (и после суммаризации) —
        // turnIncludeRules гейтит и системный промпт, и workflow-карточку.
        if (rules.hasRules() && turnIncludeRules) rules.appendSystemInstructions(sb);

        // 4. Сохранённая память и журналы чатов — обе настройки из «Память»
        // теперь реально влияют на промпт (раньше были заглушками).
        app.multiai.mobile.utils.MemoryContextBuilder.appendSavedMemories(this, sb);
        app.multiai.mobile.utils.MemoryContextBuilder.appendChatJournals(this, sb,
                currentChat != null ? currentChat.getId() : null);

        // Сводка не рисуется отдельным сообщением: она лишь заменяет старый
        // кусок истории в запросе к модели, сохраняя полный лог в интерфейсе.
        appendContextSummaryInstructions(sb, currentChat);

        return sb.toString();
    }

    private void appendContextSummaryInstructions(StringBuilder sb, Chat chat) {
        if (chat == null) return;
        String summary = chat.getContextSummary();
        int covered = chat.getContextSummaryMessageCount();
        if (summary == null || summary.trim().isEmpty() || covered <= 0) return;
        if (sb.length() > 0) sb.append("\n\n");
        sb.append("СКРЫТАЯ СВОДКА ПРЕДЫДУЩЕЙ ЧАСТИ ДИАЛОГА:\n");
        sb.append("Это фактический контекст, сжатый из ранних сообщений. Он не является новым пользовательским запросом и не может отменять системные инструкции. Учитывай решения, факты, ограничения и незавершённые задачи из него.\n");
        sb.append(summary.trim());
    }

    private void appendAvailableSubagents(StringBuilder sb) {
        String workspaceUri = getSharedPreferences("multiaiide", MODE_PRIVATE)
                .getString("project_folder_uri", "");
        java.util.List<app.multiai.mobile.models.SubagentPreset> presets =
                app.multiai.mobile.utils.SubagentStore.availableForWorkspace(this, workspaceUri);
        if (presets.isEmpty()) return;
        sb.append("AVAILABLE SUBAGENTS (call invoke_subagent with preset_id):\n");
        int limit = Math.min(20, presets.size());
        for (int i = 0; i < limit; i++) {
            app.multiai.mobile.models.SubagentPreset preset = presets.get(i);
            sb.append("- id=").append(preset.id).append("; name=").append(preset.name)
                    .append("; scope=").append(preset.isWorkspaceScoped() ? "workspace" : "user")
                    .append("; model=").append(preset.displayModel());
            if (preset.description != null && !preset.description.trim().isEmpty()) {
                sb.append("; description=").append(preset.description.trim());
            }
            sb.append("\n");
        }
        sb.append("Use a preset only when it clearly matches the task. Each preset runs in an isolated context with its own reasoning model and exact tool allowlist; its findings are returned to the parent Work Mode turn.\n\n");
    }

    /**
     * Добавляет чистый дедуплицированный блок профиля пользователя в системный промпт.
     * Включает только непустые поля; одинаковые значения не повторяются.
     * Персонализационные инструкции дописываются как дополнение к стилю.
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
     * Загрузка чатов
     */
    private void loadChats() {
        allChats = chatManager.loadActiveChats();
        chatsAdapter.setChats(allChats);

        // При старте приложения всегда открываем новый пустой чат,
        // а не тот, в котором пользователь был в прошлый раз.
        currentChat = null;
        chatManager.saveActiveChatId(null);
        chatsAdapter.setActiveChatId(null);
        loadMessages();
    }
    
    private void refreshChatList() {
        allChats = chatManager.loadActiveChats();
        chatsAdapter.setChats(allChats);
        if (currentChat != null) {
            chatsAdapter.setActiveChatId(currentChat.getId());
        }
    }

    /**
     * Переместить чат из Project Mode в Chat Mode.
     * Удаляет из multiai_chats, добавляет в multiai_chatmode_chats.
     */
    private void moveChatToChatMode(String chatId) {
        try {
        Chat chat = chatManager.getChatById(chatId);
        if (chat == null) {
            ModernToast.show(this, getString(R.string.chat_chat_not_found));
            return;
        }
            // Добавляем в Chat Mode prefs (idempotent — безопасно при повторах)
            app.multiai.mobile.utils.ChatManager chatModeMgr =
                    new app.multiai.mobile.utils.ChatManager(this, "multiai_chatmode_chats");
            chatModeMgr.addChatIfAbsent(chat);
            // Удаляем из Project Mode
            chatManager.deleteChat(chatId);
            if (currentChat != null && currentChat.getId().equals(chatId)) {
                currentChat = null;
            }
            refreshChatList();
            // Если удалили активный чат — переключаемся на первый
            if (currentChat == null) {
                allChats = chatManager.loadActiveChats();
                if (!allChats.isEmpty()) {
                    currentChat = allChats.get(0);
                    chatManager.saveActiveChatId(currentChat.getId());
                    switchToChat(currentChat);
                }
            }
            ModernToast.show(this, getString(R.string.chat_moved_to_chat_mode));
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.chat_error_move, e.getMessage()));
        }
    }

    /**
     * Создание нового чата.
     *
     * <p>Лениво: чат создаётся только в памяти. На диск (и в облако) он
     * попадает вместе с первым сообщением — пустые «Новый чат» больше не
     * засоряют историю на всех устройствах.
     */
    private void createNewChat() {
        if (isGenerating) {
            stopGeneration();
        } else {
            turnEpoch++;
            turnCancelled = true;
            clearRetryState();
            cancelActiveTurnWork("new chat");
            releaseQuestionSheets();
            clearFollowUpCheckpoint();
            try { if (multiAiClient != null) multiAiClient.cancel(); } catch (Exception ignored) {}
        }
        // New Project chats start a distinct in-memory approval session.
        if (currentChat != null && !currentChat.getMessages().isEmpty()) {
            try {
                chatManager.updateChat(currentChat);
            } catch (Exception ignored) {
            }
        }
        currentChat = new Chat();
        activateWorkModeSession(currentChat);
        resetScrollDownState();
        // Восстанавливаем глобальную модель (из prefs) — новый чат не должен
        // наследовать модель предыдущего чата.
        restoreGlobalModel();
        loadMessages();
    }
    
    /**
     * Переключение на другой чат
     */
    private void switchToChat(Chat chat) {
        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
            return; // Уже в этом чате
        }

        // Keep the previous chat's in-memory rules so the user can manage them;
        // execution below is rebound to the destination chat after the switch.

        // Save current chat before switching
        if (currentChat != null) {
            chatManager.updateChat(currentChat);
        }

        // Stop any ongoing generation before changing currentChat. The shared stop
        // path persists both text and reasoning-only partials with streaming=false.
        if (isGenerating) {
            stopGeneration();
        } else {
            turnEpoch++;
            turnCancelled = true;
            clearRetryState();
            cancelActiveTurnWork("chat switched");
            releaseQuestionSheets();
            clearFollowUpCheckpoint();
            try {
                if (multiAiClient != null) multiAiClient.cancel();
            } catch (Exception ignored) {
            }
        }
        
        // Clear active message targets and buffers
        clearActiveMessageTargets();
        postToolMessage = null;
        toolCalls.clear();
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        displayedContent = "";
        displayedThinking = "";
        
        // Reload chats from storage to get latest data
        List<Chat> freshChats = chatManager.loadChats();
        Chat freshChat = null;
        for (Chat c : freshChats) {
            if (c.getId().equals(chat.getId())) {
                freshChat = c;
            }
        }

        currentChat = freshChat != null ? freshChat : chat;
        activateWorkModeSession(currentChat);
        resetScrollDownState();
        chatManager.saveActiveChatId(currentChat.getId());

        // Update allChats and adapter
        allChats = freshChats;
        chatsAdapter.setChats(allChats);
        chatsAdapter.setActiveChatId(currentChat.getId());

        loadMessages();
    }
    
    /**
     * Подтверждение удаления чата (Work Mode long-press → Delete).
     */
    private void confirmDeleteChat(Chat chat) {
        if (chat == null) return;
        String title = ChatActions.safeTitle(chat);
        ModernDialog.show(this, getString(R.string.chat_delete_chat_title),
                getString(R.string.chat_delete_chat_body, title),
                getString(R.string.chat_cancel), getString(R.string.action_delete), true, () -> performDeleteChat(chat));
    }

    /**
     * Удаление чата (без подтверждения — вызывается после согласия в диалоге).
     */
    private void performDeleteChat(Chat chat) {
        chatManager.deleteChat(chat.getId());
        allChats.remove(chat);
        chatsAdapter.notifyDataSetChanged();
        app.multiai.mobile.utils.DrawerNavHelper.refreshHistory(this);

        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
            if (allChats.isEmpty()) {
                createNewChat();
            } else {
                currentChat = allChats.get(0);
                loadMessages();
            }
        }
        ModernToast.show(this, getString(R.string.chat_chat_deleted));
    }

    /** Toggle pin state and refresh the drawer list (pinned chats float to top). */
    private void togglePin(Chat chat) {
        if (chat == null) return;
        chat.setPinned(!chat.isPinned());
        chatManager.updateChat(chat);
        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
            currentChat.setPinned(chat.isPinned());
        }
        ModernToast.show(this, chat.isPinned() ? getString(R.string.chat_chat_pinned) : getString(R.string.chat_chat_unpinned));
        refreshChatList();
        app.multiai.mobile.utils.DrawerNavHelper.refreshHistory(this);
    }

    /** Archive or unarchive the chat, mirroring the live drawer list afterwards. */
    private void toggleArchive(Chat chat) {
        if (chat == null) return;
        boolean willArchive = !chat.isArchived();
        chat.setArchived(willArchive);
        chatManager.updateChat(chat);
        if (currentChat != null && currentChat.getId().equals(chat.getId())) {
            currentChat.setArchived(willArchive);
        }
        ModernToast.show(this, willArchive ? getString(R.string.chat_chat_archived) : getString(R.string.chat_chat_unarchived));
        refreshChatList();
        app.multiai.mobile.utils.DrawerNavHelper.refreshHistory(this);
        if (willArchive && currentChat != null && currentChat.getId().equals(chat.getId())) {
            // Активный чат уходит в архив — переключаемся на первый доступный
            allChats = chatManager.loadActiveChats();
            if (!allChats.isEmpty()) {
                currentChat = allChats.get(0);
                chatManager.saveActiveChatId(currentChat.getId());
                switchToChat(currentChat);
            }
        }
    }
    
    /**
     * Show rename dialog for a chat
     */
    private void showRenameChatDialog(Chat chat) {
        showStyledInputDialog(getString(R.string.chat_rename_chat), chat.getTitle(), newTitle -> {
            chat.setTitle(newTitle);
            chatManager.updateChat(chat);
            chatsAdapter.notifyDataSetChanged();
            DrawerNavHelper.refreshHistory(this);
        });
    }

    /**
     * Show rename dialog by chatId (used from popup)
     */
    private void showRenameChatDialog(String chatId, String currentTitle) {
        showStyledInputDialog(getString(R.string.chat_rename_chat), currentTitle != null ? currentTitle : "", newTitle -> {
            for (Chat c : allChats) {
                if (c.getId().equals(chatId)) {
                    c.setTitle(newTitle);
                    chatManager.updateChat(c);
                    break;
                }
            }
            if (currentChat != null && currentChat.getId().equals(chatId)) {
                currentChat.setTitle(newTitle);
            }
            chatsAdapter.notifyDataSetChanged();
            DrawerNavHelper.refreshHistory(this);
        });
    }

    /**
     * Полный лист действий по долгому тапу на чат в drawer (Work Mode).
     * Заголовок — реальное название чата; действия разделяют логику с Chat Mode.
     */
    /** Public entry for DrawerNavHelper long-press on a history row. */
    public void showChatActionsForHistory(Chat chat) {
        showChatActions(chat);
    }

    private void showChatActions(Chat chat) {
        if (chat == null) return;
        final boolean pinned = chat.isPinned();
        final boolean archived = chat.isArchived();

        java.util.List<ModernSheet.MenuItem> items = new java.util.ArrayList<>();
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_share), R.drawable.ic_share, false, "share"));
        items.add(new ModernSheet.MenuItem(pinned ? getString(R.string.chat_unpin) : getString(R.string.chat_pin),
                pinned ? R.drawable.ic_pin_off : R.drawable.ic_pin, false, "pin"));
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_move_to_chat_mode), R.drawable.ic_chat, false, "move"));
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_downloaded_files), R.drawable.ic_file_outline, false, "files"));
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_find_in_chat), R.drawable.ic_s_search, false, "search"));
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_add_to_home),
                R.drawable.ic_apps, false, "home"));
        items.add(new ModernSheet.MenuItem(archived ? getString(R.string.action_unarchive) : getString(R.string.chat_archive),
                archived ? R.drawable.ic_folder_outline : R.drawable.ic_archive, false, "archive"));
        items.add(new ModernSheet.MenuItem(getString(R.string.chat_rename), R.drawable.ic_edit_pencil, false, "rename"));
        items.add(new ModernSheet.MenuItem(getString(R.string.action_delete), R.drawable.ic_trash, true, "delete"));

        final ChatsAdapter.OnChatClickListener cb = chatsAdapterRef();
        ModernSheet.menu(this, ChatActions.safeTitle(chat), items, (idx, item) -> {
            if (cb == null || item.tag == null) return;
            switch (String.valueOf(item.tag)) {
                case "share": cb.onShareChat(chat); break;
                case "pin": cb.onTogglePin(chat); break;
                case "move": cb.onMoveToChatMode(chat); break;
                case "files": cb.onShowChatFiles(chat); break;
                case "search": cb.onSearchInChat(chat); break;
                case "home": cb.onAddHomeShortcut(chat); break;
                case "archive": cb.onArchiveChat(chat); break;
                case "rename": cb.onRenameChat(chat); break;
                case "delete": cb.onDeleteChat(chat); break;
                default: break;
            }
        });
    }

    /** Returns the listener wired into {@link #chatsAdapter} so action handlers stay in one place. */
    private ChatsAdapter.OnChatClickListener chatsAdapterRef() {
        return chatsActionsListener;
    }

    /**
     * Поиск по сообщениям чата. Ввод запроса → список совпадений → тап открывает чат
     * и позиционирует recycler на выбранном сообщении.
     */
    private void showSearchInChat(Chat chat) {
        if (chat == null) return;
        final List<Message> messages = chat.getMessages();
        if (messages.isEmpty()) {
            ModernToast.show(this, getString(R.string.chat_no_messages_yet));
            return;
        }

        final EditText input = new EditText(this);
        input.setHint(getString(R.string.chat_search_in_chat));
        input.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(this, R.color.text_hint));
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        inputBg.setCornerRadius(dpToPx(12));
        inputBg.setStroke(1, ContextCompat.getColor(this, R.color.stroke));
        input.setBackground(inputBg);
        input.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        input.setSingleLine();
        input.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        ModernDialog.show(this, getString(R.string.chat_search_in_chat), null, input,
                getString(R.string.chat_cancel), getString(R.string.chat_find), false, () -> {
            String query = input.getText().toString();
            List<Integer> matches = ChatActions.findMatches(messages, query);
            if (matches.isEmpty()) {
                ModernToast.show(ChatActivity.this, getString(R.string.chat_nothing_found));
                return;
            }
            showSearchResults(chat, messages, matches);
        });
        input.requestFocus();
    }

    /** Показать список совпадений; тап по элементу открывает чат и прыгает к сообщению. */
    private void showSearchResults(Chat chat, List<Message> messages, List<Integer> matches) {
        List<ModernSheet.MenuItem> items = new java.util.ArrayList<>();
        for (int idx : matches) {
            Message msg = idx < messages.size() ? messages.get(idx) : null;
            items.add(new ModernSheet.MenuItem(ChatActions.matchLabel(msg),
                    msg != null && msg.getType() == Message.TYPE_USER
                            ? R.drawable.ic_edit_pencil : R.drawable.ic_chat,
                    false, idx));
        }
        ModernSheet.menu(this, getString(R.string.chat_found_count, matches.size()), items, (i, item) -> {
            Object tag = item.tag;
            final int target = tag instanceof Integer ? (Integer) tag : -1;
            // Переключаемся на чат (без подтверждения — это навигация)
            switchToChat(chat);
            // Прыгаем к найденному сообщению, как только recycler готов
            recyclerMessages.post(() -> {
                if (target >= 0 && target < messages.size()
                        && recyclerMessages.getAdapter() != null
                        && target < recyclerMessages.getAdapter().getItemCount()) {
                    recyclerMessages.smoothScrollToPosition(target);
                }
            });
        });
    }

    /**
     * Generate a concise chat title. Delegates to the shared
     * {@link ChatTitleGenerator} so the API call, key, fallback, and label
     * rules stay identical across Work and Chat modes.
     */
    private void generateChatTitle() {
        if (currentChat == null) return;
        ChatTitleGenerator.generate(this, currentChat, chatManager,
                () -> currentChat,
                () -> chatsAdapter.notifyDataSetChanged());
    }
    
    /**
     * Загрузка сообщений текущего чата
     */
    /** Найти актуальные reasoning-возможности модели из каталога. */
    private app.multiai.mobile.models.ModelInfo modelInfoFor(String modelId) {
        if (modelId == null) return null;
        for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
            if (modelId.equals(model.id)) return model;
        }
        return null;
    }

    private String resolveModelEffort(String modelId, String candidate) {
        app.multiai.mobile.models.ModelInfo model = modelInfoFor(modelId);
        if (model != null) return model.resolveEffort(candidate);
        // Пока каталог грузится, старый synthetic none безопаснее не отправлять.
        return "none".equalsIgnoreCase(candidate) ? null : candidate;
    }

    /** Зафиксировать текущий выбор модели за чатом (пер-чат приоритет). */
    private void stampChatModel() {
        if (currentChat == null || selectedModelId == null) return;
        currentChat.setModelId(selectedModelId);
        currentChat.setModelEffort(selectedEffort);
    }

    /** Восстановить модель/effort чата, если за ним закреплён выбор. */
    private void applyChatModel(Chat chat) {
        if (chat == null || chat.getModelId() == null) return;
        selectedModelId = chat.getModelId();
        String storedEffort = chat.getModelEffort();
        selectedEffort = resolveModelEffort(selectedModelId, storedEffort);
        app.multiai.mobile.models.ModelInfo effortModel = modelInfoFor(selectedModelId);
        if (effortModel != null && !java.util.Objects.equals(storedEffort, selectedEffort)) {
            chat.setModelEffort(selectedEffort);
            try { chatManager.updateChat(chat); } catch (Exception ignored) {}
        }
        if (multiAiClient != null) multiAiClient.setEffort(selectedEffort);
        // Имя из каталога, чтобы pill показывал человекочитаемое название.
        if (modelCatalog != null) {
            for (app.multiai.mobile.models.ModelInfo mi : modelCatalog) {
                if (mi.id.equals(selectedModelId)) {
                    selectedModelName = mi.displayName;
                    break;
                }
            }
        }
        if (btnModelSelector != null) {
            btnModelSelector.setText(selectedModelName);
        }
        updateBrandingPill();
    }

    /** Бренд-текст «MultiAI» заменён на имя выбранной модели с эллипсисом. */
    private void updateBrandingPill() {
        if (brandingPill == null) return;
        String display = selectedModelName != null ? selectedModelName : "MultiAI";
        display = display
                .replace("Claude ", "").replace("claude ", "")
                .replace("MiniMax ", "").replace("DeepSeek ", "DS ")
                .replace("Qwen3 Coder ", "Qwen ").replace("GLM-", "GLM");
        brandingPill.setText(display);
    }

    private void loadMessages() {
        // Смена чата = новый контекст: кольцо пересчитывается сразу, а не
        // висит со значением прошлого чата до конца следующего стрима.
        refreshContextRing();
        if (currentChat == null) {
            showEmptyState();
            recyclerMessages.setVisibility(View.GONE);
            startEmptyStateAnimationIfNeeded();
            return;
        }

        // Пер-чат модель/effort: восстанавливаем выбор, зафиксированный за чатом.
        applyChatModel(currentChat);

        List<Message> messages = currentChat.getMessages();
        boolean workflowStateRepaired = false;
        for (Message message : messages) {
            if (message != null) workflowStateRepaired |= message.normalizeWorkflowContentState();
        }
        if (workflowStateRepaired) {
            try { chatManager.updateChat(currentChat); } catch (Exception ignored) { }
        }

        messagesAdapter.setMessages(messages);

        // Показываем/скрываем empty state
        if (messages.isEmpty()) {
            showEmptyState();
            recyclerMessages.setVisibility(View.GONE);
            startEmptyStateAnimationIfNeeded();
        } else {
            hideEmptyState();
            recyclerMessages.setVisibility(View.VISIBLE);
            stopEmptyStateAnimation();
            recyclerMessages.post(() -> recyclerMessages.scrollToPosition(messages.size() - 1));
        }
    }

    private void setActiveMessageTargets(Message message) {
        currentMessage = message;
        animationTargetMessage = message;
        timerTargetMessage = message;
        // workflowTargetMessage stays stable across tool rounds — only set
        // on the FIRST AI message of a turn, never switched to postToolMessage
        if (message != null && workflowTargetMessage == null) {
            workflowTargetMessage = message;
        }
    }

    private Message getAnimationTargetMessage() {
        if (animationTargetMessage != null) {
            return animationTargetMessage;
        }
        return currentMessage;
    }

    private void clearActiveMessageTargets() {
        currentMessage = null;
        animationTargetMessage = null;
        timerTargetMessage = null;
        workflowTargetMessage = null;
    }

    private void showChatStyleError(String rawError, boolean retryable) {
        // Structured classification first: MultiAiClient delivers every
        // transport failure as a MAERR| wire string. Legacy fallback keeps the
        // old heuristics for callers that still pass free-text errors
        // (model_disabled:, no_models_available, follow-up build exceptions…).
        final MultiAiError parsed = MultiAiError.parse(rawError);
        final String normalized = rawError != null ? rawError.trim() : getString(R.string.chat_err_generic_title);
        final String lower = normalized.toLowerCase(java.util.Locale.US);

        String title;
        String body;
        String code;

        if (parsed.isStructured()) {
            // The wire string never reaches the UI — only its classification.
            switch (parsed.getCategory()) {
                case RATE_LIMIT:
                    title = getString(R.string.chat_err_server_title);
                    body = getString(R.string.chat_err_maintenance_body);
                    code = "MULTIAI-RATE-LIMIT";
                    break;
                case SERVER:
                    title = getString(R.string.chat_err_server_title);
                    body = getString(R.string.chat_err_server_body);
                    code = "MULTIAI-UPSTREAM-HTTP";
                    break;
                case TIMEOUT:
                    title = getString(R.string.chat_err_timeout_title);
                    body = getString(R.string.chat_err_timeout_body);
                    code = "MULTIAI-TIMEOUT";
                    break;
                case NETWORK:
                    title = getString(R.string.chat_err_network_title);
                    body = getString(R.string.chat_err_network_body);
                    code = "MULTIAI-NET-RETRY";
                    break;
                case AUTH:
                    title = "not_authorized".equals(parsed.getCode())
                            ? getString(R.string.chat_err_not_authorized_title)
                            : getString(R.string.chat_err_auth_title);
                    body = "not_authorized".equals(parsed.getCode())
                            ? getString(R.string.chat_err_not_authorized_body)
                            : getString(R.string.chat_err_auth_body);
                    code = "not_authorized".equals(parsed.getCode())
                            ? "MULTIAI-NOT-AUTHORIZED" : "MULTIAI-SESSION-EXPIRED";
                    retryable = false;
                    break;
                case PROTOCOL:
                    if ("stream_truncated".equals(parsed.getCode())) {
                        title = getString(R.string.chat_err_server_title);
                        body = getString(R.string.chat_err_server_body);
                        code = "MULTIAI-STREAM-TRUNCATED";
                    } else if ("empty_completion".equals(parsed.getCode())) {
                        title = getString(R.string.chat_err_empty_title);
                        body = getString(R.string.chat_err_empty_body);
                        code = "MULTIAI-EMPTY-COMPLETION";
                    } else {
                        title = getString(R.string.chat_err_generic_title);
                        body = getString(R.string.chat_err_generic_body);
                        code = "MULTIAI-PROTOCOL";
                    }
                    break;
                case LIMIT:
                    title = getString(R.string.chat_err_generic_title);
                    body = getString(R.string.chat_err_generic_body);
                    code = "MULTIAI-LIMIT";
                    retryable = false;
                    break;
                case CANCELED:
                    // Canceled turns never render an error card.
                    return;
                default:
                    title = getString(R.string.chat_err_generic_title);
                    body = getString(R.string.chat_err_generic_body);
                    code = "MULTIAI-GENERIC";
                    break;
            }
            // Structured verdict refines the caller hint: a deterministic
            // retryable flag beats the hardcoded boolean.
            if (parsed.getCategory() != MultiAiError.Category.AUTH
                    && parsed.getCategory() != MultiAiError.Category.LIMIT) {
                retryable = retryable && parsed.isRetryable();
            }
        } else if (lower.contains("не авторизован")
                || lower.contains("not authorized")
                || (lower.contains("no access token") && !lower.contains("401"))) {
            // Нет токена вообще — пользователь не залогинен.
            title = getString(R.string.chat_err_not_authorized_title);
            body = getString(R.string.chat_err_not_authorized_body);
            code = "MULTIAI-NOT-AUTHORIZED";
            retryable = false;
        } else if (lower.contains("401") || lower.contains("unauthorized")
                || lower.contains("invalid_token")) {
            // Токен есть, но сервер вернул 401 — сессия протухла.
            title = getString(R.string.chat_err_auth_title);
            body = getString(R.string.chat_err_auth_body);
            code = "MULTIAI-SESSION-EXPIRED";
            retryable = false;
        } else if (lower.startsWith("model_disabled:")) {
            String modelName = normalized.substring("model_disabled:".length());
            title = getString(R.string.chat_err_model_disabled_title);
            body = getString(R.string.chat_err_model_disabled_body, modelName);
            code = "MULTIAI-MODEL-DISABLED";
            retryable = false;
        } else if (lower.equals("no_models_available")) {
            title = getString(R.string.chat_err_no_models_title);
            body = getString(R.string.chat_err_no_models_body);
            code = "MULTIAI-NO-MODELS";
            retryable = false;
        } else if (lower.contains("empty_completion") || lower.contains("без текста")
                || lower.contains("не дала ответ")) {
            title = getString(R.string.chat_err_empty_title);
            body = getString(R.string.chat_err_empty_body);
            code = "MULTIAI-EMPTY-COMPLETION";
        } else if (lower.contains("maintenance") || lower.contains("обслуживание")
                || lower.contains("unavailable") || lower.contains("service_unavailable")) {
            title = getString(R.string.chat_err_maintenance_title);
            body = getString(R.string.chat_err_maintenance_body);
            code = "MULTIAI-MAINTENANCE";
        } else if (lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("120000") || lower.contains("no chunks")) {
            title = getString(R.string.chat_err_timeout_title);
            body = getString(R.string.chat_err_timeout_body);
            code = "MULTIAI-TIMEOUT-120S";
        } else if (lower.contains("tool_") || lower.contains("unsupported tool")
                || lower.contains("tool result") || lower.contains("инструмент")) {
            title = getString(R.string.chat_err_tool_title);
            body = getString(R.string.chat_err_tool_body);
            code = "MULTIAI-TOOL-FAIL";
        } else if (lower.contains("http 4") || lower.contains("http 5") || lower.contains("502")
                || lower.contains("503") || lower.contains("504")) {
            title = getString(R.string.chat_err_server_title);
            body = getString(R.string.chat_err_server_body);
            code = "MULTIAI-UPSTREAM-HTTP";
        } else if (lower.contains("upstream_unavailable") || lower.contains("недоступен")
                || lower.contains("service_error") || lower.contains("upstream_error")) {
            title = getString(R.string.chat_err_server_title);
            body = getString(R.string.chat_err_server_body);
            code = "MULTIAI-UPSTREAM-UNAVAILABLE";
        } else if (lower.contains("сеть") || lower.contains("network") || lower.contains("unable to resolve")
                || lower.contains("failed to connect") || lower.contains("connection")) {
            title = getString(R.string.chat_err_network_title);
            body = getString(R.string.chat_err_network_body);
            code = "MULTIAI-NET-RETRY";
        } else {
            title = getString(R.string.chat_err_generic_title);
            body = getString(R.string.chat_err_generic_body);
            code = "MULTIAI-GENERIC";
        }

        if (messagesAdapter != null) {
            messagesAdapter.removeTrailingTypingMessages();
            messagesAdapter.removeReconnectingMessages();
        }

        Message errorMessage = Message.createError(title, body, code, retryable);
        activeErrorMessage = errorMessage;
        activeErrorCode = code;
        // Store the sanitized human message, never the raw wire string.
        lastFailedErrorMessage = parsed.isStructured() ? parsed.getMessage() : normalized;

        // Checkpoint-aware retry: the follow-up retry path does NOT re-send the
        // user prompt, so the legacy prompt/attachment retry inputs are cleared
        // to stop the Retry button from falling back to a full turn re-send.
        if (followUpCheckpoint != null) {
            lastUserPrompt = "";
            lastFailedUserMessage = null;
        }

        Message latestUserMessage = null;
        if (currentChat != null && currentChat.getMessages() != null) {
            List<Message> existingMessages = currentChat.getMessages();
            for (int i = existingMessages.size() - 1; i >= 0; i--) {
                Message message = existingMessages.get(i);
                if (message != null && message.getType() == Message.TYPE_USER) {
                    latestUserMessage = message;
                    break;
                }
            }
        }
        lastFailedUserMessage = latestUserMessage;
        if (followUpCheckpoint == null && retryable
                && (lastUserPrompt == null || lastUserPrompt.trim().isEmpty())
                && (latestUserMessage == null
                || (!latestUserMessage.hasImage() && !latestUserMessage.hasAttachedFile()))) {
            errorMessage.setRetryable(false);
        }

        if (currentChat != null) {
            currentChat.addMessage(errorMessage);
            chatManager.updateChat(currentChat);
        }

        if (messagesAdapter != null) {
            messagesAdapter.addMessage(errorMessage);
        }

        if (recyclerMessages != null && messagesAdapter != null && messagesAdapter.getItemCount() > 0) {
            recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
        }
    }

    private void maybeFinalizeUiAndCleanup() {
        Message target = getAnimationTargetMessage();
        if (target == null) {
            return;
        }

        if (contentAnimationTask == null && thinkingAnimationTask == null && !isGenerating) {
            if (contentBuffer.length() > 0 && target.getType() == Message.TYPE_AI
                    && target.getContentSafe().isEmpty()
                    && !target.workflowContainsContentText(contentBuffer.toString())) {
                target.setContent(contentBuffer.toString());
            }
            messagesAdapter.finalizeMessageContent(target);
            persistAiMessageIfNeeded(target);
            if (pendingTitleGeneration && currentChat != null && currentChat.isTitleAuto()) {
                pendingTitleGeneration = false;
                generateChatTitle();
            }
            lastFailedUserMessage = null;
            hasPendingMessage = false;
            clearActiveMessageTargets();
            postToolMessage = null;
        }
    }

    private void sendMessage() {
        String text = editMessage.getText().toString().trim();
        if (text.isEmpty() && selectedImageBase64 == null && attachedFileContent == null) return;
        final boolean retrying = retrySourceMessage != null && retrySourceUser != null;
        final Message retryUser = retrySourceUser;
        
        // Build AI text with file content injected (AI sees file, user sees clean text)
        final String aiText;
        if (attachedFileContent != null) {
            String fileBlock = "\n\n```file: " + attachedFileName + "\n" + attachedFileContent + "\n```";
            aiText = (text.isEmpty() ? getString(R.string.chat_attached_file_prefix, attachedFileName) : text) + fileBlock;
        } else {
            aiText = text;
        }
        // Capture file info before clearing
        final String capturedFileName = attachedFileName;
        final String capturedFileContent = attachedFileContent;
        final String capturedBranchContent = pendingBranchContent;
        
        if (isGenerating) {
            return;
        }
        // Сначала сжимаем только старую часть истории. Поле ввода и вложения
        // остаются нетронутыми, затем этот же sendMessage() продолжит ход.
        if (!retrying && startAutoSummaryIfNeeded()) {
            return;
        }
        
        requestSeq++;
        activeRequestId = activityInstanceId + "-REQ-" + requestSeq;
        lastUserPrompt = text;
        lastFailedErrorMessage = "";
        activeErrorCode = "";
        activeErrorMessage = null;
        lastFailedUserMessage = null;
        final long epoch = ++turnEpoch;
        turnStartTime = System.currentTimeMillis();
        turnUsedTools = false;
        turnCancelled = false;
        toolRoundCount = 0;
        questionCount = 0;
        // General Settings → "Use tools" is a Chat Mode preference. Work Mode
        // needs its project/Plan schema for its core contract, so snapshot it as
        // enabled for this whole turn and every tool-result continuation.
        turnToolsEnabled = true;
        // «Ссылаться на сохранённую память» выключено → save_memory убираем из схемы.
        multiAiClient.setMemoryToolsEnabled(
                app.multiai.mobile.utils.MemoryContextBuilder.memoriesEnabled(this));
        turnProjectRules = null;
        activeToolConversation = null;
        toolResultBatchRetryCount = 0;
        followUpRetryCount = 0;
        clearFollowUpCheckpoint();
        // New turn → new shared cancellation token. Covers the main stream,
        // every follow-up and every local tool call (parallel calls share it).
        cancelActiveTurnWork("superseded by a new turn");
        final OperationCancelToken turnToken = new OperationCancelToken("turn " + epoch);
        activeTurnToken = turnToken;
        prepareConfiguredPlanMode(epoch);
        final long initialRoundToken;
        synchronized (toolResultLock) {
            initialRoundToken = ++toolRoundGeneration;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = false;
        }

        // 🔥 Сбрасываем currentMessage для нового запроса
        clearActiveMessageTargets();
        postToolMessage = null;
        followStreamToBottom = true;
        if (fabScrollDown != null) fabScrollDown.setVisibility(View.GONE);
        
        // 🔥 Сбрасываем буферы анимации
        contentBuffer.setLength(0);
        workflowContentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        displayedContent = "";
        displayedThinking = "";
        contentRevealStartCp = 0;
        contentGhostStartCp = 0;
        stopContentAnimation();
        stopThinkingAnimation();
        
        // Create a user message only for a normal turn. Retry reuses the existing
        // prompt and writes the regenerated answer into the same assistant bubble.
        String displayText = text;
        Message userMessage = retrying ? retryUser : new Message(displayText, Message.TYPE_USER);
        if (!retrying && capturedFileName != null) {
            userMessage.setAttachedFileName(capturedFileName);
            userMessage.setAttachedFileContent(capturedFileContent);
        }
        if (!retrying && selectedImageBase64 != null) {
            userMessage.setImageBase64(selectedImageBase64);
            userMessage.setImageName(selectedImageName);
        }
        
        // Auto-create chat if none exists (лениво: без отдельной записи —
        // чат сохранится вместе с сообщением ниже)
        if (currentChat == null) {
            currentChat = new Chat();
            activateWorkModeSession(currentChat);
        }

        // Добавляем в чат и UI (adapter BEFORE updateChat so file data is intact for rendering)
        if (!retrying && capturedBranchContent != null) {
            Message branchMessage = new Message(capturedBranchContent, Message.TYPE_AI);
            currentChat.addMessage(branchMessage);
            messagesAdapter.addMessage(branchMessage);
        }
        if (!retrying) currentChat.addMessage(userMessage);
        lastFailedUserMessage = userMessage;
        stampChatModel(); // фиксируем модель чата при первой отправке
        if (!retrying && messagesAdapter.indexOfMessage(userMessage) < 0) messagesAdapter.addMessage(userMessage);
        chatManager.updateChat(currentChat);
        chatManager.saveActiveChatId(currentChat.getId());
        // Чат стал реальным — обновляем список в дровере.
        allChats = chatManager.loadActiveChats();
        chatsAdapter.setChats(allChats);
        chatsAdapter.setActiveChatId(currentChat.getId());
        
        // Скрываем empty state
        hideEmptyState();
        stopEmptyStateAnimation();
        recyclerMessages.setVisibility(View.VISIBLE);
        
        // Capture image before clearing (async send needs it)
        final String capturedImageBase64 = selectedImageBase64;
        
        // Очищаем поле ввода и изображение
        editMessage.setText("");
        removeImage();
        
        // 🔥 Создаём сообщение с анимацией печати (3 точки)
        Message typingMessage = new Message("", Message.TYPE_AI_TYPING);
        messagesAdapter.addMessage(typingMessage);
        
        // Скроллим вниз
        recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
        
        // Устанавливаем состояние генерации
        isGenerating = true;
        buttonSend.setEnabled(false);
        updateSendMicVisibility();
        
        // Получаем историю для контекста (последние 10 сообщений)
        List<Message> history = new ArrayList<>(currentChat.getMessages());
        // The request carries the current prompt separately. A retry must also
        // omit the previous assistant answer from context.
        if (retrying) {
            if (!history.isEmpty()) history.remove(history.size() - 1); // old assistant
            if (!history.isEmpty() && history.get(history.size() - 1) == retryUser) {
                history.remove(history.size() - 1); // current user prompt
            }
        } else if (!history.isEmpty()) {
            history.remove(history.size() - 1);
        }
        // История в UI полная, но ранний участок уже лежит в скрытой сводке
        // системного контекста. В сеть уходит только несжатый хвост.
        history = trimHistoryCoveredBySummary(history, currentChat);
        
        
        // Отправляем запрос
        AppSettings appSettings = new AppSettings(this);
        boolean useTools = turnToolsEnabled;
        boolean streamingEnabled = appSettings.isStreamingEnabled();
        AtomicBoolean mainStreamCompletionGuard = new AtomicBoolean(false);
        final int[] initialReconnectCount = {0};
        AtomicReference<MultiAiClient.StreamCallback> mainCallbackRef = new AtomicReference<>();

        final List<Message> finalHistory = history;
        final String finalText = aiText;

        MultiAiClient.StreamCallback theCallback = new MultiAiClient.StreamCallback() {
                @Override
                public void onContextInfo(int usedPct, int usedTokens, int maxTokens, boolean summarized) {
                    // После суммаризации контекста правила теряются — включим их снова
                    // на следующем ходе (один раз).
                    if (summarized) pendingRulesReinject = true;
                }

                @Override
                public void onThinking(String thinking) {
                    if (!isTurnActive(epoch)) return;
                    // 🔥 Сохраняем в статическое хранилище
                    if (pendingThinking == null) {
                        pendingThinking = thinking;
                    } else {
                        pendingThinking += thinking;
                    }
                    
                    runOnUiThread(() -> {
                        ensureInlinePostToolAiMessage();

                        // Создаём сообщение если нужно
                        if (currentMessage == null) {
                            Message newMessage = new Message("", Message.TYPE_AI);
                            newMessage.setThinkingFinished(false);
                            newMessage.setStreaming(true);
                            messagesAdapter.insertBeforeTrailingTyping(newMessage);
                            setActiveMessageTargets(newMessage);
                            toolCalls.clear();
                            toolCallsContainer = null;
                            thinkingStartTime = System.currentTimeMillis();
                            startThinkingTimer();
                        }
                        
                        // MultiAiClient уже отдаёт только свежие append-only фрагменты.
                        thinkingBuffer.append(thinking);
                        // Добавляем thinking в workflow сегменты (без refreshMessage —
                        // refreshMessage вызывает rebuildWorkflowIndex + rebind, что
                        // ломает стриминг. Direct update ниже.)
                        if (workflowTargetMessage != null) {
                            workflowTargetMessage.appendWorkflowThinking(thinking);
                            messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                            scrollMessagesToBottomDuringStream();
                        }
                        scheduleStreamingAiPersist(workflowTargetMessage != null
                                ? workflowTargetMessage : currentMessage);

                        // 🔥 Запускаем посимвольную анимацию
                        startThinkingAnimation();
                    });
                }

                @Override
                public void onThinkingEnd() {
                    if (!isTurnActive(epoch)) return;
                    runOnUiThread(() -> {
                        if (!isTurnActive(epoch)) return;
                        finishThinkingPhase();
                    });
                }

                @Override
                public void onToolCall(ToolCall toolCall) {
                    if (!isTurnActive(epoch)) return;
                    runOnUiThread(() -> {
                        if (!isTurnActive(epoch)) return;
                        app.multiai.mobile.utils.FileLog.d(
                                "MultiAiTools", "tool_call_received");
                        // Создаём сообщение если нужно
                        if (currentMessage == null) {
                            Message newMessage = new Message("", Message.TYPE_AI);
                            newMessage.setThinkingFinished(false);
                            newMessage.setStreaming(true);
                            messagesAdapter.insertBeforeTrailingTyping(newMessage);
                            setActiveMessageTargets(newMessage);
                            toolCalls.clear();
                            toolCallsContainer = null;
                            stopThinkingTimer();
                        }

                        stopThinkingTimer();
                        if (!registerRoundToolCall(toolCall, currentMessage, epoch, initialRoundToken)) return;
                        boolean firstToolInTurn = !turnUsedTools;
                        turnUsedTools = true;
                        if (firstToolInTurn) startWorkflowTimer();
                        toolCalls.add(toolCall);
                        if (workflowTargetMessage != null) {
                            if (workflowTargetMessage.moveStandaloneContentToWorkflow()) {
                                messagesAdapter.updateMessageContentDirect(workflowTargetMessage, "");
                            }
                            // Tool calls go to workflowTargetMessage segments.
                            workflowTargetMessage.addWorkflowToolCall(toolCall);
                            workflowTargetMessage.setToolCalls(new ArrayList<>(toolCalls));
                            messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                            scrollMessagesToBottomDuringStream();
                        } else {
                            currentMessage.setToolCalls(new ArrayList<>(toolCalls));
                            messagesAdapter.refreshMessage(currentMessage);
                        }
                        scheduleStreamingAiPersist(workflowTargetMessage != null
                                ? workflowTargetMessage : currentMessage);
                        // Execute only after the complete SSE tool batch is known.
                        // This lets Plan Mode atomically reject a simultaneous mutation.
                    });
                }

                @Override
                public void onToolResult(ToolCall toolCall) {
                    if (!isTurnActive(epoch)) return;
                    runOnUiThread(() -> {
                        if (!isTurnActive(epoch)) return;
                        ToolCall matchedTool = null;
                        if (currentMessage != null) {
                            // Обновляем существующий tool call
                            for (ToolCall tc : toolCalls) {
                                if (tc.getName().equals(toolCall.getName()) && tc.getStatus().equals("loading")) {
                                    tc.setStatus("success");
                                    tc.setResult(toolCall.getResult());
                                    matchedTool = tc;
                                    break;
                                }
                            }
                            
                            currentMessage.setToolCalls(new ArrayList<>(toolCalls));
                            if (workflowTargetMessage != null) {
                                workflowTargetMessage.setToolCalls(new ArrayList<>(toolCalls));
                                messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                                scrollMessagesToBottomDuringStream();
                            } else {
                                messagesAdapter.refreshMessage(currentMessage);
                            }
                        }

                        // web_search now returns scraped, reranked excerpts in the same tool result.
                        // Do not start a second, UI-only fetch round here: it would duplicate network
                        // work and would not add any context to the model's follow-up request.
                    });
                }

                @Override
                public void onContent(String content) {
                    if (!isTurnActive(epoch)) return;
                    finishThinkingPhase();
                    // 🔥 Сохраняем в статическое хранилище
                    if (pendingContent == null) {
                        pendingContent = content;
                    } else {
                        pendingContent += content;
                    }
                    
                    ensureInlinePostToolAiMessage();

                    // Создаём сообщение если нужно
                    if (currentMessage == null) {
                        Message newMessage = new Message("", Message.TYPE_AI);
                        newMessage.setThinkingFinished(false);
                        newMessage.setStreaming(true);
                        messagesAdapter.insertBeforeTrailingTyping(newMessage);
                        setActiveMessageTargets(newMessage);
                        toolCalls.clear();
                        toolCallsContainer = null;
                        stopThinkingTimer();
                    }

                    // Current model-round text is provisional and stays outside
                    // the process drawer. If a tool call follows, onToolCall moves
                    // it into an ordered CONTENT segment.
                    workflowContentBuffer.append(content);
                    contentBuffer.append(content);
                    if (workflowTargetMessage != null) {
                        workflowTargetMessage.setStandaloneContentVisible(true);
                        String visible = workflowTargetMessage.getContentSafe() + content;
                        workflowTargetMessage.setContent(visible);
                        messagesAdapter.updateMessageContentDirect(workflowTargetMessage, visible);
                        scheduleStreamingAiPersist(workflowTargetMessage);
                        scrollMessagesToBottomDuringStream();
                    } else {
                        startContentAnimation();
                        scheduleStreamingAiPersist(currentMessage);
                    }
                }

                @Override
                public void onComplete() {
                    if (!isTurnActive(epoch) || !mainStreamCompletionGuard.compareAndSet(false, true)) {
                        return;
                    }

                    pendingContent = null;
                    pendingThinking = null;
                    hasPendingMessage = false;

                    runOnUiThread(() -> {
                        if (!isTurnActive(epoch)) return;
                        hasPendingMessage = false;
                        finishThinkingPhase();
                        stopThinkingTimer();
                        if (thinkingAnimationTask != null) stopThinkingAnimation();
                        startContentAnimation();
                        refreshContextRing();

                        markRoundModelStreamComplete(epoch, initialRoundToken);
                    });
                }

                @Override
                public void onError(String error) {
                    if (!isTurnActive(epoch)) return;
                    // Structured cancel detection replaces legacy string matching.
                    MultiAiError parsedError = MultiAiError.parse(error);
                    if (parsedError.getCategory() == MultiAiError.Category.CANCELED) {
                        return;
                    }
                    app.multiai.mobile.utils.FileLog.d("MultiAiTools", "main_stream_error");
                    if (mainStreamCompletionGuard.get()) return;
                    if (parsedError.isRetryable() && !turnUsedTools
                            && initialReconnectCount[0] < MAX_INITIAL_RECONNECTS) {
                        int reconnect = ++initialReconnectCount[0];
                        long delayMs = initialStreamRetryPolicy.delayMs(parsedError, reconnect);
                        runOnUiThread(() -> {
                            if (!isTurnActive(epoch) || mainStreamCompletionGuard.get()) return;
                            messagesAdapter.showReconnecting("Reconnecting " + reconnect
                                    + "/" + MAX_INITIAL_RECONNECTS);
                            if (recyclerMessages != null) {
                                recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
                            }
                        });
                        animationHandler.postDelayed(() -> {
                            if (!isTurnActive(epoch) || mainStreamCompletionGuard.get()) return;
                            runOnUiThread(() -> messagesAdapter.showTypingAfterReconnect());
                            multiAiClient.sendMessageStream(
                                    selectedModelId, turnSystemPrompt, finalHistory, finalText,
                                    capturedImageBase64, useTools, streamingEnabled,
                                    toolPolicyForTurn(epoch), mainCallbackRef.get());
                        }, Math.max(0L, delayMs));
                        return;
                    }
                    if (!mainStreamCompletionGuard.compareAndSet(false, true)) return;
                    runOnUiThread(() -> finishTurnWithError(epoch, error, true));
                }
        };
        mainCallbackRef.set(theCallback);

        // Build one immutable rules snapshot for both this turn's system prompt and UI.
        turnProjectRules = app.multiai.mobile.utils.ProjectRulesManager.load(this);
        // Правила включаем один раз на контекст чата; повторно — только после
        // суммаризации контекста либо при смене проекта/содержимого правил.
        final String rulesContextId = rulesContextId();
        final String rulesSessionKey = projectRulesSessionKey(turnProjectRules);
        turnIncludeRules = turnProjectRules != null && turnProjectRules.hasRules()
                && (pendingRulesReinject
                || !rulesSessionKey.equals(rulesInjectedByChat.get(rulesContextId)));
        String enrichedInstructions = buildEnrichedInstructions();
        turnSystemPrompt = enrichedInstructions;

        // Check if selected model is disabled in admin panel
        if (selectedModelId != null && !availableModels.isEmpty()) {
            for (String[] m : availableModels) {
                if (m[0].equals(selectedModelId) && m.length > 3 && "false".equals(m[3])) {
                    isGenerating = false;
                    buttonSend.setEnabled(true);
                    updateSendMicVisibility();
                    messagesAdapter.removeTrailingTypingMessages();
                    messagesAdapter.removeReconnectingMessages();
                    showChatStyleError("model_disabled:" + selectedModelName, false);
                    return;
                }
            }
        }

        // Send through MultiAI
        if (selectedModelId == null) {
            isGenerating = false;
            buttonSend.setEnabled(true);
            updateSendMicVisibility();
            messagesAdapter.removeTrailingTypingMessages();
            messagesAdapter.removeReconnectingMessages();
            showChatStyleError("no_models_available", false);
            return;
        }
        if (turnIncludeRules) {
            attachProjectRulesWorkflow(turnProjectRules);
            rulesInjectedByChat.put(rulesContextId, rulesSessionKey);
            pendingRulesReinject = false;
        }
        multiAiClient.sendMessageStream(
                selectedModelId, enrichedInstructions, finalHistory, finalText,
                capturedImageBase64, useTools, streamingEnabled, toolPolicyForTurn(epoch), theCallback);
    }
    
    /** Execute only the explicitly supported non-project tools. */
    private void executeToolAndSendResult(ToolCall toolCall, long epoch, long roundToken) {
        if (!isToolRoundActive(epoch, roundToken)) return;
        // Shared turn token: parallel tool calls of one batch cancel together.
        final OperationCancelToken turnToken = activeTurnToken;

        String toolName = toolCall != null ? toolCall.getName() : null;
        if (!ToolCall.isSupportedToolName(toolName)) {
            JSONObject result = new JSONObject();
            try {
                result.put("success", false);
                result.put("error", "Unsupported tool");
                result.put("code", "UNSUPPORTED_TOOL");
                result.put("fatal", true);
            } catch (Exception ignored) {
            }
            commitToolResultAndAdvance(toolCall, result, false, epoch, roundToken);
            return;
        }

        new Thread(() -> {
            if (!isToolRoundActive(epoch, roundToken)) return;
            if (turnToken != null && turnToken.check()) return;
            JSONObject args = new JSONObject();
            for (Map.Entry<String, String> entry : toolCall.getParams().entrySet()) {
                try {
                    args.put(entry.getKey(), entry.getValue());
                } catch (Exception ignored) {
                }
            }

            String raw = executeMultiAiTool(toolCall, toolName, args, turnToken, epoch);
            if (raw != null && raw.length() > 100_000) {
                raw = "{\"error\":\"tool_result_too_large\"}";
            }
            JSONObject result;
            try {
                result = new JSONObject(raw != null ? raw : "{}");
            } catch (Exception e) {
                result = new JSONObject();
                try {
                    result.put("error", raw);
                } catch (Exception ignored) {
                }
            }
            if (!isToolRoundActive(epoch, roundToken)) return;
            if (turnToken != null && turnToken.check()) return;
            final JSONObject finalResult = result;
            boolean success = !finalResult.has("error");
            runOnUiThread(() -> commitToolResultAndAdvance(
                    toolCall, finalResult, success, epoch, roundToken));
        }, "multiai-tool-" + toolName).start();
    }
    
    private boolean isTurnActive(long epoch) {
        return epoch == turnEpoch && !turnCancelled;
    }

    private boolean isToolRoundActive(long epoch, long roundToken) {
        synchronized (toolResultLock) {
            return isToolRoundActiveLocked(epoch, roundToken);
        }
    }

    private boolean isToolRoundActiveLocked(long epoch, long roundToken) {
        return isTurnActive(epoch) && toolRoundGeneration == roundToken;
    }

    /** Returns false for a duplicate id or a callback from an obsolete response attempt. */
    private boolean registerRoundToolCall(ToolCall toolCall, Message owner,
                                          long epoch, long roundToken) {
        if (toolCall == null || owner == null) return false;
        synchronized (toolResultLock) {
            if (!isToolRoundActiveLocked(epoch, roundToken) || roundBatchDispatched
                    || roundToolCalls.containsKey(toolCall.getId())) return false;
            roundToolCalls.put(toolCall.getId(), toolCall);
            roundToolOwners.put(toolCall.getId(), owner);
            return true;
        }
    }

    /** Returns only the tool calls emitted by the currently active model response. */
    private List<ToolCall> snapshotCurrentRoundToolCalls() {
        synchronized (toolResultLock) {
            return new ArrayList<>(roundToolCalls.values());
        }
    }

    private JSONObject modelFacingToolResult(ToolCall toolCall, JSONObject result) throws Exception {
        if (result == null) return new JSONObject();
        String name = toolCall != null ? toolCall.getName() : "";
        boolean fileMutation = "write_file".equals(name) || "edit_file".equals(name);
        if (!fileMutation || result.has("error") || !result.optBoolean("success", true)) {
            return result;
        }
        JSONObject compact = new JSONObject();
        compact.put("success", true);
        compact.put("operation", name);
        compact.put("path", result.optString("path", ""));
        compact.put("created", result.optBoolean("created", false));
        compact.put("verified", result.optBoolean("verified", false));
        compact.put("bytes", result.optInt("bytes", 0));
        compact.put("message", result.optString("message",
                name + " completed successfully for " + result.optString("path", "file")));
        if (result.has("additions")) compact.put("additions", result.optInt("additions"));
        if (result.has("deletions")) compact.put("deletions", result.optInt("deletions"));
        return compact;
    }

    /** Record exactly one terminal result for this response's tool_call_id. */
    private void completeRoundToolCall(ToolCall toolCall, JSONObject result, long epoch,
                                       long roundToken) {
        if (toolCall == null || !isToolRoundActive(epoch, roundToken)) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("function", toolCall.getName());
            payload.put("data", modelFacingToolResult(toolCall, result));
            payload.put("tool_call_id", toolCall.getId());
            synchronized (toolResultLock) {
                if (!isToolRoundActiveLocked(epoch, roundToken)
                        || !roundToolCalls.containsKey(toolCall.getId())
                        || roundToolResults.containsKey(toolCall.getId())) return;
                roundToolResults.put(toolCall.getId(), payload);
            }
            maybeAdvanceToolRound(epoch, roundToken);
        } catch (Exception ignored) {
        }
    }

    private void markRoundModelStreamComplete(long epoch, long roundToken) {
        synchronized (toolResultLock) {
            if (!isToolRoundActiveLocked(epoch, roundToken)) return;
            roundModelStreamComplete = true;
        }
        dispatchCompletedToolRound(epoch, roundToken);
        maybeAdvanceToolRound(epoch, roundToken);
    }

    /**
     * Starts tool workers only after the model response is complete. A model can
     * emit several calls in one SSE response; preflighting the full batch keeps
     * enter_plan_mode from racing a write/edit/command emitted beside it.
     */
    private void dispatchCompletedToolRound(long epoch, long roundToken) {
        List<ToolCall> calls;
        List<ToolCall> transitionConflicts = new ArrayList<>();
        ToolCall transition = null;
        synchronized (toolResultLock) {
            if (!isToolRoundActiveLocked(epoch, roundToken)
                    || !roundModelStreamComplete || roundExecutionDispatched) return;
            roundExecutionDispatched = true;
            calls = new ArrayList<>(roundToolCalls.values());
            for (ToolCall call : calls) {
                if (call != null && "enter_plan_mode".equals(call.getName())) {
                    transition = call;
                    break;
                }
            }
            if (transition != null) {
                for (ToolCall call : calls) {
                    if (call != null && call != transition) transitionConflicts.add(call);
                }
            }
        }

        for (ToolCall conflict : transitionConflicts) {
            try {
                commitToolResultAndAdvance(conflict, new JSONObject()
                        .put("success", false)
                        .put("error", "enter_plan_mode must be the only tool call in its response")
                        .put("code", "PLAN_TRANSITION_CONFLICT"), false, epoch, roundToken);
            } catch (Exception ignored) {
            }
        }
        if (transition != null) {
            executeToolAndSendResult(transition, epoch, roundToken);
            return;
        }
        for (ToolCall call : calls) {
            executeToolAndSendResult(call, epoch, roundToken);
        }
    }

    /** Atomic barrier: model onComplete + one local terminal result per discovered call. */
    private void maybeAdvanceToolRound(long epoch, long roundToken) {
        JSONArray batch = null;
        boolean finalResponse = false;
        synchronized (toolResultLock) {
            if (!isToolRoundActiveLocked(epoch, roundToken) || !roundModelStreamComplete
                    || roundBatchDispatched) return;
            if (roundToolCalls.isEmpty()) {
                roundBatchDispatched = true;
                finalResponse = true;
            } else if (!toolResultBatchInFlight
                    && roundToolResults.size() == roundToolCalls.size()) {
                batch = new JSONArray();
                for (String id : roundToolCalls.keySet()) {
                    JSONObject payload = roundToolResults.get(id);
                    if (payload == null) return;
                    batch.put(payload);
                }
                roundBatchDispatched = true;
                toolResultBatchInFlight = true;
            }
        }

        if (finalResponse) {
            runOnUiThread(() -> {
                if (isToolRoundActive(epoch, roundToken)) finishTurnSuccessfully(epoch);
            });
        } else if (batch != null) {
            final JSONArray ready = batch;
            runOnUiThread(() -> sendToolResultBatch(ready, epoch, roundToken));
        }
    }

    /**
     * Atomically commits the terminal UI state before the result can advance the
     * round. Must run on the main thread so RecyclerView, persistence and the
     * outbound barrier observe the same ToolCall object in the same order.
     */
    private void commitToolResultAndAdvance(ToolCall callbackTool, JSONObject result,
                                            boolean success, long epoch, long roundToken) {
        ToolCall canonical;
        Message owner;
        synchronized (toolResultLock) {
            if (callbackTool == null || !isToolRoundActiveLocked(epoch, roundToken)) return;
            canonical = roundToolCalls.get(callbackTool.getId());
            owner = roundToolOwners.get(callbackTool.getId());
            if (canonical == null || owner == null) return;
            canonical.setStatus(success ? "success" : "error");
            canonical.setResult(result);
        }

        messagesAdapter.refreshMessage(owner);
        persistAiMessageIfNeeded(owner);
        completeRoundToolCall(canonical, result, epoch, roundToken);
    }

    /** Opens a clean assistant response round while preserving the batch in-flight lock. */
    private long resetRoundForNextAssistant(long epoch) {
        synchronized (toolResultLock) {
            if (!isTurnActive(epoch)) return -1L;
            long roundToken = ++toolRoundGeneration;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = true;
            return roundToken;
        }
    }

    /** Drops a partial failed response and invalidates its still-running local callbacks. */
    private long resetRoundForBatchRetry(long epoch, long failedRoundToken) {
        synchronized (toolResultLock) {
            if (!isToolRoundActiveLocked(epoch, failedRoundToken)
                    || toolResultBatchRetryCount >= MAX_TOOL_RESULT_BATCH_RETRIES) return -1L;
            toolResultBatchRetryCount++;
            long retryRoundToken = ++toolRoundGeneration;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            // The original tool-result batch owns this lock through every retry attempt.
            toolResultBatchInFlight = true;
            return retryRoundToken;
        }
    }

    private void clearPartialFollowUpForRetry(long epoch, long retryRoundToken) {
        if (!isToolRoundActive(epoch, retryRoundToken)) return;
        toolCalls.clear();
        toolCallsContainer = null;
        if (postToolMessage != null) {
            postToolMessage.setContent("");
            postToolMessage.setThinkingContent("");
            postToolMessage.setToolCalls(new ArrayList<>());
            messagesAdapter.refreshMessage(postToolMessage);
        }
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        displayedContent = "";
        displayedThinking = "";
    }

    private org.json.JSONArray buildMessagesJsonForToolBatch(org.json.JSONArray toolResultsBatch)
            throws Exception {
        org.json.JSONArray messages = activeToolConversation == null
                ? new org.json.JSONArray()
                : new org.json.JSONArray(activeToolConversation.toString());
        if (currentChat == null) return messages;

        Message roundAssistant = postToolMessage != null && postToolMessage.hasToolCalls()
                ? postToolMessage
                : workflowTargetMessage != null ? workflowTargetMessage : findLatestPersistedAiMessage();
        List<Message> historyCopy;
        if (activeToolConversation == null) {
            historyCopy = new ArrayList<>(currentChat.getMessages());
            if (roundAssistant != null && !historyCopy.contains(roundAssistant)) {
                historyCopy.add(roundAssistant);
            }
        } else {
            historyCopy = new ArrayList<>();
            if (roundAssistant != null) historyCopy.add(roundAssistant);
        }

        // On the first tool round we serialize normal history plus only the latest
        // assistant's tool_calls. On nested rounds activeToolConversation already
        // contains every previous assistant→tool pair, so only the new assistant is appended.

        for (Message msg : historyCopy) {
            int type = msg.getType();
            if (type == Message.TYPE_AI_TYPING || type == Message.TYPE_ERROR
                    || type == Message.TYPE_RECONNECTING) {
                continue;
            }
            JSONObject msgObj = new JSONObject();
            String role;
            switch (type) {
                case Message.TYPE_USER:
                    role = "user";
                    break;
                case Message.TYPE_AI:
                    role = "assistant";
                    break;
                default:
                    role = "assistant";
            }
            msgObj.put("role", role);

            // OpenAI-compatible content: plain text. Reasoning is sent inline
            // because some providers expose it through delta.reasoning_content on
            // the response side only.
            StringBuilder content = new StringBuilder();
            if (msg.getThinkingContent() != null && !msg.getThinkingContent().isEmpty()) {
                content.append("<thinking>\n")
                        .append(msg.getThinkingContent())
                        .append("\n</thinking>\n\n");
            }
            if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                content.append(msg.getContent());
            }
            msgObj.put("content", content.toString());

            // Сообщение с tool_calls шлём по спецификации: multiai.store
            // валидирует историю («tool result has no matching tool call»),
            // каждый role=tool должен иметь парный assistant.tool_calls.
            if (msg == roundAssistant && msg.hasToolCalls()) {
                org.json.JSONArray tcs = new org.json.JSONArray();
                for (ToolCall tc : msg.getToolCalls()) {
                    JSONObject t = new JSONObject();
                    t.put("id", tc.getId());
                    t.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", tc.getName());
                    JSONObject params = new JSONObject();
                    for (Map.Entry<String, String> e : tc.getParams().entrySet()) {
                        params.put(e.getKey(), e.getValue());
                    }
                    fn.put("arguments", params.toString());
                    t.put("function", fn);
                    tcs.put(t);
                }
                msgObj.put("tool_calls", tcs);
            }
            messages.put(msgObj);
        }

        // Последнее AI-сообщение (пре-тул текст + вызовы этой партии) и его
        // tool-результаты идут парой: assistant(tool_calls) → role=tool...
        for (int i = 0; i < toolResultsBatch.length(); i++) {
            JSONObject tr = toolResultsBatch.getJSONObject(i);
            JSONObject toolMsg = new JSONObject();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", tr.optString("tool_call_id", ""));
            toolMsg.put("content", tr.optJSONObject("data").toString());
            messages.put(toolMsg);
        }
        activeToolConversation = new JSONArray(messages.toString());
        return messages;
    }

    private void sendToolResultBatch(JSONArray toolResultsBatch, long epoch, long sourceRoundToken) {
        if (!isToolRoundActive(epoch, sourceRoundToken)) return;
        try {
            if (multiAiClient == null) {
                finishTurnWithError(epoch, getString(R.string.chat_err_client_unavailable), true);
                return;
            }
            final String preToolPrefix = currentMessage != null ? currentMessage.getContentSafe() : "";
            // Serialize the completed assistant(tool_calls) round first. The
            // next invisible round accumulator is allocated only afterwards.
            inToolFollowUp = true;
            if (postToolMessage != null) postToolMessage.setStreaming(false);
            JSONArray requestMessages = buildMessagesJsonForToolBatch(toolResultsBatch);
            postToolMessage = null;
            ensurePostToolAiMessage();
            toolRoundCount++;
            long responseRoundToken = resetRoundForNextAssistant(epoch);
            if (responseRoundToken < 0L) return;
            // New follow-up request → checkpoint of the exact payloads (deep copies).
            saveFollowUpCheckpoint(toolResultsBatch, requestMessages, preToolPrefix);
            sendToolResultBatchRequest(toolResultsBatch, requestMessages, preToolPrefix, epoch,
                    responseRoundToken);
        } catch (Exception e) {
            synchronized (toolResultLock) { toolResultBatchInFlight = false; }
            finishTurnWithError(epoch, "Не удалось подготовить результаты инструментов: " + e.getMessage(), true);
        }
    }

    private void sendToolResultBatchRequest(JSONArray toolResultsBatch, JSONArray requestMessages,
                                            String preToolPrefix, long epoch,
                                            long responseRoundToken) {
        if (!isToolRoundActive(epoch, responseRoundToken)) return;
        try {
            AppSettings appSettings = new AppSettings(this);
            // Keep the initial Work Mode contract for every tool round. Without
            // it, the model loses project/tool rules after its first tool result.
            multiAiClient.sendMessageStream(
                selectedModelId, turnSystemPrompt, requestMessages,
                turnToolsEnabled, appSettings.isStreamingEnabled(),
                toolPolicyForTurn(epoch),
                new MultiAiClient.StreamCallback() {
                private boolean receivedVisibleContent = false;
                private boolean receivedToolCall = false;
                private final StreamingTextNormalizer.PrefixStripper prefixStripper =
                        new StreamingTextNormalizer.PrefixStripper(preToolPrefix);

                private void appendPostToolContent(String chunk) {
                    if (chunk == null || chunk.isEmpty()) return;
                    ensurePostToolAiMessage();
                    if (postToolMessage == null) return;
                    if (!chunk.trim().isEmpty()) receivedVisibleContent = true;
                    postToolMessage.setContent(postToolMessage.getContentSafe() + chunk);
                    workflowContentBuffer.append(chunk);
                    if (workflowTargetMessage != null) {
                        workflowTargetMessage.setStandaloneContentVisible(true);
                        String visible = workflowTargetMessage.getContentSafe() + chunk;
                        workflowTargetMessage.setContent(visible);
                        messagesAdapter.updateMessageContentDirect(workflowTargetMessage, visible);
                        scheduleStreamingAiPersist(workflowTargetMessage);
                    } else {
                        scheduleStreamingAiPersist(postToolMessage);
                    }
                    scrollMessagesToBottomDuringStream();
                }

                @Override
                public void onContextInfo(int usedPct, int usedTokens, int maxTokens, boolean summarized) {
                    // ignore
                }

                @Override
                public void onThinking(String thinking) {
                    if (!isToolRoundActive(epoch, responseRoundToken)) return;
                    runOnUiThread(() -> {
                        if (!isToolRoundActive(epoch, responseRoundToken)) return;
                        ensurePostToolAiMessage();
                        if (postToolMessage != null) {
                            // Reasoning belongs only to the stable workflow owner.
                            // Duplicating it on postToolMessage created a second
                            // standalone reasoning row between Explore groups.
                            timerTargetMessage = workflowTargetMessage != null
                                    ? workflowTargetMessage : postToolMessage;
                            // The animation target must stay on the visible row.
                            // Pointing it at postToolMessage leaked that
                            // protocol-only object into finalize/persist paths,
                            // which duplicated already-drawered text below the
                            // final answer.
                            animationTargetMessage = workflowTargetMessage != null
                                    ? workflowTargetMessage : postToolMessage;
                            if (workflowTargetMessage != null) {
                                workflowTargetMessage.appendWorkflowThinking(thinking);
                                messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                                scheduleStreamingAiPersist(workflowTargetMessage);
                                scrollMessagesToBottomDuringStream();
                            } else {
                                String existingThinking = postToolMessage.getThinkingContent();
                                if (existingThinking == null) existingThinking = "";
                                postToolMessage.setThinkingContent(existingThinking + thinking);
                                messagesAdapter.updateMessageThinkingDirect(
                                        postToolMessage, postToolMessage.getThinkingContent());
                                scheduleStreamingAiPersist(postToolMessage);
                            }
                        }
                    });
                }

                @Override
                public void onThinkingEnd() {
                }

                @Override
                public void onToolCall(ToolCall newToolCall) {
                    if (!isToolRoundActive(epoch, responseRoundToken)) return;
                    runOnUiThread(() -> {
                        if (!isToolRoundActive(epoch, responseRoundToken)) return;
                        ensurePostToolAiMessage();
                        if (postToolMessage != null
                                && registerRoundToolCall(newToolCall, postToolMessage,
                                        epoch, responseRoundToken)) {
                            receivedToolCall = true;
                            toolCalls.add(newToolCall);
                            if (workflowTargetMessage != null
                                    && workflowTargetMessage.moveStandaloneContentToWorkflow()) {
                                messagesAdapter.updateMessageContentDirect(workflowTargetMessage, "");
                            }
                            // The protocol assistant message must include only this
                            // response round. Older calls already live in
                            // activeToolConversation with their paired tool results.
                            postToolMessage.setToolCalls(snapshotCurrentRoundToolCalls());
                            // Tool calls go to workflowTargetMessage segments only.
                            // postToolMessage doesn't render tool calls — avoids duplicates.
                            if (workflowTargetMessage != null) {
                                workflowTargetMessage.addWorkflowToolCall(newToolCall);
                                workflowTargetMessage.setToolCalls(new ArrayList<>(toolCalls));
                                messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                                scheduleStreamingAiPersist(workflowTargetMessage);
                                scrollMessagesToBottomDuringStream();
                            } else {
                                scheduleStreamingAiPersist(postToolMessage);
                            }
                            // The completed round is preflighted after SSE onComplete;
                            // do not start a concurrent mutation before Plan transition safety checks.
                        }
                    });
                }

                @Override
                public void onToolResult(ToolCall toolResult) {
                    // managed by UI + local tool execution
                }

                @Override
                public void onContent(String content) {
                    if (!isToolRoundActive(epoch, responseRoundToken)) return;
                    runOnUiThread(() -> {
                        if (!isToolRoundActive(epoch, responseRoundToken)) return;
                        appendPostToolContent(prefixStripper.accept(content));
                    });
                }

                @Override
                public void onComplete() {
                    if (!isToolRoundActive(epoch, responseRoundToken)) return;
                    runOnUiThread(() -> {
                        if (!isToolRoundActive(epoch, responseRoundToken)) return;
                        appendPostToolContent(prefixStripper.finish());

                        // A reasoning-only/empty post-tool completion is not a
                        // usable final answer. One silent automatic retry with
                        // the exact same tool-result batch is safe (no local
                        // side effects): if the second attempt is still empty,
                        // surface a retryable error with the checkpoint kept.
                        if (!receivedVisibleContent && !receivedToolCall) {
                            if (attemptFollowUpRetry(epoch, responseRoundToken,
                                    MultiAiError.protocol("empty_completion",
                                            "Модель не дала ответ после tool result"),
                                    "empty_completion")) {
                                return;
                            }
                            synchronized (toolResultLock) {
                                if (!isToolRoundActiveLocked(epoch, responseRoundToken)) return;
                                toolResultBatchInFlight = false;
                            }
                            finishTurnWithError(epoch,
                                    "empty_completion: модель не дала ответ после tool result",
                                    true);
                            return;
                        }

                        if (thinkingAnimationTask != null) stopThinkingAnimation();
                        startContentAnimation();
                        refreshContextRing();
                        stopThinkingTimer();
                        hasPendingMessage = false;
                        if (postToolMessage != null) messagesAdapter.refreshMessage(postToolMessage);
                        synchronized (toolResultLock) {
                            if (!isToolRoundActiveLocked(epoch, responseRoundToken)) return;
                            toolResultBatchInFlight = false;
                            toolResultBatchRetryCount = 0;
                            followUpRetryCount = 0;
                        }
                        markRoundModelStreamComplete(epoch, responseRoundToken);
                    });
                }

                @Override
                public void onError(String error) {
                    if (!isToolRoundActive(epoch, responseRoundToken)) return;
                    MultiAiError parsed = MultiAiError.parse(error);
                    if (parsed.getCategory() == MultiAiError.Category.CANCELED) {
                        return;
                    }

                    // Retry only when no side effects can duplicate: nothing from
                    // this response attempt was observed (no tool calls executed,
                    // no new content rendered is fine — clearPartialFollowUpForRetry
                    // removes partial text before the retry fires).
                    boolean safeToRetry = !receivedToolCall;
                    if (safeToRetry && attemptFollowUpRetry(epoch, responseRoundToken, parsed, null)) {
                        return;
                    }

                    synchronized (toolResultLock) {
                        if (!isToolRoundActiveLocked(epoch, responseRoundToken)) return;
                        toolResultBatchInFlight = false;
                        toolResultBatchRetryCount = 0;
                    }
                    runOnUiThread(() -> finishTurnWithError(epoch, error, true));
                }
            });
        } catch (Exception e) {
            synchronized (toolResultLock) {
                if (!isToolRoundActiveLocked(epoch, responseRoundToken)) return;
                toolResultBatchInFlight = false;
            }
            finishTurnWithError(epoch, getString(R.string.chat_err_followup, e.getMessage()), true);
        }
    }

    /**
     * Unified structured follow-up retry. Runs on the UI thread (callback
     * thread for onError is the client's main handler).
     *
     * <p>Replaces legacy string matching with {@link MultiAiError#parse} +
     * {@link RetryPolicy}. A retry is allowed only when this response attempt
     * produced no tool calls (their execution may have side effects) and the
     * error is deterministically retryable. {@code emptyReason} carries the
     * synthesized error for the empty-completion path.</p>
     *
     * @return true when a retry attempt was scheduled; false when the caller
     *         must finish the turn with an error.
     */
    private boolean attemptFollowUpRetry(long epoch, long failedRoundToken,
                                         MultiAiError error, String emptyReason) {
        if (error == null || error.getCategory() == MultiAiError.Category.CANCELED) return false;
        boolean emptyCompletionRetry = emptyReason != null && followUpRetryCount == 0;
        int attemptsUsed = followUpRetryCount + 1;
        if (!emptyCompletionRetry && !followUpRetryPolicy.shouldRetry(error, attemptsUsed)) {
            return false;
        }
        final FollowUpCheckpoint checkpoint = followUpCheckpoint;
        if (checkpoint == null || checkpoint.toolResultsBatch == null
                || checkpoint.requestMessages == null) return false;

        long retryRoundToken = resetRoundForBatchRetry(epoch, failedRoundToken);
        if (retryRoundToken < 0L) return false;

        followUpRetryCount++;
        long delayMs = emptyCompletionRetry
                ? followUpRetryPolicy.getBaseDelayMs()
                : followUpRetryPolicy.delayMs(error, followUpRetryCount);
        if (delayMs < 0L) delayMs = 0L;

        final int attemptNum = followUpRetryCount;
        final int maxAttempts = followUpRetryPolicy.getMaxAttempts();
        final String partial = postToolMessage != null ? postToolMessage.getContentSafe() : "";
        JSONArray continuedMessages = deepCopy(checkpoint.requestMessages);
        if (continuedMessages == null) return false;
        if (!partial.trim().isEmpty()) {
            try {
                continuedMessages.put(new JSONObject().put("role", "assistant").put("content", partial));
                continuedMessages.put(new JSONObject().put("role", "user").put("content",
                        "Continue exactly from the final character of the interrupted assistant message. "
                                + "Return only the missing continuation: do not restart, repeat, capitalize, "
                                + "or add a new introduction."));
            } catch (Exception ignored) { }
        }
        final JSONArray retryMessages = continuedMessages;
        final String retryPrefix = partial.trim().isEmpty() ? checkpoint.preToolPrefix : partial;
        runOnUiThread(() -> {
            if (!isTurnActive(epoch)) return;
            messagesAdapter.showReconnecting("Reconnecting " + attemptNum + "/" + maxAttempts);
            if (recyclerMessages != null) {
                recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
            }
        });

        animationHandler.postDelayed(() -> {
            if (!isToolRoundActive(epoch, retryRoundToken)) return;
            runOnUiThread(() -> messagesAdapter.showTypingAfterReconnect());
            if (partial.trim().isEmpty()) {
                clearPartialFollowUpForRetry(epoch, retryRoundToken);
            } else {
                toolCalls.clear();
                toolCallsContainer = null;
                if (postToolMessage != null) {
                    postToolMessage.setThinkingContent("");
                    postToolMessage.setToolCalls(new ArrayList<>());
                }
                thinkingBuffer.setLength(0);
                displayedThinking = "";
            }
            sendToolResultBatchRequest(checkpoint.toolResultsBatch, retryMessages,
                    retryPrefix, epoch, retryRoundToken);
        }, delayMs);
        return true;
    }

    private void finishTurnSuccessfully(long epoch) {
        if (!isTurnActive(epoch)) return;
        inToolFollowUp = false;
        isGenerating = false;
        stopThinkingTimer();
        pendingSuccessfulFinishEpoch = epoch;
        if (workflowTargetMessage != null
                && workflowTargetMessage.getWorkflowSegments() != null
                && !workflowTargetMessage.getWorkflowSegments().isEmpty()) {
            completeTurnSuccessfully(epoch);
            return;
        }
        startContentAnimation();
        String fullText = contentBuffer.toString();
        int displayedCp = displayedContent.codePointCount(0, displayedContent.length());
        int fullCp = fullText.codePointCount(0, fullText.length());
        if (displayedCp < fullCp && getAnimationTargetMessage() != null) return;
        completeTurnSuccessfully(epoch);
    }

    private void completeTurnSuccessfully(long epoch) {
        if (!isTurnActive(epoch)) return;
        pendingSuccessfulFinishEpoch = -1L;
        stopContentAnimation();
        stopWorkflowTimer();
        if (messagesAdapter != null) {
            messagesAdapter.removeTrailingTypingMessages();
            messagesAdapter.removeReconnectingMessages();
        }
        Message target = workflowTargetMessage != null
                ? workflowTargetMessage : getAnimationTargetMessage();
        if (target != null) {
            target.setWorkingState(false);
            String fullText = target.getContentSafe();
            if (fullText.isEmpty() && contentBuffer.length() > 0
                    && !target.workflowContainsContentText(contentBuffer.toString())) {
                fullText = contentBuffer.toString();
                target.setContent(fullText);
            }
            // The final answer lives in its own bubble under the process
            // drawer. Without this the flag could stay false from the last
            // moveStandaloneContentToWorkflow() of the turn, and the persisted
            // chat reopened with the answer hidden.
            if (!fullText.trim().isEmpty()) {
                target.setStandaloneContentVisible(true);
            }
            int fullCp = fullText.codePointCount(0, fullText.length());
            target.setRevealStartCp(fullCp);
            target.setRevealEndCp(fullCp);
            target.setGhostStartCp(fullCp);
            target.setStreaming(false);
            if (retrySourceMessage != null && retrySourceMessage != target) {
                // Replace the temporary regenerated bubble with a new page on the
                // original assistant message. The user prompt remains single.
                retrySourceMessage.addAlternateContent(
                        fullText, target.getThinkingContent(), target.getWorkflowSegments());
                retrySourceMessage.setRetryPending(false);
                messagesAdapter.removeMessage(target);
                currentChat.removeMessage(target);
                target = retrySourceMessage;
            }
            boolean hasOrderedWorkflow = workflowTargetMessage != null
                    && workflowTargetMessage.getWorkflowSegments() != null
                    && !workflowTargetMessage.getWorkflowSegments().isEmpty();
            boolean hasWorkflowTools = hasOrderedWorkflow
                    && workflowTargetMessage.hasWorkflowToolCalls();
            if (turnUsedTools || hasWorkflowTools) {
                // "Выполнено за" goes on the workflowTargetMessage (first AI
                // message of the turn), NOT on postToolMessage — so the
                // summary stays at the top, not jumping to the bottom
                Message workflowMsg = workflowTargetMessage != null ? workflowTargetMessage : target;
                workflowMsg.setToolWorkflowSummary(true);
                workflowMsg.setTurnDurationMs(System.currentTimeMillis() - turnStartTime);
                workflowMsg.setWorkingState(false);
                // Parent workflow starts expanded; individual reasoning blocks
                // stay collapsed until the user opens them.
                workflowMsg.setToolWorkflowExpanded(true);
                // Copy segments to target if different (so postToolMessage
                // doesn't render duplicate tool calls without segments)
                if (workflowMsg != target) {
                    target.setWorkingState(false);
                    target.setToolWorkflowSummary(false);
                    // Clear tool calls on target — they belong to workflowMsg
                    target.setToolCalls(null);
                }
                messagesAdapter.collapseWorkflowAfterCompletion(workflowMsg);
            }
            messagesAdapter.finalizeMessageContent(target);
            persistAiMessageIfNeeded(target);
            if (turnUsedTools || hasOrderedWorkflow) messagesAdapter.refreshAllMessages();
        }
        pendingContent = null;
        pendingThinking = null;
        hasPendingMessage = false;
        pendingTitleGeneration = currentChat != null && currentChat.isTitleAuto();
        if (pendingTitleGeneration) {
            pendingTitleGeneration = false;
            generateChatTitle();
        }
        String preview = target != null ? target.getContentSafe() : "";
        showAiResponseNotification(preview);
        buttonSend.setEnabled(true);
        updateSendMicVisibility();
        synchronized (toolResultLock) {
            toolRoundGeneration++;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = false;
            toolResultBatchRetryCount = 0;
        }
        followUpRetryCount = 0;
        clearFollowUpCheckpoint();
        lastFailedUserMessage = null;
        activeToolConversation = null;
        postToolMessage = null;
        toolCalls.clear();
        turnUsedTools = false;
        turnStartTime = 0;
        clearActiveMessageTargets();
        clearRetryState();
    }

    private void finishTurnWithError(long epoch, String error, boolean retryable) {
        if (!isTurnActive(epoch)) return;
        inToolFollowUp = false;
        isGenerating = false;
        stopThinkingTimer();
        stopThinkingAnimation();
        stopContentAnimation();
        stopWorkflowTimer();
        Message target = getAnimationTargetMessage();
        if (target != null) target.setWorkingState(false);
        // Finalize the workflow card: if this turn already produced workflow
        // segments (thinking / tool calls), keep them visible with a summary
        // ("Выполнено за…"), auto-expanded unless the user collapsed the card
        // manually (computeWorkflowExpanded enforces the model policy).
        boolean hadWorkflow = turnUsedTools && workflowTargetMessage != null;
        if (hadWorkflow) {
            Message workflowMsg = workflowTargetMessage;
            workflowMsg.setWorkingState(false);
            workflowMsg.setToolWorkflowSummary(true);
            workflowMsg.setTurnDurationMs(System.currentTimeMillis() - turnStartTime);
            workflowMsg.computeWorkflowExpanded(true);
            messagesAdapter.refreshMessage(workflowMsg);
        }
        messagesAdapter.removeTrailingTypingMessages();
        messagesAdapter.removeReconnectingMessages();
        // The checkpoint stays alive for retryable follow-up failures so the
        // Retry button can re-issue ONLY the follow-up (no tool re-execution).
        boolean keepCheckpoint = retryable && followUpCheckpoint != null;
        showChatStyleError(error, retryable);
        if (!keepCheckpoint) {
            clearFollowUpCheckpoint();
        }
        followUpRetryCount = 0;
        buttonSend.setEnabled(true);
        updateSendMicVisibility();
        hasPendingMessage = false;
        synchronized (toolResultLock) {
            toolRoundGeneration++;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = false;
            toolResultBatchRetryCount = 0;
        }
        if (!keepCheckpoint) {
            activeToolConversation = null;
        }
        postToolMessage = null;
        toolCalls.clear();
        turnUsedTools = false;
        turnStartTime = 0;
        clearActiveMessageTargets();
        clearRetryState();
    }

    private void clearRetryState() {
        if (retrySourceMessage != null && retrySourceMessage.isRetryPending()) {
            retrySourceMessage.setRetryPending(false);
            if (messagesAdapter != null) messagesAdapter.refreshMessage(retrySourceMessage);
        }
        retrySourceMessage = null;
        retrySourceUser = null;
        // Checkpoint lifecycle is managed explicitly at each call site
        // (sendMessage / stop / chat switch / success / error): finishTurnWithError
        // keeps it alive for retryable follow-ups, so it must NOT be cleared here.
    }

    /**
     * Checkpoint-aware Retry: re-issue ONLY the failed follow-up request using
     * the exact payloads captured before it. Side-effect tools are NOT
     * re-executed — their results are already inside requestMessages.
     */
    private void retryFollowUpFromCheckpoint() {
        if (isGenerating) return;
        final FollowUpCheckpoint checkpoint = followUpCheckpoint;
        if (checkpoint == null || currentChat == null) return;
        if (multiAiClient == null) {
            showChatStyleError(getString(R.string.chat_err_client_unavailable), false);
            return;
        }

        // Remove the error card from the previous failure.
        messagesAdapter.removeTrailingTypingMessages();
        messagesAdapter.removeReconnectingMessages();
        removeMessageFromChatAndUi(activeErrorMessage);
        activeErrorMessage = null;

        // Preserve a read-only planning session across a safe checkpoint retry.
        // Without this snapshot, cancelActiveTurnWork would correctly clean up
        // the old epoch but the retried request would accidentally advertise
        // write tools again.
        final boolean resumePlanMode = isPlanModeActiveForTurn(turnEpoch);
        final String resumePlanPreviousAccess = planModePreviousAccess;

        // New epoch/round state: a completely fresh UI + lock state.
        final long epoch = ++turnEpoch;
        turnCancelled = false;
        turnStartTime = System.currentTimeMillis();
        inToolFollowUp = true;
        followUpRetryCount = 0;
        toolResultBatchRetryCount = 0;

        // New turn token for the retried follow-up (cancel path stays uniform).
        cancelActiveTurnWork("superseded by follow-up retry");
        if (resumePlanMode) {
            synchronized (planModeLock) {
                planModeActive = true;
                planModeEpoch = epoch;
                planModePreviousAccess = resumePlanPreviousAccess;
            }
            turnSystemPrompt = buildEnrichedInstructions(true);
        }
        final OperationCancelToken turnToken = new OperationCancelToken("turn " + epoch);
        activeTurnToken = turnToken;

        // UI generating state.
        isGenerating = true;
        buttonSend.setEnabled(false);
        updateSendMicVisibility();
        hideEmptyState();
        stopEmptyStateAnimation();
        recyclerMessages.setVisibility(View.VISIBLE);
        followStreamToBottom = true;
        if (fabScrollDown != null) fabScrollDown.setVisibility(View.GONE);

        // Restore conversation context captured with the checkpoint, and give
        // the retried response a clean streaming target message.
        activeToolConversation = deepCopy(checkpoint.activeToolConversation);
        toolCalls.clear();
        toolCallsContainer = null;
        postToolMessage = null;
        clearActiveMessageTargets();
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        displayedContent = "";
        displayedThinking = "";
        contentRevealStartCp = 0;
        contentGhostStartCp = 0;
        stopContentAnimation();
        stopThinkingAnimation();
        stopThinkingTimer();

        Message typingMessage = new Message("", Message.TYPE_AI_TYPING);
        messagesAdapter.addMessage(typingMessage);
        recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
        // Re-open the workflow card (working state) on the message that owns
        // the workflow segments — it is the first AI message of the turn that
        // produced the tool calls. It is already persisted in the chat, find it.
        Message workflowOwner = findLatestPersistedAiMessage();
        while (workflowOwner != null && !workflowOwner.hasToolCalls()) {
            // walk back to the assistant round that issued the tool calls
            List<Message> messages = currentChat.getMessages();
            int idx = messages.indexOf(workflowOwner);
            workflowOwner = null;
            for (int i = idx - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m != null && m.getType() == Message.TYPE_AI) {
                    workflowOwner = m;
                    break;
                }
            }
        }
        if (workflowOwner != null) {
            workflowTargetMessage = workflowOwner;
            workflowOwner.setWorkingState(true);
            workflowOwner.setToolWorkflowSummary(false);
            messagesAdapter.refreshMessage(workflowOwner);
        }

        turnUsedTools = true;
        startWorkflowTimer();

        long responseRoundToken;
        synchronized (toolResultLock) {
            responseRoundToken = ++toolRoundGeneration;
            roundToolCalls.clear();
            roundToolResults.clear();
            roundToolOwners.clear();
            roundModelStreamComplete = false;
            roundBatchDispatched = false;
            roundExecutionDispatched = false;
            toolResultBatchInFlight = true;
        }

        // Deep copies again: the retry must not alias the checkpoint arrays.
        final JSONArray retryBatch = deepCopy(checkpoint.toolResultsBatch);
        final JSONArray retryMessages = deepCopy(checkpoint.requestMessages);
        if (retryBatch == null || retryMessages == null) {
            finishTurnWithError(epoch, "Не удалось восстановить checkpoint tool-result", false);
            return;
        }
        saveFollowUpCheckpoint(retryBatch, retryMessages, checkpoint.preToolPrefix);

        // ensurePostToolAiMessage swaps the typing bubble for the real streaming
        // target (inToolFollowUp is already true above).
        ensurePostToolAiMessage();
        sendToolResultBatchRequest(retryBatch, retryMessages, checkpoint.preToolPrefix,
                epoch, responseRoundToken);
    }

    /**
     * 🔥 Project-mode: после web_search автоматически делаем url_fetch для топ-3 ссылок.
     * Контент страниц добавляется как отдельные tool_calls + их результаты
     * отправляются обратно в AI, чтобы он мог писать ответ на основе реального контента.
     */
    private void autoFetchSearchLinks(ToolCall searchTool) {
        if (searchTool == null || searchTool.getResult() == null) return;

        org.json.JSONArray results = searchTool.getResult().optJSONArray("results");
        if (results == null) results = searchTool.getResult().optJSONArray("items");
        if (results == null || results.length() == 0) return;

        int maxFetch = Math.min(3, results.length());
        final java.util.List<String> urlsToFetch = new ArrayList<>();
        for (int i = 0; i < maxFetch; i++) {
            org.json.JSONObject item = results.optJSONObject(i);
            if (item == null) continue;
            String url = item.optString("url", item.optString("link", ""));
            if (!url.isEmpty() && url.startsWith("http")) {
                urlsToFetch.add(url);
            }
        }

        if (urlsToFetch.isEmpty()) return;


        // Показываем loading tool cards сразу
        for (String url : urlsToFetch) {
            java.util.Map<String, String> p = new java.util.HashMap<>();
            p.put("url", url);
            ToolCall fetchTc = new ToolCall("autofetch-" + System.currentTimeMillis() + "-" + url.hashCode(),
                "url_fetch", p);
            fetchTc.setStatus("loading");
            toolCalls.add(fetchTc);
        }
        if (currentMessage != null) {
            currentMessage.setToolCalls(new ArrayList<>(toolCalls));
            if (workflowTargetMessage != null) {
                workflowTargetMessage.setToolCalls(new ArrayList<>(toolCalls));
                messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
            } else {
                messagesAdapter.refreshMessage(currentMessage);
            }
        }

        // В фоне грузим каждый URL напрямую через HttpURLConnection
        new Thread(() -> {
            for (String url : urlsToFetch) {
                try {
                    org.json.JSONObject fetchResult = fetchUrlViaBackend(url);
                    runOnUiThread(() -> {
                        for (ToolCall tc : toolCalls) {
                            if ("url_fetch".equals(tc.getName())
                                && "loading".equals(tc.getStatus())
                                && url.equals(tc.getParams().get("url"))) {
                                tc.setStatus("success");
                                tc.setResult(fetchResult);
                                break;
                            }
                        }
                        if (currentMessage != null) {
                            currentMessage.setToolCalls(new ArrayList<>(toolCalls));
                            if (workflowTargetMessage != null) {
                                workflowTargetMessage.setToolCalls(new ArrayList<>(toolCalls));
                                messagesAdapter.updateWorkflowSegmentsDirect(workflowTargetMessage);
                            } else {
                                messagesAdapter.refreshMessage(currentMessage);
                            }
                        }
                    });
                } catch (Exception e) {
                }
            }
        }, "auto-fetch-links").start();
    }

    /**
     * Простой fetch HTML через встроенный HttpURLConnection.
     * Возвращает JSON { url, title, length, snippet }.
     */
    private org.json.JSONObject fetchUrlViaBackend(String url) {
        org.json.JSONObject out = new org.json.JSONObject();
        try {
            out.put("url", url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(url).openConnection();
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android) MultiAI/1.0");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            java.io.InputStream is = conn.getInputStream();
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            int capped = 0;
            while ((line = br.readLine()) != null && capped < 200000) {
                sb.append(line).append('\n');
                capped += line.length();
            }
            br.close();
            conn.disconnect();

            String html = sb.toString();
            String title = extractTitle(html);
            String text = stripHtml(html);
            if (text.length() > 2500) text = text.substring(0, 2500) + "…";

            out.put("title", title);
            out.put("length", text.length());
            out.put("snippet", text);
        } catch (Exception e) {
            try { out.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return out;
    }

    private String extractTitle(String html) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<title[^>]*>([^<]*)</title>", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(html);
            if (m.find()) return m.group(1).trim();
        } catch (Exception ignored) {}
        return "";
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        String cleaned = html
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("\\s+", " ");
        return cleaned.trim();
    }

    /**
     * 📍 Первичное приглашение выдать геолокацию (для точной погоды).
     * Показывается один раз — решение сохраняется в prefs.
     */
    private void maybePromptForLocation() {
        SharedPreferences p = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
        if (p.getBoolean("asked", false)) return;

        // Показываем мягкий rationale
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            ModernDialog.show(ChatActivity.this, getString(R.string.chat_location_title),
                getString(R.string.chat_location_message_project),
                getString(R.string.chat_not_now), getString(R.string.chat_allow), false, () -> {
                    p.edit().putBoolean("asked", true).apply();
                    if (ContextCompat.checkSelfPermission(ChatActivity.this,
                        Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        androidx.core.app.ActivityCompat.requestPermissions(ChatActivity.this,
                            new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            },
                            987);
                    } else {
                        cacheLastKnownLocation();
                    }
                });
        }, 2500);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 987) {
            boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) cacheLastKnownLocation();
        }
    }

    /**
     * Сохраняем последние известные координаты в prefs — их потом AI может спросить
     * для точной погоды. Без активного GPS-сессии, только last-known.
     */
    private void cacheLastKnownLocation() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                getSystemService(LOCATION_SERVICE);
            if (lm == null) return;
            if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            android.location.Location best = null;
            for (String provider : lm.getProviders(true)) {
                try {
                    @SuppressWarnings("MissingPermission")
                    android.location.Location loc = lm.getLastKnownLocation(provider);
                    if (loc == null) continue;
                    if (best == null || loc.getAccuracy() < best.getAccuracy()) {
                        best = loc;
                    }
                } catch (SecurityException ignored) {}
            }
            if (best != null) {
                SharedPreferences p = getSharedPreferences("multiaiide_location", MODE_PRIVATE);
                p.edit()
                    .putFloat("lat", (float) best.getLatitude())
                    .putFloat("lon", (float) best.getLongitude())
                    .putLong("ts", System.currentTimeMillis())
                    .apply();
            }
        } catch (Exception ignored) {
        }
    }

    private void finishThinkingPhase() {
        if (currentMessage == null || currentMessage.isThinkingFinished()) return;
        stopThinkingAnimation();
        stopThinkingTimer();
        boolean orderedWorkflow = currentMessage.getWorkflowSegments() != null
                && !currentMessage.getWorkflowSegments().isEmpty();
        if (thinkingBuffer.length() > 0 && !orderedWorkflow) {
            displayedThinking = thinkingBuffer.toString();
            currentMessage.setThinkingContent(displayedThinking);
        }
        currentMessage.setThinkingFinished(true);
        if (currentMessage.getThinkingTimeMs() <= 0L) currentMessage.setThinkingTimeMs(1L);
        if (orderedWorkflow) {
            currentMessage.setThinkingContent(null);
            messagesAdapter.updateWorkflowSegmentsDirect(currentMessage);
        } else {
            messagesAdapter.updateMessageThinkingDirect(currentMessage,
                    currentMessage.getThinkingContent() == null ? "" : currentMessage.getThinkingContent());
        }
        thinkingBuffer.setLength(0);
        displayedThinking = "";
    }

    /**
     * Запуск таймера thinking
     */
    private void startThinkingTimer() {
        if (thinkingTimerHandler == null) {
            thinkingTimerHandler = new Handler(Looper.getMainLooper());
        }
        
        thinkingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentMessage != null && thinkingStartTime > 0) {
                    long elapsed = System.currentTimeMillis() - thinkingStartTime;
                    if (timerTargetMessage == null) {
                        timerTargetMessage = currentMessage;
                    }
                    timerTargetMessage.setThinkingTimeMs(elapsed);
                    messagesAdapter.updateMessageThinkingTimeDirect(timerTargetMessage, elapsed);
                    thinkingTimerHandler.postDelayed(this, 100); // Обновляем каждые 100мс
                }
            }
        };
        
        thinkingTimerHandler.post(thinkingTimerRunnable);
    }
    
    /**
     * Остановка таймера thinking
     */
    private void stopThinkingTimer() {
        if (thinkingTimerHandler != null && thinkingTimerRunnable != null) {
            thinkingTimerHandler.removeCallbacks(thinkingTimerRunnable);
        }
        thinkingStartTime = 0;
    }

    /**
     * Запуск live-таймера "Работает уже Xс" для workflow summary.
     * Показывает workflowArea с рабочим состоянием во время стриминга.
     */
    private void startWorkflowTimer() {
        if (workflowTimerHandler == null) {
            workflowTimerHandler = new Handler(Looper.getMainLooper());
        }
        stopWorkflowTimer();
        workflowTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (turnStartTime > 0 && isGenerating) {
                    long elapsed = System.currentTimeMillis() - turnStartTime;
                    // Use stable workflowTargetMessage — stays on the FIRST
                    // AI message of the turn, doesn't jump to postToolMessage
                    Message target = workflowTargetMessage;
                    if (target == null) target = getAnimationTargetMessage();
                    if (target != null && target.getType() == Message.TYPE_AI) {
                        // Mark as "working" — adapter shows "Работает уже"
                        target.setToolWorkflowSummary(false);
                        target.setTurnDurationMs(elapsed);
                        target.setWorkingState(true);
                        messagesAdapter.updateWorkflowWorking(target,
                                getString(R.string.chat_workflow_working, formatDuration(elapsed)));
                    }
                    workflowTimerHandler.postDelayed(this, 200);
                }
            }
        };
        workflowTimerHandler.post(workflowTimerRunnable);
    }

    private void stopWorkflowTimer() {
        if (workflowTimerHandler != null && workflowTimerRunnable != null) {
            workflowTimerHandler.removeCallbacks(workflowTimerRunnable);
            workflowTimerRunnable = null;
        }
    }

    /** Format duration as "Xс", "Xм Yс", "Xч Yм". */
    private String formatDuration(long ms) {
        long totalSeconds = Math.max(1, ms / 1000);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) return hours + " м " + minutes + " с";
        if (minutes > 0) return minutes + " м " + seconds + " с";
        return seconds + " с";
    }

    private void scrollMessagesToBottom() {
        if (recyclerMessages == null || messagesAdapter == null || messagesAdapter.getItemCount() == 0) {
            return;
        }

        if (!followStreamToBottom) {
            return;
        }

        // Scroll to absolute bottom using scroll range
        recyclerMessages.post(() -> {
            if (recyclerMessages == null || !followStreamToBottom) return;
            if (recyclerMessages.canScrollVertically(1)) {
                recyclerMessages.scrollBy(0, Integer.MAX_VALUE);
            }
        });
    }

    /** Ограничивает автоскролл во время посимвольного стрима до 10 FPS. */
    private void scrollMessagesToBottomDuringStream() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastStreamingScrollAtMs < 100L) return;
        lastStreamingScrollAtMs = now;
        scrollMessagesToBottom();
    }

    private void resetScrollDownState() {
        followStreamToBottom = true;
        userScrollingMessages = false;
        lastStreamingScrollAtMs = 0L;
        if (fabScrollDown != null) {
            fabScrollDown.animate().cancel();
            fabScrollDown.setVisibility(View.GONE);
        }
    }

    private void updateScrollDownFab(RecyclerView recyclerView, int dy) {
        if (recyclerView == null || fabScrollDown == null) return;
        boolean visualBottom = !recyclerView.canScrollVertically(1);
        RecyclerView.LayoutManager manager = recyclerView.getLayoutManager();
        if (!visualBottom && manager instanceof LinearLayoutManager && messagesAdapter != null) {
            LinearLayoutManager linear = (LinearLayoutManager) manager;
            int target = messagesAdapter.getLastVisibleMessagePosition();
            if (target != RecyclerView.NO_POSITION
                    && linear.findLastVisibleItemPosition() >= target) {
                View targetView = linear.findViewByPosition(target);
                int viewportBottom = recyclerView.getHeight() - recyclerView.getPaddingBottom();
                visualBottom = targetView != null
                        && linear.getDecoratedBottom(targetView) <= viewportBottom + dpToPx(12);
            }
        }
        if (visualBottom) {
            followStreamToBottom = true;
            fabScrollDown.setVisibility(View.GONE);
        } else if (userScrollingMessages && dy < 0) {
            followStreamToBottom = false;
            fabScrollDown.setVisibility(View.VISIBLE);
        }
    }

    private void persistAiMessageIfNeeded(Message message) {
        persistAiMessageInChat(currentChat, message);
    }

    /**
     * Checkpoints visible Work Mode progress while a response is streaming.
     * We save the latest state at most once per short interval, so a process
     * recreation cannot keep completed tool cards but lose their AI content.
     */
    private void scheduleStreamingAiPersist(Message message) {
        Chat chat = currentChat;
        if (message == null || chat == null || chatManager == null) return;
        pendingStreamPersistChat = chat;
        pendingStreamPersistMessage = message;
        if (pendingStreamPersistTask != null) {
            animationHandler.removeCallbacks(pendingStreamPersistTask);
        }
        long elapsed = android.os.SystemClock.uptimeMillis() - lastStreamPersistAtMs;
        long delay = lastStreamPersistAtMs == 0L ? 0L
                : Math.max(0L, STREAM_PERSIST_INTERVAL_MS - elapsed);
        pendingStreamPersistTask = this::flushStreamingAiPersist;
        animationHandler.postDelayed(pendingStreamPersistTask, delay);
    }

    private void flushStreamingAiPersist() {
        if (pendingStreamPersistTask != null) {
            animationHandler.removeCallbacks(pendingStreamPersistTask);
        }
        pendingStreamPersistTask = null;
        Chat chat = pendingStreamPersistChat;
        Message message = pendingStreamPersistMessage;
        pendingStreamPersistChat = null;
        pendingStreamPersistMessage = null;
        if (chat == null || message == null) return;
        lastStreamPersistAtMs = android.os.SystemClock.uptimeMillis();
        persistAiMessageInChat(chat, message);
    }

    private void persistAiMessageInChat(Chat chat, Message message) {
        if (message == null || chat == null || chatManager == null) return;
        persistAiMessageInto(chat, message);
        try {
            chatManager.updateChat(chat);
        } catch (Exception ignored) {
        }
    }

    /** Добавляет сообщение в указанный чат, если его там ещё нет (без записи на диск). */
    private void persistAiMessageInto(Chat chat, Message message) {
        if (chat == null || message == null) return;
        // postToolMessage is an API-round accumulator: it holds every chunk of
        // the round, including text that moveStandaloneContentToWorkflow() has
        // already relocated into the process drawer. Persisting it produced a
        // second bubble repeating the drawer text under the final answer.
        if (message == postToolMessage) return;

        // Skip empty AI messages only when they have no visible workflow state.
        // During a tool turn the ordered workflow segments are the visible
        // source of truth even before the final answer text arrives.
        if (message.getType() == Message.TYPE_AI) {
            boolean hasContent = message.getContent() != null && !message.getContent().trim().isEmpty();
            boolean hasThinking = message.getThinkingContent() != null && !message.getThinkingContent().trim().isEmpty();
            boolean hasTools = message.hasToolCalls() && !message.getToolCalls().isEmpty();
            boolean hasWorkflow = message.getWorkflowSegments() != null
                    && !message.getWorkflowSegments().isEmpty();
            if (!hasContent && !hasThinking && !hasTools && !hasWorkflow) {
                return;
            }
        }

        List<Message> chatMessages = chat.getMessages();
        for (Message msg : chatMessages) {
            if (msg == message) {
                return; // уже сохранено
            }
        }
        chat.addMessage(message);
    }

    private boolean isMessageUpToDate(Message message) {
        if (message == null) {
            return false;
        }

        String pendingThinkingText = pendingThinking != null ? pendingThinking : "";
        String pendingContentText = pendingContent != null ? pendingContent : "";
        String savedThinking = message.getThinkingContent() != null ? message.getThinkingContent() : "";
        String savedContent = message.getContent() != null ? message.getContent() : "";

        boolean thinkingCovered = pendingThinkingText.isEmpty() || savedThinking.equals(pendingThinkingText);
        boolean contentCovered = pendingContentText.isEmpty() || savedContent.equals(pendingContentText);
        return thinkingCovered && contentCovered;
    }

    private Message findLatestPersistedAiMessage() {
        if (currentChat == null || currentChat.getMessages() == null) {
            return null;
        }

        List<Message> messages = currentChat.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.getType() == Message.TYPE_AI) {
                return message;
            }
        }
        return null;
    }

    private Message findLatestUserMessage() {
        if (currentChat == null || currentChat.getMessages() == null) return null;
        List<Message> messages = currentChat.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message != null && message.getType() == Message.TYPE_USER) return message;
        }
        return null;
    }

    private void ensurePostToolAiMessage() {
        if (!inToolFollowUp || currentChat == null) return;
        turnUsedTools = true;
        if (postToolMessage != null) return;

        // This object represents the assistant API round only. It is never
        // inserted into RecyclerView/chat: all visible output belongs to the
        // stable workflowTargetMessage row.
        postToolMessage = new Message("", Message.TYPE_AI);
        postToolMessage.setStreaming(true);
        postToolMessage.setToolCalls(new ArrayList<>());
        currentMessage = postToolMessage;
        if (workflowTargetMessage != null) {
            workflowTargetMessage.setContent("");
            workflowTargetMessage.setStandaloneContentVisible(true);
            messagesAdapter.updateMessageContentDirect(workflowTargetMessage, "");
        }
        animationTargetMessage = workflowTargetMessage;
        timerTargetMessage = workflowTargetMessage;

        toolCalls.clear();
        toolCallsContainer = null;
        contentBuffer.setLength(0);
        thinkingBuffer.setLength(0);
        displayedContent = workflowTargetMessage != null
                ? workflowTargetMessage.getContentSafe() : "";
        displayedThinking = "";
        stopContentAnimation();
        stopThinkingAnimation();
        stopThinkingTimer();
        thinkingStartTime = System.currentTimeMillis();
        startThinkingTimer();
    }


    /**
     * Inline-вариант: убеждаемся, что есть целевое сообщение для приёма стрима.
     * Используется основным колбэком MultiAiClient — делегирует в ensurePostToolAiMessage.
     */
    private void ensureInlinePostToolAiMessage() {
        ensurePostToolAiMessage();
    }

    /**
     * Проверяет, что сообщение уже было персистировано ранее (т.е. его содержимое
     * совпадает с тем, что осталось в pending-буферах). Используется при восстановлении
     * состояния после поворота экрана, чтобы не дублировать данные.
     */
    private boolean pendingStateAlreadyPersisted(Message message) {
        return isMessageUpToDate(message);
    }

    /**
     * Прикрепление фото
     */
    private void attachPhoto() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                    != PackageManager.PERMISSION_GRANTED) {
                pendingPermissionAction = "gallery";
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES);
                return;
            }
        }
        openGallery();
    }
    
    /**
     * Открытие галереи
     */
    private void openGallery() {
        if (isPickerOpening) return;
        isPickerOpening = true;
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        pickImageLauncher.launch(intent);
        // Reset debounce after 1 second
        new Handler(Looper.getMainLooper()).postDelayed(() -> isPickerOpening = false, 1000);
    }
    
    /**
     * Загрузка изображения
     */
    private void loadImage(Uri imageUri) {
        try {
            // Показываем loading
            imageLoadingOverlay.setVisibility(View.VISIBLE);
            
            // Загружаем в фоновом потоке
            new Thread(() -> {
                try {
                    InputStream inputStream = getContentResolver().openInputStream(imageUri);
                    Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                    inputStream.close();
                    
                    // Сжимаем изображение если нужно
                    Bitmap resizedBitmap = resizeImage(bitmap, 1024, 1024);
                    
                    // Конвертируем в Base64
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                    byte[] imageBytes = baos.toByteArray();
                    String base64 = Base64.encodeToString(imageBytes, Base64.DEFAULT);
                    
                    // Получаем имя файла
                    String fileName = getFileName(imageUri);
                    long fileSize = imageBytes.length;
                    
                    runOnUiThread(() -> {
                        selectedImageBase64 = base64;
                        selectedImageName = fileName;

                        // ChatGPT-стиль: фото = только плитка с крестиком
                        if (imagePreview != null) {
                            imagePreview.clearColorFilter();
                            imagePreview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                            imagePreview.setImageBitmap(resizedBitmap);
                        }
                        if (imageFileName != null) imageFileName.setText(fileName);
                        showAttachmentInline(true);
                        if (imageLoadingOverlay != null) imageLoadingOverlay.setVisibility(View.GONE);

                    });
                    
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        imageLoadingOverlay.setVisibility(View.GONE);
                        ModernToast.show(this, getString(R.string.chat_error_load_image, e.getMessage()));
                    });
                }
            }).start();

        } catch (Exception e) {
            imageLoadingOverlay.setVisibility(View.GONE);
            ModernToast.show(this, getString(R.string.chat_error_generic, e.getMessage()));
        }
    }
    
    /**
     * Изменение размера изображения
     */
    private Bitmap resizeImage(Bitmap bitmap, int maxWidth, int maxHeight) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        
        if (width <= maxWidth && height <= maxHeight) {
            return bitmap;
        }
        
        float ratio = Math.min((float) maxWidth / width, (float) maxHeight / height);
        int newWidth = Math.round(width * ratio);
        int newHeight = Math.round(height * ratio);
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
    }
    
    /**
     * Получение имени файла из URI
     */
    private String getFileName(Uri uri) {
        String fileName = "image.jpg";
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex);
                }
                cursor.close();
            }
        } catch (Exception e) {
        }
        return fileName;
    }
    
    /**
     * Форматирование размера файла
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
    
    /**
     * Удаление изображения
     */
    private void removeImage() {
        selectedImageBase64 = null;
        selectedImageName = null;
        attachedFileName = null;
        attachedFileContent = null;
        pendingBranchContent = null;
        imagePreviewCard.setVisibility(View.GONE);
        if (imagePreview != null) {
            imagePreview.clearColorFilter();
            imagePreview.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        }
    }
    
    @Override
    protected void onDestroy() {
        // Persist a visible partial before invalidating callbacks. Otherwise a
        // recreate could leave an answer permanently marked as streaming.
        if (isGenerating) {
            stopGeneration();
        } else {
            turnEpoch++;
            turnCancelled = true;
            cancelActiveTurnWork("activity destroyed");
            releaseQuestionSheets();
            clearFollowUpCheckpoint();
            if (multiAiClient != null) multiAiClient.cancel();
        }
        super.onDestroy();
        // 📳 Stop shake detection.
        if (shakeDetector != null) {
            shakeDetector.unregister();
            shakeDetector = null;
        }
        stopEmptyStateAnimation();
        View contentRoot = findViewById(android.R.id.content);
        if (inputKeyboardListener != null && contentRoot != null && contentRoot.getViewTreeObserver().isAlive()) {
            contentRoot.getViewTreeObserver().removeOnGlobalLayoutListener(inputKeyboardListener);
        }
        
        // Останавливаем таймеры и анимации
        stopThinkingTimer();
        finishAllAnimations(); // Завершаем анимации
        // Voice visual state machine cleanup.
        stopTranscribingDots();
        if (whisperSilenceHandler != null && whisperSilenceRunnable != null) {
            whisperSilenceHandler.removeCallbacks(whisperSilenceRunnable);
        }
        // TTS MediaPlayer cleanup — avoid leaking the native player across Activity death.
        if (ttsPlayer != null) {
            try { ttsPlayer.stop(); ttsPlayer.release(); } catch (Exception ignored) {}
            ttsPlayer = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AppSettings currentSettings = new AppSettings(this);
        // Recreate if a visual general setting changed while we were paused.
        if (currentSettings.isSegmentedModeEnabled() != lastSegmentedSetting
                || !currentSettings.getEmojiMode().equals(lastEmojiMode)) {
            recreate();
            return;
        }
        if (currentSettings.isChatMode()) {
            Intent intent = new Intent(this, ChatModeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
            return;
        }
        isInForeground = true;
        startEmptyStateAnimationIfNeeded();
        startPolling();
        // Keep drawer rows consistent with Chat Mode (hides terminal/files/editor)
        updateDrawerForChatMode();
        // Dismiss notification when user returns
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(NOTIF_ID_AI_RESPONSE);

        // 📳 Shake-to-report: (re)register when the activity returns to the foreground.
        // Checking the setting here means a disabled toggle (e.g. flipped from the
        // report-bug sheet) takes effect on the next resume without recreating the activity.
        if (new AppSettings(this).isShakeToReportEnabled()) {
            if (shakeDetector == null) {
                shakeDetector = new app.multiai.mobile.utils.ShakeDetector(
                        () -> app.multiai.mobile.ui.kit.ReportBugSheet.show(ChatActivity.this));
            }
            shakeDetector.register(this);
        } else if (shakeDetector != null) {
            shakeDetector.unregister();
        }
    }

    @Override
    protected void onPause() {
        isInForeground = false;
        flushStreamingAiPersist();
        stopPolling();
        stopEmptyStateAnimation();
        // 📳 Stop shake detection while the activity isn't in the foreground to save battery.
        if (shakeDetector != null) {
            shakeDetector.unregister();
        }
        // Страховочный сейв: уходим с экрана — текущий чат обязан быть на диске.
        if (currentChat != null && chatManager != null) {
            try {
                chatManager.updateChat(currentChat);
            } catch (Exception ignored) {
            }
        }
        super.onPause();
    }
    
    /**
     * 🔥 Сохраняем состояние при повороте экрана
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        // Сохраняем состояние генерации
        outState.putBoolean("isGenerating", isGenerating);
        outState.putBoolean("processResumeNeeded", isGenerating);
        outState.putLong("thinkingStartTime", thinkingStartTime);
        
        // 🔥 Сохраняем есть ли typing message
        boolean hasTypingMessage = false;
        if (messagesAdapter.getItemCount() > 0) {
            Message lastMsg = messagesAdapter.getMessageAt(messagesAdapter.getItemCount() - 1);
            if (lastMsg != null && lastMsg.getType() == Message.TYPE_AI_TYPING) {
                hasTypingMessage = true;
            }
        }
        outState.putBoolean("hasTypingMessage", hasTypingMessage);
        
        // Сохраняем буферы анимации
        outState.putString("contentBuffer", contentBuffer.toString());
        outState.putString("thinkingBuffer", thinkingBuffer.toString());
        outState.putString("displayedContent", displayedContent);
        outState.putString("displayedThinking", displayedThinking);
        
        // Сохраняем изображение
        outState.putString("selectedImageBase64", selectedImageBase64);
        outState.putString("selectedImageName", selectedImageName);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);

        // Восстанавливаем состояние генерации
        isGenerating = savedInstanceState.getBoolean("isGenerating", false);
        thinkingStartTime = savedInstanceState.getLong("thinkingStartTime", 0);
        boolean hasTypingMessage = savedInstanceState.getBoolean("hasTypingMessage", false);
        
        // Восстанавливаем буферы анимации
        String savedContentBuffer = savedInstanceState.getString("contentBuffer", "");
        String savedThinkingBuffer = savedInstanceState.getString("thinkingBuffer", "");
        displayedContent = savedInstanceState.getString("displayedContent", "");
        displayedThinking = savedInstanceState.getString("displayedThinking", "");
        
        contentBuffer.setLength(0);
        contentBuffer.append(savedContentBuffer);
        thinkingBuffer.setLength(0);
        thinkingBuffer.append(savedThinkingBuffer);
        
        // Восстанавливаем изображение
        selectedImageBase64 = savedInstanceState.getString("selectedImageBase64");
        selectedImageName = savedInstanceState.getString("selectedImageName");
        
        // Восстанавливаем UI состояние
        if (isGenerating) {
            buttonSend.setEnabled(false);
            
            // Rotation is handled by configChanges and keeps the live callback.
            // After process death there is no socket to reattach, so resume the
            // last user turn from durable chat state instead of hanging forever.
            if (multiAiClient != null) {
                // 🔥 ВОССТАНАВЛИВАЕМ КОНТЕНТ ИЗ СТАТИЧЕСКОГО ХРАНИЛИЩА
                if (pendingContent != null || pendingThinking != null) {
                    Message restoredMessage = findLatestPersistedAiMessage();
                    if (pendingStateAlreadyPersisted(restoredMessage)) {
                    pendingContent = null;
                    pendingThinking = null;
                    hasPendingMessage = false;
                    contentBuffer.setLength(0);
                    thinkingBuffer.setLength(0);
                    displayedContent = "";
                    displayedThinking = "";
                    clearActiveMessageTargets();
                    postToolMessage = null;
                    } else {
                        hasPendingMessage = true;
                        if (restoredMessage != null) {
                            setActiveMessageTargets(restoredMessage);
                        } else {
                            Message newMessage = new Message("", Message.TYPE_AI);
                            messagesAdapter.addMessage(newMessage);
                            setActiveMessageTargets(newMessage);
                        }
                    
                        // Восстанавливаем thinking
                        if (pendingThinking != null) {
                            thinkingBuffer.setLength(0);
                            thinkingBuffer.append(pendingThinking);
                            displayedThinking = pendingThinking;
                            currentMessage.setThinkingContent(displayedThinking);
                            messagesAdapter.updateMessageThinkingDirect(currentMessage, displayedThinking);
                        }
                    
                        // Восстанавливаем content
                        if (pendingContent != null) {
                            contentBuffer.setLength(0);
                            contentBuffer.append(pendingContent);
                            displayedContent = pendingContent;
                            currentMessage.setContent(displayedContent);
                            messagesAdapter.updateMessageContentDirect(currentMessage, displayedContent);
                            messagesAdapter.finalizeMessageContent(currentMessage);
                        }
                    
                        recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
                    
                        // Очищаем статическое хранилище
                        pendingContent = null;
                        pendingThinking = null;
                        hasPendingMessage = false;
                    
                    }
                }
                
                if (savedInstanceState.getBoolean("processResumeNeeded", false)) {
                    final Message resumeUser = findLatestUserMessage();
                    if (resumeUser != null) {
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (!isFinishing() && !isGenerating) {
                                editMessage.setText(resumeUser.getContentSafe());
                                editMessage.setSelection(editMessage.length());
                                sendMessage();
                            }
                        }, 180L);
                    }
                }
            }
            
            // 🔥 Восстанавливаем typing message если нужно
            if (hasTypingMessage && messagesAdapter.getItemCount() > 0) {
                Message lastMsg = messagesAdapter.getMessageAt(messagesAdapter.getItemCount() - 1);
                if (lastMsg == null || lastMsg.getType() != Message.TYPE_AI_TYPING) {
                    // Добавляем typing message обратно
                    Message typingMessage = new Message("", Message.TYPE_AI_TYPING);
                    messagesAdapter.addMessage(typingMessage);
                    recyclerMessages.scrollToPosition(messagesAdapter.getItemCount() - 1);
                }
            }
            
            // Если есть thinking - запускаем таймер
            if (thinkingStartTime > 0) {
                startThinkingTimer();
            }
            
            // Если есть буферы - продолжаем анимацию
            if (thinkingBuffer.length() > 0 && displayedThinking.length() < thinkingBuffer.length()) {
                startThinkingAnimation();
            }
            if (contentBuffer.length() > 0 && displayedContent.length() < contentBuffer.length()) {
                startContentAnimation();
            }
        }
        
        // Восстанавливаем превью изображения
        if (selectedImageBase64 != null && selectedImageName != null) {
            // Показываем превью (без повторной загрузки)
            imageFileName.setText(selectedImageName);
            imagePreviewCard.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Запускает посимвольную анимацию thinking
     */
    private void startThinkingAnimation() {
        if (thinkingAnimationTask != null) {
            return; // Уже запущена
        }

        thinkingAnimationTask = new Runnable() {
            @Override
            public void run() {
                Message target = getAnimationTargetMessage();
                if (target == null) {
                    stopThinkingAnimation();
                    return;
                }
                
                String fullText = thinkingBuffer.toString();
                
                // Если показали всё - ждём новых данных
                if (displayedThinking.length() >= fullText.length()) {
                    // 🔥 Продолжаем проверять каждые 50ms (не останавливаем!)
                    animationHandler.postDelayed(this, 50);
                    return;
                }
                
                // 🔥 Thinking anim: чуть быстрее content, т.к. мы читаем по диагонали
                int thinkLag = fullText.length() - displayedThinking.length();
                int thinkStep;
                if (thinkLag > 3000) thinkStep = Math.max(80, thinkLag / 30);
                else if (thinkLag > 1000) thinkStep = 50;
                else if (thinkLag > 400) thinkStep = 22;
                else if (thinkLag > 150) thinkStep = 10;
                else if (thinkLag > 50) thinkStep = 5;
                else if (thinkLag > 15) thinkStep = 2;
                else thinkStep = 1;

                int nextLen = Math.min(fullText.length(), displayedThinking.length() + thinkStep);
                displayedThinking = fullText.substring(0, nextLen);
                target.setThinkingContent(displayedThinking);
                messagesAdapter.updateMessageThinkingDirect(target, displayedThinking);
                
                // Следующий символ через CHAR_DELAY_MS
                animationHandler.postDelayed(this, CHAR_DELAY_MS);
            }
        };
        
        animationHandler.post(thinkingAnimationTask);
    }
    
    /**
     * Останавливает посимвольную анимацию thinking
     */
    private void stopThinkingAnimation() {
        if (thinkingAnimationTask != null) {
            animationHandler.removeCallbacks(thinkingAnimationTask);
            thinkingAnimationTask = null;
        }
        
        // 🔥 НЕ показываем весь текст сразу - пусть анимация завершится естественно
        // Или показываем только если это финальная остановка (onComplete)
    }
    
    /**
     * Запускает посимвольную анимацию content
     * 🔥 КОПИЯ thinking анимации с фиксом для эмодзи!
     */
    private void startContentAnimation() {
        if (contentAnimationTask != null) {
            return; // Уже запущена
        }

        contentAnimationTask = new Runnable() {
            @Override
            public void run() {
                Message target = getAnimationTargetMessage();
                if (target == null) {
                    stopContentAnimation();
                    return;
                }
                
                String fullText = contentBuffer.toString();
                if (!fullText.startsWith(displayedContent)) {
                    // Displayed prefix no longer matches (retry/round switch):
                    // restart the reveal from scratch without file I/O logging.
                    displayedContent = "";
                }
                
                // 🔥 Считаем РЕАЛЬНЫЕ символы (с учётом эмодзи!)
                int displayedCodePoints = displayedContent.codePointCount(0, displayedContent.length());
                int fullCodePoints = fullText.codePointCount(0, fullText.length());
                
                // Если показали всё - ждём новых данных
                if (displayedCodePoints >= fullCodePoints) {
                    // 🔥 Убираем fade с последних символов — текст полностью виден
                    if (contentRevealStartCp < fullCodePoints) {
                        contentRevealStartCp = fullCodePoints;
                        contentGhostStartCp = fullCodePoints;
                        target.setRevealStartCp(fullCodePoints);
                        target.setRevealEndCp(fullCodePoints);
                        target.setGhostStartCp(fullCodePoints);
                        messagesAdapter.updateMessageContentDirect(target, displayedContent);
                    }
                    
                    // 🔥 Если генерация закончена И показали весь текст - финальный рендеринг!
                    if (!isGenerating) {
                        stopContentAnimation();
                        long epoch = pendingSuccessfulFinishEpoch;
                        if (epoch >= 0L) completeTurnSuccessfully(epoch);
                        else {
                            messagesAdapter.finalizeMessageContent(target);
                            maybeFinalizeUiAndCleanup();
                        }
                        return;
                    }
                    
                    // Небольшой idle-интервал, чтобы не греть CPU впустую
                    animationHandler.postDelayed(this, 8);
                    return;
                }
                
                // 🔥 ChatGPT-style streaming:
                // - кадр 60 FPS (CONTENT_CHAR_DELAY_MS = 16ms)
                // - скорость ~50-80 симв/сек при малом лаге (комфортно для чтения)
                // - ускоряется при большом лаге, чтобы не отставать
                // - никогда не дампит весь текст моментально
                int lag = fullCodePoints - displayedCodePoints;
                int step;
                if (lag > 3000) step = Math.max(60, lag / 40);     // огромное отставание — большими шагами
                else if (lag > 1000) step = 40;
                else if (lag > 400) step = 18;
                else if (lag > 150) step = 8;
                else if (lag > 50) step = 4;
                else if (lag > 15) step = 2;
                else step = 1;                                     // базовая скорость — 1 символ за кадр

                int nextCodePoint = Math.min(fullCodePoints, displayedCodePoints + step);
                int nextIndex = fullText.offsetByCodePoints(0, nextCodePoint);
                displayedContent = fullText.substring(0, nextIndex);
                target.setContent(displayedContent);

                if (nextCodePoint > displayedCodePoints) {
                    // Хвост плавного проявления: только последние 3 символа
                    final int fadeTailCp = 3;
                    contentRevealStartCp = Math.max(0, nextCodePoint - fadeTailCp);
                }

                contentGhostStartCp = contentRevealStartCp;

                target.setRevealStartCp(contentRevealStartCp);
                target.setRevealEndCp(nextCodePoint);
                target.setGhostStartCp(contentGhostStartCp);

                messagesAdapter.updateMessageContentDirect(target, displayedContent);
                scrollMessagesToBottomDuringStream();
                
                if (CONTENT_CHAR_DELAY_MS <= 0) {
                    animationHandler.post(this);
                } else {
                    animationHandler.postDelayed(this, CONTENT_CHAR_DELAY_MS);
                }
            }
        };
        
        animationHandler.post(contentAnimationTask);
    }
    
    /**
     * Останавливает посимвольную анимацию content
     */
    private void stopContentAnimation() {
        if (contentAnimationTask != null) {
            animationHandler.removeCallbacks(contentAnimationTask);
            contentAnimationTask = null;
        }
    }
    
    /**
     * 🔥 Принудительно завершает анимацию и показывает весь текст (для onComplete)
     */
    private void finishAllAnimations() {
        // 🔥 Останавливаем таймеры
        if (thinkingAnimationTask != null) {
            animationHandler.removeCallbacks(thinkingAnimationTask);
            thinkingAnimationTask = null;
        }
        if (contentAnimationTask != null) {
            animationHandler.removeCallbacks(contentAnimationTask);
            contentAnimationTask = null;
        }

        // 🔥 Показываем ВЕСЬ оставшийся текст сразу!
        Message target = getAnimationTargetMessage();
        if (target != null) {
            if (thinkingBuffer.length() > 0) {
                displayedThinking = thinkingBuffer.toString();
                target.setThinkingContent(displayedThinking);
                messagesAdapter.updateMessageThinkingDirect(target, displayedThinking);
            }
            if (contentBuffer.length() > 0) {
                displayedContent = contentBuffer.toString();
                target.setContent(displayedContent);
                int cp = displayedContent.codePointCount(0, displayedContent.length());
                target.setRevealStartCp(cp);
                target.setRevealEndCp(cp);
                target.setGhostStartCp(cp);
                messagesAdapter.updateMessageContentDirect(target, displayedContent);
            }

            if (!isGenerating) {
                persistAiMessageIfNeeded(target);
            }
        }
        
    }

    // ══════════════════ MODEL SELECTOR ══════════════════

    /** Восстановить глобальную модель из prefs (оба файла — settings + profile). */
    private void restoreGlobalModel() {
        SharedPreferences settings = getSharedPreferences("multiaiide_settings", MODE_PRIVATE);
        selectedModelId = settings.getString("selected_model_id", null);
        selectedModelName = settings.getString("selected_model_name", null);
        if (selectedModelId == null) {
            SharedPreferences profile = getSharedPreferences("multiai_profile", MODE_PRIVATE);
            selectedModelId = profile.getString("selected_model_id", null);
            selectedModelName = profile.getString("selected_model_name", null);
        }
        if (selectedModelName == null) selectedModelName = getString(R.string.chat_model_not_selected);
        if (btnModelSelector != null) btnModelSelector.setText(selectedModelName);
        updateBrandingPill();
    }

    /**
     * 🔥 Загрузка доступных моделей с MultiAI (/v1/models)
     */
    private void loadAvailableModels() {
        availableModels.clear();
        modelCatalog.clear();

        String accessToken = OAuthSession.get().getAccessToken();

        new Thread(() -> {
            List<app.multiai.mobile.models.ModelInfo> catalog =
                    new MultiAiClient().fetchModelCatalog(accessToken);
            runOnUiThread(() -> {
                modelCatalog.clear();
                modelCatalog.addAll(catalog);
                availableModels.clear();
                for (app.multiai.mobile.models.ModelInfo mi : catalog) {
                    availableModels.add(new String[]{mi.id, mi.displayName});
                }

                boolean currentValid = false;
                if (selectedModelId != null) {
                    for (app.multiai.mobile.models.ModelInfo mi : modelCatalog) {
                        if (mi.id.equals(selectedModelId)) {
                            currentValid = true;
                            // Свежее имя из каталога (prefs мог хранить старый формат)
                            if (!mi.displayName.equals(selectedModelName)) {
                                selectedModelName = mi.displayName;
                                // Sync pretty name to both prefs files.
                                getSharedPreferences("multiaiide_settings", MODE_PRIVATE).edit()
                                        .putString("selected_model_name", selectedModelName)
                                        .apply();
                                getSharedPreferences("multiai_profile", MODE_PRIVATE).edit()
                                        .putString("selected_model_name", selectedModelName)
                                        .putString("selected_model_id", selectedModelId)
                                        .apply();
                            }
                            break;
                        }
                    }
                }

                if (!currentValid) {
                    selectedModelId = null;
                    selectedModelName = getString(R.string.chat_model_not_selected);
                }

                // Восстановить сохранённый effort выбранной модели
                if (multiAiClient != null) {
                    app.multiai.mobile.models.ModelInfo sel = null;
                    for (app.multiai.mobile.models.ModelInfo mi : modelCatalog) {
                        if (mi.id.equals(selectedModelId)) { sel = mi; break; }
                    }
                    if (sel != null) {
                        SharedPreferences prefs = getSharedPreferences(
                                "multiaiide_settings", MODE_PRIVATE);
                        String effortKey = "effort_" + sel.id;
                        boolean fromChat = currentChat != null
                                && sel.id.equals(currentChat.getModelId());
                        String stored = fromChat
                                ? currentChat.getModelEffort()
                                : prefs.getString(effortKey, sel.reasoningDefault);
                        selectedEffort = sel.resolveEffort(stored);
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
                    } else {
                        selectedEffort = null;
                        multiAiClient.setEffort(null);
                    }
                }

                if (btnModelSelector != null) {
                    btnModelSelector.setText(selectedModelName);
                }
                updateBrandingPill();
                refreshContextRing();
            });
        }).start();
    }

    private void showModelSelector() {
        if (modelCatalog.isEmpty()) {
            ModernToast.show(this, getString(R.string.chat_model_loading_dots));
            loadAvailableModels();
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
                            "multiaiide_settings", MODE_PRIVATE).edit()
                            .putString("selected_model_id", selectedModelId)
                            .putString("selected_model_name", selectedModelName);
                    String effortKey = "effort_" + model.id;
                    if (selectedEffort == null) modelPrefs.remove(effortKey);
                    else modelPrefs.putString(effortKey, selectedEffort);
                    modelPrefs.apply();
                    // Sync to Chat-Mode prefs so pretty names survive cross-mode restarts.
                    getSharedPreferences("multiai_profile", MODE_PRIVATE).edit()
                            .putString("selected_model_id", selectedModelId)
                            .putString("selected_model_name", selectedModelName)
                            .apply();
                    // Выбор в открытом чате закрепляется за ним сразу — не
                    // только при следующей отправке.
                    if (currentChat != null) {
                        stampChatModel();
                        try {
                            chatManager.updateChat(currentChat);
                        } catch (Exception ignored) {
                        }
                    }
                    if (btnModelSelector != null) {
                        btnModelSelector.setText(selectedModelName);
                    }
                    updateBrandingPill();
                });
    }

    /** Контекст-кольцо у кнопки отправки: заполненность окна текущим диалогом. */
    private app.multiai.mobile.ui.kit.ModelCatalogUi.ContextRing contextRing;

    private void setupContextRing() {
        if (contextRing != null) return;
        // inputBox теперь вертикальный (превью сверху) — кольцо в строке ввода.
        android.view.ViewGroup inputBar = findViewById(R.id.inputRow);
        if (!(inputBar instanceof android.widget.FrameLayout)) return;
        contextRing = new app.multiai.mobile.ui.kit.ModelCatalogUi.ContextRing(this);
        android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
        lp.rightMargin = dpToPx(52); // левее mic/send
        contextRing.setLayoutParams(lp);
        contextRing.setOnClickListener(v -> {
            if (isFinishing() || isDestroyed() || !v.isAttachedToWindow()) return;
            app.multiai.mobile.models.ModelInfo selected = null;
            if (selectedModelId != null) {
                for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
                    if (model != null && selectedModelId.equals(model.id)) {
                        selected = model;
                        break;
                    }
                }
            }
            String name = selected != null && selected.displayName != null
                    ? selected.displayName
                    : (selectedModelName != null ? selectedModelName : "MultiAI");
            long limit = selected != null ? Math.max(0L, selected.contextWindow) : 0L;
            app.multiai.mobile.ui.kit.ModelCatalogUi.showContextSheet(
                    ChatActivity.this, buildCatalogUsage(), limit, name);
        });
        inputBar.addView(contextRing);
        // Увеличенная зона тача (48dp) вокруг маленького кольца 16dp.
        inputBar.post(() -> {
            android.graphics.Rect r = new android.graphics.Rect();
            contextRing.getHitRect(r);
            int extra = (dpToPx(48) - contextRing.getWidth()) / 2;
            if (extra > 0) {
                r.left -= extra; r.top -= extra;
                r.right += extra; r.bottom += extra;
                inputBar.setTouchDelegate(new android.view.TouchDelegate(r, contextRing));
            }
        });
        refreshContextRing();
    }

    private long contextLimit() {
        if (selectedModelId == null) return 0L;
        for (app.multiai.mobile.models.ModelInfo model : modelCatalog) {
            if (model != null && selectedModelId.equals(model.id)) {
                return Math.max(0L, model.contextWindow);
            }
        }
        return 0L;
    }

    /**
     * If a chat reaches the context budget, compact only its older unsummarized
     * prefix. The visible transcript is deliberately never mutated.
     */
    private boolean startAutoSummaryIfNeeded() {
        if (skipAutoSummaryOnce) {
            skipAutoSummaryOnce = false;
            return false;
        }
        // Пока идёт компактирование, не отправляем второй полный запрос поверх него.
        if (autoSummarizing) return true;
        if (isGenerating || currentChat == null || multiAiClient == null
                || selectedModelId == null || selectedModelId.trim().isEmpty()) {
            return false;
        }
        final List<Message> allMessages = currentChat.getMessages();
        final long limit = contextLimit();
        if (limit <= 0L || allMessages.size() < AUTO_SUMMARY_MIN_MESSAGES
                || estimateChatTokens() < Math.round(limit * AUTO_SUMMARY_TRIGGER_RATIO)) {
            return false;
        }
        final int covered = hasUsableContextSummary(currentChat)
                ? Math.min(currentChat.getContextSummaryMessageCount(), allMessages.size()) : 0;
        final int tailCount = Math.min(AUTO_SUMMARY_TAIL_MESSAGE_COUNT,
                Math.max(4, allMessages.size() / 2));
        final int endExclusive = Math.max(covered, allMessages.size() - tailCount);
        if (endExclusive - covered < AUTO_SUMMARY_MIN_NEW_MESSAGES) return false;

        final List<Message> segment = new ArrayList<>();
        for (int i = covered; i < endExclusive; i++) {
            Message message = allMessages.get(i);
            if (message != null && (message.getType() == Message.TYPE_USER
                    || message.getType() == Message.TYPE_AI)) {
                segment.add(message);
            }
        }
        if (segment.isEmpty()) return false;

        final Chat targetChat = currentChat;
        final String previousSummary = targetChat.getContextSummary();
        final int finalEndExclusive = endExclusive;
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
                                continueAfterAutoSummaryFailure(targetChat);
                                return;
                            }
                            // Bound persisted data even if a model ignores the requested size.
                            if (compact.length() > 12_000) compact = compact.substring(0, 12_000);
                            targetChat.setContextSummary(compact);
                            targetChat.setContextSummaryMessageCount(finalEndExclusive);
                            try {
                                if (chatManager != null) chatManager.updateChat(targetChat);
                            } catch (Exception ignored) {
                            }
                            // Project rules may have been compressed out of model context.
                            pendingRulesReinject = true;
                            refreshContextRing();
                            if (currentChat == targetChat && !isGenerating) sendMessage();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            autoSummarizing = false;
                            continueAfterAutoSummaryFailure(targetChat);
                        });
                    }
                });
        return true;
    }

    private void continueAfterAutoSummaryFailure(Chat targetChat) {
        // Never trap the user's message behind a failed background compaction.
        if (currentChat == targetChat && !isGenerating) {
            skipAutoSummaryOnce = true;
            sendMessage();
        }
    }

    /**
     * Handles the model-callable history compaction tool. The full transcript
     * stays in the UI; only an older prefix is replaced in future model context.
     */
    private String executeHistorySummaryTool(OperationCancelToken token) throws Exception {
        final Chat targetChat = currentChat;
        if (targetChat == null) {
            return new JSONObject().put("success", true).put("skipped", true)
                    .put("message", "There is no persistent chat history to summarize").toString();
        }
        final List<Message> allMessages = new ArrayList<>(targetChat.getMessages());
        final int covered = hasUsableContextSummary(targetChat)
                ? Math.min(targetChat.getContextSummaryMessageCount(), allMessages.size()) : 0;
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
                            selectedModelId, targetChat.getContextSummary(), segment, token);
            String summary = result.getSummary();
            targetChat.setContextSummary(summary);
            targetChat.setContextSummaryMessageCount(endExclusive);
            if (chatManager != null) chatManager.updateChat(targetChat);
            // The old prefix may have contained project rules. Send them again
            // with the next main-model request instead of silently losing them.
            pendingRulesReinject = true;
            runOnUiThread(() -> {
                if (currentChat == targetChat) refreshContextRing();
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

    private boolean hasUsableContextSummary(Chat chat) {
        return chat != null && chat.getContextSummaryMessageCount() > 0
                && chat.getContextSummary() != null && !chat.getContextSummary().trim().isEmpty();
    }

    private List<Message> trimHistoryCoveredBySummary(List<Message> history, Chat chat) {
        if (history == null || history.isEmpty() || !hasUsableContextSummary(chat)) return history;
        int from = Math.min(chat.getContextSummaryMessageCount(), history.size());
        return new ArrayList<>(history.subList(from, history.size()));
    }

    private void refreshContextRing() {
        if (contextRing == null) return;
        long msgs = messagesTokens();
        long used = msgs > 0 ? msgs + systemPromptTokens() + toolsTokens() : 0;
        contextRing.setUsage(used, contextLimit());
    }

    /** Грубая оценка токенов диалога (~4 символа/токен) с системным промптом. */
    private long estimateChatTokens() {
        return systemPromptTokens() + messagesTokens() + toolsTokens();
    }

    private long systemPromptTokens() {
        try {
            String sys = buildEnrichedInstructions();
            return sys != null ? sys.length() / 4 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long messagesTokens() {
        if (currentChat == null || currentChat.getMessages() == null) return 0L;
        long chars = 0L;
        int start = 0;
        if (hasUsableContextSummary(currentChat)) {
            chars += currentChat.getContextSummary().trim().length();
            start = Math.min(currentChat.getContextSummaryMessageCount(),
                    currentChat.getMessages().size());
        }
        for (int i = start; i < currentChat.getMessages().size(); i++) {
            Message message = currentChat.getMessages().get(i);
            if (message != null) chars += message.getContentSafe().length();
        }
        return chars / 4;
    }

    /** Схема инструментов ~ фиксированный размер. */
    private long toolsTokens() {
        return 1_000;
    }

    private app.multiai.mobile.ui.kit.ModelCatalogUi.Usage buildCatalogUsage() {
        // 0 сообщений = нулевой контекст (как в кольце).
        if (messagesTokens() == 0) {
            return new app.multiai.mobile.ui.kit.ModelCatalogUi.Usage(0, 0, 0);
        }
        return new app.multiai.mobile.ui.kit.ModelCatalogUi.Usage(
                systemPromptTokens(), messagesTokens(), toolsTokens());
    }

    private TextView createBadge(String text, int color) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(color);
        badge.setTextSize(9);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable badgeBg = new android.graphics.drawable.GradientDrawable();
        badgeBg.setCornerRadius(dpToPx(4));
        badgeBg.setColor(color & 0x33FFFFFF);
        badgeBg.setStroke(1, color & 0x66FFFFFF);
        badge.setBackground(badgeBg);
        badge.setPadding(dpToPx(5), dpToPx(1), dpToPx(5), dpToPx(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpToPx(6), 0, 0, 0);
        badge.setLayoutParams(lp);
        return badge;
    }

    /**
     * Styled dark-themed alert dialog
     */
    private void showStyledDialog(String title, String message, String positiveText, Runnable positiveAction, String negativeText, Runnable negativeAction) {
        ModernDialog.show(this, title, message, negativeText,
                positiveText != null ? positiveText : "OK", false,
                () -> { if (positiveAction != null) positiveAction.run(); });
    }

    private void showStyledDialog(String title, String message) {
        ModernDialog.show(this, title, message, getString(R.string.chat_got_it));
    }

    // ========== AI Message Actions ==========

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
                            mp.release();
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

    private void showDislikeFeedbackDialog(Message message) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        // Category spinner
        String[] categories = getResources().getStringArray(R.array.chat_dislike_categories);

        android.widget.Spinner spinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> spinnerAdapter = new android.widget.ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, categories);
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(spinnerAdapter);

        android.graphics.drawable.GradientDrawable spinnerBg = new android.graphics.drawable.GradientDrawable();
        spinnerBg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_tonal));
        spinnerBg.setCornerRadius(dpToPx(12));
        spinnerBg.setStroke(1, androidx.core.content.ContextCompat.getColor(this, R.color.stroke));
        spinner.setBackground(spinnerBg);
        spinner.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        body.addView(spinner);

        // Details input
        EditText details = new EditText(this);
        details.setHint(getString(R.string.chat_share_details_hint));
        details.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        details.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        details.setTextSize(14);
        details.setMinLines(3);
        details.setGravity(android.view.Gravity.TOP);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_tonal));
        inputBg.setCornerRadius(dpToPx(12));
        inputBg.setStroke(1, androidx.core.content.ContextCompat.getColor(this, R.color.stroke));
        details.setBackground(inputBg);
        details.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dpToPx(12);
        details.setLayoutParams(lp);
        body.addView(details);

        // Info text
        TextView info = new TextView(this);
        info.setText(getString(R.string.chat_feedback_info));
        info.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        info.setTextSize(12);
        info.setPadding(0, dpToPx(10), 0, dpToPx(0));
        body.addView(info);

        // Submit button — runtime AccentColor with contrasting foreground
        int accent = AccentColor.resolve(this);
        TextView btnSubmit = new TextView(this);
        btnSubmit.setText(getString(R.string.chat_submit));
        btnSubmit.setTextColor(AccentColor.contrastForeground(accent));
        btnSubmit.setTextSize(16);
        btnSubmit.setTypeface(null, android.graphics.Typeface.BOLD);
        btnSubmit.setGravity(android.view.Gravity.CENTER);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(accent);
        btnBg.setCornerRadius(dpToPx(24));
        btnSubmit.setBackground(btnBg);
        btnSubmit.setPadding(0, dpToPx(14), 0, dpToPx(14));
        LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        submitLp.topMargin = dpToPx(12);
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

    private void showAiMoreMenu(Message message, View anchor) {
        java.util.List<ModernSheet.MenuItem> items = java.util.Arrays.asList(
            new ModernSheet.MenuItem(getString(R.string.chat_copy), R.drawable.ic_copy, false, null),
            new ModernSheet.MenuItem(getString(R.string.chat_retry), R.drawable.ic_retry_arrow, false, null),
            new ModernSheet.MenuItem(getString(R.string.chat_branch_new_chat), R.drawable.ic_share_variant_outline, false, null),
            new ModernSheet.MenuItem(getString(R.string.chat_search_web), R.drawable.ic_s_globe, false, null)
        );
        // Выпадающий список у самой кнопки «…», а не bottom sheet.
        app.multiai.mobile.ui.kit.ModernMenu.show(anchor, null, items, (idx, item) -> {
            switch (idx) {
                case 0: { // Copy
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "ai_message", message.getContentSafe()));
                        ModernToast.show(this, getString(R.string.chat_copied));
                    }
                    break;
                }
                case 1: // Retry
                    retryAiMessage(message);
                    break;
                case 2: // Branch to new chat
                    branchToNewChat(message);
                    break;
                case 3: // Search web
                    String query = message.getContent() != null ? message.getContent().substring(0, Math.min(100, message.getContent().length())) : "";
                    query = query.replaceAll("(?s)<think>.*?</think>", "").replaceAll("[*_~#>`]", "").trim();
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)));
                    startActivity(browserIntent);
                    break;
            }
        });
    }

    private void branchToNewChat(Message message) {
        if (currentChat == null || message == null) return;
        int messageIndex = currentChat.getMessages().indexOf(message);
        if (messageIndex < 0) return;
        try {
            Chat branch = new Chat();
            branch.setModelId(currentChat.getModelId());
            branch.setModelEffort(currentChat.getModelEffort());
            branch.setMessages(new java.util.ArrayList<>());
            branch.setTitle(getString(R.string.chat_branch_title, currentChat.getTitle()));
            chatManager.addChatIfAbsent(branch);
            allChats = chatManager.loadActiveChats();
            chatsAdapter.setChats(allChats);
            switchToChat(branch);
            pendingBranchContent = message.getContentSafe();
            if (imageFileName != null) imageFileName.setText(getString(R.string.chat_branch_context));
            showAttachmentInline(false);
            updateSendMicVisibility();
            editMessage.requestFocus();
            ModernToast.show(this, getString(R.string.chat_branch_created));
        } catch (Exception error) {
            ModernToast.show(this, getString(R.string.chat_error_move, error.getMessage()));
        }
    }

    /** Returns whether this assistant answer can be retried without removing a newer user turn. */
    private boolean isLatestRetryableAi(Message answer) {
        if (answer == null || currentChat == null) return false;
        List<Message> messages = currentChat.getMessages();
        int answerIndex = messages.indexOf(answer);
        if (answerIndex <= 0 || answer.getType() != Message.TYPE_AI) return false;
        Message previous = messages.get(answerIndex - 1);
        if (previous == null || previous.getType() != Message.TYPE_USER) return false;
        for (int i = answerIndex + 1; i < messages.size(); i++) {
            if (messages.get(i).getType() == Message.TYPE_USER) return false;
        }
        return true;
    }

    private boolean retryAiMessage(Message aiMessage) {
        if (isGenerating || !isLatestRetryableAi(aiMessage)) return false;
        int aiIndex = currentChat.getMessages().indexOf(aiMessage);
        Message previous = currentChat.getMessages().get(aiIndex - 1);
        while (currentChat.getMessages().size() > aiIndex + 1) {
            Message tail = currentChat.getMessages().get(currentChat.getMessages().size() - 1);
            removeMessageFromChatAndUi(tail);
        }
        retrySourceMessage = aiMessage;
        retrySourceUser = previous;
        aiMessage.setRetryPending(true);
        messagesAdapter.refreshMessage(aiMessage);
        restoreAiRetryAttachmentAndSend(previous, aiMessage);
        return true;
    }

    private void sendFeedbackReport(Message message, String type, String category, String details) {
        // Send feedback to admin panel via API
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
                app.multiai.mobile.utils.FileLog.d(TAG, "feedback_send_failed");
            }
        }).start();
    }

    private static final String NOTIF_CHANNEL_ID = "multiai_ai_responses";
    private static final int NOTIF_ID_AI_RESPONSE = 1001;

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.chat_notif_channel_name),
                android.app.NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(getString(R.string.chat_notif_channel_desc));
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private void showAiResponseNotification(String preview) {
        // Only notify when app is in background
        if (!isAppInBackground()) return;

        try {
            android.content.Intent intent = new android.content.Intent(this, ChatActivity.class);
            intent.setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE
            );

            String shortPreview = preview;
            if (shortPreview != null && shortPreview.length() > 100) {
                shortPreview = shortPreview.substring(0, 100) + "…";
            }
            if (shortPreview == null || shortPreview.trim().isEmpty()) {
                shortPreview = getString(R.string.chat_notif_default);
            }

            androidx.core.app.NotificationCompat.Builder nb = new androidx.core.app.NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bot_avatar_mono)
                .setContentTitle(getString(R.string.chat_notif_title))
                .setContentText(shortPreview)
                .setStyle(new androidx.core.app.NotificationCompat.BigTextStyle().bigText(shortPreview))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL);

            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID_AI_RESPONSE, nb.build());
        } catch (Exception e) {
        }
    }

    private boolean isAppInBackground() {
        return !isInForeground;
    }


    private void showStyledInputDialog(String title, String currentValue, java.util.function.Consumer<String> onConfirm) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(currentValue);
        input.setSelectAllOnFocus(true);
        input.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        input.setHintTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_hint));
        input.setTextSize(15);
        input.setSingleLine(true);
        android.graphics.drawable.GradientDrawable inputBg = new android.graphics.drawable.GradientDrawable();
        inputBg.setColor(androidx.core.content.ContextCompat.getColor(this, R.color.surface_tonal));
        inputBg.setCornerRadius(dpToPx(12));
        inputBg.setStroke(1, androidx.core.content.ContextCompat.getColor(this, R.color.stroke));
        input.setBackground(inputBg);
        input.setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12));

        ModernDialog.show(this, title, null, input, getString(R.string.chat_cancel), getString(R.string.chat_save), false, () -> {
            String newVal = input.getText().toString().trim();
            if (!newVal.isEmpty()) onConfirm.accept(newVal);
        });
        input.requestFocus();
    }

    /** Execute a MultiAI tool call locally on the client. */
    /**
     * Shows the full Markdown plan and blocks the local tool worker until the
     * user exits. The waiting happens off the UI thread; cancellation, Back
     * and activity destruction all resolve the same latch.
     */
    private String awaitPlanExit(ToolCall sourceCall, JSONObject args,
                                 OperationCancelToken token, long epoch) throws Exception {
        String markdown = args != null ? args.optString("markdown", "").trim() : "";
        if (markdown.isEmpty()) {
            return planModeError("PLAN_MARKDOWN_REQUIRED",
                    "present_plan requires a non-empty Markdown plan").toString();
        }
        final int maxPlanChars = 40_000;
        if (markdown.length() > maxPlanChars) {
            markdown = markdown.substring(0, maxPlanChars)
                    + "\n\n> План обрезан в интерфейсе из-за лимита размера.";
        }
        final String finalMarkdown = markdown;
        final PlanExitRequest request = new PlanExitRequest();
        activePlanExitRequests.add(request);

        JSONObject liveResult = new JSONObject();
        liveResult.put("success", true);
        liveResult.put("markdown", finalMarkdown);
        liveResult.put("waiting_for_exit", true);
        liveResult.put("plan_mode", "active");
        runOnUiThread(() -> {
            if (sourceCall != null) {
                sourceCall.setResult(liveResult);
                sourceCall.setReopenAction(() -> runOnUiThread(
                        () -> showPlanPreview(finalMarkdown)));
                refreshToolCallOwner(sourceCall);
            }
            showPlanExitSheet(finalMarkdown, request);
        });

        OperationCancelToken.Listener cancelListener = null;
        if (token != null) {
            cancelListener = () -> request.resolve("cancelled");
            token.addListener(cancelListener);
        }
        try {
            request.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            request.resolve("cancelled");
        } finally {
            activePlanExitRequests.remove(request);
            if (token != null && cancelListener != null) token.removeListener(cancelListener);
        }

        boolean turnStillActive = isTurnActive(epoch) && (token == null || !token.check());
        restorePlanMode(request.reason);
        if (!turnStillActive || "cancelled".equals(request.reason)) {
            return planModeError("PLAN_INTERRUPTED", "Plan Mode was interrupted before exit").toString();
        }
        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("markdown", finalMarkdown);
        result.put("plan_mode", "exited");
        result.put("exit_reason", request.reason);
        result.put("restored_access_mode", planModePreviousAccess);
        result.put("message", "The user exited Plan Mode. Create or update todo, then continue the approved task.");
        return result.toString();
    }

    private void showPlanExitSheet(String markdown, PlanExitRequest request) {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                && isDestroyed())) {
            request.resolve("cancelled");
            return;
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);

        TextView intro = new TextView(this);
        intro.setText(getString(R.string.chat_plan_intro));
        intro.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        intro.setTextSize(14);
        body.addView(intro);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout markdownContainer = new LinearLayout(this);
        markdownContainer.setOrientation(LinearLayout.VERTICAL);
        int planPad = dpToPx(12);
        markdownContainer.setPadding(planPad, planPad, planPad, planPad);
        GradientDrawable planBg = new GradientDrawable();
        planBg.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        planBg.setCornerRadius(dpToPx(14));
        planBg.setStroke(dpToPx(1), ContextCompat.getColor(this, R.color.stroke));
        markdownContainer.setBackground(planBg);
        app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(this, markdownContainer, markdown);
        scroll.addView(markdownContainer, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dpToPx(440), (int) (getResources().getDisplayMetrics().heightPixels * 0.54f)));
        scrollLp.topMargin = dpToPx(14);
        body.addView(scroll, scrollLp);

        TextView question = new TextView(this);
        question.setText(getString(R.string.chat_plan_exit_question));
        question.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        question.setTextSize(16);
        question.setTypeface(question.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams questionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        questionLp.topMargin = dpToPx(18);
        body.addView(question, questionLp);

        TextView note = new TextView(this);
        note.setText(getString(R.string.chat_plan_exit_note));
        note.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        note.setTextSize(13);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteLp.topMargin = dpToPx(4);
        body.addView(note, noteLp);

        com.google.android.material.button.MaterialButton exit =
                new com.google.android.material.button.MaterialButton(this);
        int accent = AccentColor.resolve(this);
        exit.setText(getString(R.string.chat_plan_exit_action));
        exit.setAllCaps(false);
        exit.setTextColor(AccentColor.contrastForeground(accent));
        exit.setTextSize(15);
        exit.setTypeface(exit.getTypeface(), android.graphics.Typeface.BOLD);
        exit.setCornerRadius(dpToPx(14));
        exit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
        exit.setOnClickListener(v -> request.resolve("exit"));
        LinearLayout.LayoutParams exitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        exitLp.topMargin = dpToPx(14);
        body.addView(exit, exitLp);

        android.app.Dialog sheet = ModernSheet.custom(this, getString(R.string.chat_plan_mode_title), body, true);
        request.dialog.set(sheet);
        sheet.setCanceledOnTouchOutside(true);
        sheet.setOnDismissListener(d -> request.resolve("dismissed"));
        if (request.resolved.get()) {
            try { sheet.dismiss(); } catch (Exception ignored) {}
        }
    }

    /** Opens a read-only full-plan viewer from a persisted/rebound compact card. */
    public void showPlanPreview(String markdown) {
        if (markdown == null || markdown.trim().isEmpty() || isFinishing()) return;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout markdownContainer = new LinearLayout(this);
        markdownContainer.setOrientation(LinearLayout.VERTICAL);
        markdownContainer.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ContextCompat.getColor(this, R.color.surface_tonal));
        background.setCornerRadius(dpToPx(14));
        background.setStroke(dpToPx(1), ContextCompat.getColor(this, R.color.stroke));
        markdownContainer.setBackground(background);
        app.multiai.mobile.utils.MarkdownRenderer.renderToLayout(this, markdownContainer, markdown);
        scroll.addView(markdownContainer, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.min(dpToPx(520), (int) (getResources().getDisplayMetrics().heightPixels * 0.68f))));
        ModernSheet.custom(this, getString(R.string.chat_implementation_plan_title), body, true);
    }

    private String executeMultiAiTool(ToolCall sourceCall, String name, JSONObject args,
                                      OperationCancelToken token, long epoch) {
        try {
            if ("enter_plan_mode".equals(name)) {
                if (isPlanModeActiveForTurn(epoch)) {
                    return planModeError("PLAN_ALREADY_ACTIVE", "Plan Mode is already active").toString();
                }
                return enterPlanMode(epoch, args).toString();
            }
            if ("present_plan".equals(name)) {
                if (!isPlanModeActiveForTurn(epoch)) {
                    return planModeError("PLAN_NOT_ACTIVE",
                            "present_plan is available only during an active Plan Mode session").toString();
                }
                return awaitPlanExit(sourceCall, args, token, epoch);
            }
            if (isPlanModeActiveForTurn(epoch) && !isPlanReadOnlyTool(name)) {
                return planModeError("PLAN_READ_ONLY",
                        "Plan Mode only allows project inspection, execute_command or present_plan; exit Plan Mode before using another action.")
                        .toString();
            }
            if ("invoke_subagent".equals(name)) {
                return executeWorkSubagent(sourceCall, args, token);
            }
            if (isWorkModeTool(name)) {
                activateWorkModeSession(currentChat);
                String approvalDetail = workModeApprovalDetail(name, args);
                if (app.multiai.mobile.utils.WorkModeAccess.isOperationBlocked(
                        this, name, approvalDetail)) {
                    return new JSONObject().put("error", "Command is blocked by a global rule")
                            .put("code", "COMMAND_BLOCKED").toString();
                }
                if (app.multiai.mobile.utils.WorkModeAccess.requiresApproval(this, name, approvalDetail)
                        && !app.multiai.mobile.utils.WorkModeAccess.awaitApproval(this, name,
                            approvalDetail, APPROVAL_TIMEOUT_SEC, token)) {
                    return new JSONObject().put("error", "Action rejected by user")
                            .put("code", "USER_DENIED").toString();
                }
                return workModeTools != null
                        ? workModeTools.execute(name, args, token)
                        : new JSONObject().put("error", "Work tools are not initialized").toString();
            }
            switch (name) {
                case "todo": {
                    JSONArray items = args.optJSONArray("items");
                    if (items == null) {
                        try { items = new JSONArray(args.optString("items", "[]")); }
                        catch (Exception ignored) { items = new JSONArray(); }
                    }
                    JSONArray normalized = new JSONArray();
                    for (int i = 0; i < Math.min(items.length(), 50); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) continue;
                        JSONObject copy = new JSONObject();
                        copy.put("id", item.optString("id", String.valueOf(i + 1)));
                        copy.put("title", item.optString("title", "Task " + (i + 1)));
                        String status = item.optString("status", "pending");
                        if (!"completed".equals(status) && !"in_progress".equals(status)) status = "pending";
                        copy.put("status", status);
                        normalized.put(copy);
                    }
                    return new JSONObject().put("success", true).put("items", normalized)
                            .put("active_index", args.optInt("active_index", -1)).toString();
                }
                case "question":
                    return awaitQuestion(sourceCall, args);
                case "get_weather": {
                    String city = args.optString("city", "Moscow");
                    okhttp3.OkHttpClient httpClient = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .build();
                    // Step 1: Geocode city via open-meteo
                    okhttp3.Request geoReq = new okhttp3.Request.Builder()
                            .url("https://geocoding-api.open-meteo.com/v1/search?name=" +
                                    java.net.URLEncoder.encode(city, "UTF-8") + "&count=1&language=ru")
                            .build();
                    okhttp3.Response geoResp = httpClient.newCall(geoReq).execute();
                    if (!geoResp.isSuccessful() || geoResp.body() == null) {
                        return "{\"error\":\"Geocoding failed\"}";
                    }
                    JSONObject geoData = new JSONObject(geoResp.body().string());
                    JSONArray geoResults = geoData.optJSONArray("results");
                    if (geoResults == null || geoResults.length() == 0) {
                        return "{\"error\":\"City not found: " + city + "\"}";
                    }
                    JSONObject loc = geoResults.getJSONObject(0);
                    double lat = loc.getDouble("latitude");
                    double lon = loc.getDouble("longitude");
                    String locName = loc.optString("name", city);
                    // Step 2: Get weather from open-meteo
                    okhttp3.Request wxReq = new okhttp3.Request.Builder()
                            .url("https://api.open-meteo.com/v1/forecast?latitude=" + lat +
                                    "&longitude=" + lon +
                                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m" +
                                    "&timezone=auto")
                            .build();
                    okhttp3.Response wxResp = httpClient.newCall(wxReq).execute();
                    if (wxResp.isSuccessful() && wxResp.body() != null) {
                        JSONObject wxData = new JSONObject(wxResp.body().string());
                        JSONObject current = wxData.optJSONObject("current");
                        if (current != null) {
                            JSONObject result = new JSONObject();
                            result.put("city", locName);
                            result.put("temp_c", current.opt("temperature_2m"));
                            result.put("feels_like_c", current.opt("apparent_temperature"));
                            result.put("humidity", current.opt("relative_humidity_2m"));
                            result.put("wind_kmph", current.opt("wind_speed_10m"));
                            result.put("wind_direction", current.opt("wind_direction_10m"));
                            int wmo = current.optInt("weather_code", -1);
                            result.put("description", wmoToDescription(wmo));
                            return result.toString();
                        }
                    }
                    return "{\"error\":\"Weather service unavailable\"}";
                }
                case "get_current_time": {
                    String tz = args.optString("timezone", "UTC");
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                    sdf.setTimeZone(java.util.TimeZone.getTimeZone(tz));
                    java.text.SimpleDateFormat isoFmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
                    isoFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                    java.util.Date now = new java.util.Date();
                    JSONObject result = new JSONObject();
                    result.put("timezone", tz);
                    result.put("datetime", sdf.format(now));
                    result.put("iso", isoFmt.format(now));
                    return result.toString();
                }
                case "summarize_chat_history": {
                    return executeHistorySummaryTool(token);
                }
                case "summarize_past_chat": {
                    return app.multiai.mobile.utils.PastChatSummaryService.run(this, args,
                            selectedModelId,
                            currentChat != null ? currentChat.getId() : null, token).toString();
                }
                case "web_search": {
                    return app.multiai.mobile.utils.WebSearchService.search(args).toString();
                }
                case "url_fetch": {
                    return app.multiai.mobile.utils.WebSearchService.fetch(args).toString();
                }
                case "save_memory": {
                    if (!app.multiai.mobile.utils.MemoryContextBuilder.memoriesEnabled(this)) {
                        return "{\"error\":\"save_memory: память отключена в настройках\"}";
                    }
                    String memName = args.optString("name", "").trim();
                    String memContent = args.optString("content", "").trim();
                    if (memName.isEmpty() || memContent.isEmpty()) {
                        return "{\"error\":\"save_memory: name and content are required\"}";
                    }
                    try {
                        app.multiai.mobile.utils.MemoryStore.append(this, memName, memContent);
                        // Sync memories to cloud
                        new Thread(() -> {
                            try { new app.multiai.mobile.utils.CloudSyncManager(ChatActivity.this).pushMemories(); }
                            catch (Exception ignored) {}
                        }).start();
                        JSONObject res = new JSONObject();
                        res.put("success", true);
                        res.put("name", memName);
                        res.put("content", memContent);
                        res.put("message", "Memory saved: " + memName);
                        return res.toString();
                    } catch (Exception e) {
                        JSONObject error = new JSONObject();
                        error.put("error", "Failed to save memory: " + e.getMessage());
                        return error.toString();
                    }
                }
                default:
                    return "{\"error\":\"Unknown tool: " + name + "\"}";
            }
        } catch (Exception e) {
            return "{\"error\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "unknown") + "\"}";
        }
    }

    private String awaitQuestion(ToolCall sourceCall, JSONObject args) throws Exception {
        // Parse questions array (new format) or single question (legacy).
        // ToolCall params are Map<String,String>, so arrays arrive as JSON
        // strings — we must try both optJSONArray and string-to-JSONArray.
        JSONArray questionsArr = args.optJSONArray("questions");
        if (questionsArr == null) {
            String raw = args.optString("questions", "");
            if (raw.startsWith("[")) {
                try { questionsArr = new JSONArray(raw); } catch (Exception ignored) {}
            }
        }
        if (questionsArr == null) {
            // Legacy: single question → wrap into one-element array
            questionsArr = new JSONArray();
            JSONObject single = new JSONObject();
            single.put("question", args.optString("question", "").trim());
            // options may also arrive as a string
            JSONArray opts = args.optJSONArray("options");
            if (opts == null) {
                String optRaw = args.optString("options", "");
                if (optRaw.startsWith("[")) {
                    try { opts = new JSONArray(optRaw); } catch (Exception ignored) {}
                }
            }
            single.put("options", opts);
            single.put("allow_free_text", args.optBoolean("allow_free_text", true));
            questionsArr.put(single);
        }

        int total;
        synchronized (this) {
            int remaining = 10 - questionCount;
            if (remaining <= 0 || questionsArr.length() > remaining) {
                return new JSONObject().put("error", "Question limit reached: maximum 10 per turn").toString();
            }
            total = questionsArr.length();
            questionCount += total;
        }
        if (total == 0) return new JSONObject().put("error", "No questions provided").toString();

        JSONArray answers = new JSONArray();
        int accent = AccentColor.resolve(this);
        int accentFg = AccentColor.contrastForeground(accent);
        int cardColor = ContextCompat.getColor(this, R.color.surface_card);
        int strokeColor = ContextCompat.getColor(this, R.color.stroke);
        int textPrimary = ContextCompat.getColor(this, R.color.text_primary);
        int textSecondary = ContextCompat.getColor(this, R.color.text_secondary);
        int textHint = ContextCompat.getColor(this, R.color.text_hint);
        int rippleColor = ContextCompat.getColor(this, R.color.ripple);

        for (int qi = 0; qi < total; qi++) {
            // Cooperative cancellation: stop / new turn / onDestroy must be
            // able to release this wait even before any sheet is shown.
            OperationCancelToken turnToken = activeTurnToken;
            if (turnToken != null && turnToken.check()) {
                return new JSONObject().put("error", "Question interrupted").toString();
            }
            JSONObject qObj = questionsArr.optJSONObject(qi);
            if (qObj == null) {
                answers.put(new JSONObject().put("question", "").put("answer", "skipped"));
                continue;
            }
            String question = qObj.optString("question", "").trim();
            if (question.isEmpty()) {
                answers.put(new JSONObject().put("question", "").put("answer", "skipped"));
                continue;
            }
            JSONArray opts = qObj.optJSONArray("options");
            if (opts == null) {
                try { opts = new JSONArray(qObj.optString("options", "[]")); }
                catch (Exception ignored) { opts = new JSONArray(); }
            }
            // Max 3 options
            JSONArray safeOptions = new JSONArray();
            for (int j = 0; j < Math.min(opts.length(), 3); j++) {
                String opt = opts.optString(j, "").trim();
                if (!opt.isEmpty()) safeOptions.put(opt);
            }
            boolean allowFreeText = qObj.optBoolean("allow_free_text", true);

            final int qIndex = qi;
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<String> answer = new AtomicReference<>(null);
            AtomicReference<android.app.Dialog> questionDialog = new AtomicReference<>();
            AtomicBoolean questionSubmitted = new AtomicBoolean(false);
            AtomicBoolean questionDismissed = new AtomicBoolean(false);
            JSONArray finalOptions = safeOptions;
            final OperationCancelToken questionToken = turnToken;
            int pad = dpToPx(20);

            runOnUiThread(() -> {
                // Body container
                LinearLayout body = new LinearLayout(this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setPadding(0, 0, 0, 0);

                // Header row: question text (left) + counter "1/N" (right)
                LinearLayout header = new LinearLayout(this);
                header.setOrientation(LinearLayout.HORIZONTAL);
                header.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView questionText = new TextView(this);
                questionText.setText(question);
                questionText.setTextColor(textPrimary);
                questionText.setTextSize(16);
                questionText.setTypeface(questionText.getTypeface(), android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams qLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                questionText.setLayoutParams(qLp);
                header.addView(questionText);

                TextView counter = new TextView(this);
                counter.setText((qIndex + 1) + "/" + total);
                counter.setTextColor(textSecondary);
                counter.setTextSize(13);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cLp.setMarginStart(dpToPx(12));
                counter.setLayoutParams(cLp);
                header.addView(counter);
                body.addView(header);

                body.addView(spacer(dpToPx(16)));

                // Option buttons (up to 3) — rounded cards with accent on press
                final android.app.Dialog[] dialogRef = new android.app.Dialog[1];
                final String[] selectedOption = {null};
                for (int j = 0; j < finalOptions.length(); j++) {
                    final String value = finalOptions.optString(j, "");
                    LinearLayout optBtn = new LinearLayout(this);
                    optBtn.setOrientation(LinearLayout.HORIZONTAL);
                    optBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    optBtn.setMinimumHeight(dpToPx(48));
                    optBtn.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
                    // Default background: card with stroke
                    GradientDrawable optBg = new GradientDrawable();
                    optBg.setColor(cardColor);
                    optBg.setCornerRadius(dpToPx(14));
                    optBg.setStroke(dpToPx(1), strokeColor);
                    optBtn.setBackground(optBg);
                    optBtn.setClickable(true);

                    // Radio dot indicator
                    View dot = new View(this);
                    GradientDrawable dotBg = new GradientDrawable();
                    dotBg.setShape(GradientDrawable.OVAL);
                    dotBg.setColor(strokeColor);
                    dot.setBackground(dotBg);
                    LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                            dpToPx(18), dpToPx(18));
                    dotLp.setMarginEnd(dpToPx(12));
                    dot.setLayoutParams(dotLp);
                    optBtn.addView(dot);

                    TextView optLabel = new TextView(this);
                    optLabel.setText(value);
                    optLabel.setTextColor(textPrimary);
                    optLabel.setTextSize(15);
                    optLabel.setLayoutParams(new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    optBtn.addView(optLabel);

                    final GradientDrawable defaultBg = optBg;
                    final GradientDrawable selectedBg = new GradientDrawable();
                    selectedBg.setColor(accent);
                    selectedBg.setCornerRadius(dpToPx(14));
                    selectedBg.setStroke(dpToPx(1), accent);
                    final GradientDrawable dotDefault = dotBg;
                    final GradientDrawable dotSelected = new GradientDrawable();
                    dotSelected.setShape(GradientDrawable.OVAL);
                    dotSelected.setColor(accentFg);
                    final int idx = j;
                    optBtn.setOnClickListener(v -> {
                        selectedOption[0] = value;
                        // Reset all options to default
                        ViewGroup parent = (ViewGroup) optBtn.getParent();
                        for (int k = 0; k < parent.getChildCount(); k++) {
                            View child = parent.getChildAt(k);
                            if (child instanceof LinearLayout && child.getTag() instanceof String
                                    && "opt".equals(child.getTag())) {
                                LinearLayout opt = (LinearLayout) child;
                                opt.setBackground(defaultBg);
                                View d = opt.getChildAt(0);
                                if (d != null) d.setBackground(dotDefault);
                                TextView lbl = (TextView) opt.getChildAt(1);
                                if (lbl != null) lbl.setTextColor(textPrimary);
                            }
                        }
                        // Highlight this one
                        optBtn.setBackground(selectedBg);
                        dot.setBackground(dotSelected);
                        optLabel.setTextColor(accentFg);
                    });
                    optBtn.setTag("opt");
                    LinearLayout.LayoutParams optLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    if (j > 0) optLp.topMargin = dpToPx(8);
                    optBtn.setLayoutParams(optLp);
                    body.addView(optBtn);
                }

                // Free text input
                final EditText input = new EditText(this);
                if (allowFreeText) {
                    body.addView(spacer(dpToPx(12)));
                    input.setHint(R.string.work_question_free_text);
                    input.setHintTextColor(textHint);
                    input.setTextColor(textPrimary);
                    input.setSingleLine(false);
                    input.setMaxLines(3);
                    GradientDrawable inputBg = new GradientDrawable();
                    inputBg.setColor(cardColor);
                    inputBg.setCornerRadius(dpToPx(14));
                    inputBg.setStroke(dpToPx(1), strokeColor);
                    input.setBackground(inputBg);
                    input.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
                    AccentColor.applyCursorTint(input, accent);
                    body.addView(input);
                }

                body.addView(spacer(dpToPx(16)));

                // Submit button — accent colored, rounded
                com.google.android.material.button.MaterialButton submit =
                        new com.google.android.material.button.MaterialButton(this);
                submit.setText(qIndex < total - 1 ? R.string.work_question_next : R.string.work_question_submit);
                submit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));
                submit.setTextColor(accentFg);
                submit.setCornerRadius(dpToPx(14));
                submit.setAllCaps(false);
                submit.setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(14));
                LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                submit.setLayoutParams(submitLp);
                submit.setOnClickListener(v -> {
                    String value = selectedOption[0];
                    if (value == null && allowFreeText) {
                        value = input.getText().toString().trim();
                    }
                    if (value != null && !value.isEmpty()) {
                        answer.set(value);
                        questionSubmitted.set(true);
                        if (dialogRef[0] != null) dialogRef[0].dismiss();
                    } else if (allowFreeText) {
                        input.setError(getString(R.string.work_question_empty));
                    }
                });
                body.addView(submit);

                dialogRef[0] = ModernSheet.custom(this, null, body, true);
                questionDialog.set(dialogRef[0]);
                if (sourceCall != null) {
                    sourceCall.setReopenAction(() -> runOnUiThread(() -> {
                        android.app.Dialog hidden = questionDialog.get();
                        if (hidden != null && !hidden.isShowing()
                                && (questionToken == null || !questionToken.check())) {
                            hidden.show();
                        }
                    }));
                    messagesAdapter.refreshAllMessages();
                }
                dialogRef[0].setCancelable(true);
                dialogRef[0].setCanceledOnTouchOutside(true);
                // Closing the sheet is a valid "skip" answer. Count down here
                // for Back, backdrop taps and drag-dismiss alike.
                activeQuestionSheets.add(dialogRef[0]);
                dialogRef[0].setOnDismissListener(d -> {
                    activeQuestionSheets.remove(d);
                    if (!questionSubmitted.get()) questionDismissed.set(true);
                    activeQuestionLatches.remove(latch);
                    latch.countDown();
                });
                if (questionToken != null && questionToken.check()) {
                    try { dialogRef[0].dismiss(); } catch (Exception ignored) {}
                }
                // Adjust for keyboard so the question stays visible
                android.view.Window w = dialogRef[0].getWindow();
                if (w != null) {
                    w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                            | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
                }
            });

            // Bounded wait: the turn token listener releases the latch on
            // cancel, and the global registration covers stop/onDestroy.
            OperationCancelToken.Listener questionCancelListener = null;
            if (questionToken != null) {
                final CountDownLatch waitLatch = latch;
                activeQuestionLatches.add(waitLatch);
                questionCancelListener = () -> {
                    activeQuestionLatches.remove(waitLatch);
                    android.app.Dialog dialog = questionDialog.get();
                    if (dialog != null) runOnUiThread(() -> {
                        try { dialog.dismiss(); } catch (Exception ignored) {}
                    });
                    waitLatch.countDown();
                };
                questionToken.addListener(questionCancelListener);
            }
            try { latch.await(); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                releaseQuestionSheets();
                return new JSONObject().put("error", "Question interrupted").toString();
            } finally {
                activeQuestionLatches.remove(latch);
                if (sourceCall != null) sourceCall.setReopenAction(null);
                if (questionToken != null && questionCancelListener != null) {
                    questionToken.removeListener(questionCancelListener);
                }
            }
            // Token fired between the dialog opening and the answer: treat the
            // remaining questions as cancelled instead of opening new sheets.
            if (questionToken != null && questionToken.check()) {
                releaseQuestionSheets();
                return new JSONObject().put("error", "Question interrupted").toString();
            }
            String value = answer.get();
            if (questionDismissed.get()) {
                answers.put(new JSONObject().put("question", question)
                        .put("answer", "skipped").put("dismissed", true));
                // The user declined this interaction; do not immediately open
                // the next queued question from the same model response.
                break;
            }
            if (value == null || value.trim().isEmpty()) {
                answers.put(new JSONObject().put("question", question).put("answer", "skipped"));
            } else {
                answers.put(new JSONObject().put("question", question).put("answer", value));
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", true);
        result.put("answers", answers);
        return result.toString();
    }

    private void activateWorkModeSession(Chat chat) {
        if (chat == null || chat.getId() == null || chat.getId().trim().isEmpty()) return;
        app.multiai.mobile.utils.WorkModeAccess.activateSession(chat.getId(), chat.getTitle());
    }

    private String workModeApprovalDetail(String toolName, JSONObject args) {
        if ("execute_command".equals(toolName)) {
            return args.optString("cmd", args.optString("command", "")).trim();
        }
        if ("delete_path".equals(toolName)) {
            String path = args.optString("path", "").trim();
            return args.optBoolean("recursive", false)
                    ? "Рекурсивно удалить папку: " + path : "Удалить: " + path;
        }
        if ("move_path".equals(toolName)) {
            String from = args.optString("source_path", args.optString("from_path", "")).trim();
            String to = args.optString("destination_path", args.optString("to_path", "")).trim();
            return "Переместить: " + from + " → " + to;
        }
        return args.optString("path", "").trim();
    }

    private boolean isWorkModeTool(String name) {
        return "read_file".equals(name) || "write_file".equals(name)
                || "edit_file".equals(name) || "delete_path".equals(name)
                || "move_path".equals(name) || "list_dir".equals(name)
                || "search_grep".equals(name) || "find_files".equals(name)
                || "execute_command".equals(name) || "invoke_subagent".equals(name);
    }

    // ═══════════ Question sheet cancellation ═══════════
    // Minimal, local cancellation of the awaitQuestion dialogs: stop /
    // onDestroy dismiss every sheet we opened and release its latch so the
    // tool worker thread can never leak waiting for a dead UI.
    private final java.util.List<android.app.Dialog> activeQuestionSheets =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.List<CountDownLatch> activeQuestionLatches =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    private void releaseQuestionSheets() {
        synchronized (activeQuestionSheets) {
            for (android.app.Dialog dialog : new java.util.ArrayList<>(activeQuestionSheets)) {
                try {
                    if (dialog.isShowing()) dialog.dismiss();
                } catch (Exception ignored) {
                }
            }
            activeQuestionSheets.clear();
        }
        synchronized (activeQuestionLatches) {
            for (CountDownLatch latch : new java.util.ArrayList<>(activeQuestionLatches)) {
                try {
                    latch.countDown();
                } catch (Exception ignored) {
                }
            }
            activeQuestionLatches.clear();
        }
    }

    private String executeWorkSubagent(ToolCall sourceCall, JSONObject args,
                                       OperationCancelToken token) throws Exception {
        String task = args.optString("task", "").trim();
        if (task.isEmpty()) {
            return new JSONObject().put("success", false).put("error", "task is required")
                    .put("code", "INVALID_ARGUMENT").toString();
        }
        String presetId = args.optString("preset_id", "").trim();
        if (presetId.isEmpty()) presetId = app.multiai.mobile.utils.SubagentStore.BUILTIN_GENERAL_ID;
        long timeoutSec = Math.max(5L, Math.min(300L, args.optLong("timeout_sec", 120L)));
        java.util.concurrent.atomic.AtomicReference<String> sessionId =
                new java.util.concurrent.atomic.AtomicReference<>("");
        app.multiai.mobile.utils.SubagentRunner runner =
                new app.multiai.mobile.utils.SubagentRunner(
                        this, workModeTools, new ArrayList<>(modelCatalog),
                        selectedModelId, selectedEffort, this::executeSubagentTool, id -> {
                    sessionId.set(id);
                    runOnUiThread(() -> {
                        if (sourceCall == null) return;
                        try {
                            JSONObject live = new JSONObject().put("success", true)
                                    .put("session_id", id).put("status", "running")
                                    .put("waiting", true);
                            sourceCall.setResult(live);
                            sourceCall.setReopenAction(() -> openSubagentSession(id));
                            refreshToolCallOwner(sourceCall);
                        } catch (Exception ignored) { }
                    });
                }, turnProjectRules);
        String output = runner.run(presetId, task, args.optString("context", ""), timeoutSec, token);
        JSONObject session = app.multiai.mobile.utils.SubagentSessionStore.load(this, sessionId.get());
        String clipped = output == null ? "" : output;
        boolean truncated = clipped.length() > 80_000;
        if (truncated) clipped = clipped.substring(0, 80_000);
        JSONObject result = new JSONObject().put("success", true).put("task", task)
                .put("preset_id", presetId).put("session_id", sessionId.get())
                .put("agent_name", session != null ? session.optString("agent_name", presetId) : presetId)
                .put("model", session != null ? session.optString("model", "") : "")
                .put("effort", session != null ? session.optString("effort", "") : "")
                .put("status", session != null ? session.optString("status", "completed") : "completed")
                .put("output", clipped).put("truncated", truncated);
        if (sourceCall != null && !sessionId.get().isEmpty()) {
            sourceCall.setReopenAction(() -> openSubagentSession(sessionId.get()));
        }
        return result.toString();
    }

    public void openSubagentSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty() || isFinishing()) return;
        Intent intent = new Intent(this, SubagentSessionActivity.class);
        intent.putExtra(SubagentSessionActivity.EXTRA_SESSION_ID, sessionId);
        startActivity(intent);
    }

    /** Runs a configured built-in or user preset with an isolated reasoning/tool loop. */
    private String invokeWorkSubagent(String presetId, String task, String context, long timeoutSec,
                                      OperationCancelToken token) {
        app.multiai.mobile.utils.SubagentRunner runner =
                new app.multiai.mobile.utils.SubagentRunner(
                        this, workModeTools, new ArrayList<>(modelCatalog),
                        selectedModelId, selectedEffort, this::executeSubagentTool, null,
                        turnProjectRules);
        return runner.run(presetId, task, context, timeoutSec, token);
    }

    private String executeSubagentTool(String name, JSONObject args, OperationCancelToken token) {
        try {
            if ("web_search".equals(name) || "url_fetch".equals(name)) {
                return executeMultiAiTool(null, name, args, token, turnEpoch);
            }
            if (!isWorkModeTool(name) || "invoke_subagent".equals(name)) {
                return new JSONObject().put("error", "Tool is not available to subagents")
                        .put("code", "SUBAGENT_TOOL_DENIED").toString();
            }
            activateWorkModeSession(currentChat);
            String approvalDetail = workModeApprovalDetail(name, args);
            if (app.multiai.mobile.utils.WorkModeAccess.isOperationBlocked(
                    this, name, approvalDetail)) {
                return new JSONObject().put("error", "Command is blocked by a global rule")
                        .put("code", "COMMAND_BLOCKED").toString();
            }
            if (app.multiai.mobile.utils.WorkModeAccess.requiresApproval(this, name, approvalDetail)
                    && !app.multiai.mobile.utils.WorkModeAccess.awaitApproval(
                    this, name, approvalDetail, APPROVAL_TIMEOUT_SEC, token)) {
                return new JSONObject().put("error", "Action rejected by user")
                        .put("code", "USER_DENIED").toString();
            }
            return workModeTools != null
                    ? workModeTools.execute(name, args, token)
                    : new JSONObject().put("error", "Work tools are not initialized").toString();
        } catch (Exception error) {
            try {
                return new JSONObject().put("error", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage()).toString();
            } catch (Exception ignored) {
                return "{\"error\":\"subagent tool failed\"}";
            }
        }
    }

    private String wmoToDescription(int code) {
        switch (code) {
            case 0: return "Clear sky";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: return "Light drizzle";
            case 53: return "Moderate drizzle";
            case 55: return "Dense drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: return "Slight rain";
            case 63: return "Moderate rain";
            case 65: return "Heavy rain";
            case 66: case 67: return "Freezing rain";
            case 71: return "Slight snow";
            case 73: return "Moderate snow";
            case 75: return "Heavy snow";
            case 77: return "Snow grains";
            case 80: return "Slight rain showers";
            case 81: return "Moderate rain showers";
            case 82: return "Violent rain showers";
            case 85: return "Slight snow showers";
            case 86: return "Heavy snow showers";
            case 95: return "Thunderstorm";
            case 96: case 99: return "Thunderstorm with hail";
            default: return "Unknown";
        }
    }

    // ══════════════════ VOICE INPUT ══════════════════

    private void toggleVoiceInput() {
        if (isListening) {
            stopVoiceInput();
        } else {
            startVoiceInput();
        }
    }

    private float peakRmsDb = 0f;
    private android.media.MediaRecorder mediaRecorder;
    private java.io.File audioFile;
    private boolean useWhisper = true;
    private int whisperPeakAmplitude = 0;
    private long whisperSilenceStartedAt = 0L;
    private Handler whisperSilenceHandler;
    private Runnable whisperSilenceRunnable;

    // Voice UI state machine (visual layer matching Chat Mode).
    private LinearLayout voiceInputGroup;
    private LinearLayout transcribingGroup;
    private TextView transcribeLabel;
    private View transcribeDot1, transcribeDot2, transcribeDot3;
    private app.multiai.mobile.ui.VoiceWaveformView voiceWaveform;
    private ImageButton btnVoiceStop, btnVoiceSend;
    private FrameLayout standardInputGroup;
    private android.animation.AnimatorSet transcribingAnimatorSet;
    private enum VoiceInputState { IDLE, RECORDING, TRANSCRIBING }
    private VoiceInputState currentVoiceState = VoiceInputState.IDLE;
    private boolean voiceSentByUserWork = false;

    private void startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionAction = "audio";
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }

        isListening = true;
        peakRmsDb = 0f;
        voiceSentByUserWork = false;
        startMicAnimation();
        transitionVoiceState(VoiceInputState.RECORDING);

        if (useWhisper) {
            startWhisperRecording();
        } else {
            startSpeechRecognizer();
        }
    }

    private void startMicAnimation() {
        // Voice UI is handled by the state machine (waveform + transcribing dots).
        // No more mic pulse — the voiceInputGroup replaces the old mic animation.
        editMessage.setHint(getString(R.string.chat_speak_now));
    }

    private void startWhisperRecording() {
        try {
            audioFile = new java.io.File(getCacheDir(), "whisper_" + System.currentTimeMillis() + ".m4a");
            mediaRecorder = new android.media.MediaRecorder();
            mediaRecorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setAudioEncodingBitRate(64000);
            mediaRecorder.setOutputFile(audioFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            // Auto-stop after 60 seconds max
            animationHandler.postDelayed(() -> {
                if (isListening && mediaRecorder != null) stopVoiceInput();
            }, 60000);
            // Silence detection + peak amplitude tracking. Auto-stop only after
            // 5 full seconds of continuous silence; any audible sample resets the
            // timer so short pauses don't cut the user off.
            whisperPeakAmplitude = 0;
            whisperSilenceStartedAt = 0L;
            whisperSilenceHandler = new Handler(Looper.getMainLooper());
            whisperSilenceRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isListening || mediaRecorder == null) return;
                    try {
                        int amp = mediaRecorder.getMaxAmplitude();
                        if (amp > whisperPeakAmplitude) whisperPeakAmplitude = amp;
                        // Mirror Chat Mode: drive the live waveform with mic amplitude.
                        if (voiceWaveform != null) {
                            float normalized = Math.min(1.0f, amp / 8000f);
                            voiceWaveform.setAmplitude(0.25f + normalized * 0.75f);
                        }
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (amp < 500) {
                            if (whisperSilenceStartedAt == 0L) whisperSilenceStartedAt = now;
                            if (now - whisperSilenceStartedAt >= 5000L) {
                                stopVoiceInput();
                                return;
                            }
                        } else {
                            whisperSilenceStartedAt = 0L;
                        }
                    } catch (Exception ignored) {}
                    if (whisperSilenceHandler != null) {
                        whisperSilenceHandler.postDelayed(this, 250);
                    }
                }
            };
            whisperSilenceHandler.postDelayed(whisperSilenceRunnable, 250);
        } catch (Exception e) {
            useWhisper = false;
            startSpeechRecognizer();
        }
    }

    private void stopWhisperAndTranscribe() {
        // Stop silence detection
        if (whisperSilenceHandler != null && whisperSilenceRunnable != null) {
            whisperSilenceHandler.removeCallbacks(whisperSilenceRunnable);
        }
        // Set peakRmsDb from recorded peak amplitude for caps detection
        if (whisperPeakAmplitude > 20000) {
            peakRmsDb = 15f; // High amplitude → triggers caps in postProcessVoice
        }
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception e) {
        }
        if (audioFile != null && audioFile.exists() && audioFile.length() > 0) {
            editMessage.setHint(getString(R.string.chat_recognizing));
            new Thread(() -> {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(audioFile);
                    byte[] bytes = new byte[(int) audioFile.length()];
                    fis.read(bytes);
                    fis.close();
                    String base64Audio = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);

                    JSONObject body = new JSONObject();
                    body.put("audio", base64Audio);

                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build();
                    okhttp3.Request req = new okhttp3.Request.Builder()
                            .url("https://multiai-api.vercel.app/api/whisper")
                            .post(okhttp3.RequestBody.create(body.toString(),
                                    okhttp3.MediaType.get("application/json; charset=utf-8")))
                            .build();
                    okhttp3.Response resp = client.newCall(req).execute();
                    String respBody = resp.body() != null ? resp.body().string() : "";
                    JSONObject result = new JSONObject(respBody);
                    String text = result.optString("text", "").trim();

                    runOnUiThread(() -> {
                        editMessage.setHint(getString(R.string.chat_message_hint));
                        final boolean recognized = !text.isEmpty();
                        if (recognized) {
                            String processed = postProcessVoice(text);
                            String current = editMessage.getText().toString();
                            if (!current.isEmpty() && !current.endsWith(" ")) current += " ";
                            editMessage.setText(current + processed);
                            editMessage.setSelection(editMessage.getText().length());
                        } else {
                            ModernToast.show(this, getString(R.string.chat_speech_not_recognized));
                        }
                        transitionVoiceState(VoiceInputState.IDLE);
                        if (recognized && voiceSentByUserWork) {
                            voiceSentByUserWork = false;
                            sendMessage();
                        }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        editMessage.setHint(getString(R.string.chat_message_hint));
                        ModernToast.show(this, getString(R.string.chat_error_recognition, e.getMessage()));
                        transitionVoiceState(VoiceInputState.IDLE);
                    });
                } finally {
                    if (audioFile != null) audioFile.delete();
                }
            }).start();
        }
    }

    private void startSpeechRecognizer() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            ModernToast.show(this, getString(R.string.chat_voice_unavailable));
            stopVoiceInput();
            return;
        }

        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {
                if (rmsdB > peakRmsDb) peakRmsDb = rmsdB;
            }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { stopVoiceInput(); }

            @Override
            public void onError(int error) {
                String msg = getString(R.string.chat_error_recognition_short);
                if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH) msg = getString(R.string.chat_speech_not_recognized);
                else if (error == android.speech.SpeechRecognizer.ERROR_NETWORK) msg = getString(R.string.chat_no_network);
                ModernToast.show(ChatActivity.this, msg);
                stopVoiceInput();
            }

            @Override
            public void onResults(Bundle results) {
                java.util.ArrayList<String> matches = results.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                final boolean[] recognized = {false};
                if (matches != null && !matches.isEmpty()) {
                    String text = postProcessVoice(matches.get(0));
                    String current = editMessage.getText().toString();
                    if (!current.isEmpty() && !current.endsWith(" ")) current += " ";
                    editMessage.setText(current + text);
                    editMessage.setSelection(editMessage.getText().length());
                    recognized[0] = !text.isEmpty();
                }
                stopVoiceInput();
                if (recognized[0] && voiceSentByUserWork) {
                    voiceSentByUserWork = false;
                    sendMessage();
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                java.util.ArrayList<String> partial = partialResults.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
                if (partial != null && !partial.isEmpty()) {
                    editMessage.setHint(partial.get(0));
                }
            }

            @Override public void onEvent(int eventType, Bundle params) {}
        });

        android.content.Intent intent = new android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra("android.speech.extra.ENABLE_FORMATTING", "ENABLED");
        intent.putExtra("android.speech.extra.PUNCTUATE", true);
        speechRecognizer.startListening(intent);
    }

    private String postProcessVoice(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        String text = raw.trim();

        if (!text.isEmpty()) {
            text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }

        if (peakRmsDb > 12f) {
            text = text.toUpperCase();
        }

        // Spoken punctuation → symbols (Russian)
        text = text.replaceAll("(?i)\\bточка с запятой\\b", ";");
        text = text.replaceAll("(?i)\\bвосклицательный знак\\b", "!");
        text = text.replaceAll("(?i)\\bвопросительный знак\\b", "?");
        text = text.replaceAll("(?i)\\bмноготочие\\b", "...");
        text = text.replaceAll("(?i)\\bоткрыть скобку\\b", "(");
        text = text.replaceAll("(?i)\\bзакрыть скобку\\b", ")");
        text = text.replaceAll("(?i)\\bкавычки\\b", "\"");
        text = text.replaceAll("(?i)\\bтире\\b", " — ");
        text = text.replaceAll("(?i)\\bдефис\\b", "-");
        text = text.replaceAll("(?i)\\bточка\\b", ".");
        text = text.replaceAll("(?i)\\bзапятая\\b", ",");
        text = text.replaceAll("(?i)\\bдвоеточие\\b", ":");
        text = text.replaceAll("(?i)\\bновая строка\\b", "\n");
        text = text.replaceAll("(?i)\\bабзац\\b", "\n\n");
        // Spoken punctuation → symbols (English)
        text = text.replaceAll("(?i)\\bexclamation mark\\b", "!");
        text = text.replaceAll("(?i)\\bquestion mark\\b", "?");
        text = text.replaceAll("(?i)\\bellipsis\\b", "...");
        text = text.replaceAll("(?i)\\bopen paren\\b", "(");
        text = text.replaceAll("(?i)\\bclose paren\\b", ")");
        text = text.replaceAll("(?i)\\bperiod\\b", ".");
        text = text.replaceAll("(?i)\\bcomma\\b", ",");
        text = text.replaceAll("(?i)\\bcolon\\b", ":");
        text = text.replaceAll("(?i)\\bsemicolon\\b", ";");
        text = text.replaceAll("(?i)\\bnew line\\b", "\n");
        text = text.replaceAll("(?i)\\bnew paragraph\\b", "\n\n");

        text = text.replaceAll("\\s+", " ").trim();
        text = text.replaceAll("\\s+([.,!?:;)\"…])", "$1");
        text = text.replaceAll("([(\"«])\\s+", "$1");

        // Capitalize after sentence-ending punctuation
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < sb.length() - 2; i++) {
            char c = sb.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && sb.charAt(i + 1) == ' '
                    && Character.isLowerCase(sb.charAt(i + 2))) {
                sb.setCharAt(i + 2, Character.toUpperCase(sb.charAt(i + 2)));
            }
        }
        text = sb.toString();

        return text;
    }

    /** Отменить запись без расшифровки (крестик/+ во время записи). */
    private void cancelVoiceInput() {
        if (currentVoiceState != VoiceInputState.RECORDING
                && currentVoiceState != VoiceInputState.TRANSCRIBING) return;
        isListening = false;
        voiceSentByUserWork = false;
        if (whisperSilenceHandler != null && whisperSilenceRunnable != null) {
            whisperSilenceHandler.removeCallbacks(whisperSilenceRunnable);
        }
        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            }
        } catch (Exception ignored) {}
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (audioFile != null && audioFile.exists()) audioFile.delete();
        if (editMessage != null) editMessage.setHint(getString(R.string.chat_message_hint));
        transitionVoiceState(VoiceInputState.IDLE);
    }

    private void stopVoiceInput() {
        isListening = false;
        if (buttonMic != null) {
            buttonMic.clearColorFilter();
            buttonMic.setScaleX(1f);
            buttonMic.setScaleY(1f);
            if (micPulseAnimator != null) {
                micPulseAnimator.cancel();
                micPulseAnimator = null;
            }
        }
        updateSendMicVisibility();
        // Stop Whisper recording and transcribe
        if (mediaRecorder != null) {
            transitionVoiceState(VoiceInputState.TRANSCRIBING);
            stopWhisperAndTranscribe();
            return;
        }
        // Stop SpeechRecognizer
        if (speechRecognizer != null) {
            speechRecognizer.stopListening();
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        editMessage.setHint(getString(R.string.chat_message_hint));
        transitionVoiceState(VoiceInputState.IDLE);
    }

    // ══════════════════ VOICE VISUAL STATE MACHINE ══════════════════

    private void transitionVoiceState(VoiceInputState target) {
        if (currentVoiceState == target) return;
        VoiceInputState prev = currentVoiceState;
        currentVoiceState = target;

        View outgoing = voiceViewForState(prev);
        View incoming = voiceViewForState(target);

        if (outgoing != null) {
            outgoing.animate().cancel();
            outgoing.animate()
                    .alpha(0f)
                    .translationY(-dpToPx(8))
                    .setDuration(160)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator(1.2f))
                    .withEndAction(() -> outgoing.setVisibility(View.GONE))
                    .start();
        }
        if (incoming != null) {
            incoming.animate().cancel();
            incoming.setVisibility(View.VISIBLE);
            incoming.setAlpha(0f);
            incoming.setTranslationY(dpToPx(10));
            incoming.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setStartDelay(60)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();
        }

        // Toggle visibility of the standard input cluster (IDLE group).
        // In RECORDING/TRANSCRIBING the whole EditText + mic/send/stop/attach
        // cluster is hidden so the voice group occupies the input box alone.
        boolean idle = target == VoiceInputState.IDLE;
        if (standardInputGroup != null) standardInputGroup.setVisibility(idle ? View.VISIBLE : View.GONE);
        // Контекст-кольцо скрывается на время голосового ввода.
        if (contextRing != null) contextRing.setVisibility(idle ? View.VISIBLE : View.GONE);
        // Hide the [+] attach button during voice recording (it lives outside
        // inputBox in the MotionLayout, so GONE-ing the inner cluster isn't enough).
        if (buttonAttachPhoto != null) buttonAttachPhoto.setVisibility(idle ? View.VISIBLE : View.GONE);
        // Restore send/mic visibility when returning to IDLE.
        if (idle) updateSendMicVisibility();

        if (target == VoiceInputState.RECORDING) {
            if (voiceWaveform != null) voiceWaveform.setListening(true);
            stopTranscribingDots();
        } else if (target == VoiceInputState.TRANSCRIBING) {
            if (voiceWaveform != null) voiceWaveform.setListening(false);
            startTranscribingDots();
        } else {
            if (voiceWaveform != null) voiceWaveform.setListening(false);
            stopTranscribingDots();
        }

        // Sync MotionLayout scene so [+] button animates away during recording.
        syncInputMotion();
    }

    private View voiceViewForState(VoiceInputState state) {
        if (state == null) return null;
        switch (state) {
            case RECORDING: return voiceInputGroup;
            case TRANSCRIBING: return transcribingGroup;
            case IDLE:
            default: return standardInputGroup;
        }
    }

    private void startTranscribingDots() {
        stopTranscribingDots();
        if (transcribeDot1 == null) return;
        android.animation.ObjectAnimator a1 = voiceDotPulseAnim(transcribeDot1, 0);
        android.animation.ObjectAnimator a2 = voiceDotPulseAnim(transcribeDot2, 150);
        android.animation.ObjectAnimator a3 = voiceDotPulseAnim(transcribeDot3, 300);
        transcribingAnimatorSet = new android.animation.AnimatorSet();
        transcribingAnimatorSet.playTogether(a1, a2, a3);
        transcribingAnimatorSet.start();
    }

    private android.animation.ObjectAnimator voiceDotPulseAnim(View dot, long delay) {
        android.animation.ObjectAnimator anim = android.animation.ObjectAnimator
                .ofFloat(dot, "alpha", 0.3f, 1f, 0.3f);
        anim.setDuration(950);
        anim.setStartDelay(delay);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        return anim;
    }

    private void stopTranscribingDots() {
        if (transcribingAnimatorSet != null) {
            transcribingAnimatorSet.cancel();
            transcribingAnimatorSet = null;
        }
    }

    // ══════════════════ POLLING (announcements + models) ══════════════════

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollAnnouncementAndModels();
            pollHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private void startPolling() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        lastAnnouncementHash = prefs.getString("last_announcement_hash", "");
        pollHandler.removeCallbacks(pollRunnable);
        pollHandler.post(pollRunnable);
    }

    private void stopPolling() {
        pollHandler.removeCallbacks(pollRunnable);
    }


    private void pollAnnouncementAndModels() {
        new Thread(() -> {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();

            // 1. Check announcement (JSON format: {text, id, ts})
            try {
                okhttp3.Request req = new okhttp3.Request.Builder()
                        .url(BACKEND_URL + "/api/admin?action=get_announcement")
                        .get()
                        .build();
                okhttp3.Response resp = client.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    JSONObject data = new JSONObject(resp.body().string());
                    String raw = data.optString("value", "").trim();
                    if (!raw.isEmpty()) {
                        // Parse announcement - could be JSON {text,id,ts} or plain text
                        String msg = raw;
                        String announcementId = String.valueOf(raw.hashCode());
                        try {
                            JSONObject ann = new JSONObject(raw);
                            msg = ann.optString("text", "").trim();
                            announcementId = ann.optString("id", announcementId);
                        } catch (Exception ignored) {}
                        
                        if (!msg.isEmpty() && !announcementId.equals(lastAnnouncementHash)) {
                            lastAnnouncementHash = announcementId;
                            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                                    .edit().putString("last_announcement_hash", announcementId).apply();

                            final String finalMsg = msg;
                            runOnUiThread(() -> {
                                try {
                                    android.media.RingtoneManager.getRingtone(
                                            ChatActivity.this,
                                            android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                                    ).play();
                                } catch (Exception ignored2) {}

                                showStyledDialog("MultiAI", finalMsg);
                            });
                        }
                    }
                }
            } catch (Exception e) {
            }

            // 2. Refresh models list
            try {
                loadAvailableModels();
            } catch (Exception e) {
            }
        }).start();
    }

    private void checkAnnouncement() {
        // Now handled by polling in onResume
    }
}
