package app.multiai.mobile.ui;

import android.animation.ObjectAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spannable;
import android.text.style.TypefaceSpan;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import app.multiai.mobile.R;
import app.multiai.mobile.terminal.PersistentShell;
import app.multiai.mobile.terminal.ToolsManager;
import app.multiai.mobile.terminal.TerminalPackageManager;
import app.multiai.mobile.terminal.TerminalService;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.utils.DrawerNavHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TerminalActivity — local terminal backed by a PERSISTENT interactive
 * shell process (see PersistentShell).
 *
 *  - Every user command is fed to the same sh process, so cd / export /
 *    PATH changes survive between commands.
 *  - Commands that read stdin (adb pair, python input()) are fed line by
 *    line: when the shell goes idle for a moment without emitting the
 *    end-of-command marker, the UI flips to INPUT mode and the next Enter
 *    writes to stdin directly.
 *  - The Activity declares android:configChanges so rotation does NOT
 *    recreate it, and the shell therefore survives orientation changes.
 *
 * Available tools: python, pip, node, npm, git, java, javac, gradle,
 * aapt2, adb, zipalign, apksigner, and all toybox coreutils (ls, cat,
 * cd, mkdir, rm, cp, mv, grep, find, sed, awk, head, tail, echo, ...).
 */
public class TerminalActivity extends AppCompatActivity {

    private static final String HOME_HINT =
            "/sdcard"; // initial cwd hint before the shell reports a real one
    private static final int MAX_HISTORY_CHARS = 100_000;
    private static final int MAX_HISTORY_LINES = 5_000;

    private TextView textTerminalOutput;
    private ScrollView scrollTerminal;
    private EditText editCommand;
    private ImageButton btnExecute;
    private ImageButton btnBack;
    private View storagePermissionGate;
    private boolean waitingForStoragePermission;

    private StringBuilder terminalHistory;
    private Handler mainHandler;
    private ExecutorService extractExecutor;

    private ToolsManager toolsManager;
    private TerminalPackageManager packageManager;
    private PersistentShell shell;
    private boolean toolsReady = false;

    private FrameLayout setupOverlay;
    private LinearLayout packageList;
    private View installProgressGroup;
    private View setupBottomBar;
    private ImageView setupHeroIcon;
    private ImageView setupArrow;
    private TextView setupTitle;
    private TextView setupSubtitle;
    private TextView setupSummary;
    private TextView installStage;
    private TextView installDetail;
    private MaterialButton setupInstall;
    private MaterialButton setupBasicFallback;
    private LinearProgressIndicator installProgress;
    private final Set<String> selectedPackages = new HashSet<>();
    private final Set<String> pendingRemoval = new HashSet<>();
    private final Map<String, FrameLayout> packageChecks = new HashMap<>();
    private final Map<String, View> packageCards = new HashMap<>();
    private List<TerminalPackageManager.PackageSpec> packageManifest = new ArrayList<>();
    private boolean javaManualSelection;
    private boolean androidManualSelection;
    private boolean managerMode;
    private ObjectAnimator arrowAnimator;
    private android.animation.ValueAnimator progressAnimator;
    private int displayedInstallProgress;
    private int targetInstallProgress;
    private long completedProgressWeight;
    private long currentStageWeight;
    private long totalInstallWeight = 1;
    private TerminalPackageManager.Stage lastInstallStage;
    private String lastProgressPackageId;
    private volatile boolean installRunning;

