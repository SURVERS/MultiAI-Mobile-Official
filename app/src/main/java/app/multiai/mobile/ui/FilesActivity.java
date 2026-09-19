package app.multiai.mobile.ui;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.multiai.mobile.R;
import app.multiai.mobile.adapters.FilesAdapter;
import app.multiai.mobile.models.FileEntry;
import app.multiai.mobile.ui.kit.ModernToast;
import app.multiai.mobile.utils.DrawerNavHelper;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

public class FilesActivity extends AppCompatActivity {
    
    private DrawerLayout drawerLayout;
    private ImageButton btnMenu;
    private ImageButton btnBack;
    private ImageButton btnChangeFolder;
    private View emptyState;
    private View filesContainer;
    private Button btnOpenFolder;
    private RecyclerView recyclerFiles;
    private TextView textFolderPath;
    private TextView textProjectSummary;
    private ActivityResultLauncher<Uri> folderPickerLauncher;
    private Uri projectFolderUri;

    // ── Copy/Move destination picker ──
    // Separate from folderPickerLauncher (which changes the PROJECT folder).
    // When the user taps Copy/Move we stash the operation here, then launch
    // this picker so they can choose WHERE the files should go.
    private ActivityResultLauncher<Uri> copyMoveLauncher;
    private boolean copyMovePendingIsMove = false;
    private List<FileEntry> copyMovePendingFiles = Collections.emptyList();

    // ── Selection mode (long-press to multi-select files) ──
    private FilesAdapter filesAdapter;
    private View selectionBar;
    private TextView selectionCountText;

    // ── Copy/Move progress dialog ──
    // Shown while performCopyOrMoveInto() runs on a background thread. Built lazily
    // and dismissed on the UI thread once the operation completes (or fails). The
    // label + determinate max are configured per-operation via showProgressDialog().
    private Dialog progressDialog;
    private TextView progressLabel;
    private ProgressBar progressBar;

    // Навигация по папкам. A stack of (treeUri, docId, displayName) tuples describing
    // each ancestor folder we've descended into. We keep DocumentFile out of the hot
    // path — it's only materialized on demand — so folder navigation stays fast.
    private final Stack<FolderNode> folderStack = new Stack<>();
    private FolderNode currentFolderNode;
    private DocumentFile currentFolder;       // lazily resolved when needed
    private int folderLoadRequestId = 0;

    /** A navigable folder: its tree URI, document id within that tree, and label. */
    private static final class FolderNode {
        final Uri treeUri;
        final String docId;
        final String name;
        FolderNode(Uri treeUri, String docId, String name) {
            this.treeUri = treeUri;
            this.docId = docId;
            this.name = name != null ? name : "";
        }
    }

    // ── Disk-scan cache ──
    // Scanning a SAF folder is slow if done per-file via DocumentFile. We use a
    // single batched DocumentsContract query (see queryChildEntries) and cache the
    // resulting fully-resolved entries keyed by folder Uri; re-scan only when the
    // folder's last-modified time changes (or the cache is empty).
    private static final class FolderCache {
        final List<FileEntry> files;
        final int folderCount;
        final int fileCount;
        final long lastModified;
        FolderCache(List<FileEntry> files, int folderCount, int fileCount, long lastModified) {
            this.files = files;
            this.folderCount = folderCount;
            this.fileCount = fileCount;
            this.lastModified = lastModified;
        }
    }
    private static FolderCache cachedFolder = null;
    private static Uri cachedFolderUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (app.multiai.mobile.utils.AppLockManager.get(this).routeIfLocked(this)) return;
        setContentView(R.layout.activity_files);
        // Apply FLAG_SECURE if the user enabled «Блокировать скриншоты».
        app.multiai.mobile.utils.SecurityHelper.applySecure(this);

        initViews();
        setupDrawer();
        setupFolderPicker();
        setupListeners();
        
