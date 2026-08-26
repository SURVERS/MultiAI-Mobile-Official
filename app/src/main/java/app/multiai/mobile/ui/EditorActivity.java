package app.multiai.mobile.ui;

import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import android.graphics.pdf.PdfRenderer;
import android.widget.MediaController;
import android.webkit.MimeTypeMap;

import app.multiai.mobile.R;
import app.multiai.mobile.adapters.EditorTabsAdapter;
import app.multiai.mobile.models.EditorTab;
import app.multiai.mobile.ui.kit.BinaryActionCard;
import app.multiai.mobile.ui.kit.ModernDialog;
import app.multiai.mobile.ui.kit.ModernSheet;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.utils.DrawerNavHelper;

public class EditorActivity extends AppCompatActivity {

    private final ExecutorService fileIo = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "multiai-editor-io");
        thread.setDaemon(true);
        return thread;
    });

    private DrawerLayout drawerLayout;
    private ImageButton btnMenu;
    private ImageButton btnBack;
    private ImageButton btnSearch;
    private ImageButton btnSave;
    private TextView textEditorTitle;

    private RecyclerView recyclerTabs;
    private EditText editCode;

    private View searchPanel;
    private EditText editSearch;
    private EditText editReplace;
    private Button btnFindNext;
    private Button btnReplaceOne;
    private Button btnReplaceAll;

    private View emptyEditorState;
    private View previewContainer;
    private TextView textPreviewTitle;
    /** Hint shown only for binary files: "long-press for actions". */
    private TextView textBinaryHint;
    private ImageView imagePreview;
    private VideoView videoPreview;
    private View audioPreview;
    private ImageButton btnPlayPauseAudio;
    private ImageButton btnAudioPrev;
    private ImageButton btnAudioNext;
    private TextView textAudioStatus;
    private TextView audioTrackTitle;
    private SeekBar audioSeekBar;
    private TextView audioTimeCurrent;
    private TextView audioTimeRemaining;
    /** Root clip container; circular via ViewOutlineProvider. */
    private View audioAlbumRoot;
    /** Rotating gradient background (visible only when no embedded art). */
    private ImageView audioAlbumBackground;
    /** Album-art bitmap tile (centerCrop, circular). Empty when no art. */
    private ImageView audioAlbumArt;
    /** Small static music glyph centered over the gradient (no-art case). */
    private ImageView audioAlbumIcon;
    /** True once a real bitmap has been set on {@link #audioAlbumArt}. */
    private boolean audioHasArt = false;
    /** Infinite spin animator; started on play, cancelled on pause/stop. */
    private ObjectAnimator albumSpin;
    private final Handler audioUpdateHandler = new Handler(Looper.getMainLooper());
    private Runnable audioUpdateRunnable;
    private boolean audioUserSeeking = false;

    private final List<EditorTab> tabs = new ArrayList<>();
    private EditorTabsAdapter tabsAdapter;
    private int activeTabIndex = -1;

    private static final String STATE_SNAPSHOT_FILE = "editor_snapshot_file";

    private final Handler syntaxHandler = new Handler(Looper.getMainLooper());
    private Runnable syntaxRunnable;
    private long syntaxGeneration;
    private boolean ignoreEditorChanges = false;
    private File stateSnapshotFile;

    // Cached syntax-highlight colors (resolved from semantic tokens so they
    // adapt to light/dark). Resolved lazily in applySyntaxHighlight().
    private int syntaxCommentColor = 0;
    private int syntaxStringColor = 0;
    private int syntaxKeywordColor = 0;
    private int syntaxLiteralColor = 0;
    private int syntaxNumberColor = 0;

    private String lastSearchQuery = "";
    private int nextSearchStart = 0;

    private MediaPlayer audioPlayer;
    private boolean audioPrepared = false;
    private boolean audioPlaying = false;

    /**
     * Tree URI of the folder the currently-open file lives in (passed by
     * FilesActivity as {@code project_folder_uri}). Used to enumerate sibling
     * audio files for the player's prev/next controls. May be null when the
     * file was opened from outside the project tree (share-intent, etc.), in
     * which case prev/next are disabled.
     */
    private Uri currentFolderTreeUri;
    /** Cached sibling audio DocumentFiles (sorted by name) + current index. */
    private List<DocumentFile> audioSiblings = java.util.Collections.emptyList();
    /** Cached sibling audio URIs (sorted by name) — populated via batched SAF query. */
    private List<Uri> audioSiblingUris = java.util.Collections.emptyList();
    private List<String> audioSiblingNames = java.util.Collections.emptyList();
    private int audioSiblingIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_editor);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        initViews();
        setupDrawer();
        setupTabs();
        setupListeners();
        if (!restoreEditorState(savedInstanceState)) {
            handleIncomingIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (app.multiai.mobile.utils.AppLockManager.get(this)
                .routeIfLocked(this, intent)) return;
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        btnMenu = findViewById(R.id.btnMenu);
        btnBack = findViewById(R.id.btnBack);
        btnSearch = findViewById(R.id.btnSearch);
        btnSave = findViewById(R.id.btnSave);
        textEditorTitle = findViewById(R.id.textEditorTitle);

        recyclerTabs = findViewById(R.id.recyclerTabs);
        editCode = findViewById(R.id.editCode);
        enableEditorFlingScroll();

        searchPanel = findViewById(R.id.searchPanel);
        editSearch = findViewById(R.id.editSearch);
        editReplace = findViewById(R.id.editReplace);
        btnFindNext = findViewById(R.id.btnFindNext);
        btnReplaceOne = findViewById(R.id.btnReplaceOne);
        btnReplaceAll = findViewById(R.id.btnReplaceAll);

        emptyEditorState = findViewById(R.id.emptyEditorState);
        previewContainer = findViewById(R.id.previewContainer);
        textPreviewTitle = findViewById(R.id.textPreviewTitle);
        textBinaryHint = findViewById(R.id.textBinaryHint);
        imagePreview = findViewById(R.id.imagePreview);
        videoPreview = findViewById(R.id.videoPreview);
        audioPreview = findViewById(R.id.audioPreview);
        btnPlayPauseAudio = findViewById(R.id.btnPlayPauseAudio);
        btnAudioPrev = findViewById(R.id.btnAudioPrev);
        btnAudioNext = findViewById(R.id.btnAudioNext);
        textAudioStatus = findViewById(R.id.textAudioStatus);
        audioTrackTitle = findViewById(R.id.audioTrackTitle);
        audioSeekBar = findViewById(R.id.audioSeekBar);
        audioTimeCurrent = findViewById(R.id.audioTimeCurrent);
        audioTimeRemaining = findViewById(R.id.audioTimeRemaining);
        audioAlbumRoot = findViewById(R.id.audioAlbumRoot);
        audioAlbumBackground = findViewById(R.id.audioAlbumBackground);
        audioAlbumArt = findViewById(R.id.audioAlbumArt);
        // Pagination bar (visible only when a file has >1 page).
        paginationBar = findViewById(R.id.paginationBar);
        textPageIndicator = findViewById(R.id.textPageIndicator);
        btnPagePrev = findViewById(R.id.btnPagePrev);
        btnPageNext = findViewById(R.id.btnPageNext);
        audioAlbumIcon = findViewById(R.id.audioAlbumIcon);
        applyCircularClip(audioAlbumRoot);
        applyCircularClip(audioAlbumArt);
        // Tint the SeekBar progress + thumb with the accent so it reads on both
        // light and dark surfaces.
        int seekTint = app.multiai.mobile.utils.AccentColor.resolve(this);
        android.content.res.ColorStateList seekTintList =
                android.content.res.ColorStateList.valueOf(seekTint);
        audioSeekBar.setProgressTintList(seekTintList);
        audioSeekBar.setThumbTintList(seekTintList);
        audioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && audioPlayer != null && audioPrepared) {
                    try {
                        audioPlayer.seekTo(progress);
                    } catch (Exception ignored) {
                    }
                }
                updateTimeDisplay();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                audioUserSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                audioUserSeeking = false;
                // Resume periodic updates so the time/seekbar keep advancing after a drag.
                if (audioPlayer != null && audioPlaying) {
                    startAudioUpdates();
                }
                updateTimeDisplay();
            }
        });

        recyclerTabs.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        recyclerTabs.setItemAnimator(null);
        searchPanel.setVisibility(View.GONE);

        editCode.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignoreEditorChanges) {
                    return;
                }
                EditorTab tab = getActiveTab();
                if (tab == null || !tab.isTextFile()) {
                    return;
                }

                String current = s != null ? s.toString() : "";
                if (currentPageIndex >= 0 && currentPageIndex < pageContents.size()) {
                    pageContents.set(currentPageIndex, current);
                }
                String fullContent = joinPageContents();
                tab.setContent(fullContent);
                tab.setDirty(!fullContent.equals(tab.getOriginalContent()));
                updateTitle(tab);
                refreshTabsUi();
                scheduleSyntaxHighlight();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void setupTabs() {
        tabsAdapter = new EditorTabsAdapter();
        tabsAdapter.setOnTabActionListener(new EditorTabsAdapter.OnTabActionListener() {
            @Override
            public void onTabSelected(int position) {
                switchToTab(position);
            }

            @Override
            public void onTabClosed(int position) {
                requestCloseTab(position);
            }
        });
        recyclerTabs.setAdapter(tabsAdapter);
    }

    private void setupListeners() {
        btnMenu.setOnClickListener(v -> {
            View drawerView = findViewById(R.id.drawerFrame);
            if (drawerLayout.isDrawerOpen(drawerView)) {
                drawerLayout.closeDrawer(drawerView);
            } else {
                drawerLayout.openDrawer(drawerView);
            }
        });

        btnBack.setOnClickListener(v -> onBackPressed());

        btnSearch.setOnClickListener(v -> {
            EditorTab active = getActiveTab();
            // Search/replace requires an open text file. Without one the panel has
            // nothing to operate on and the empty-state editor has no content.
            if (active == null) {
                ModernToast.show(this, getString(R.string.scr_editor_search_no_file));
                return;
            }
            if (!active.isTextFile()) {
                ModernToast.show(this, getString(R.string.scr_editor_search_no_file));
                return;
            }
            boolean open = searchPanel.getVisibility() != View.VISIBLE;
            searchPanel.setVisibility(open ? View.VISIBLE : View.GONE);
            if (open) {
                editSearch.requestFocus();
            }
        });

        btnSave.setOnClickListener(v -> saveActiveTab());

        btnFindNext.setOnClickListener(v -> findNext());
        btnReplaceOne.setOnClickListener(v -> replaceOne());
        btnReplaceAll.setOnClickListener(v -> replaceAll());

        btnPlayPauseAudio.setOnClickListener(v -> toggleAudioPlayback());
        btnAudioPrev.setOnClickListener(v -> switchAudioRelative(-1));
        btnAudioNext.setOnClickListener(v -> switchAudioRelative(+1));

        // Pagination navigation
        if (btnPagePrev != null) btnPagePrev.setOnClickListener(v -> goToPage(currentPageIndex - 1));
        if (btnPageNext != null) btnPageNext.setOnClickListener(v -> goToPage(currentPageIndex + 1));
    }

    private void setupDrawer() {
        View drawerView = findViewById(R.id.drawerFrame);

        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                btnMenu.setRotation(slideOffset * 90f);
            }
        });

        DrawerNavHelper.setup(this, DrawerNavHelper.NavTarget.EDITOR,
                target -> {
                    switch (target) {
                        case CHAT:
                            startActivity(new Intent(this, ChatActivity.class));
                            finish();
                            return true;
                        case TERMINAL:
                            startActivity(new Intent(this, TerminalActivity.class));
                            finish();
                            return true;
                        case FILES:
                            startActivity(new Intent(this, FilesActivity.class));
                            finish();
                            return true;
                        case EDITOR:
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

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) {
            renderState();
            return;
        }

        String fileUriRaw = intent.getStringExtra("file_uri");
        if (fileUriRaw == null || fileUriRaw.trim().isEmpty()) {
            renderState();
            return;
        }

        Uri fileUri;
        try {
            fileUri = Uri.parse(fileUriRaw);
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_editor_open_uri_error));
            renderState();
            return;
        }

        String name = intent.getStringExtra("file_name");
        if (name == null || name.trim().isEmpty()) {
            DocumentFile oneFile = DocumentFile.fromSingleUri(this, fileUri);
            if (oneFile != null && oneFile.getName() != null && !oneFile.getName().trim().isEmpty()) {
                name = oneFile.getName();
            } else {
                name = "file";
            }
        }

        String path = intent.getStringExtra("file_path");
        if (path == null) {
            path = name;
        }

        String folderRaw = intent.getStringExtra("project_folder_uri");
        if (folderRaw != null && !folderRaw.trim().isEmpty()) {
            try {
                currentFolderTreeUri = Uri.parse(folderRaw);
            } catch (Exception ignored) {
                currentFolderTreeUri = null;
            }
        }

        openOrSwitchTab(fileUri, name, path);
    }

    private void openOrSwitchTab(Uri uri, String name, String path) {
        String target = uri.toString();
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).getUri().toString().equals(target)) {
                switchToTab(i);
                return;
            }
        }

        String extension = getExtension(name);
        boolean textFile = isTextFile(extension);

        EditorTab tab = new EditorTab(uri, name, path, extension, textFile);
        tabs.add(tab);
        switchToTab(tabs.size() - 1);
        if (textFile) loadTextContent(tab);
    }

    /** Loads the full file, then splits it into pages for display. */
    private static final int LINES_PER_PAGE = 500; // ~500 lines per page for smooth scroll

    // Pagination state for large files.
    private View paginationBar;
    private TextView textPageIndicator;
    private ImageButton btnPagePrev;
    private ImageButton btnPageNext;
    private int currentPageIndex = 0;
    private int totalPages = 1;
    private java.util.List<String> pageContents = new java.util.ArrayList<>();

    private void loadTextContent(EditorTab tab) {
        fileIo.execute(() -> {
            StringBuilder builder = new StringBuilder();
            Exception failure = null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getContentResolver().openInputStream(tab.getUri())))) {
                char[] buffer = new char[16 * 1024];
                int read;
                while ((read = reader.read(buffer)) != -1) builder.append(buffer, 0, read);
            } catch (Exception e) {
                failure = e;
            }
            String content = builder.toString();
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalFailure == null) {
                    tab.setContent(content);
                    tab.setOriginalContent(content);
                    tab.setDirty(false);
                    tab.setReadFailed(false);
                    if (getActiveTab() == tab) switchToTab(activeTabIndex);
                } else {
                    tab.setContent("");
                    tab.setOriginalContent("");
                    tab.setReadFailed(true);
                    ModernToast.show(this, getString(R.string.scr_editor_read_error,
                            finalFailure.getMessage()));
                }
            });
        });
    }

    private void switchToTab(int index) {
        if (index != activeTabIndex) commitActiveEditorState();
        if (index < 0 || index >= tabs.size()) {
            activeTabIndex = -1;
            renderState();
            return;
        }

        activeTabIndex = index;
        EditorTab tab = tabs.get(index);
        updateTitle(tab);

        releaseAudio();
        stopVideoPreview();
        clearPreviewViews();

        if (tab.isTextFile()) {
            // Text editor shown: hide the other two panes to prevent overlap.
            crossfadeHide(previewContainer);
            crossfadeShow(editCode);
            emptyEditorState.setVisibility(View.GONE);

            // Split content into pages for display.
            splitIntoPages(tab.getContent());
            currentPageIndex = 0;
            applyCurrentPage();

            scheduleSyntaxHighlight();
        } else {
            // Binary preview shown: hide the editor + empty panes.
            editCode.setVisibility(View.GONE);
            emptyEditorState.setVisibility(View.GONE);
            if (paginationBar != null) paginationBar.setVisibility(View.GONE);
            crossfadeShow(previewContainer);
            showPreview(tab);
        }

        refreshTabsUi();
    }

    /**
     * Split the full file content into pages of ~{@link #LINES_PER_PAGE} lines.
     * Small files produce a single page and the pagination bar stays hidden.
     */
    private void splitIntoPages(String fullContent) {
        pageContents.clear();
        if (fullContent == null || fullContent.isEmpty()) {
            pageContents.add("");
            totalPages = 1;
            return;
        }
        String[] lines = fullContent.split("\n", -1);
        if (lines.length <= LINES_PER_PAGE) {
            pageContents.add(fullContent);
            totalPages = 1;
            return;
        }
        StringBuilder page = new StringBuilder();
        int lineCount = 0;
        for (String line : lines) {
            if (lineCount > 0) page.append('\n');
            page.append(line);
            lineCount++;
            if (lineCount >= LINES_PER_PAGE) {
                pageContents.add(page.toString());
                page = new StringBuilder();
                lineCount = 0;
            }
        }
        if (lineCount > 0) {
            pageContents.add(page.toString());
        }
        totalPages = pageContents.size();
    }

    /** Render the current page into editCode + update the pagination bar. */
    private void applyCurrentPage() {
        if (pageContents.isEmpty()) return;
        String pageText = pageContents.get(currentPageIndex);
        ignoreEditorChanges = true;
        editCode.setText(pageText);
        editCode.setSelection(0);
        ignoreEditorChanges = false;
        // Highlight the freshly-loaded page after a short delay (not via TextWatcher
        // which is suppressed by ignoreEditorChanges).
        scheduleSyntaxHighlight();

        if (paginationBar != null) {
            if (totalPages > 1) {
                paginationBar.setVisibility(View.VISIBLE);
                textPageIndicator.setText((currentPageIndex + 1) + " / " + totalPages);
                btnPagePrev.setEnabled(currentPageIndex > 0);
                btnPageNext.setEnabled(currentPageIndex < totalPages - 1);
                btnPagePrev.setAlpha(currentPageIndex > 0 ? 1f : 0.3f);
                btnPageNext.setAlpha(currentPageIndex < totalPages - 1 ? 1f : 0.3f);
            } else {
                paginationBar.setVisibility(View.GONE);
            }
        }
    }

    /** Navigate to a page, committing the current page's edits first. */
    private void goToPage(int newIndex) {
        if (newIndex < 0 || newIndex >= totalPages || newIndex == currentPageIndex) return;
        // Save current page text back.
        commitActiveEditorState();
        currentPageIndex = newIndex;
        applyCurrentPage();
        scheduleSyntaxHighlight();
    }

    private String joinPageContents() {
        StringBuilder full = new StringBuilder();
        for (int i = 0; i < pageContents.size(); i++) {
            if (i > 0) full.append('\n');
            full.append(pageContents.get(i));
        }
        return full.toString();
    }

    private void commitActiveEditorState() {
        EditorTab tab = getActiveTab();
        if (tab == null || !tab.isTextFile() || pageContents.isEmpty()) return;
        if (currentPageIndex >= 0 && currentPageIndex < pageContents.size()) {
            pageContents.set(currentPageIndex, editCode.getText().toString());
        }
        String fullContent = joinPageContents();
        tab.setContent(fullContent);
        tab.setDirty(!fullContent.equals(tab.getOriginalContent()));
    }

    private void renderState() {
        if (tabs.isEmpty()) {
            activeTabIndex = -1;
            textEditorTitle.setText(getString(R.string.scr_editor_title));
            // Empty state: ensure editor + preview are fully hidden (no overlap).
            crossfadeHide(previewContainer);
            crossfadeHide(editCode);
            // Hide search/replace panel — nothing to search without an open file.
            searchPanel.setVisibility(View.GONE);
            if (paginationBar != null) paginationBar.setVisibility(View.GONE);
            crossfadeShow(emptyEditorState);
            refreshTabsUi();
            return;
        }

        if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
            activeTabIndex = tabs.size() - 1;
        }
        switchToTab(activeTabIndex);
    }

    private void refreshTabsUi() {
        tabsAdapter.setTabs(tabs, activeTabIndex);
    }

    private void updateTitle(EditorTab tab) {
        if (tab == null) {
            textEditorTitle.setText(getString(R.string.scr_editor_title));
            return;
        }

        String title = tab.getName();
        if (tab.isDirty()) {
            title += " *";
        }
        textEditorTitle.setText(title);
    }

    private EditorTab getActiveTab() {
        if (activeTabIndex < 0 || activeTabIndex >= tabs.size()) {
            return null;
        }
        return tabs.get(activeTabIndex);
    }

    private void requestCloseTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }

        if (index == activeTabIndex) commitActiveEditorState();
        EditorTab tab = tabs.get(index);
        if (!tab.isTextFile() || !tab.isDirty()) {
            closeTab(index);
            return;
        }

        java.util.List<ModernSheet.MenuItem> items = Arrays.asList(
            new ModernSheet.MenuItem(getString(R.string.scr_save), 0, false, null),
            new ModernSheet.MenuItem(getString(R.string.scr_dont_save), 0, false, null),
            new ModernSheet.MenuItem(getString(R.string.scr_cancel), 0, false, null)
        );
        ModernSheet.menu(this, getString(R.string.scr_editor_save_changes), items, (idx, item) -> {
            switch (idx) {
                case 0:
                    saveTabByIndex(index, saved -> { if (saved) closeTab(index); });
                    break;
                case 1:
                    closeTab(index);
                    break;
                case 2:
                    break;
            }
        });
    }

    private void closeTab(int index) {
        if (index < 0 || index >= tabs.size()) {
            return;
        }

        if (index == activeTabIndex) commitActiveEditorState();
        tabs.remove(index);

        if (tabs.isEmpty()) {
            // No tabs left — go back to the Files screen instead of showing an empty editor.
            activeTabIndex = -1;
            refreshTabsUi();
            startActivity(new Intent(this, FilesActivity.class));
            finish();
            return;
        }

        if (activeTabIndex >= index) {
            activeTabIndex = Math.max(0, activeTabIndex - 1);
        }
        switchToTab(activeTabIndex);
    }

    private void saveTabByIndex(int index, Consumer<Boolean> callback) {
        if (index < 0 || index >= tabs.size()) {
            callback.accept(false);
            return;
        }
        EditorTab tab = tabs.get(index);
        if (tab.isReadFailed()) {
            callback.accept(false);
            return;
        }
        String content;
        if (index == activeTabIndex && tab.isTextFile()) {
            // Активный таб: editCode содержит только текущую страницу —
            // собираем все страницы обратно в полный документ.
            commitActiveEditorState();
            content = tab.getContent();
        } else {
            content = tab.getContent();
        }
        saveTab(tab, content, callback);
    }

    private void saveActiveTab() {
        EditorTab tab = getActiveTab();
        if (tab == null) {
            ModernToast.show(this, getString(R.string.scr_editor_no_active_file));
            return;
        }

        if (!tab.isTextFile()) {
            // Binary files aren't editable. The preview itself provides playback/open UX.
            ModernToast.show(this, getString(R.string.scr_editor_not_editable));
            return;
        }

        if (tab.isReadFailed()) {
            ModernToast.show(this, getString(R.string.scr_editor_read_error,
                    getString(R.string.scr_editor_file_not_loaded)), true);
            return;
        }

        // Commit current page back, then reassemble all pages into full content.
        commitActiveEditorState();
        String content = tab.getContent();
        saveTab(tab, content, saved -> {
            if (!saved) return;
            tab.setOriginalContent(content);
            tab.setDirty(false);
            ModernToast.show(this, getString(R.string.scr_saved));
            refreshTabsUi();
            updateTitle(tab);
        });
    }

    private void saveTab(EditorTab tab, String content, Consumer<Boolean> callback) {
        final String value = content != null ? content : "";
        fileIo.execute(() -> {
            File staged = new File(getCacheDir(), "editor-stage-" + UUID.randomUUID());
            File backup = new File(getCacheDir(), "editor-backup-" + UUID.randomUUID());
            Exception failure = null;
            try {
                try (FileOutputStream out = new FileOutputStream(staged);
                     OutputStreamWriter writer = new OutputStreamWriter(out,
                             java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(value);
                    writer.flush();
                    out.getFD().sync();
                }
                try (InputStream in = getContentResolver().openInputStream(tab.getUri());
                     FileOutputStream out = new FileOutputStream(backup)) {
                    if (in != null) copyStream(in, out);
                    out.getFD().sync();
                }
                try (InputStream in = new FileInputStream(staged);
                     OutputStream out = getContentResolver().openOutputStream(tab.getUri(), "rwt")) {
                    if (out == null) throw new java.io.IOException("Cannot open file for writing");
                    copyStream(in, out);
                    out.flush();
                }
            } catch (Exception e) {
                failure = e;
                try (InputStream in = new FileInputStream(backup);
                     OutputStream out = getContentResolver().openOutputStream(tab.getUri(), "rwt")) {
                    if (out != null) { copyStream(in, out); out.flush(); }
                } catch (Exception ignored) {}
            } finally {
                staged.delete();
                backup.delete();
            }
            Exception finalFailure = failure;
            runOnUiThread(() -> {
                if (finalFailure == null) {
                    tab.setContent(value);
                    tab.setOriginalContent(value);
                    tab.setDirty(false);
                    callback.accept(true);
                } else {
                    ModernToast.show(this, getString(R.string.scr_editor_save_error,
                            finalFailure.getMessage()), true);
                    callback.accept(false);
                }
            });
        });
    }

    private static void copyStream(InputStream in, OutputStream out) throws java.io.IOException {
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
    }

    /**
     * Show a pane with a short, restrained fade-in. If the view is already
     * visible the call is a no-op so re-renders don't flicker.
     */
    private void crossfadeShow(View view) {
        if (view == null || view.getVisibility() == View.VISIBLE) {
            return;
        }
        view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate().cancel();
        view.animate().alpha(1f).setDuration(140).start();
    }

    /**
     * Hide a pane with a short fade-out, collapsing it to GONE afterwards so it
     * can never overlap the visible content.
     */
    private void crossfadeHide(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            if (view != null) {
                view.setVisibility(View.GONE);
            }
            return;
        }
        view.animate().cancel();
        view.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction(() -> {
                    view.setVisibility(View.GONE);
                    view.setAlpha(1f);
                })
                .start();
    }

    /**
     * Give the EditText real momentum scrolling (fling) like MT Manager.
     * Uses a Scroller with viscous fluid interpolator + fling() for natural
     * deceleration. The touch listener tracks velocity and launches the fling
     * on ACTION_UP, then runs a frame callback to apply scroll positions.
     */
    private android.widget.Scroller editorScroller;
    private Runnable editorScrollerCallback;
    private boolean editorIsFliping;

    private void enableEditorFlingScroll() {
        editorScroller = new android.widget.Scroller(this,
                new android.view.animation.DecelerateInterpolator(1.2f));
        editCode.setVerticalScrollBarEnabled(true);
        editCode.setOverScrollMode(View.OVER_SCROLL_ALWAYS);

        final android.view.VelocityTracker[] trackerHolder = {null};
        final float density = getResources().getDisplayMetrics().density;

        editCode.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Stop any running fling when the user touches the screen.
                    if (editorScroller != null && !editorScroller.isFinished()) {
                        editorScroller.forceFinished(true);
                    }
                    editorIsFliping = false;
                    if (trackerHolder[0] == null) {
                        trackerHolder[0] = android.view.VelocityTracker.obtain();
                    }
                    trackerHolder[0].clear();
                    trackerHolder[0].addMovement(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (trackerHolder[0] != null) trackerHolder[0].addMovement(event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (trackerHolder[0] != null) {
                        trackerHolder[0].addMovement(event);
                        trackerHolder[0].computeCurrentVelocity(1000,
                                android.view.ViewConfiguration.getMaximumFlingVelocity());
                        float vy = trackerHolder[0].getYVelocity();

                        // Compute real scroll bounds from the layout.
                        int currentY = editCode.getScrollY();
                        int layoutHeight = 0;
                        if (editCode.getLayout() != null) {
                            layoutHeight = editCode.getLayout().getHeight();
                        }
                        int viewHeight = editCode.getHeight()
                                - editCode.getPaddingTop() - editCode.getPaddingBottom();
                        int maxScroll = Math.max(0, layoutHeight - viewHeight);

                        // Launch a Scroller fling — it computes the entire trajectory
                        // with viscous fluid deceleration (same physics as ListView).
                        editorScroller.fling(
                                0, currentY,           // startX, startY (absolute)
                                0, (int) -vy,          // velocityX, velocityY
                                0, 0,                  // minX, maxX (no horizontal)
                                0, maxScroll);         // minY, maxY (clamped to content)

                        editorIsFliping = true;
                        if (editorScrollerCallback == null) {
                            editorScrollerCallback = () -> {
                                if (editorScroller == null || !editorIsFliping) return;
                                if (editorScroller.computeScrollOffset()) {
                                    int y = editorScroller.getCurrY();
                                    editCode.scrollTo(0, y);
                                    editCode.postOnAnimation(editorScrollerCallback);
                                } else {
                                    editorIsFliping = false;
                                }
                            };
                        }
                        editCode.postOnAnimation(editorScrollerCallback);
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && trackerHolder[0] != null) {
                        trackerHolder[0].recycle();
                        trackerHolder[0] = null;
                    }
                    break;
            }
            return false;
        });
    }

    private void scheduleSyntaxHighlight() {
        // Skip highlighting entirely while we're programmatically setting text
        // (pagination, tab switch, etc.) — it would run on every setText and lag.
        if (ignoreEditorChanges) return;
        syntaxHandler.removeCallbacksAndMessages(null);
        long generation = ++syntaxGeneration;
        syntaxRunnable = () -> applySyntaxHighlight(generation);
        // Long debounce so highlighting never fires during rapid edits or loading.
        syntaxHandler.postDelayed(syntaxRunnable, 600);
    }

    /**
     * Off-main-thread syntax highlight. Building regex spans on a large Editable
     * causes ANR/crash on the UI thread. We compute spans on a background thread,
     * then apply them back on UI. No file-size limit — patterns are linear and
     * capped on match count to stay fast.
     */
    private static final int MAX_SPANS_PER_PATTERN = 2000; // safety cap per pattern

    private void applySyntaxHighlight(long generation) {
        final EditorTab tab = getActiveTab();
        if (tab == null || !tab.isTextFile() || generation != syntaxGeneration) return;
        final int pageIndex = currentPageIndex;
        final Editable editable = editCode.getText();
        if (editable == null || editable.length() == 0) return;

        resolveSyntaxColors();

        // Snapshot the text and compute spans off the main thread.
        final String text = editable.toString();
        final int commentColor = syntaxCommentColor;
        final int stringColor = syntaxStringColor;
        final int keywordColor = syntaxKeywordColor;
        final int literalColor = syntaxLiteralColor;
        final int numberColor = syntaxNumberColor;
        final String[] keywords = keywordsForExt(tab.getExtension());
        final int selStart = editCode.getSelectionStart();
        final int selEnd = editCode.getSelectionEnd();

        new Thread(() -> {
            try {
                // Build the entire SpannableStringBuilder on the background thread:
                // all span objects are created and attached here, so the UI thread
                // only does a single setText() — no incremental setSpan/removeSpan
                // which was causing the lag/crash on large files.
                SpannableStringBuilder ssb = new SpannableStringBuilder(text);

                applySpansToBuilder(ssb, text, Pattern.compile("(?m)//.*$"), commentColor, false);
                applySpansToBuilder(ssb, text, Pattern.compile("(?m)#.*$"), commentColor, false);
                applySpansToBuilder(ssb, text, Pattern.compile("(?s)/\\*.*?\\*/"), commentColor, false);
                applySpansToBuilder(ssb, text, Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\""), stringColor, false);
                applySpansToBuilder(ssb, text, Pattern.compile("'(?:\\\\.|[^'\\\\])*'"), stringColor, false);
                applySpansToBuilder(ssb, text, Pattern.compile("(?<![A-Za-z0-9_])(true|false|null)(?![A-Za-z0-9_])"), literalColor, true);
                if (keywords.length > 0) {
                    StringBuilder regex = new StringBuilder("(?<![A-Za-z0-9_])(");
                    for (int i = 0; i < keywords.length; i++) {
                        if (i > 0) regex.append("|");
                        regex.append(Pattern.quote(keywords[i]));
                    }
                    regex.append(")(?![A-Za-z0-9_])");
                    applySpansToBuilder(ssb, text, Pattern.compile(regex.toString()), keywordColor, true);
                }
                applySpansToBuilder(ssb, text, Pattern.compile("(?<![A-Za-z0-9_])[0-9]+(\\.[0-9]+)?(?![A-Za-z0-9_])"), numberColor, false);

                final SpannableStringBuilder result = ssb;
                final int finalSelStart = Math.min(selStart, result.length());
                final int finalSelEnd = Math.min(selEnd, result.length());

                runOnUiThread(() -> {
                    if (generation != syntaxGeneration
                            || getActiveTab() != tab
                            || currentPageIndex != pageIndex
                            || !text.equals(editCode.getText().toString())) {
                        return;
                    }
                    ignoreEditorChanges = true;
                    try {
                        editCode.setText(result);
                        if (finalSelStart >= 0 && finalSelEnd >= 0) {
                            editCode.setSelection(finalSelStart, finalSelEnd);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        ignoreEditorChanges = false;
                    }
                });
            } catch (Exception ignored) {
                // Pattern engine overflow / OOM — silently skip highlighting.
            }
        }).start();
    }

    /** Attach spans directly to a SpannableStringBuilder on a background thread. */
    private static void applySpansToBuilder(SpannableStringBuilder ssb, String text,
                                            Pattern pattern, int color, boolean bold) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            if (count >= MAX_SPANS_PER_PATTERN) break;
            int start = matcher.start();
            int end = matcher.end();
            if (start >= 0 && end > start && end <= ssb.length()) {
                ssb.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (bold) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                count++;
            }
        }
    }

    /**
     * Resolve (once, lazily) the syntax-highlight colors from the semantic color
     * tokens added for light/dark support. Falls back to the previous hard-coded
     * ARGB constants when a token isn't defined, so this never crashes if the
     * palette is incomplete.
     */
    private void resolveSyntaxColors() {
        if (syntaxCommentColor != 0) {
            return;
        }
        syntaxCommentColor = resolveColor(R.color.syntax_comment, 0xFF808080);
        syntaxStringColor = resolveColor(R.color.syntax_string, 0xFFEDEDED);
        syntaxKeywordColor = resolveColor(R.color.syntax_keyword, 0xFF38BDF8);
        syntaxLiteralColor = resolveColor(R.color.syntax_literal, 0xFFFB7185);
        syntaxNumberColor = resolveColor(R.color.syntax_number, 0xFFCCCCCC);
    }

    private int resolveColor(int resId, int fallback) {
        try {
            return ContextCompat.getColor(this, resId);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void clearHighlightSpans(Editable editable) {
        ForegroundColorSpan[] colors = editable.getSpans(0, editable.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : colors) {
            editable.removeSpan(span);
        }

        StyleSpan[] styles = editable.getSpans(0, editable.length(), StyleSpan.class);
        for (StyleSpan span : styles) {
            editable.removeSpan(span);
        }
    }

    private void applyPattern(Editable editable, String text, Pattern pattern, int color, boolean bold) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (start < 0 || end <= start || end > editable.length()) {
                continue;
            }
            editable.setSpan(new ForegroundColorSpan(color), start, end, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (bold) {
                editable.setSpan(new StyleSpan(Typeface.BOLD), start, end, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private String[] keywordsForExt(String ext) {
        if (ext == null) {
            return new String[0];
        }
        switch (ext) {
            case "java":
            case "kt":
            case "kts":
                return new String[] {
                    "package", "import", "class", "interface", "public", "private", "protected", "static",
                    "void", "int", "long", "double", "float", "boolean", "if", "else", "for", "while",
                    "switch", "case", "return", "new", "try", "catch", "finally", "throw", "extends", "implements"
                };
            case "js":
            case "jsx":
            case "ts":
            case "tsx":
                return new String[] {
                    "const", "let", "var", "function", "class", "import", "export", "return", "if", "else",
                    "for", "while", "switch", "case", "break", "continue", "new", "try", "catch", "await", "async"
                };
            case "py":
                return new String[] {
                    "def", "class", "import", "from", "if", "elif", "else", "for", "while", "return", "yield",
                    "try", "except", "finally", "lambda", "with", "as", "pass", "break", "continue", "async", "await"
                };
            case "xml":
                return new String[] {
                    "android", "layout_width", "layout_height", "id", "text", "src", "padding", "margin"
                };
            case "json":
                return new String[] {
                    "true", "false", "null"
                };
            case "md":
                return new String[] {
                    "#", "##", "###", "-", "```"
                };
            default:
                return new String[0];
        }
    }

    private void findNext() {
        EditorTab tab = getActiveTab();
        if (tab == null || !tab.isTextFile()) {
            return;
        }

        String query = editSearch.getText().toString();
        if (query.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_editor_search_empty));
            return;
        }

        String source = editCode.getText().toString();
        if (!query.equals(lastSearchQuery)) {
            lastSearchQuery = query;
            nextSearchStart = 0;
        }

        int from = Math.max(nextSearchStart, editCode.getSelectionEnd());
        int found = source.indexOf(query, from);
        if (found < 0) {
            found = source.indexOf(query);
        }

        if (found < 0) {
            ModernToast.show(this, getString(R.string.scr_editor_no_matches));
            return;
        }

        editCode.requestFocus();
        editCode.setSelection(found, found + query.length());
        nextSearchStart = found + query.length();
    }

    private void replaceOne() {
        EditorTab tab = getActiveTab();
        if (tab == null || !tab.isTextFile()) {
            return;
        }

        String query = editSearch.getText().toString();
        if (query.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_editor_search_empty));
            return;
        }

        String replacement = editReplace.getText().toString();
        int start = editCode.getSelectionStart();
        int end = editCode.getSelectionEnd();

        if (start >= 0 && end > start) {
            String selected = editCode.getText().subSequence(start, end).toString();
            if (query.equals(selected)) {
                editCode.getText().replace(start, end, replacement);
                editCode.setSelection(start + replacement.length());
                nextSearchStart = start + replacement.length();
                return;
            }
        }

        findNext();
    }

    private void replaceAll() {
        EditorTab tab = getActiveTab();
        if (tab == null || !tab.isTextFile()) {
            return;
        }

        String query = editSearch.getText().toString();
        if (query.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_editor_search_empty));
            return;
        }

        String replacement = editReplace.getText().toString();
        String source = editCode.getText().toString();
        if (!source.contains(query)) {
            ModernToast.show(this, getString(R.string.scr_editor_no_matches_short));
            return;
        }

        int count = 0;
        int from = 0;
        while (true) {
            int idx = source.indexOf(query, from);
            if (idx < 0) {
                break;
            }
            count++;
            from = idx + query.length();
        }

        String replaced = source.replace(query, replacement);
        ignoreEditorChanges = true;
        editCode.setText(replaced);
        editCode.setSelection(Math.min(replaced.length(), editCode.length()));
        ignoreEditorChanges = false;

        tab.setContent(replaced);
        tab.setDirty(!replaced.equals(tab.getOriginalContent()));
        updateTitle(tab);
        refreshTabsUi();
        scheduleSyntaxHighlight();
        ModernToast.show(this, getString(R.string.scr_editor_replaced_count, count));
    }

    private void showPreview(EditorTab tab) {
        String ext = tab.getExtension();
        textPreviewTitle.setText(tab.getName());

        // Binary files support a long-press action card — surface a small hint.
        if (textBinaryHint != null) {
            textBinaryHint.setVisibility(View.VISIBLE);
        }

        if (isImage(ext)) {
            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagePreview.setColorFilter(null);
            imagePreview.setImageURI(tab.getUri());
            previewContainer.setOnLongClickListener(v -> { showBinaryActionCard(tab); return true; });
            return;
        }

        if ("pdf".equals(ext)) {
            renderPdfFirstPage(tab.getUri());
            previewContainer.setOnLongClickListener(v -> { showBinaryActionCard(tab); return true; });
            return;
        }

        if (isVideo(ext)) {
            videoPreview.setVisibility(View.VISIBLE);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoPreview);
            videoPreview.setMediaController(controller);
            videoPreview.setVideoURI(tab.getUri());
            videoPreview.seekTo(1);
            return;
        }

        if (isAudio(ext)) {
            audioPreview.setVisibility(View.VISIBLE);
            prepareAudio(tab);
            previewContainer.setOnLongClickListener(v -> { showBinaryActionCard(tab); return true; });
            return;
        }

        if ("apk".equals(ext)) {
            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            imagePreview.setImageResource(R.drawable.ic_android);
            imagePreview.setColorFilter(ContextCompat.getColor(this, R.color.icon));
            textPreviewTitle.setText(tab.getName());
            previewContainer.setOnLongClickListener(v -> { showBinaryActionCard(tab); return true; });
            return;
        }

        imagePreview.setVisibility(View.VISIBLE);
        imagePreview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imagePreview.setImageResource(R.drawable.ic_open_in_new);
        imagePreview.setColorFilter(ContextCompat.getColor(this, R.color.icon_muted));
        textPreviewTitle.setText(tab.getName());
        previewContainer.setOnLongClickListener(v -> { showBinaryActionCard(tab); return true; });
    }

    /**
     * Build and show the {@link BinaryActionCard} for a non-text tab. Triggered
     * ONLY by a long-press on the preview area (never automatically on open).
     */
    private void showBinaryActionCard(EditorTab tab) {
        String name = tab.getName();
        String ext = tab.getExtension();
        int iconRes;
        String typeLabel;
        java.util.List<BinaryActionCard.Action> actions = new java.util.ArrayList<>();

        if (ext.equals("apk")) {
            iconRes = R.drawable.ic_android;
            typeLabel = "Android Package";
            actions.add(new BinaryActionCard.Action(R.drawable.ic_android, getString(R.string.scr_binary_install), () -> installApk(tab)));
            actions.add(new BinaryActionCard.Action(R.drawable.ic_share, getString(R.string.scr_share), () -> shareApk(tab)));
        } else if (ext.matches("png|jpg|jpeg|gif|webp|bmp")) {
            iconRes = R.drawable.ic_file_image_outline;
            typeLabel = getString(R.string.scr_binary_image);
            actions.add(new BinaryActionCard.Action(R.drawable.ic_open_in_new, getString(R.string.action_open), () -> openInExternalApp(tab)));
            actions.add(new BinaryActionCard.Action(R.drawable.ic_share, getString(R.string.scr_share), () -> shareFile(tab)));
        } else if (ext.matches("mp3|wav|ogg|m4a|flac|aac")) {
            iconRes = R.drawable.ic_music_note_outline;
            typeLabel = getString(R.string.scr_binary_audio);
            actions.add(new BinaryActionCard.Action(R.drawable.ic_share, getString(R.string.scr_share), () -> shareFile(tab)));
        } else if (ext.equals("pdf")) {
            iconRes = R.drawable.ic_file_pdf_box;
            typeLabel = getString(R.string.scr_binary_pdf);
            actions.add(new BinaryActionCard.Action(R.drawable.ic_open_in_new, getString(R.string.action_open), () -> openInExternalApp(tab)));
            actions.add(new BinaryActionCard.Action(R.drawable.ic_share, getString(R.string.scr_share), () -> shareFile(tab)));
        } else {
            iconRes = R.drawable.ic_file_outline;
            typeLabel = getString(R.string.scr_binary_file);
            actions.add(new BinaryActionCard.Action(R.drawable.ic_open_in_new, getString(R.string.action_open), () -> openInExternalApp(tab)));
            actions.add(new BinaryActionCard.Action(R.drawable.ic_share, getString(R.string.scr_share), () -> shareFile(tab)));
        }

        BinaryActionCard.show(this, name, typeLabel, iconRes, actions);
    }

    private void clearPreviewViews() {
        previewContainer.setOnClickListener(null);
        previewContainer.setOnLongClickListener(null);
        if (textBinaryHint != null) {
            textBinaryHint.setVisibility(View.GONE);
        }
        imagePreview.setVisibility(View.GONE);
        imagePreview.setImageDrawable(null);
        imagePreview.setColorFilter(null);
        videoPreview.setVisibility(View.GONE);
        audioPreview.setVisibility(View.GONE);
        btnPlayPauseAudio.setImageResource(R.drawable.ic_play_circle_outline);
        textAudioStatus.setText(getString(R.string.scr_audio_ready));
        audioSeekBar.setProgress(0);
        audioSeekBar.setMax(0);
        audioTimeCurrent.setText("0:00");
        audioTimeRemaining.setText("-0:00");
        // Reset the album-art stack so stale bitmaps/gradients don't leak into the next tab.
        audioHasArt = false;
        if (audioAlbumArt != null) {
            audioAlbumArt.setImageDrawable(null);
            audioAlbumArt.setVisibility(View.GONE);
        }
        if (audioAlbumBackground != null) {
            audioAlbumBackground.setImageDrawable(null);
            audioAlbumBackground.setVisibility(View.GONE);
        }
        if (audioAlbumIcon != null) {
            audioAlbumIcon.setVisibility(View.GONE);
        }
    }

    private boolean renderPdfFirstPage(Uri uri) {
        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            pfd = getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) {
                return false;
            }
            renderer = new PdfRenderer(pfd);
            if (renderer.getPageCount() <= 0) {
                return false;
            }
            page = renderer.openPage(0);

            int width = Math.max(1, page.getWidth());
            int height = Math.max(1, page.getHeight());
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);

            imagePreview.setVisibility(View.VISIBLE);
            imagePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagePreview.setImageBitmap(bitmap);
            imagePreview.setColorFilter(null);
            textPreviewTitle.setText(textPreviewTitle.getText() + getString(R.string.scr_pdf_page_preview));
            return true;
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_pdf_render_error, e.getMessage()));
            return false;
        } finally {
            try {
                if (page != null) {
                    page.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (renderer != null) {
                    renderer.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (pfd != null) {
                    pfd.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void prepareAudio(EditorTab tab) {
        releaseAudio();
        stopAlbumSpin();
        textAudioStatus.setText(getString(R.string.scr_audio_preparing));
        btnPlayPauseAudio.setImageResource(R.drawable.ic_play_circle_outline);
        audioSeekBar.setProgress(0);
        audioSeekBar.setMax(0);
        audioTimeCurrent.setText("0:00");
        audioTimeRemaining.setText("-0:00");
        audioTrackTitle.setText(tab.getName());

        // Load album art (embedded artwork, if any) off the main thread.
        loadAlbumArt(tab.getUri());
        // Enable prev/next based on the surrounding audio files in the same folder.
        updateAudioNavButtons(tab);

        // Pre-compute the real duration via MediaMetadataRetriever: some AAC/m4a
        // files report a truncated duration through MediaPlayer.getDuration() on
        // prepareAsync, which clamps the seekbar (e.g. to ~7s) and causes the
        // progress indicator to reset while playback continues. The retriever
        // returns the authoritative duration before playback starts.
        int realDuration = readAudioDurationMs(tab.getUri());

        try {
            audioPlayer = new MediaPlayer();
            audioPlayer.setDataSource(this, tab.getUri());
            audioPlayer.setOnPreparedListener(mp -> {
                audioPrepared = true;
                int prepared = mp.getDuration();
                int max = prepared > 0 ? prepared : realDuration;
                if (realDuration > max) max = realDuration; // trust the retriever
                audioSeekBar.setMax(max);
                updateTimeDisplay();
                textAudioStatus.setText(getString(R.string.scr_audio_ready));
            });
            audioPlayer.setOnCompletionListener(mp -> {
                audioPlaying = false;
                btnPlayPauseAudio.setImageResource(R.drawable.ic_play_circle_outline);
                textAudioStatus.setText(getString(R.string.scr_audio_complete));
                audioSeekBar.setProgress(0);
                stopAlbumSpin();
                stopAudioUpdates();
                updateTimeDisplay();
            });
            audioPlayer.prepareAsync();
        } catch (Exception e) {
            textAudioStatus.setText(getString(R.string.scr_audio_error));
            ModernToast.show(this, getString(R.string.scr_audio_error) + ": " + e.getMessage());
        }
    }

    /** Best-effort authoritative duration (ms) via MediaMetadataRetriever. */
    private int readAudioDurationMs(Uri uri) {
        android.media.MediaMetadataRetriever mmr = null;
        try {
            mmr = new android.media.MediaMetadataRetriever();
            mmr.setDataSource(this, uri);
            String d = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d != null) {
                long ms = Long.parseLong(d);
                if (ms > 0) return (int) ms;
            }
        } catch (Exception ignored) {
        } finally {
            if (mmr != null) {
                try { mmr.release(); } catch (Exception ignored) {}
            }
        }
        return 0;
    }

    /**
     * Decode embedded artwork ({@link MediaMetadataRetriever#embeddedPicture})
     * from the audio file on a background thread. Two outcomes:
     * <ul>
     *   <li>Art present → bitmap set on {@link #audioAlbumArt}, gradient hidden,
     *       centered icon hidden. The album-art tile spins while playing.</li>
     *   <li>No art → gradient sweep set on {@link #audioAlbumBackground},
     *       album-art tile cleared, centered music icon shown. Only the gradient
     *       spins while playing; the icon stays static.</li>
     * </ul>
     */
    private void loadAlbumArt(Uri uri) {
        // Default to the no-art state immediately so stale bitmaps aren't shown.
        showNoArtState();

        new Thread(() -> {
            byte[] art = null;
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(this, uri);
                art = mmr.getEmbeddedPicture();
            } catch (Exception ignored) {
                art = null;
            } finally {
                if (mmr != null) {
                    try { mmr.release(); } catch (Exception ignored) {}
                }
            }
            final boolean hasArt = art != null && art.length > 0;
            final Bitmap bmp = hasArt ? BitmapFactory.decodeByteArray(art, 0, art.length) : null;
            runOnUiThread(() -> {
                if (bmp != null) {
                    audioHasArt = true;
                    audioAlbumBackground.setImageDrawable(null);
                    audioAlbumBackground.setVisibility(View.GONE);
                    audioAlbumIcon.setVisibility(View.GONE);
                    audioAlbumArt.setVisibility(View.VISIBLE);
                    audioAlbumArt.setColorFilter(null);
                    audioAlbumArt.setImageBitmap(bmp);
                    // Re-target the spin animator onto the art tile (if already running).
                    retargetSpin();
                } else {
                    showNoArtState();
                }
            });
        }).start();
    }

    /** Configure the no-art "spinning record" look: gradient + centered static icon. */
    private void showNoArtState() {
        audioHasArt = false;
        audioAlbumArt.setImageDrawable(null);
        audioAlbumArt.setVisibility(View.GONE);
        audioAlbumBackground.setVisibility(View.VISIBLE);
        audioAlbumBackground.setImageDrawable(buildSpinGradient());
        audioAlbumIcon.setVisibility(View.VISIBLE);
        // Re-target the spin animator onto the gradient (if already running).
        retargetSpin();
    }

    /**
     * Build a multi-color sweep gradient used as the rotating vinyl background.
     * Colors are drawn from the current accent + a few complementary hues so it
     * reads as a vibrant record label in both light and dark themes.
     */
    private GradientDrawable buildSpinGradient() {
        int accent = app.multiai.mobile.utils.AccentColor.resolve(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        // Sweep needs at least 2 colors; rotate hues around the accent for a vivid label.
        g.setColors(new int[] {
                accent,
                0xFF7C4DFF,
                0xFFEC407A,
                0xFF26A69A,
                0xFFFFCA28,
                accent
        });
        return g;
    }

    /**
     * Lazily build the infinite linear rotation used for the "spinning record"
     * effect. The animated target is chosen per state: the album-art tile when a
     * bitmap is present, otherwise the gradient background (the centered icon
     * stays put).
     */
    private ObjectAnimator getOrCreateAlbumSpin() {
        if (albumSpin == null) {
            albumSpin = ObjectAnimator.ofFloat(spinTarget(), "rotation", 0f, 360f);
            albumSpin.setDuration(6000);
            albumSpin.setInterpolator(new LinearInterpolator());
            albumSpin.setRepeatCount(ValueAnimator.INFINITE);
            albumSpin.setRepeatMode(ValueAnimator.RESTART);
        }
        return albumSpin;
    }

    /** The view that should rotate while playing (art tile if art, else gradient). */
    private View spinTarget() {
        return audioHasArt ? audioAlbumArt : audioAlbumBackground;
    }

    /** If the spin animator is running, retarget it onto the current spin target. */
    private void retargetSpin() {
        if (albumSpin == null) {
            return;
        }
        boolean wasRunning = albumSpin.isRunning();
        albumSpin.cancel();
        albumSpin.setTarget(spinTarget());
        if (wasRunning) {
            albumSpin.start();
        }
    }

    private void startAlbumSpin() {
        // Ensure target is current before starting.
        if (albumSpin == null) {
            getOrCreateAlbumSpin();
        } else {
            albumSpin.setTarget(spinTarget());
        }
        albumSpin.start();
    }

    private void stopAlbumSpin() {
        if (albumSpin != null) {
            albumSpin.cancel();
        }
    }

    /** Clip a view to a circle (radius = half its width) via an outline provider. */
    private static void applyCircularClip(View view) {
        if (view == null) return;
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
    }

    /**
     * Enumerate audio files in the same folder as {@code tab} (via the tree URI
     * passed by FilesActivity), sorted by name, and remember the current file's
     * index so prev/next can be enabled/disabled. The lookup runs on a worker
     * thread because {@link DocumentFile#listFiles()} does I/O.
     */
    private void updateAudioNavButtons(EditorTab tab) {
        // Reset to disabled until the lookup completes; happens fast in practice.
        audioSiblings = java.util.Collections.emptyList();
        audioSiblingIndex = -1;
        btnAudioPrev.setEnabled(false);
        btnAudioNext.setEnabled(false);
        btnAudioPrev.setAlpha(0.4f);
        btnAudioNext.setAlpha(0.4f);

        final Uri currentUri = tab.getUri();
        final Uri treeUri = currentFolderTreeUri != null ? currentFolderTreeUri : extractTreeUri(currentUri);
        if (treeUri == null) {
            return;
        }

        new Thread(() -> {
            // Determine the parent document id whose children we want. Prefer the
            // immediate parent of the current file (derived from its document id) so
            // prev/next works even when the file sits in a subfolder and only the
            // project root tree URI was passed. Fall back to the tree root itself.
            String parentDocId = resolveParentDocId(treeUri, currentUri);
            List<Uri> childUris = new ArrayList<>();
            List<String> childNames = new ArrayList<>();
            if (!queryChildDocuments(treeUri, parentDocId, childUris, childNames)) {
                // Batched query failed (some providers don't support it) — fall back
                // to the slow per-file DocumentFile listing so prev/next still works.
                fallbackListChildren(treeUri, parentDocId, childUris, childNames);
            }

            // Keep only audio files, paired with their names for stable sorting.
            List<Uri> audioUris = new ArrayList<>();
            List<String> audioNames = new ArrayList<>();
            for (int i = 0; i < childUris.size(); i++) {
                String name = i < childNames.size() ? childNames.get(i) : "";
                if (isAudio(getExtension(name))) {
                    audioUris.add(childUris.get(i));
                    audioNames.add(name);
                }
            }
            // Sort both lists together by name (case-insensitive).
            Integer[] order = new Integer[audioUris.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            final java.util.Comparator<Integer> byName = (a, b) ->
                    String.CASE_INSENSITIVE_ORDER.compare(
                            audioNames.get(a) != null ? audioNames.get(a) : "",
                            audioNames.get(b) != null ? audioNames.get(b) : "");
            java.util.Arrays.sort(order, byName);

            final List<Uri> sortedUris = new ArrayList<>(order.length);
            final List<String> sortedNames = new ArrayList<>(order.length);
            for (Integer idx : order) {
                sortedUris.add(audioUris.get(idx));
                sortedNames.add(audioNames.get(idx));
            }

            // Match the current file by Uri (handle tree-vs-single uri differences).
            int found = -1;
            String curLast = lastSegment(currentUri);
            for (int i = 0; i < sortedUris.size(); i++) {
                Uri u = sortedUris.get(i);
                if (currentUri.equals(u) || (curLast != null && curLast.equals(lastSegment(u)))) {
                    found = i;
                    break;
                }
            }

            final int idx = found;
            runOnUiThread(() -> {
                audioSiblingUris = sortedUris;
                audioSiblingNames = sortedNames;
                audioSiblings = java.util.Collections.emptyList(); // legacy list no longer used
                audioSiblingIndex = idx;
                boolean hasPrev = idx > 0;
                boolean hasNext = idx >= 0 && idx < sortedUris.size() - 1;
                btnAudioPrev.setEnabled(hasPrev);
                btnAudioNext.setEnabled(hasNext);
                btnAudioPrev.setAlpha(hasPrev ? 1f : 0.4f);
                btnAudioNext.setAlpha(hasNext ? 1f : 0.4f);
            });
        }).start();
    }

    /**
     * Try to recover the SAF tree URI that grants access to the document behind
     * {@code fileUri}. A SAF document uri has the form
     * {@code content://com.android.externalstorage.documents/tree/PRIMARY%3AFoo/document/PRIMARY%3AFoo%2Fbar.mp3};
     * the tree segment is everything up to and including {@code tree/...}.
     */
    private static Uri extractTreeUri(Uri fileUri) {
        if (fileUri == null) return null;
        String ssp = fileUri.getEncodedSchemeSpecificPart();
        if (ssp == null) return null;
        int tree = ssp.indexOf("/tree/");
        if (tree < 0) return null;
        int doc = ssp.indexOf("/document/", tree + 6);
        String treePart = doc > 0 ? ssp.substring(tree + 6, doc) : ssp.substring(tree + 6);
        // Rebuild a clean tree uri.
        return Uri.parse(fileUri.getScheme() + "://" + fileUri.getAuthority()
                + "/tree/" + treePart);
    }

    /**
     * Derive the parent document id for the file behind {@code fileUri} (so we list
     * its actual folder, not the project root). Returns null to mean "use the tree
     * root document".
     */
    private static String resolveParentDocId(Uri treeUri, Uri fileUri) {
        String docId = DocumentsContract.getDocumentId(fileUri);
        if (docId == null || docId.isEmpty()) return null;
        // Document ids in externalstorage are path-like ("PRIMARY:Music/foo.mp3").
        // Parent = strip the last path segment.
        int slash = Math.max(docId.lastIndexOf('/'), docId.lastIndexOf('\\'));
        if (slash <= 0) return null;
        String parent = docId.substring(0, slash);
        // If the parent equals the tree's own document, listing the root is correct.
        return parent;
    }

    /** Last decoded path segment of a uri, or null. Used as a match fallback. */
    private static String lastSegment(Uri uri) {
        if (uri == null) return null;
        String last = uri.getLastPathSegment();
        if (last == null) return null;
        int slash = Math.max(last.lastIndexOf('/'), last.lastIndexOf('\\'));
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    /**
     * Batched SAF query: ask the provider for ALL child documents of
     * {@code parentDocId} (or the tree root when null) in a single Cursor. This is
     * 10-50x faster than calling DocumentFile.listFiles() because it avoids a
     * separate ContentResolver query per file. Returns false if the query isn't
     * supported so the caller can fall back.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private boolean queryChildDocuments(Uri treeUri, String parentDocId,
                                        List<Uri> outUris, List<String> outNames) {
        String docId = parentDocId != null ? parentDocId : DocumentsContract.getTreeDocumentId(treeUri);
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        } catch (Exception ignored) {
            return false;
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    childrenUri,
                    new String[] {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    },
                    null, null, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                String childId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (childId == null) continue;
                // Skip directories.
                if (mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR)) continue;
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                outUris.add(childUri);
                outNames.add(name != null ? name : "");
            }
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (c != null) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** Slow-path fallback using DocumentFile per-file queries. */
    private void fallbackListChildren(Uri treeUri, String parentDocId,
                                      List<Uri> outUris, List<String> outNames) {
        DocumentFile folder;
        try {
            if (parentDocId != null) {
                Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId);
                folder = DocumentFile.fromSingleUri(this, docUri);
            } else {
                folder = DocumentFile.fromTreeUri(this, treeUri);
            }
        } catch (Exception ignored) {
            return;
        }
        if (folder == null) return;
        DocumentFile[] children;
        try {
            children = folder.listFiles();
        } catch (Exception ignored) {
            return;
        }
        if (children == null) return;
        for (DocumentFile child : children) {
            if (child == null || !child.isFile()) continue;
            outUris.add(child.getUri());
            outNames.add(child.getName() != null ? child.getName() : "");
        }
    }

    /**
     * Switch to the previous ({@code -1}) or next ({@code +1}) audio file in the
     * current folder. Replaces the active tab's contents in place so playback
     * state and the rest of the UI stay consistent. No-op if the requested
     * neighbour doesn't exist or the sibling list isn't loaded.
     */
    private void switchAudioRelative(int direction) {
        if (audioSiblingUris.isEmpty() || audioSiblingIndex < 0) {
            return;
        }
        int target = audioSiblingIndex + direction;
        if (target < 0 || target >= audioSiblingUris.size()) {
            return;
        }
        Uri nextUri = audioSiblingUris.get(target);
        if (nextUri == null) {
            return;
        }
        EditorTab active = getActiveTab();
        if (active == null) {
            return;
        }
        String name = target < audioSiblingNames.size() && audioSiblingNames.get(target) != null
                ? audioSiblingNames.get(target) : "audio";
        // Update the active tab in place so re-prepares and the title stay correct.
        active.replaceUriName(nextUri, name, getExtension(name));
        audioSiblingIndex = target;
        updateTitle(active);
        refreshTabsUi();
        prepareAudio(active);

        boolean hasPrev = target > 0;
        boolean hasNext = target < audioSiblingUris.size() - 1;
        btnAudioPrev.setEnabled(hasPrev);
        btnAudioNext.setEnabled(hasNext);
        btnAudioPrev.setAlpha(hasPrev ? 1f : 0.4f);
        btnAudioNext.setAlpha(hasNext ? 1f : 0.4f);
    }

    private void toggleAudioPlayback() {
        if (audioPlayer == null || !audioPrepared) {
            ModernToast.show(this, getString(R.string.scr_audio_not_ready));
            return;
        }

        try {
            if (audioPlaying) {
                audioPlayer.pause();
                audioPlaying = false;
                btnPlayPauseAudio.setImageResource(R.drawable.ic_play_circle_outline);
                textAudioStatus.setText(getString(R.string.scr_audio_paused));
                stopAlbumSpin();
                stopAudioUpdates();
                updateTimeDisplay();
            } else {
                audioPlayer.start();
                audioPlaying = true;
                btnPlayPauseAudio.setImageResource(R.drawable.ic_pause_circle_outline);
                textAudioStatus.setText(getString(R.string.scr_audio_playing));
                startAlbumSpin();
                startAudioUpdates();
            }
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_playback_error));
        }
    }

    /** Start periodically refreshing the audio seekbar + time text (~every 200ms). */
    private void startAudioUpdates() {
        stopAudioUpdates();
        audioUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                // Keep polling while the player exists, even across transient
                // audioPlaying flips (start() can briefly report false). Stopping
                // playback removes callbacks via stopAudioUpdates().
                if (audioPlayer != null) {
                    if (audioPlaying && !audioUserSeeking) {
                        int pos;
                        try {
                            pos = audioPlayer.getCurrentPosition();
                        } catch (Exception e) {
                            pos = audioSeekBar.getProgress();
                        }
                        int max = audioSeekBar.getMax();
                        if (pos > max) {
                            // MediaPlayer reported a position past the (stale) max —
                            // grow the seekbar so progress never "resets" while audio
                            // keeps playing past the prepared duration.
                            audioSeekBar.setMax(pos);
                        }
                        if (!audioSeekBar.isPressed()) {
                            audioSeekBar.setProgress(pos);
                        }
                        updateTimeDisplay();
                    }
                    audioUpdateHandler.postDelayed(this, 200);
                }
            }
        };
        audioUpdateHandler.postDelayed(audioUpdateRunnable, 200);
    }

    private void stopAudioUpdates() {
        if (audioUpdateRunnable != null) {
            audioUpdateHandler.removeCallbacks(audioUpdateRunnable);
            audioUpdateRunnable = null;
        }
    }

    /**
     * Render elapsed time on the left (e.g. "1:23") and remaining time on the
     * right, shown as a negative duration (e.g. "-2:42") like most music apps.
     */
    private void updateTimeDisplay() {
        if (audioPlayer == null) {
            audioTimeCurrent.setText("0:00");
            audioTimeRemaining.setText("-0:00");
            return;
        }
        int current;
        try {
            current = audioPlayer.getCurrentPosition();
        } catch (Exception e) {
            current = audioSeekBar.getProgress();
        }
        int total = audioSeekBar.getMax();
        int remaining = total - current;
        if (remaining < 0) {
            remaining = 0;
        }
        audioTimeCurrent.setText(formatTime(current));
        audioTimeRemaining.setText("-" + formatTime(remaining));
    }

    /** Format milliseconds as "m:ss" (e.g. 83000 -> "1:23"). */
    private String formatTime(int ms) {
        if (ms < 0) {
            ms = 0;
        }
        int totalSeconds = ms / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /**
     * Copy the APK to a cache File, expose it through FileProvider, and launch
     * ACTION_VIEW so the SYSTEM package-installer dialog appears (the standard
     * "установить это приложение?" prompt). Silent installs aren't possible for
     * non-system apps, and the package installer requires a {@code content://}
     * URI — a raw SAF DocumentFile URI won't be accepted.
     */
    private void installApk(EditorTab tab) {
        ModernToast.show(this, getString(R.string.scr_apk_preparing));
        new Thread(() -> {
            File apkFile = null;
            try {
                File installDir = new File(getCacheDir(), "apk_installs");
                if (!installDir.exists() && !installDir.mkdirs()) {
                    throw new IllegalStateException(getString(R.string.scr_create_dir_error));
                }
                // Sanitize the file name and force .apk extension.
                String rawName = tab.getName() != null ? tab.getName() : "install.apk";
                String safeName = rawName.replaceAll("[^A-Za-z0-9._-]", "_");
                if (!safeName.toLowerCase(Locale.US).endsWith(".apk")) {
                    safeName += ".apk";
                }
                apkFile = new File(installDir, safeName);

                // Stream the DocumentFile contents into the local cache file.
                try (InputStream in = getContentResolver().openInputStream(tab.getUri());
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    if (in == null) {
                        throw new IllegalStateException(getString(R.string.scr_open_read_stream_error));
                    }
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                }

                final Uri apkUri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", apkFile);
                runOnUiThread(() -> {
                    Intent openIntent = new Intent(Intent.ACTION_VIEW);
                    openIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    openIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(openIntent);
                    } catch (Exception e) {
                        ModernToast.show(EditorActivity.this,
                                getString(R.string.scr_installer_open_error, e.getMessage()));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> ModernToast.show(
                        EditorActivity.this, getString(R.string.scr_apk_install_error, e.getMessage())));
                // Best-effort cleanup of a half-written file.
                if (apkFile != null) {
                    try { apkFile.delete(); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    /** Share an APK tab via ACTION_SEND with the package-archive mime type. */
    private void shareApk(EditorTab tab) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/vnd.android.package-archive");
        shareIntent.putExtra(Intent.EXTRA_STREAM, tab.getUri());
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.scr_share_apk)));
    }

    /** Generic file share (images, audio, PDF, …) — resolves the mime type per tab. */
    private void shareFile(EditorTab tab) {
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType(resolveMimeType(tab));
        shareIntent.putExtra(Intent.EXTRA_STREAM, tab.getUri());
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, getString(R.string.scr_share_file_name, tab.getName())));
    }

    private void openInExternalApp(EditorTab tab) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(tab.getUri(), resolveMimeType(tab));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.scr_open_file)));
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_no_app_to_open));
        }
    }

    private String resolveMimeType(EditorTab tab) {
        DocumentFile file = DocumentFile.fromSingleUri(this, tab.getUri());
        if (file != null && file.getType() != null && !file.getType().isEmpty()) {
            return file.getType();
        }

        String ext = tab.getExtension();
        if (ext.isEmpty()) {
            return "*/*";
        }

        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        if (mime != null && !mime.isEmpty()) {
            return mime;
        }

        if ("mkv".equals(ext)) {
            return "video/x-matroska";
        }
        return "*/*";
    }

    private void stopVideoPreview() {
        try {
            if (videoPreview != null && videoPreview.isPlaying()) {
                videoPreview.pause();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseAudio() {
        stopAudioUpdates();
        stopAlbumSpin();
        audioPrepared = false;
        audioPlaying = false;
        audioUserSeeking = false;
        if (audioPlayer != null) {
            try {
                audioPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                audioPlayer.release();
            } catch (Exception ignored) {
            }
            audioPlayer = null;
        }
    }

    private boolean isTextFile(String ext) {
        return ext.equals("java") || ext.equals("kt") || ext.equals("kts") || ext.equals("xml") ||
            ext.equals("js") || ext.equals("jsx") || ext.equals("ts") || ext.equals("tsx") ||
            ext.equals("py") || ext.equals("json") || ext.equals("md") || ext.equals("txt") ||
            ext.equals("yml") || ext.equals("yaml") || ext.equals("gradle") || ext.equals("properties") ||
            ext.equals("html") || ext.equals("css") || ext.equals("sh") || ext.equals("c") ||
            ext.equals("cpp") || ext.equals("h") || ext.equals("hpp") || ext.equals("go") ||
            ext.equals("rs") || ext.equals("php") || ext.equals("rb") || ext.equals("swift");
    }

    private boolean isImage(String ext) {
        return ext.equals("png") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("gif") ||
            ext.equals("webp") || ext.equals("bmp") || ext.equals("svg");
    }

    private boolean isAudio(String ext) {
        return ext.equals("mp3") || ext.equals("wav") || ext.equals("ogg") || ext.equals("m4a")
                || ext.equals("aac") || ext.equals("flac") || ext.equals("opus");
    }

    private boolean isVideo(String ext) {
        return ext.equals("mp4") || ext.equals("mkv") || ext.equals("mov") || ext.equals("webm") || ext.equals("avi");
    }

    private String getExtension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        commitActiveEditorState();
        if (writeEditorSnapshot()) {
            outState.putString(STATE_SNAPSHOT_FILE, stateSnapshotFile.getName());
        }
        super.onSaveInstanceState(outState);
    }

    private boolean writeEditorSnapshot() {
        try {
            if (stateSnapshotFile == null) {
                stateSnapshotFile = new File(getCacheDir(),
                        "editor_state_" + UUID.randomUUID() + ".json");
            }
            JSONObject root = new JSONObject();
            root.put("active", activeTabIndex);
            root.put("page", currentPageIndex);
            root.put("folder", currentFolderTreeUri != null
                    ? currentFolderTreeUri.toString() : JSONObject.NULL);
            JSONArray savedTabs = new JSONArray();
            for (EditorTab tab : tabs) {
                JSONObject savedTab = new JSONObject();
                savedTab.put("uri", tab.getUri().toString());
                savedTab.put("name", tab.getName());
                savedTab.put("path", tab.getPath());
                savedTab.put("extension", tab.getExtension());
                savedTab.put("text", tab.isTextFile());
                savedTab.put("content", tab.getContent());
                savedTab.put("original", tab.getOriginalContent());
                savedTab.put("dirty", tab.isDirty());
                savedTab.put("readFailed", tab.isReadFailed());
                savedTabs.put(savedTab);
            }
            root.put("tabs", savedTabs);
            try (FileOutputStream output = new FileOutputStream(stateSnapshotFile, false)) {
                output.write(root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean restoreEditorState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return false;
        String fileName = savedInstanceState.getString(STATE_SNAPSHOT_FILE);
        if (fileName == null || !fileName.matches("editor_state_[A-Za-z0-9-]+\\.json")) {
            return false;
        }
        File snapshot = new File(getCacheDir(), fileName);
        if (!snapshot.isFile()) return false;
        try (FileInputStream input = new FileInputStream(snapshot);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) bytes.write(buffer, 0, read);
            JSONObject root = new JSONObject(
                    bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
            JSONArray savedTabs = root.optJSONArray("tabs");
            if (savedTabs == null || savedTabs.length() == 0) return false;
            tabs.clear();
            for (int i = 0; i < savedTabs.length(); i++) {
                JSONObject savedTab = savedTabs.optJSONObject(i);
                if (savedTab == null) continue;
                String uri = savedTab.optString("uri", "");
                if (uri.isEmpty()) continue;
                EditorTab tab = new EditorTab(
                        Uri.parse(uri),
                        savedTab.optString("name", "file"),
                        savedTab.optString("path", "file"),
                        savedTab.optString("extension", ""),
                        savedTab.optBoolean("text", false));
                tab.setContent(savedTab.optString("content", ""));
                tab.setOriginalContent(savedTab.optString("original", ""));
                tab.setDirty(savedTab.optBoolean("dirty", false));
                tab.setReadFailed(savedTab.optBoolean("readFailed", false));
                tabs.add(tab);
            }
            if (tabs.isEmpty()) return false;
            stateSnapshotFile = snapshot;
            String folder = root.optString("folder", "");
            currentFolderTreeUri = folder.isEmpty() || "null".equals(folder)
                    ? null : Uri.parse(folder);
            int restoredTab = Math.max(0, Math.min(root.optInt("active", 0), tabs.size() - 1));
            int restoredPage = Math.max(0, root.optInt("page", 0));
            activeTabIndex = -1;
            switchToTab(restoredTab);
            if (getActiveTab() != null && getActiveTab().isTextFile()) {
                currentPageIndex = Math.min(restoredPage, totalPages - 1);
                applyCurrentPage();
            }
            return true;
        } catch (Exception ignored) {
            tabs.clear();
            return false;
        }
    }

    @Override
    public void onBackPressed() {
        commitActiveEditorState();
        boolean hasDirty = false;
        for (EditorTab tab : tabs) {
            if (tab.isTextFile() && tab.isDirty()) {
                hasDirty = true;
                break;
            }
        }

        if (!hasDirty) {
            super.onBackPressed();
            return;
        }

        ModernDialog.show(this, getString(R.string.scr_unsaved_tabs), getString(R.string.scr_exit_without_save), getString(R.string.scr_cancel), getString(R.string.scr_exit), false, () -> EditorActivity.super.onBackPressed());
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopVideoPreview();
        if (audioPlayer != null && audioPlaying) {
            try {
                audioPlayer.pause();
                audioPlaying = false;
                btnPlayPauseAudio.setImageResource(R.drawable.ic_play_circle_outline);
                textAudioStatus.setText(getString(R.string.scr_audio_paused));
                stopAlbumSpin();
                stopAudioUpdates();
                updateTimeDisplay();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        fileIo.shutdownNow();
        syntaxGeneration++;
        syntaxHandler.removeCallbacksAndMessages(null);
        stopAudioUpdates();
        stopAlbumSpin();
        if (albumSpin != null) {
            albumSpin.removeAllListeners();
            albumSpin = null;
        }
        releaseAudio();
        if (isFinishing() && stateSnapshotFile != null) {
            stateSnapshotFile.delete();
        }
    }
}