    // Current working directory as reported by the shell after each command.
    private String currentCwd = HOME_HINT;
    // True while a command is running (input field is for stdin, not cmds).
    private boolean inputMode = false;
    // True while a command is actively executing — drives the disabled visual
    // state on the run button + input field.
    private boolean commandExecuting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_terminal);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        initViews();
        setupDrawer();
        setupListeners();

        terminalHistory = new StringBuilder();
        mainHandler = new Handler(Looper.getMainLooper());
        extractExecutor = Executors.newSingleThreadExecutor();

        toolsManager = new ToolsManager(this);
        packageManager = new TerminalPackageManager(this);

        // Start foreground service
        Intent serviceIntent = new Intent(this, TerminalService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        prepareTools();
    }

    private void requestStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        }
    }

    private void initViews() {
        textTerminalOutput = findViewById(R.id.textTerminalOutput);
        scrollTerminal = findViewById(R.id.scrollTerminal);
        editCommand = findViewById(R.id.editCommand);
        btnExecute = findViewById(R.id.btnExecute);
        btnBack = findViewById(R.id.btnBack);
        storagePermissionGate = findViewById(R.id.storagePermissionGate);
        View grantStorage = findViewById(R.id.btnGrantStorageAccess);
        if (grantStorage != null) grantStorage.setOnClickListener(v -> requestStorageAccess());
        setupOverlay = findViewById(R.id.setupOverlay);
        packageList = findViewById(R.id.packageList);
        installProgressGroup = findViewById(R.id.installProgressGroup);
        setupBottomBar = findViewById(R.id.setupBottomBar);
        setupHeroIcon = findViewById(R.id.setupHeroIcon);
        setupArrow = findViewById(R.id.setupArrow);
        setupTitle = findViewById(R.id.setupTitle);
        setupSubtitle = findViewById(R.id.setupSubtitle);
        setupSummary = findViewById(R.id.setupSummary);
        setupInstall = findViewById(R.id.setupInstall);
        setupBasicFallback = findViewById(R.id.setupBasicFallback);
        setupBasicFallback.setOnClickListener(v -> completeBasicSetup());
        installStage = findViewById(R.id.installStage);
        installDetail = findViewById(R.id.installDetail);
        installProgress = findViewById(R.id.installProgress);
        View setupBack = findViewById(R.id.setupBack);
        if (setupBack != null) setupBack.setOnClickListener(v -> {
            if (installRunning) {
                if (packageManager != null) packageManager.cancelInstall();
            } else if (managerMode) {
                closeToolsManager();
            } else {
                finish();
            }
        });
        View btnManageTools = findViewById(R.id.btnManageTools);
        if (btnManageTools != null) btnManageTools.setOnClickListener(v -> openToolsManager());
    }

    private void setupListeners() {
        btnExecute.setOnClickListener(v -> onEnterPressed());
        editCommand.setOnEditorActionListener((v, actionId, event) -> {
            onEnterPressed();
            return true;
        });
        btnBack.setOnClickListener(v -> finish());
    }

    private void setupDrawer() {
        DrawerLayout drawerLayout = findViewById(R.id.drawerLayout);
        View drawerView = findViewById(R.id.drawerFrame);
        ImageButton buttonMenu = findViewById(R.id.buttonMenu);

        if (drawerLayout == null || drawerView == null || buttonMenu == null) {
            return;
        }

        buttonMenu.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(drawerView)) {
                drawerLayout.closeDrawer(drawerView);
            } else {
                drawerLayout.openDrawer(drawerView);
            }
        });

        DrawerNavHelper.setup(this, DrawerNavHelper.NavTarget.TERMINAL,
                target -> {
                    switch (target) {
                        case CHAT:
                            startActivity(new Intent(this, ChatActivity.class));
                            finish();
                            return true;
                        case FILES:
                            startActivity(new Intent(this, FilesActivity.class));
                            finish();
                            return true;
                        case EDITOR:
                            startActivity(new Intent(this, EditorActivity.class));
                            finish();
                            return true;
                        case TERMINAL:
                        default:
                            drawerLayout.closeDrawer(drawerView);
                            return true;
                    }
                },
                v -> {
                    startActivity(new Intent(this, ChatModeSettingsActivity.class));
                    finish();
                },
                v -> {
                    startActivity(new Intent(this, ProfileActivity.class));
                    finish();
                });
    }

    /**
     * Extract tools if needed, or reuse cached extraction, then start the
     * persistent shell.
     */
    private boolean hasStorageAccess() {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R
                || android.os.Environment.isExternalStorageManager();
    }

    private void showStoragePermissionGate() {
        waitingForStoragePermission = true;
        if (shell != null) {
            shell.stop();
            shell = null;
        }
        toolsReady = false;
        storagePermissionGate.setVisibility(View.VISIBLE);
        scrollTerminal.setVisibility(View.GONE);
        View input = findViewById(R.id.inputContainer);
        if (input != null) input.setVisibility(View.GONE);
        textTerminalOutput.setText(getString(R.string.scr_term_storage_request));
        editCommand.setEnabled(false);
        btnExecute.setEnabled(false);
        // Mirror the executing-disabled visual so the run control reads as off.
        btnExecute.setAlpha(0.4f);
        commandExecuting = true;
    }

    private void hideStoragePermissionGate() {
        waitingForStoragePermission = false;
        storagePermissionGate.setVisibility(View.GONE);
        scrollTerminal.setVisibility(View.VISIBLE);
        View input = findViewById(R.id.inputContainer);
        if (input != null) input.setVisibility(View.VISIBLE);
        // Clear the disabled visual so the run control is ready again.
        commandExecuting = false;
        btnExecute.setAlpha(1f);
    }

    private void prepareTools() {
        if (!hasStorageAccess()) {
            showStoragePermissionGate();
            return;
        }
        hideStoragePermissionGate();
        if (packageManager.isConfigured()) {
            textTerminalOutput.setText(getString(R.string.scr_term_starting_shell));
            editCommand.setEnabled(false);
            btnExecute.setEnabled(false);
            extractExecutor.execute(() -> {
                toolsManager.initializeEnvironment();
                mainHandler.post(() -> {
                    terminalHistory.setLength(0);
                    toolsReady = true;
                    editCommand.setEnabled(true);
                    btnExecute.setEnabled(true);
                    startShell();
                });
            });
            return;
        }
        showPackageSetup();
    }

    private void openToolsManager() {
        if (installRunning) return;
        managerMode = true;
        selectedPackages.clear();
        pendingRemoval.clear();
        setupTitle.setText(getString(R.string.scr_term_tools_title));
        setupSubtitle.setText(getString(R.string.scr_term_tools_subtitle));
        setupSubtitle.setVisibility(View.VISIBLE);
        setupHeroIcon.setImageResource(R.drawable.ic_terminal_tools);
        setupArrow.setVisibility(View.GONE);
        packageList.setVisibility(View.VISIBLE);
        installProgressGroup.setVisibility(View.GONE);
        setupBasicFallback.setVisibility(View.GONE);
        setupBottomBar.setVisibility(View.VISIBLE);
        setupInstall.setEnabled(true);
        setupOverlay.setAlpha(0f);
        setupOverlay.setVisibility(View.VISIBLE);
        setupOverlay.animate().alpha(1f).setDuration(180).start();
        extractExecutor.execute(() -> {
            List<TerminalPackageManager.PackageSpec> loaded = packageManager.loadManifest();
            mainHandler.post(() -> renderPackageChoices(loaded));
        });
    }

    private void closeToolsManager() {
        if (installRunning) return;
        setupOverlay.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            setupOverlay.setVisibility(View.GONE);
            setupOverlay.setAlpha(1f);
            managerMode = false;
        }).start();
    }

    private void showPackageSetup() {
        managerMode = false;
        setupOverlay.setVisibility(View.VISIBLE);
        editCommand.setEnabled(false);
        btnExecute.setEnabled(false);
        setupInstall.setOnClickListener(v -> {
            if (selectedPackages.isEmpty()) {
                completeBasicSetup();
            } else {
                installSelectedPackages();
            }
        });
        extractExecutor.execute(() -> {
            List<TerminalPackageManager.PackageSpec> loaded = packageManager.loadManifest();
            mainHandler.post(() -> renderPackageChoices(loaded));
        });
    }

    private void renderPackageChoices(List<TerminalPackageManager.PackageSpec> specs) {
        packageManifest = specs;
        if (managerMode) {
            selectedPackages.clear();
            pendingRemoval.clear();
        }
        packageList.removeAllViews();
        packageChecks.clear();
        packageCards.clear();
        packageList.addView(createBasicCard());

        TerminalPackageManager.PackageSpec gradle = null;
        List<TerminalPackageManager.PackageSpec> modules = new ArrayList<>();
        for (TerminalPackageManager.PackageSpec spec : specs) {
            if (spec.hidden) continue;
            if ("gradle".equals(spec.id)) gradle = spec; else modules.add(spec);
        }
        if (gradle != null) packageList.addView(createPackageCard(gradle, true));

        for (int i = 0; i < modules.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.addView(createPackageCard(modules.get(i), false), bentoCellParams(true));
            if (i + 1 < modules.size()) {
                row.addView(createPackageCard(modules.get(i + 1), false), bentoCellParams(false));
            } else {
                View spacer = new View(this);
                row.addView(spacer, bentoCellParams(false));
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(12);
            row.setLayoutParams(rowParams);
            packageList.addView(row);
        }
        updateSetupSummary();
    }

    private LinearLayout.LayoutParams bentoCellParams(boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (first) params.rightMargin = dp(6); else params.leftMargin = dp(6);
        return params;
    }

    private View createBasicCard() {
        LinearLayout card = basePackageCard(true);
        card.setActivated(true);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView icon = shellIconTile();
        top.addView(icon);
        View push = new View(this);
        top.addView(push, new LinearLayout.LayoutParams(0, 1, 1f));
        TextView included = includedBadge();
        top.addView(included);
        card.addView(top);
        TextView title = bentoTitle(getString(R.string.scr_term_basic_shell), 20);
        title.setPadding(0, dp(14), 0, 0);
        card.addView(title);
        card.addView(bentoDetail(getString(R.string.scr_term_basic_detail)));
        return card;
    }

    private View createPackageCard(TerminalPackageManager.PackageSpec spec, boolean featured) {
        LinearLayout card = basePackageCard(featured);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ImageView icon = toolIcon(spec.id, featured);
        top.addView(icon);
        View push = new View(this);
        top.addView(push, new LinearLayout.LayoutParams(0, 1, 1f));
        FrameLayout check = selectionControl(false);
        top.addView(check);
        card.addView(top);

        TextView title = bentoTitle(spec.name, featured ? 22 : 18);
        title.setPadding(0, featured ? dp(16) : dp(12), 0, 0);
        card.addView(title);
        card.addView(bentoDetail(spec.subtitle));
        TextView size = bentoDetail(formatBytes(spec.downloadBytes));
        size.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        size.setTypeface(null, android.graphics.Typeface.BOLD);
        size.setPadding(0, dp(8), 0, 0);
        card.addView(size);

        packageChecks.put(spec.id, check);
        packageCards.put(spec.id, card);
        boolean installed = managerMode && packageManager.isPackageHealthyForUi(spec);
        card.setActivated(installed);
        updateSelectionPill(spec.id, installed);
        if (installed) {
            size.setText(getString(R.string.scr_term_installed_format, formatBytes(spec.unpackedBytes)));
        }
        card.setOnClickListener(v -> handlePackageTap(spec));
        return card;
    }

    private LinearLayout basePackageCard(boolean featured) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(featured ? android.view.Gravity.CENTER_VERTICAL : android.view.Gravity.TOP);
        card.setPadding(featured ? dp(20) : dp(16), featured ? dp(18) : dp(14),
                featured ? dp(20) : dp(16), featured ? dp(18) : dp(14));
        card.setBackgroundResource(R.drawable.terminal_tool_card_bg);
        card.setElevation(dp(featured ? 4 : 2));
        card.setClipChildren(false);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            card.setOutlineAmbientShadowColor(ContextCompat.getColor(this, R.color.shadow_ambient));
            card.setOutlineSpotShadowColor(ContextCompat.getColor(this, R.color.shadow_spot));
        }
        if (featured) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(12);
            card.setLayoutParams(params);
        }
        return card;
    }

    private TextView shellIconTile() {
        TextView icon = new TextView(this);
        icon.setText("$_");
        icon.setGravity(android.view.Gravity.CENTER);
        icon.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        icon.setTextSize(16);
        icon.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        icon.setBackgroundResource(R.drawable.terminal_icon_tile_bg);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return icon;
    }

    private ImageView toolIcon(String id, boolean featured) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(toolIconRes(id));
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int width = featured ? dp(128) : dp(58);
        int height = featured ? dp(62) : dp(54);
        icon.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        icon.setAdjustViewBounds(true);
        return icon;
    }

    private int toolIconRes(String id) {
        switch (id) {
            case "python": return R.drawable.tool_python;
            case "node": return R.drawable.tool_node;
            case "git": return R.drawable.tool_git;
            case "java": return R.drawable.tool_java;
            case "android": return R.drawable.tool_android;
            case "gradle": return R.drawable.tool_gradle;
            default: return R.drawable.ic_terminal_tools;
        }
    }

    private TextView includedBadge() {
        TextView badge = new TextView(this);
        badge.setText(getString(R.string.scr_term_included));
        badge.setSingleLine(true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setTextColor(ContextCompat.getColor(this, R.color.icon_on_accent));
        badge.setTextSize(12);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setBackgroundResource(R.drawable.terminal_included_badge_bg);
        badge.setPadding(dp(12), 0, dp(12), 0);
        badge.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
        return badge;
    }

    private FrameLayout selectionControl(boolean selected) {
        FrameLayout control = new FrameLayout(this);
        control.setBackgroundResource(selected
                ? R.drawable.terminal_check_selected_bg : R.drawable.terminal_check_bg);
        ImageView check = new ImageView(this);
        check.setImageResource(R.drawable.ic_check);
        check.setColorFilter(ContextCompat.getColor(this, R.color.icon_on_accent));
        check.setPadding(dp(8), dp(8), dp(8), dp(8));
        check.setAlpha(selected ? 1f : 0f);
        check.setScaleX(selected ? 1f : 0.35f);
        check.setScaleY(selected ? 1f : 0.35f);
        control.addView(check, new FrameLayout.LayoutParams(dp(32), dp(32)));
        control.setTag(check);
        control.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
        return control;
    }

    private TextView bentoTitle(String text, int size) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        title.setTextSize(size);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return title;
    }

    private TextView bentoDetail(String text) {
        TextView detail = new TextView(this);
        detail.setText(text);
        detail.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        detail.setTextSize(12);
        detail.setMaxLines(2);
        detail.setLineSpacing(dp(1), 1f);
        detail.setPadding(0, dp(4), 0, 0);
        return detail;
    }

    private String packageGlyph(String id) {
        switch (id) {
            case "python": return "Py";
            case "node": return "JS";
            case "git": return "Git";
            case "java": return "J";
            case "android": return "A";
            case "gradle": return "G";
            default: return "$_";
        }
    }

    private void handlePackageTap(TerminalPackageManager.PackageSpec spec) {
        if (!managerMode || !packageManager.isPackageInstalled(spec.id)) {
            setPackageSelected(spec.id, !selectedPackages.contains(spec.id));
            return;
        }
        boolean removing = pendingRemoval.contains(spec.id);
        if (!removing) {
            List<String> dependents = packageManager.installedDependents(spec.id, packageManifest, pendingRemoval);
            if (!dependents.isEmpty()) {
                ModernToast.show(this, getString(R.string.scr_term_used_by,
                        android.text.TextUtils.join(", ", dependents)), true);
                return;
            }
            pendingRemoval.add(spec.id);
        } else {
            pendingRemoval.remove(spec.id);
        }
        View card = packageCards.get(spec.id);
        if (card != null) {
            card.setActivated(!pendingRemoval.contains(spec.id));
            card.setAlpha(pendingRemoval.contains(spec.id) ? 0.58f : 1f);
        }
        updateSelectionPill(spec.id, !pendingRemoval.contains(spec.id));
        updateSetupSummary();
    }

    private void setPackageSelected(String id, boolean selected) {
        if (selected) selectedPackages.add(id); else selectedPackages.remove(id);
        if ("java".equals(id) && !selectedPackages.contains("gradle")) javaManualSelection = selected;
        if ("android".equals(id) && !selectedPackages.contains("gradle")) androidManualSelection = selected;
        if ("gradle".equals(id)) updateGradleDependencies(selected);
        View card = packageCards.get(id);
        if (card != null) card.setActivated(selected);
        updateSelectionPill(id, selected);
        updateSetupSummary();
    }

    private void updateGradleDependencies(boolean gradleSelected) {
        View javaCard = packageCards.get("java");
        View androidCard = packageCards.get("android");
        TransitionManager.beginDelayedTransition(packageList, new AutoTransition().setDuration(260));
        if (gradleSelected) {
            javaManualSelection = selectedPackages.contains("java");
            androidManualSelection = selectedPackages.contains("android");
            selectedPackages.add("java");
            selectedPackages.add("android");
            updateSelectionPill("java", true);
            updateSelectionPill("android", true);
            if (javaCard != null) javaCard.setVisibility(View.GONE);
            if (androidCard != null) androidCard.setVisibility(View.GONE);
        } else {
            if (!javaManualSelection) selectedPackages.remove("java");
            if (!androidManualSelection) selectedPackages.remove("android");
            updateSelectionPill("java", javaManualSelection);
            updateSelectionPill("android", androidManualSelection);
            if (javaCard != null) javaCard.setVisibility(View.VISIBLE);
            if (androidCard != null) androidCard.setVisibility(View.VISIBLE);
        }
    }

    private void updateSelectionPill(String id, boolean selected) {
        FrameLayout control = packageChecks.get(id);
        if (control == null) return;
        ImageView check = (ImageView) control.getTag();
        control.setBackgroundResource(selected
                ? R.drawable.terminal_check_selected_bg : R.drawable.terminal_check_bg);
        check.animate().cancel();
        check.animate()
                .alpha(selected ? 1f : 0f)
                .scaleX(selected ? 1f : 0.35f)
                .scaleY(selected ? 1f : 0.35f)
                .setDuration(180)
                .setInterpolator(selected
                        ? new android.view.animation.OvershootInterpolator(1.6f)
                        : new android.view.animation.AccelerateInterpolator())
                .start();
    }

    private void updateSetupSummary() {
        long bytes = packageManager.totalDownloadBytes(packageManifest, selectedPackages);
        int visibleCount = selectedPackages.size();
        if (selectedPackages.contains("gradle")) visibleCount -= 2;
        if (managerMode) {
            long removalBytes = 0;
            for (String id : pendingRemoval) removalBytes += packageManager.installedBytes(id, packageManifest);
            StringBuilder summary = new StringBuilder();
            if (visibleCount > 0) summary.append(getString(R.string.scr_term_download_summary, visibleCount, formatBytes(bytes)));
            if (!pendingRemoval.isEmpty()) {
                if (summary.length() > 0) summary.append("   •   ");
                summary.append(getString(R.string.scr_term_remove_summary, pendingRemoval.size(), formatBytes(removalBytes)));
            }
            setupSummary.setText(summary.length() == 0 ? getString(R.string.scr_term_all_applied) : summary.toString());
            setupInstall.setText(summary.length() == 0 ? getString(R.string.scr_done) : getString(R.string.scr_term_apply_changes));
            setupInstall.setOnClickListener(v -> {
                if (summary.length() == 0) closeToolsManager(); else confirmAndApplyToolChanges();
            });
        } else {
            setupSummary.setText(visibleCount == 0
                    ? getString(R.string.scr_term_basic_set)
                    : getString(R.string.scr_term_n_tools, visibleCount, formatBytes(bytes)));
            setupInstall.setText(visibleCount == 0 ? getString(R.string.scr_term_open_basic) : getString(R.string.scr_term_download_install));
        }
    }

    private void confirmAndApplyToolChanges() {
        if (pendingRemoval.isEmpty()) {
            applyToolChanges();
            return;
        }
        showModernRemoveSheet();
    }

    private void showModernRemoveSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog =
                new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(18), dp(24), dp(26));
        content.setBackgroundResource(R.drawable.terminal_setup_bottom_bg);

        View handle = new View(this);
        android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
        handleBg.setColor(ContextCompat.getColor(this, R.color.stroke));
        handleBg.setCornerRadius(dp(2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(42), dp(4));
        handleParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.addView(handle, handleParams);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_trash);
        icon.setColorFilter(ContextCompat.getColor(this, R.color.error));
        icon.setBackgroundResource(R.drawable.terminal_icon_tile_bg);
        icon.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(58), dp(58));
        iconParams.topMargin = dp(24);
        iconParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.addView(icon, iconParams);

        TextView title = bentoTitle(getString(R.string.scr_term_remove_tools_title), 22);
        title.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(18);
        content.addView(title, titleParams);

        TextView message = bentoDetail(getString(R.string.scr_term_remove_count_msg, pendingRemoval.size()));
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextSize(14);
        content.addView(message);

        MaterialButton remove = new MaterialButton(this);
        remove.setText(getString(R.string.scr_remove));
        remove.setTextColor(ContextCompat.getColor(this, R.color.text_on_accent));
        remove.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error)));
        remove.setCornerRadius(dp(20));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        removeParams.topMargin = dp(22);
        content.addView(remove, removeParams);

        MaterialButton cancel = new MaterialButton(this);
        cancel.setText(getString(R.string.scr_cancel));
        cancel.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        cancel.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.surface_tonal)));
        cancel.setCornerRadius(dp(20));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        cancelParams.topMargin = dp(10);
        content.addView(cancel, cancelParams);

        remove.setOnClickListener(v -> { dialog.dismiss(); applyToolChanges(); });
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(content);

        // Make the window fully transparent and remove the Material container's
        // default background so only our rounded content background shows — this
        // gets rid of the grey square corners visible at the bottom of the sheet.
        dialog.setOnShowListener(d -> {
            android.view.Window window = dialog.getWindow();
            if (window != null) {
                window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
                window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            }
            FrameLayout bottomSheet = dialog.findViewById(
                    com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                bottomSheet.setBackgroundResource(android.R.color.transparent);
            }
        });
        dialog.show();
    }

    private void applyToolChanges() {
        if (installRunning) return;
        installRunning = true;
        if (shell != null) {
            shell.stop();
            shell = null;
        }
        setupInstall.setEnabled(false);
        setupBottomBar.setVisibility(View.GONE);
        crossfadeSetupToProgress();
        setupSubtitle.setVisibility(View.GONE);
        setupTitle.setText(getString(R.string.scr_term_applying));
        setupHeroIcon.setImageResource(R.drawable.ic_terminal_tools);
        setupArrow.setVisibility(View.GONE);
        startArrowAnimation();
        Set<String> removals = new HashSet<>(pendingRemoval);
        Set<String> installs = new HashSet<>(selectedPackages);
        extractExecutor.execute(() -> {
            try {
                if (!removals.isEmpty()) {
                    packageManager.removePackages(removals, packageManifest, (stage, spec, current, total, message) ->
                            mainHandler.post(() -> updateInstallProgress(stage, spec, current, total, message)));
                }
                if (!installs.isEmpty()) {
                    packageManager.install(packageManifest, installs, (stage, spec, current, total, message) ->
                            mainHandler.post(() -> updateInstallProgress(stage, spec, current, total, message)));
                }
                toolsManager.initializeEnvironment();
                mainHandler.post(() -> {
                    installRunning = false;
                    selectedPackages.clear();
                    pendingRemoval.clear();
                    toolsReady = true;
                    startShell();
                    openToolsManager();
                });
            } catch (Exception error) {
                mainHandler.post(() -> showInstallError(error.getMessage()));
            }
        });
    }

    private void completeBasicSetup() {
        getSharedPreferences("terminal_packages", MODE_PRIVATE).edit().putBoolean("configured", true).apply();
        toolsManager.initializeEnvironment();
        revealTerminal();
    }

    private void installSelectedPackages() {
        if (installRunning) return;
        installRunning = true;
        setupInstall.setEnabled(false);
        setupBottomBar.setVisibility(View.GONE);
        crossfadeSetupToProgress();
        setupArrow.setVisibility(View.GONE);
        setupTitle.setText(getString(R.string.scr_term_preparing_tools));
        setupSubtitle.setVisibility(View.GONE);
        startArrowAnimation();
        displayedInstallProgress = 0;
        targetInstallProgress = 0;
        installProgress.setProgressCompat(0, false);
        Set<String> selection = new HashSet<>(selectedPackages);
        List<TerminalPackageManager.PackageSpec> queue = packageManager.resolveSelection(packageManifest, selection);
        totalInstallWeight = 10;
        for (TerminalPackageManager.PackageSpec item : queue) {
            totalInstallWeight += Math.max(1, item.downloadBytes) + Math.max(1, item.unpackedBytes) + 4L * 1024L * 1024L;
        }
        completedProgressWeight = 0;
        currentStageWeight = 0;
        lastInstallStage = null;
        lastProgressPackageId = null;
        extractExecutor.execute(() -> {
            try {
                packageManager.install(packageManifest, selection, (stage, spec, current, total, message) ->
                        mainHandler.post(() -> updateInstallProgress(stage, spec, current, total, message)));
                mainHandler.post(this::revealTerminal);
            } catch (Exception e) {
                mainHandler.post(() -> showInstallError(e.getMessage()));
            }
        });
    }

    private void updateInstallProgress(TerminalPackageManager.Stage stage,
                                       TerminalPackageManager.PackageSpec spec,
                                       long current, long total, String message) {
        switch (stage) {
            case DOWNLOADING:
                setupTitle.setText(getString(R.string.scr_term_downloading));
                installStage.setText(spec.name);
                setupHeroIcon.setImageResource(R.drawable.ic_cloud_download);
                break;
            case VERIFYING:
                installStage.setText(getString(R.string.scr_term_verifying, spec.name));
                break;
            case EXTRACTING:
                setupTitle.setText(getString(R.string.scr_term_extracting));
                installStage.setText(spec.name);
                setupHeroIcon.setImageResource(R.drawable.ic_folder);
                break;
            case REMOVING:
                setupTitle.setText(getString(R.string.scr_term_removing_tools));
                installStage.setText(spec.name);
                setupHeroIcon.setImageResource(R.drawable.ic_trash);
                break;
            case APPLYING_PERMISSIONS:
                setupTitle.setText(getString(R.string.scr_term_configuring));
                installStage.setText(getString(R.string.scr_term_setting_perms));
                setupHeroIcon.setImageResource(R.drawable.ic_security);
                break;
            case VALIDATING:
                installStage.setText(getString(R.string.scr_term_checking_cmds));
                break;
            case COMPLETE:
                installStage.setText(getString(R.string.scr_done));
                break;
        }
        installDetail.setText(message);
        updateOverallProgress(stage, spec, current, total);
    }

    private void updateOverallProgress(TerminalPackageManager.Stage stage,
                                       TerminalPackageManager.PackageSpec spec,
                                       long current, long total) {
        String packageId = spec != null ? spec.id : "final";
        if (lastInstallStage != null && (lastInstallStage != stage || !packageId.equals(lastProgressPackageId))) {
            completedProgressWeight += currentStageWeight;
        }
        long stageWeight;
        switch (stage) {
            case DOWNLOADING: stageWeight = Math.max(1, spec.downloadBytes); break;
            case EXTRACTING: stageWeight = Math.max(1, spec.unpackedBytes); break;
            case REMOVING: stageWeight = Math.max(1, spec.unpackedBytes); break;
            case VERIFYING: stageWeight = 2L * 1024L * 1024L; break;
            case APPLYING_PERMISSIONS: stageWeight = 4L * 1024L * 1024L; break;
            case VALIDATING: stageWeight = 2L * 1024L * 1024L; break;
            case COMPLETE: stageWeight = 1; break;
            default: stageWeight = 1;
        }
        currentStageWeight = stageWeight;
        lastInstallStage = stage;
        lastProgressPackageId = packageId;
        double fraction;
        if (stage == TerminalPackageManager.Stage.COMPLETE) {
            fraction = 1d;
        } else if (total > 0) {
            fraction = Math.max(0d, Math.min(1d, current / (double) total));
        } else {
            fraction = 0.35d;
        }
        int next = (int) Math.min(99, ((completedProgressWeight + stageWeight * fraction) * 100d) / totalInstallWeight);
        animateInstallProgress(Math.max(targetInstallProgress, next));
    }

    private void animateInstallProgress(int next) {
        targetInstallProgress = Math.max(targetInstallProgress, Math.min(100, next));
        if (progressAnimator != null && progressAnimator.isRunning()) return;
        progressAnimator = android.animation.ValueAnimator.ofInt(displayedInstallProgress, targetInstallProgress);
        progressAnimator.setDuration(240);
        progressAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
        progressAnimator.addUpdateListener(animation -> {
            displayedInstallProgress = (int) animation.getAnimatedValue();
            installProgress.setProgressCompat(displayedInstallProgress, false);
        });
        progressAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if (displayedInstallProgress < targetInstallProgress) animateInstallProgress(targetInstallProgress);
            }
        });
        progressAnimator.start();
    }

    private void startArrowAnimation() {
        if (arrowAnimator != null) arrowAnimator.cancel();
        setupArrow.setVisibility(View.GONE);
        // The stage icon already communicates the active operation. Pulse the hero tile
        // instead of drawing a second floating chevron underneath it.
        arrowAnimator = ObjectAnimator.ofFloat(setupHeroIcon, View.SCALE_X, 1f, 1.045f, 1f);
        arrowAnimator.setDuration(1200);
        arrowAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        arrowAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        arrowAnimator.addUpdateListener(animation ->
                setupHeroIcon.setScaleY((float) animation.getAnimatedValue()));
        arrowAnimator.start();
    }

    private void showInstallError(String error) {
        installRunning = false;
        if (arrowAnimator != null) arrowAnimator.cancel();
        setupArrow.setVisibility(View.GONE);
        setupHeroIcon.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f).setDuration(120).withEndAction(() -> {
            setupHeroIcon.setImageResource(R.drawable.ic_error_x);
            setupHeroIcon.setColorFilter(null);
            setupHeroIcon.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(1.3f)).start();
        }).start();
        setupTitle.setText(getString(R.string.scr_term_install_failed));
        installStage.setText(getString(R.string.scr_term_module_unavailable));
        installDetail.setText(error == null ? getString(R.string.scr_term_unknown_error) : error);
        setupInstall.setText(getString(R.string.scr_retry));
        setupInstall.setEnabled(true);
        setupInstall.setOnClickListener(v -> installSelectedPackages());
        setupBasicFallback.setVisibility(View.VISIBLE);
    }

    private void revealTerminal() {
        installRunning = false;
        requestStorageAccess();
        if (arrowAnimator != null) arrowAnimator.cancel();
        animateInstallProgress(100);
        toolsManager.initializeEnvironment();
        toolsReady = true;
        editCommand.setEnabled(true);
        btnExecute.setEnabled(true);
        setupOverlay.animate().alpha(0f).setDuration(260).withEndAction(() -> {
            setupOverlay.setVisibility(View.GONE);
            setupOverlay.setAlpha(1f);
            startShell();
        }).start();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * Restrained crossfade from the package list into the install progress
     * group. Used when a setup/apply run starts so the hand-off reads as one
     * smooth transition instead of a hard swap. The package list collapses to
     * GONE at the end so it can't overlap the progress UI.
     */
    private void crossfadeSetupToProgress() {
        if (packageList.getVisibility() == View.VISIBLE) {
            packageList.animate().cancel();
            packageList.animate()
                    .alpha(0f)
                    .setDuration(160)
                    .withEndAction(() -> {
                        packageList.setVisibility(View.GONE);
                        packageList.setAlpha(1f);
                    })
                    .start();
        }
        if (installProgressGroup.getVisibility() != View.VISIBLE) {
            installProgressGroup.setAlpha(0f);
            installProgressGroup.setVisibility(View.VISIBLE);
        }
        installProgressGroup.animate().cancel();
        installProgressGroup.animate().alpha(1f).setDuration(200).start();
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 " + getString(R.string.scr_unit_mb);
        double mb = bytes / 1048576d;
        if (mb < 1024) return String.format(Locale.US, getString(R.string.scr_format_mb), mb, getString(R.string.scr_unit_mb));
        return String.format(Locale.US, getString(R.string.scr_format_gb), mb / 1024d, getString(R.string.scr_unit_gb));
    }

    private void startShell() {
        if (!hasStorageAccess()) {
            showStoragePermissionGate();
            return;
        }
        if (shell != null && shell.isAlive()) return;
        appendOutput("MultiAI terminal — type 'help' for bundled tools.\n");
        shell = new PersistentShell(this, toolsManager, new PersistentShell.Listener() {
            @Override
            public void onOutput(String text) {
                appendOutput(text);
            }

            @Override
            public void onCommandDone(int exitCode, String cwd, boolean waitingForInput) {
                if (!cwd.isEmpty()) currentCwd = cwd;
                setCommandExecuting(false);
                if (waitingForInput) {
                    // Command is reading stdin: subsequent Enters feed stdin.
                    inputMode = true;
                } else {
                    inputMode = false;
                    appendOutput(prompt());
                }
            }

            @Override
            public void onDied() {
                appendOutput("\n(shell exited)\n");
            }
        });
        shell.start();
        // Switch to /sdcard so the user lands somewhere writable. This is a
        // real cd into the persistent shell, so it sticks across commands.
        // The onCommandDone callback will print the first prompt once cd
        // completes, so we don't print one here.
        shell.sendCommand("cd /sdcard");
    }

    /** Build a prompt like "/sdcard/test/ $ " (trailing slash on dirs). */
    private String prompt() {
        String shortCwd = currentCwd;
        // Shorten /storage/emulated/0 -> /sdcard for readability.
        if (shortCwd.equals("/storage/emulated/0")) shortCwd = "/sdcard";
        else if (shortCwd.startsWith("/storage/emulated/0/"))
            shortCwd = "/sdcard/" + shortCwd.substring("/storage/emulated/0/".length());
        // Always show a trailing slash so paths read as directories.
        if (!shortCwd.endsWith("/")) shortCwd += "/";
        return shortCwd + "$ ";
    }

    /** Handle Enter on the input field: either a new command or stdin data. */
    private void onEnterPressed() {
        if (!hasStorageAccess()) {
            showStoragePermissionGate();
            return;
        }
        if (!toolsReady || shell == null || !shell.isAlive()) {
            ModernToast.show(this, getString(R.string.scr_term_shell_not_ready));
            return;
        }
        String line = editCommand.getText().toString();
        editCommand.setText("");

        if (inputMode) {
            // INPUT mode: feed the typed line to the running command's
            // stdin (FIFO). "/eof" is the special token that closes stdin
            // — useful to end heredocs, python REPL, cat, etc.
            if (line.trim().equals("/eof")) {
                appendOutput("(eof)\n");
                shell.sendEof();
                inputMode = false;
                appendOutput(prompt());
                return;
            }
            appendOutput(line + "\n");
            shell.sendInput(line);
            return;
        }

        // Normal command path. Echo the command line as typed.
        appendOutput(line + "\n");

        // A few conveniences handled in-Java so the prompt behaves nicely.
        String trimmed = line.trim();
        if (trimmed.equals("clear") || trimmed.equals("cls")) {
            terminalHistory.setLength(0);
            textTerminalOutput.setText("");
            appendOutput(prompt());
            return;
        }
        if (trimmed.equals("help")) {
            showHelp();
            appendOutput(prompt());
            return;
        }
        // exit/quit: tear down this activity (the shell dies with it).
        if (trimmed.equals("exit") || trimmed.equals("quit")) {
            finish();
            return;
        }
        // Empty line: just re-show the prompt.
        if (trimmed.isEmpty()) {
            appendOutput(prompt());
            return;
        }

        // Command-not-found pre-check: extract the first token and verify
        // it resolves to something on PATH or is a builtin/alias. If not,
        // show a clean Russian message instead of sh's raw "<stdin>[N]:
        // foo: inaccessible or not found" noise.
        String firstToken = firstToken(trimmed);
        if (firstToken != null && !isResolvable(firstToken)) {
            appendOutput(getString(R.string.scr_term_cmd_not_found, firstToken) + "\n");
            appendOutput(prompt());
            return;
        }

        // Mark the run control as "executing" until onCommandDone fires back.
        setCommandExecuting(true);
        shell.sendCommand(line);
    }

    /**
     * Apply (or clear) the "command executing" disabled visual on the run
     * button + input field. While executing, the run button is dimmed and the
     * field is made non-focusable so the user gets a clear signal that the
     * shell is busy. This is purely visual — it does not touch the shell engine.
     */
    private void setCommandExecuting(boolean executing) {
        if (commandExecuting == executing) return;
        commandExecuting = executing;
        if (executing) {
            btnExecute.setEnabled(false);
            btnExecute.setAlpha(0.4f);
            editCommand.setEnabled(false);
        } else {
            btnExecute.setEnabled(true);
            btnExecute.setAlpha(1f);
            editCommand.setEnabled(true);
        }
    }

    /** First whitespace-delimited token of the command (sans leading env
     *  assignments like FOO=bar CMD ...). */
    private static String firstToken(String cmd) {
        String s = cmd.trim();
        // Skip VAR=value prefixes
        while (s.matches("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+.*")) {
            s = s.replaceFirst("^[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+", "");
        }
        int sp = Character.isWhitespace(s.charAt(0)) ? 1 : 0;
        String rest = s.substring(sp);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (Character.isWhitespace(rest.charAt(i))) { end = i; break; }
        }
        String tok = rest.substring(0, end);
        return tok.isEmpty() ? null : tok;
    }

    /** True if 'name' is a shell builtin/keyword or an executable found on
     *  PATH / an absolute path we can stat. */
    private boolean isResolvable(String name) {
        if (name == null || name.isEmpty()) return false;
        // Shell builtins & keywords (toybox sh). Keep generous — false
        // positives only mean we let sh try the command, which is fine.
        String[] builtins = {
                "cd", "echo", "exit", "export", "set", "unset", "read", "true",
                "false", ":", ".", "source", "alias", "unalias", "bg", "fg",
                "jobs", "kill", "trap", "type", "hash", "help", "history",
                "let", "local", "printf", "pwd", "return", "shift", "test",
                "times", "umask", "wait", "eval", "exec",
                // common control-flow tokens
                "if", "then", "else", "elif", "fi", "for", "do", "done",
                "while", "until", "case", "esac", "in", "function", "select",
                "break", "continue"
        };
        for (String b : builtins) {
            if (b.equals(name)) return true;
        }
        // Absolute / relative path: check the file exists and is executable.
        if (name.contains("/")) {
            File f = new File(currentCwd).toPath().resolve(name).toFile();
            return f.canExecute();
        }
        // Otherwise search PATH from the tools environment.
        Map<String, String> env = toolsManager.getEnv();
        String path = env != null ? env.get("PATH") : null;
        if (path == null) return true; // can't tell — let sh handle it
        String[] dirs = path.split(":");
        for (String d : dirs) {
            if (d.isEmpty()) continue;
            File f = new File(d, name);
            if (f.isFile() && f.canExecute()) return true;
        }
        return false;
    }

    private void showHelp() {
        appendOutput("Bundled tools:\n");
        appendOutput("  python / pip        Python 3.14\n");
        appendOutput("  node / npm         Node.js 26\n");
        appendOutput("  git                Git 2.55\n");
        appendOutput("  curl <URL>         HTTP/HTTPS + HTTP/2 + HTTP/3\n");
        appendOutput("  java / javac       OpenJDK 21\n");
        appendOutput("  gradle <task>      Gradle 8.14 (embedded)\n");
        appendOutput("  aapt2 / zipalign   Android SDK build tools\n");
        appendOutput("  adb                Android Debug Bridge (aarch64)\n");
        appendOutput("  apksigner          APK signing\n");
        appendOutput("Shell: ls, cd, cat, mkdir, rm, cp, mv, grep, find, sed, awk, head, tail, echo, ...\n");
        appendOutput("Interactive prompts (read, adb pair, python input()) work: just keep typing.\n");
        appendOutput("Type /eof to close stdin, 'clear' to reset, 'exit' to close.\n");
    }

    private void appendOutput(String text) {
        terminalHistory.append(text);
        trimTerminalHistory();
        // Use a monospace typeface span for a terminal look.
        SpannableStringBuilder ssb = new SpannableStringBuilder(terminalHistory);
        ssb.setSpan(new TypefaceSpan("monospace"), 0, ssb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        textTerminalOutput.setText(ssb);
        scrollToBottom();
    }

    private void trimTerminalHistory() {
        int trimBefore = Math.max(0, terminalHistory.length() - MAX_HISTORY_CHARS);
        int lines = 0;
        for (int i = terminalHistory.length() - 1; i >= 0; i--) {
            if (terminalHistory.charAt(i) == '\n' && ++lines > MAX_HISTORY_LINES) {
                trimBefore = Math.max(trimBefore, i + 1);
                break;
            }
        }
        if (trimBefore > 0 && trimBefore < terminalHistory.length()
                && Character.isLowSurrogate(terminalHistory.charAt(trimBefore))) {
            trimBefore++;
        }
        if (trimBefore > 0) terminalHistory.delete(0, trimBefore);
    }

    private void scrollToBottom() {
        scrollTerminal.post(() -> scrollTerminal.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (waitingForStoragePermission && hasStorageAccess()) {
            hideStoragePermissionGate();
            prepareTools();
        } else if (!hasStorageAccess() && packageManager != null && packageManager.isConfigured()) {
            showStoragePermissionGate();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shell != null) shell.stop();
        if (packageManager != null) packageManager.cancelInstall();
        if (extractExecutor != null) extractExecutor.shutdownNow();
        stopService(new Intent(this, TerminalService.class));
    }
}