        // Проверяем есть ли сохранённая папка
        checkSavedFolder();
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawerLayout);
        btnMenu = findViewById(R.id.btnMenu);
        btnBack = findViewById(R.id.btnBack);
        btnChangeFolder = findViewById(R.id.btnChangeFolder);
        emptyState = findViewById(R.id.emptyState);
        filesContainer = findViewById(R.id.filesContainer);
        btnOpenFolder = findViewById(R.id.btnOpenFolder);
        recyclerFiles = findViewById(R.id.recyclerFiles);
        textFolderPath = findViewById(R.id.textFolderPath);
        textProjectSummary = findViewById(R.id.textProjectSummary);
        applyLightThemeBrandIcons();
        
        // Настраиваем RecyclerView
        recyclerFiles.setLayoutManager(new LinearLayoutManager(this));
        recyclerFiles.setItemAnimator(app.multiai.mobile.ui.kit.MotionKit.listAnimator());

        buildSelectionBar();
    }

    /** Applies the same branded drawable and tint used by the drawer in light theme. */
    private void applyLightThemeBrandIcons() {
        int uiMode = getResources().getConfiguration().uiMode;
        boolean isLightTheme = (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (!isLightTheme) return;

        android.content.res.ColorStateList tint = android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.icon));
        ImageView toolbarLogo = findViewById(R.id.filesBrandLogo);
        ImageView emptyLogo = findViewById(R.id.filesEmptyBrandLogo);
        if (toolbarLogo != null) toolbarLogo.setImageTintList(tint);
        if (emptyLogo != null) emptyLogo.setImageTintList(tint);
    }

    /**
     * Builds the bottom selection action bar programmatically and attaches it as the
     * last child of the content FrameLayout (which already wraps emptyState/filesContainer),
     * so it floats above the file list. Starts hidden (GONE) and slides up on demand.
     */
    private void buildSelectionBar() {
        ViewGroup contentFrame = (ViewGroup) filesContainer.getParent();
        int error = ContextCompat.getColor(this, R.color.error);
        int textPrimary = ContextCompat.getColor(this, R.color.text_primary);
        int icon = ContextCompat.getColor(this, R.color.icon);
        int surfaceCard = ContextCompat.getColor(this, R.color.surface_card);
        int stroke = ContextCompat.getColor(this, R.color.stroke);

        // Root bar: rounded top corners, card background, top stroke, elevation.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) dp(12);
        bar.setPadding(pad, pad, pad, pad);
        bar.setVisibility(View.GONE);
        bar.setTranslationY(dp(80));
        bar.setElevation(dp(8));
        bar.setBackgroundColor(surfaceCard);
        // 1dp top border via a thin divider drawn as background layer is awkward; emulate with
        // a GradientDrawable that has a top stroke + rounded top corners.
        android.graphics.drawable.GradientDrawable barBg =
                new android.graphics.drawable.GradientDrawable();
        barBg.setColor(surfaceCard);
        barBg.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0});
        barBg.setStroke((int) dp(1), stroke);
        bar.setBackground(barBg);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        // Respect navigation bar inset.
        barLp.bottomMargin = 0;
        bar.setLayoutParams(barLp);

        // Left: close (X) button to exit selection mode.
        ImageButton btnClose = makeIconButton(R.drawable.ic_close, icon);
        btnClose.setOnClickListener(v -> exitSelectionMode());
        bar.addView(btnClose);

        // Count text — "N выбран".
        TextView countText = new TextView(this);
        countText.setText(getResources().getQuantityString(R.plurals.scr_n_selected, 1, 1));
        countText.setTextColor(textPrimary);
        countText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        countText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        countLp.leftMargin = (int) dp(10);
        countText.setLayoutParams(countLp);
        bar.addView(countText);
        selectionCountText = countText;

        // Action buttons (right). Each weighted 0 with square size.
        ImageButton btnMove = makeIconButton(R.drawable.ic_folder, icon);
        btnMove.setOnClickListener(v -> onMoveSelected());
        bar.addView(btnMove);

        ImageButton btnCopy = makeIconButton(R.drawable.ic_copy, icon);
        btnCopy.setOnClickListener(v -> onCopySelected());
        bar.addView(btnCopy);

        ImageButton btnDelete = makeIconButton(R.drawable.ic_delete_outline, error);
        btnDelete.setOnClickListener(v -> onDeleteSelected());
        bar.addView(btnDelete);

        contentFrame.addView(bar);
        selectionBar = bar;
    }

    private ImageButton makeIconButton(int drawableRes, int tintColor) {
        ImageButton b = new ImageButton(this);
        int size = (int) dp(44);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        b.setLayoutParams(lp);
        b.setImageResource(drawableRes);
        TypedValue out = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true);
        b.setBackgroundResource(out.resourceId);
        b.setImageTintList(ColorStateList.valueOf(tintColor));
        return b;
    }

    private float dp(int v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /**
     * Crossfade from {@code out} (fades + collapses to GONE) into {@code in}
     * (fades in). Either may be null; nulls are skipped. Used for the smooth
     * empty-state ↔ file-list transition.
     */
    private void crossfadeView(View out, View in) {
        if (out != null && out.getVisibility() == View.VISIBLE) {
            out.animate().cancel();
            out.animate()
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction(() -> {
                        out.setVisibility(View.GONE);
                        out.setAlpha(1f);
                    })
                    .start();
        }
        if (in != null) {
            if (in.getVisibility() != View.VISIBLE) {
                in.setAlpha(0f);
                in.setVisibility(View.VISIBLE);
            }
            in.animate().cancel();
            in.animate().alpha(1f).setDuration(160).start();
        }
    }

    private void setupDrawer() {
        // Get the FrameLayout that wraps the drawer
        View drawerView = findViewById(R.id.drawerFrame);

        // Setup drawer toggle animation
        btnMenu.setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(drawerView)) {
                drawerLayout.closeDrawer(drawerView);
            } else {
                drawerLayout.openDrawer(drawerView);
            }
        });

        // Close drawer when clicking outside
        drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(View drawer, float slideOffset) {
                // Animate hamburger icon rotation
                btnMenu.setRotation(slideOffset * 90);
            }
        });

        DrawerNavHelper.setup(this, DrawerNavHelper.NavTarget.FILES,
                target -> {
                    switch (target) {
                        case CHAT:
                            startActivity(new Intent(FilesActivity.this, ChatActivity.class));
                            finish();
                            return true;
                        case TERMINAL:
                            startActivity(new Intent(FilesActivity.this, TerminalActivity.class));
                            finish();
                            return true;
                        case EDITOR:
                            startActivity(new Intent(FilesActivity.this, EditorActivity.class));
                            finish();
                            return true;
                        case FILES:
                        default:
                            drawerLayout.closeDrawer(drawerView);
                            return true;
                    }
                },
                v -> {
                    startActivity(new Intent(FilesActivity.this, ChatModeSettingsActivity.class));
                    finish();
                },
                v -> {
                    startActivity(new Intent(FilesActivity.this, ProfileActivity.class));
                    finish();
                });
    }

    private void setupFolderPicker() {
        // Регистрируем launcher для выбора папки
        folderPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri != null) {
                    // Сохраняем URI папки
                    projectFolderUri = uri;
                    
                    // Получаем постоянный доступ к папке
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                    
                    // Сохраняем в SharedPreferences
                    getSharedPreferences("multiaiide", MODE_PRIVATE)
                        .edit()
                        .putString("project_folder_uri", uri.toString())
                        .apply();

                    // Every selected project gets its own persistent MultiAI rules folder.
                    new Thread(() -> app.multiai.mobile.utils.ProjectRulesManager
                            .ensureRulesDirectory(FilesActivity.this),
                            "multiai-rules-init").start();

                    // New project folder selected — drop any stale listing so it scans fresh.
                    invalidateCache();

                    // Показываем файлы
                    showProjectFiles(uri);
                }
            }
        );

        // Separate launcher for Copy/Move destination. On result we perform the
        // pending operation (if any); if the user cancels (uri == null) we just
        // stay in selection mode — no action, no toast.
        copyMoveLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocumentTree(),
            uri -> {
                if (uri == null) {
                    // Cancelled — keep selection mode so the user can retry.
                    return;
                }
                if (copyMovePendingFiles.isEmpty()) {
                    return;
                }
                // Grab + persist write access so we can create files inside the tree.
                try {
                    getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception ignored) {
                }
                DocumentFile destFolder = DocumentFile.fromTreeUri(this, uri);
                if (destFolder == null || !destFolder.exists()) {
                    ModernToast.show(this, getString(R.string.scr_folder_unavailable));
                    return;
                }
                performCopyOrMoveInto(destFolder, copyMovePendingFiles, copyMovePendingIsMove);
                // Clear the pending op regardless of outcome.
                copyMovePendingFiles = Collections.emptyList();
                copyMovePendingIsMove = false;
            }
        );
    }

    private void setupListeners() {
        btnOpenFolder.setOnClickListener(v -> {
            // Открываем выбор папки
            folderPickerLauncher.launch(null);
        });

        // "Сменить папку" в верхнем баре — открывает тот же picker.
        if (btnChangeFolder != null) {
            btnChangeFolder.setOnClickListener(v -> folderPickerLauncher.launch(null));
        }

        // Кнопка "Назад" для навигации по папкам
        btnBack.setOnClickListener(v -> {
            navigateBack();
        });
    }
    
    private void navigateBack() {
        if (!folderStack.isEmpty()) {
            // Возвращаемся на уровень выше
            folderStack.pop();
            if (!folderStack.isEmpty()) {
                currentFolderNode = folderStack.peek();
                showFolderContents(currentFolderNode);
            } else {
                // Вернулись в корень проекта
                FolderNode root = rootFolderNode();
                if (root != null) {
                    currentFolderNode = root;
                    folderStack.push(root);
                    showFolderContents(root);
                }
            }
        }
    }

    private void openFolder(FileEntry folder) {
        // Build a FolderNode for the clicked subfolder. The tree URI stays the same;
        // the new docId is the child document id we already resolved during listing.
        Uri treeUri = currentFolderNode != null ? currentFolderNode.treeUri : projectFolderUri;
        String childDocId = docIdFromUri(treeUri, folder.uri);
        FolderNode node = new FolderNode(treeUri, childDocId, folder.name);
        currentFolderNode = node;
        folderStack.push(node);
        showFolderContents(node);
    }

    /** Build the FolderNode for the project root from the saved tree URI. */
    private FolderNode rootFolderNode() {
        Uri tree = projectFolderUri;
        if (tree == null) return null;
        String rootDocId;
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Exception ignored) {
            rootDocId = null;
        }
        String name = DocumentFile.fromTreeUri(this, tree) != null
                ? safeName(DocumentFile.fromTreeUri(this, tree)) : getString(R.string.scr_project);
        return new FolderNode(tree, rootDocId, name);
    }

    private void checkSavedFolder() {
        // Проверяем есть ли сохранённая папка
        String savedUriString = getSharedPreferences("multiaiide", MODE_PRIVATE)
            .getString("project_folder_uri", null);

        if (savedUriString != null) {
            try {
                Uri savedUri = Uri.parse(savedUriString);

                // Проверяем что у нас есть доступ к этой папке
                try {
                    getContentResolver().takePersistableUriPermission(
                        savedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    );
                } catch (Exception e) {
                    // Доступ потерян, игнорируем
                }

                // Проверяем что папка существует
                DocumentFile folder = DocumentFile.fromTreeUri(this, savedUri);
                if (folder != null && folder.exists() && folder.isDirectory()) {
                    projectFolderUri = savedUri;
                    new Thread(() -> app.multiai.mobile.utils.ProjectRulesManager
                            .ensureRulesDirectory(FilesActivity.this),
                            "multiai-rules-restore").start();
                    showProjectFiles(savedUri);
                    return;
                }
            } catch (Exception e) {
                // Ошибка парсинга URI, игнорируем
            }
        }

        // Если нет сохранённой папки или она недоступна - показываем empty state
        emptyState.setAlpha(0f);
        emptyState.setVisibility(View.VISIBLE);
        emptyState.animate().alpha(1f).setDuration(180).start();
    }

    private void showProjectFiles(Uri folderUri) {
        // Crossfade from the empty state into the files container so the
        // transition reads as one smooth hand-off instead of a hard swap.
        crossfadeView(emptyState, filesContainer);

        // Build the root FolderNode from the tree URI and descend into it.
        String rootDocId;
        try {
            rootDocId = DocumentsContract.getTreeDocumentId(folderUri);
        } catch (Exception ignored) {
            rootDocId = null;
        }
        DocumentFile rootFolder = DocumentFile.fromTreeUri(this, folderUri);
        String name = rootFolder != null ? safeName(rootFolder) : getString(R.string.scr_project);
        FolderNode node = new FolderNode(folderUri, rootDocId, name);
        currentFolder = rootFolder;
        currentFolderNode = node;
        folderStack.clear();
        folderStack.push(node);
        showFolderContents(node);
    }
    
    private void showFolderContents(FolderNode folder) {
        // Показываем путь
        textFolderPath.setText(buildDisplayPath(folder));

        // Показываем/скрываем кнопку "Назад"
        if (folderStack.size() > 1) {
            btnBack.setVisibility(View.VISIBLE);
        } else {
            btnBack.setVisibility(View.GONE);
        }

        if (textProjectSummary != null) {
            textProjectSummary.setText(getString(R.string.scr_loading));
        }

        // Создаём адаптер если его нет
        if (recyclerFiles.getAdapter() == null) {
            filesAdapter = new FilesAdapter();
            filesAdapter.setOnFileClickListener(new FilesAdapter.OnFileClickListener() {
                @Override
                public void onFileClick(FileEntry file) {
                    openFile(file);
                }

                @Override
                public void onFolderClick(FileEntry folderEntry) {
                    openFolder(folderEntry);
                }

                @Override
                public void onLongClick(FileEntry file) {
                    enterSelectionMode();
                    updateSelectionBar();
                }

                @Override
                public void onSelectionToggle(FileEntry file) {
                    if (filesAdapter.getSelectedCount() == 0) {
                        exitSelectionMode();
                    } else {
                        updateSelectionBar();
                    }
                }
            });
            recyclerFiles.setAdapter(filesAdapter);
        }

        // ── Cache fast-path ──
        // The cache key is the folder's document URI (tree + docId). mtime is read
        // via a single DocumentFile call only for the cache check; if it matches we
        // render the cached entries instantly and skip the background scan entirely.
        Uri folderUri = buildDocUri(folder);
        long currentMtime = readLastModified(folder);
        if (cachedFolder != null
                && folderUri.equals(cachedFolderUri)
                && currentMtime == cachedFolder.lastModified) {
            if (textProjectSummary != null) {
                textProjectSummary.setText(
                        getResources().getQuantityString(R.plurals.scr_folders_count, cachedFolder.folderCount, cachedFolder.folderCount)
                        + " · "
                        + getResources().getQuantityString(R.plurals.scr_files_count, cachedFolder.fileCount, cachedFolder.fileCount));
            }
            if (filesAdapter != null) {
                exitSelectionMode();
                filesAdapter.setFiles(cachedFolder.files);
            }
            return;
        }

        int requestId = ++folderLoadRequestId;
        final Uri treeUri = folder.treeUri;
        final String parentDocId = folder.docId;

        new Thread(() -> {
            // ── Batched SAF query ──
            // A single ContentResolver.query() over the child-documents URI returns
            // name + mime + size + last_modified + document_id for EVERY child in one
            // cursor. This is 10-50x faster than DocumentFile.listFiles() (which fires
            // one query per file) and is what makes large folders open near-instantly,
            // matching MT Manager's behaviour.
            List<FileEntry> entries = new ArrayList<>();
            boolean ok = queryChildEntries(treeUri, parentDocId, entries);

            if (!ok) {
                // Provider doesn't support the batched query — fall back to the slow
                // per-file DocumentFile listing so the folder still loads.
                fallbackListEntries(treeUri, parentDocId, entries);
            }

            int folderCount = 0;
            int fileCount = 0;
            for (FileEntry e : entries) {
                if (e.isDirectory) folderCount++;
                else fileCount++;
            }

            entries.sort((f1, f2) -> {
                if (f1.isDirectory && !f2.isDirectory) return -1;
                if (!f1.isDirectory && f2.isDirectory) return 1;
                return f1.name.compareToIgnoreCase(f2.name);
            });

            final long mtime = readLastModified(folder);
            final int folders = folderCount;
            final int files = fileCount;
            final List<FileEntry> result = entries;

            runOnUiThread(() -> {
                if (requestId != folderLoadRequestId) {
                    return;
                }

                cachedFolderUri = folderUri;
                cachedFolder = new FolderCache(result, folders, files, mtime);

                if (textProjectSummary != null) {
                    textProjectSummary.setText(
                            getResources().getQuantityString(R.plurals.scr_folders_count, folders, folders)
                            + " · "
                            + getResources().getQuantityString(R.plurals.scr_files_count, files, files));
                }

                RecyclerView.Adapter<?> adapter = recyclerFiles.getAdapter();
                if (adapter instanceof FilesAdapter) {
                    exitSelectionMode();
                    ((FilesAdapter) adapter).setFiles(result);
                }
            });
        }).start();
    }

    /** Drop the cached folder listing so the next showFolderContents() re-scans. */
    private void invalidateCache() {
        cachedFolder = null;
        cachedFolderUri = null;
    }

    private String buildDisplayPath(FolderNode folder) {
        if (folder == null) {
            return getString(R.string.scr_project);
        }
        if (folderStack.size() <= 1) {
            return !folder.name.isEmpty() ? folder.name : getString(R.string.scr_project);
        }

        StringBuilder path = new StringBuilder();
        for (int i = 0; i < folderStack.size(); i++) {
            FolderNode item = folderStack.get(i);
            String part = item != null ? item.name : null;
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (path.length() > 0) {
                path.append("/");
            }
            path.append(part);
        }

        if (path.length() == 0) {
            return !folder.name.isEmpty() ? folder.name : getString(R.string.scr_project);
        }
        return path.toString();
    }

    private void openFile(FileEntry file) {
        if (file == null || file.uri == null) {
            ModernToast.show(this, getString(R.string.scr_file_not_found));
            return;
        }

        Intent editorIntent = new Intent(this, EditorActivity.class);
        editorIntent.putExtra("file_uri", file.uri.toString());
        editorIntent.putExtra("file_name", file.name);
        editorIntent.putExtra("file_path", buildDisplayPath(currentFolderNode) + "/" + file.name);
        editorIntent.putExtra("project_folder_uri", projectFolderUri != null ? projectFolderUri.toString() : "");
        startActivity(editorIntent);
    }

    // ── Batched folder-listing helpers ──

    /**
     * The core speed optimization: ask the SAF provider for ALL child documents of
     * {@code parentDocId} (or the tree root when null) in ONE Cursor, resolving
     * display name, mime type, size and document id in a single round-trip.
     * Returns false if the provider rejects the query so the caller can fall back.
     */
    @android.annotation.SuppressLint("InlinedApi")
    private boolean queryChildEntries(Uri treeUri, String parentDocId, List<FileEntry> out) {
        if (treeUri == null) return false;
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
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE
                    },
                    null, null, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                String childId = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.getLong(3);
                if (childId == null) continue;
                boolean isDir = mime != null && mime.equals(DocumentsContract.Document.MIME_TYPE_DIR);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                out.add(new FileEntry(childUri, name, mime, size, isDir));
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

    /** Slow-path fallback using DocumentFile.listFiles() when the batch query isn't supported. */
    private void fallbackListEntries(Uri treeUri, String parentDocId, List<FileEntry> out) {
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
            if (child == null) continue;
            out.add(new FileEntry(child.getUri(), child.getName(), child.getType(),
                    child.length(), child.isDirectory()));
        }
    }

    /** Build the document URI for a FolderNode (used as the cache key). */
    private static Uri buildDocUri(FolderNode node) {
        if (node == null || node.treeUri == null || node.docId == null) {
            return node != null ? node.treeUri : null;
        }
        try {
            return DocumentsContract.buildDocumentUriUsingTree(node.treeUri, node.docId);
        } catch (Exception ignored) {
            return node.treeUri;
        }
    }

    /** Read the folder's last-modified time via a single DocumentFile lookup (cache check only). */
    private long readLastModified(FolderNode node) {
        Uri docUri = buildDocUri(node);
        if (docUri == null) return 0L;
        DocumentFile df = DocumentFile.fromSingleUri(this, docUri);
        try {
            return df != null ? df.lastModified() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    /** Null-safe name lookup for a DocumentFile. */
    private static String safeName(DocumentFile df) {
        try {
            String n = df != null ? df.getName() : null;
            return n != null ? n : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * Extract the document id encoded inside a child document URI built via
     * {@link DocumentsContract#buildDocumentUriUsingTree}. Used when descending
     * into a subfolder so we can list ITS children next.
     */
    private static String docIdFromUri(Uri treeUri, Uri docUri) {
        try {
            return DocumentsContract.getDocumentId(docUri);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ── Selection mode ──

    private void enterSelectionMode() {
        if (filesAdapter == null) return;
        filesAdapter.setSelectionMode(true);
        if (selectionBar != null && selectionBar.getVisibility() != View.VISIBLE) {
            selectionBar.setVisibility(View.VISIBLE);
            selectionBar.setTranslationY(dp(80));
            selectionBar.setAlpha(0f);
            selectionBar.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(220)
                    .start();
        }
    }

    private void exitSelectionMode() {
        if (filesAdapter != null && filesAdapter.isSelectionMode()) {
            filesAdapter.setSelectionMode(false);
        }
        if (selectionBar != null && selectionBar.getVisibility() == View.VISIBLE) {
            selectionBar.animate()
                    .translationY(dp(80))
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction(() -> selectionBar.setVisibility(View.GONE))
                    .start();
        }
    }

    private void updateSelectionBar() {
        if (selectionCountText == null || filesAdapter == null) return;
        int n = filesAdapter.getSelectedCount();
        selectionCountText.setText(getResources().getQuantityString(R.plurals.scr_n_selected, n, n));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (filesAdapter != null && filesAdapter.isSelectionMode()) {
            exitSelectionMode();
            return;
        }
        super.onBackPressed();
    }

    // ── Selection actions ──

    private void onDeleteSelected() {
        if (filesAdapter == null) return;
        List<FileEntry> selectedFiles = filesAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_nothing_selected));
            return;
        }
        exitSelectionMode();
        invalidateCache();

        new Thread(() -> {
            int deleted = 0;
            for (FileEntry e : selectedFiles) {
                if (e == null || e.uri == null) continue;
                try {
                    // Prefer the batched deleteDocument call (no per-file query).
                    if (DocumentsContract.deleteDocument(getContentResolver(), e.uri)) {
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // Fallback to DocumentFile.delete() for providers that don't honour the call.
                    try {
                        DocumentFile df = DocumentFile.fromSingleUri(this, e.uri);
                        if (df != null && df.delete()) {
                            deleted++;
                        }
                    } catch (Exception ignored2) {
                    }
                }
            }
            final int deletedCount = deleted;
            runOnUiThread(() -> {
                ModernToast.show(FilesActivity.this,
                        deletedCount > 0 ? getString(R.string.scr_deleted_count, deletedCount) : getString(R.string.scr_delete_failed));
                if (currentFolderNode != null) {
                    showFolderContents(currentFolderNode);
                }
            });
        }).start();
    }

    private void onMoveSelected() {
        startCopyOrMove(true);
    }

    private void onCopySelected() {
        startCopyOrMove(false);
    }

    /**
     * Stashes the pending operation (move vs copy) and the selected files, then
     * opens the destination FOLDER PICKER. The actual copy/move happens in
     * {@link #performCopyOrMoveInto} after the picker returns.
     */
    private void startCopyOrMove(boolean isMove) {
        if (filesAdapter == null) return;
        List<FileEntry> selectedFiles = filesAdapter.getSelectedFiles();
        if (selectedFiles.isEmpty()) {
            ModernToast.show(this, getString(R.string.scr_nothing_selected));
            return;
        }
        copyMovePendingIsMove = isMove;
        // Snapshot so adapter mutations don't race with the picker.
        copyMovePendingFiles = new ArrayList<>(selectedFiles);
        try {
            copyMoveLauncher.launch(null);
        } catch (Exception e) {
            ModernToast.show(this, getString(R.string.scr_folder_picker_failed));
            copyMovePendingFiles = Collections.emptyList();
            copyMovePendingIsMove = false;
        }
    }

    /**
     * Performs the copy (or copy-then-delete-source for Move) of the given files
     * into {@code destFolder}. SAF has no atomic move, so Move = Copy + delete.
     * Runs on a background thread, shows a progress dialog with per-file updates,
     * then a ModernToast naming the destination, invalidates the folder cache,
     * refreshes the current list, and exits selection mode.
     */
    private void performCopyOrMoveInto(DocumentFile destFolder,
                                       List<FileEntry> workList, boolean isMove) {
        // Leave selection mode while the work runs.
        exitSelectionMode();
        invalidateCache();

        final String destName = destFolder.getName() != null ? destFolder.getName() : getString(R.string.scr_folder_word);
        final String baseLabel = isMove ? getString(R.string.scr_moving) : getString(R.string.scr_copying);
        final int total = workList.size();
        // Multi-file → determinate progress (max = file count).
        // Single file → indeterminate (we don't track bytes for simplicity).
        final boolean determinate = total > 1;

        runOnUiThread(() -> showProgressDialog(baseLabel, determinate ? total : 0));

        new Thread(() -> {
            int ok = 0;
            for (int i = 0; i < workList.size(); i++) {
                FileEntry entry = workList.get(i);
                DocumentFile src = entry != null ? entry.asDocumentFile(FilesActivity.this) : null;
                try {
                    boolean copied = src != null && copyDocumentFile(src, destFolder);
                    if (copied && (!isMove || src.delete())) {
                        ok++;
                    }
                } catch (Exception ignored) {
                }
                final int done = i + 1;
                if (determinate) {
                    runOnUiThread(() -> updateProgressDialog(done, total));
                }
            }
            final int count = ok;
            runOnUiThread(() -> {
                dismissProgressDialog();
                ModernToast.show(FilesActivity.this,
                        count > 0
                                ? (isMove ? getString(R.string.scr_moved_to, destName) : getString(R.string.scr_copied_to, destName))
                                : (isMove ? getString(R.string.scr_move_failed) : getString(R.string.scr_copy_failed)));
                if (currentFolderNode != null) {
                    showFolderContents(currentFolderNode);
                }
            });
        }).start();
    }

    /**
     * Builds (if needed) and shows the non-cancelable copy/move progress dialog.
     * When {@code max} > 0 the bar is determinate (per-file); otherwise indeterminate.
     */
    private void showProgressDialog(String label, int max) {
        if (isFinishing() || isDestroyed()) return;
        if (progressDialog == null) {
            buildProgressDialog();
        }
        progressLabel.setText(label + "...");
        if (max > 0) {
            progressBar.setIndeterminate(false);
            progressBar.setMax(max);
            progressBar.setProgress(0);
        } else {
            progressBar.setIndeterminate(true);
        }
        if (!progressDialog.isShowing()) {
            try {
                progressDialog.show();
            } catch (Exception ignored) {
            }
        }
    }

    /** Updates the determinate bar + label, e.g. "Копирование... (2/5)". */
    private void updateProgressDialog(int done, int total) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        if (!progressBar.isIndeterminate()) {
            progressBar.setProgress(done);
        }
        // Pull the current verb from the existing label (everything before "...").
        String current = progressLabel.getText().toString();
        String verb = current.endsWith("...") ? current.substring(0, current.length() - 3) : current;
        // Strip any previous "(n/m)" suffix in case update fires more than once.
        int paren = verb.indexOf(" (");
        if (paren > 0) verb = verb.substring(0, paren);
        progressLabel.setText(verb + "... (" + done + "/" + total + ")");
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Lazily builds the progress dialog once: a transparent-themed Dialog whose
     * window hosts a rounded card with an indeterminate ProgressBar + a label.
     * Stored in {@link #progressDialog} and reused across operations.
     */
    private void buildProgressDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCanceledOnTouchOutside(false);
        d.setCancelable(false);
        d.setOnKeyListener((dialog, keyCode, event) -> {
            // Block BACK from dismissing the dialog — the user must wait.
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                return true;
            }
            return false;
        });

        int bg = ContextCompat.getColor(this, R.color.surface_card);
        int textPrimary = ContextCompat.getColor(this, R.color.text_primary);
        int accent = app.multiai.mobile.utils.AccentColor.resolve(this);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) dp(24);
        card.setPadding(pad, pad, pad, pad);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(bg);
        cardBg.setCornerRadius(dp(20));
        card.setBackground(cardBg);
        card.setElevation(dp(8));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(6));
        progressBar.setLayoutParams(pbLp);
        progressBar.setIndeterminate(true);
        progressBar.setProgressTintList(ColorStateList.valueOf(accent));
        progressBar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        card.addView(progressBar);

        progressLabel = new TextView(this);
        progressLabel.setText(getString(R.string.scr_copying) + "...");
        progressLabel.setTextColor(textPrimary);
        progressLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        progressLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = (int) dp(16);
        progressLabel.setLayoutParams(labelLp);
        card.addView(progressLabel);

        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout((int) dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        progressDialog = d;
    }

    /**
     * Copies a DocumentFile (file or directory) into {@code destFolder}. Handles name
     * collisions by appending "(копия)" / "(копия N)" before the extension. Returns
     * true if the copy completed without exceptions so the caller can decide to delete
     * the source for a Move operation.
     */
    private boolean copyDocumentFile(DocumentFile src, DocumentFile destFolder) {
        if (src == null || !src.exists() || destFolder == null) return false;

        // Защита от копирования папки в себя/потомка — бесконечная рекурсия.
        String srcUri = src.getUri().toString();
        String destUri = destFolder.getUri().toString();
        if (srcUri.equals(destUri)) return false;
        if (src.isDirectory() && destUri.startsWith(srcUri + "/")) return false;

        if (src.isDirectory()) {
            String dirName = uniqueNameFor(destFolder, src.getName(), null, true);
            DocumentFile newDir = destFolder.createDirectory(dirName);
            if (newDir == null) return false;
            boolean copied = true;
            DocumentFile[] children = src.listFiles();
            if (children == null) {
                copied = false;
            } else {
                for (DocumentFile child : children) {
                    if (!copyDocumentFile(child, newDir)) copied = false;
                }
            }
            if (!copied) newDir.delete();
            return copied;
        }

        String name = src.getName();
        String mime = src.getType();
        String targetName = uniqueNameFor(destFolder, name, mime, false);
        DocumentFile target = destFolder.createFile(mime != null ? mime : "application/octet-stream",
                targetName);
        if (target == null) return false;

        try (InputStream in = getContentResolver().openInputStream(src.getUri());
             OutputStream out = getContentResolver().openOutputStream(target.getUri(), "wt")) {
            if (in == null || out == null) {
                target.delete();
                return false;
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            return true;
        } catch (Exception e) {
            target.delete();
            return false;
        }
    }

    /**
     * Computes a non-colliding name inside {@code folder}. For a missing extension the
     * extension part is empty. Appends " (копия)" first, then " (копия 2)", " (копия 3)" etc.
     */
    private String uniqueNameFor(DocumentFile folder, String rawName, String mime, boolean isDirectory) {
        String name = rawName != null && !rawName.isEmpty() ? rawName
                : (isDirectory ? getString(R.string.scr_folder_default) : getString(R.string.scr_file_default));

        String base = name;
        String ext = "";
        if (!isDirectory) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1) {
                base = name.substring(0, dot);
                ext = name.substring(dot);
            }
        }

        String candidate = name;
        if (!folderHasChildNamed(folder, candidate)) {
            return candidate;
        }
        candidate = base + " (" + getString(R.string.scr_copy_word) + ")" + ext;
        if (!folderHasChildNamed(folder, candidate)) {
            return candidate;
        }
        int n = 2;
        while (true) {
            candidate = base + " (" + getString(R.string.scr_copy_word) + " " + n + ")" + ext;
            if (!folderHasChildNamed(folder, candidate)) {
                return candidate;
            }
            n++;
            if (n > 999) {
                return base + " (" + getString(R.string.scr_copy_word) + " " + System.currentTimeMillis() + ")" + ext;
            }
        }
    }

    /** Returns true if {@code folder} contains an immediate child with the given name. */
    private boolean folderHasChildNamed(DocumentFile folder, String name) {
        if (folder == null || name == null) return false;
        try {
            DocumentFile[] children = folder.listFiles();
            if (children == null) return false;
            for (DocumentFile c : children) {
                String childName = c.getName();
                if (childName != null && childName.equals(name)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
