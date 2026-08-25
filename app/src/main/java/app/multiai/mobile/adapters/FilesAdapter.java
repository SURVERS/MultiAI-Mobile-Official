package app.multiai.mobile.adapters;

import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import app.multiai.mobile.R;
import app.multiai.mobile.models.FileEntry;
import app.multiai.mobile.utils.AccentColor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Adapter for the files list. Operates on fully-resolved {@link FileEntry}
 * objects so binding never triggers a ContentResolver query — the data (name,
 * mime, size, isDirectory) is already in memory. This is what keeps scrolling
 * and folder-opening instant even with hundreds of files.
 */
public class FilesAdapter extends RecyclerView.Adapter<FilesAdapter.FileViewHolder> {

    private List<FileEntry> files = new ArrayList<>();
    private OnFileClickListener listener;
    private static final long KILOBYTE = 1024L;
    private static final long MEGABYTE = KILOBYTE * 1024L;
    private static final long GIGABYTE = MEGABYTE * 1024L;

    /** Selection state — keyed by entry Uri string so it survives list reorders. */
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selectionMode = false;

    public interface OnFileClickListener {
        void onFileClick(FileEntry file);
        void onFolderClick(FileEntry folder);
        /** Called when the user long-presses a row; should enter selection mode and select it. */
        void onLongClick(FileEntry file);
        /** Called when a row is tapped while already in selection mode. */
        void onSelectionToggle(FileEntry file);
    }

    public void setOnFileClickListener(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(List<FileEntry> files) {
        this.files = files;
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean selectionMode) {
        this.selectionMode = selectionMode;
        if (!selectionMode) {
            selected.clear();
        }
        notifyDataSetChanged();
    }

    public boolean isSelected(FileEntry file) {
        return file != null && selected.contains(keyOf(file));
    }

    public void setSelected(FileEntry file, boolean select) {
        if (file == null) return;
        String key = keyOf(file);
        boolean changed = select ? selected.add(key) : selected.remove(key);
        if (changed) {
            notifyDataSetChanged();
        }
    }

    /** Returns the currently selected entries in the order they appear in the list. */
    public List<FileEntry> getSelectedFiles() {
        List<FileEntry> out = new ArrayList<>();
        for (FileEntry f : files) {
            if (selected.contains(keyOf(f))) {
                out.add(f);
            }
        }
        return out;
    }

    public int getSelectedCount() {
        return selected.size();
    }

    public void clearSelection() {
        if (!selected.isEmpty()) {
            selected.clear();
            notifyDataSetChanged();
        }
    }

    private static String keyOf(FileEntry file) {
        return file.uri != null ? file.uri.toString() : file.name;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileEntry file = files.get(position);
        holder.bind(file);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    class FileViewHolder extends RecyclerView.ViewHolder {
        private ImageView iconFile;
        private ImageView iconAction;
        private TextView textFileName;
        private TextView textFileMeta;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            iconFile = itemView.findViewById(R.id.iconFile);
            iconAction = itemView.findViewById(R.id.iconAction);
            textFileName = itemView.findViewById(R.id.textFileName);
            textFileMeta = itemView.findViewById(R.id.textFileMeta);
        }

        void bind(FileEntry file) {
            String name = !file.name.isEmpty() ? file.name
                    : itemView.getContext().getString(R.string.kit_file_unnamed);
            textFileName.setText(name);

            final boolean sel = isSelected(file);
            applySelectionVisual(sel);

            if (file.isDirectory) {
                iconFile.setImageResource(R.drawable.ic_folder_outline);
                iconFile.setColorFilter(mutedIconColor());
                if (!sel) {
                    iconAction.setImageResource(R.drawable.ic_open_in_new);
                    iconAction.setColorFilter(mutedIconColor());
                }
                textFileMeta.setText(R.string.kit_file_folder);
            } else {
                String ext = getExtension(name);
                bindFileIcon(name);
                if (!sel) {
                    iconAction.setImageResource(getActionIconForExtension(ext));
                    iconAction.setColorFilter(mutedIconColor());
                }
                textFileMeta.setText(buildMeta(file, ext));
            }

            itemView.setOnClickListener(v -> {
                if (listener == null) return;
                if (selectionMode) {
                    setSelected(file, !isSelected(file));
                    listener.onSelectionToggle(file);
                } else if (file.isDirectory) {
                    listener.onFolderClick(file);
                } else {
                    listener.onFileClick(file);
                }
            });

            itemView.setOnLongClickListener(v -> {
                if (listener == null) return false;
                selectionMode = true;
                setSelected(file, true);
                listener.onLongClick(file);
                return true;
            });
        }

        /** Tints the row + swaps the trailing icon for a checkmark when selected. */
        private void applySelectionVisual(boolean selected) {
            Drawable bg = itemView.getBackground();
            if (bg == null) {
                bg = ContextCompat.getDrawable(itemView.getContext(), R.drawable.file_row_background);
                itemView.setBackground(bg);
            }
            int tintColor;
            if (selected) {
                tintColor = ContextCompat.getColor(itemView.getContext(), R.color.surface_tonal);
                iconAction.setImageResource(R.drawable.ic_check_circle);
                iconAction.setColorFilter(
                        AccentColor.resolve(itemView.getContext()),
                        PorterDuff.Mode.SRC_IN);
            } else {
                tintColor = ContextCompat.getColor(itemView.getContext(), R.color.surface_card);
            }
            bg.setColorFilter(tintColor, PorterDuff.Mode.SRC_ATOP);
            bg.invalidateSelf();
        }

        /**
         * Иконка файла по расширению — те же цветные vscode-иконки, что и в тулах
         * ассистента (FileIconDrawable). Без colorFilter: SVG несёт свои цвета.
         */
        private void bindFileIcon(String name) {
            String ext = getExtension(name);
            // Медиа/apk/pdf нет в vscode-наборе — оставляем прежние аккуратные mdi-иконки.
            if (isImageExt(ext) || isAudioExt(ext) || isVideoExt(ext)
                    || "pdf".equals(ext) || "apk".equals(ext)) {
                if ("pdf".equals(ext)) {
                    iconFile.setImageResource(R.drawable.ic_file_pdf_box);
                    iconFile.setColorFilter(resolveColor(R.color.syntax_literal, 0xFFFB7185));
                } else if (isImageExt(ext)) {
                    iconFile.setImageResource(R.drawable.ic_file_image_outline);
                    iconFile.setColorFilter(resolveColor(R.color.syntax_keyword, 0xFF38BDF8));
                } else if (isAudioExt(ext)) {
                    iconFile.setImageResource(R.drawable.ic_music_note_outline);
                    iconFile.setColorFilter(mutedIconColor());
                } else if (isVideoExt(ext)) {
                    iconFile.setImageResource(R.drawable.ic_file_video_outline);
                    iconFile.setColorFilter(mutedIconColor());
                } else {
                    iconFile.setImageResource(R.drawable.ic_android);
                    iconFile.setColorFilter(primaryIconColor());
                }
                return;
            }
            // Всё остальное (код, конфиги, текст) — цветные vscode-иконки по расширению.
            iconFile.clearColorFilter();
            iconFile.setImageDrawable(app.multiai.mobile.views.FileIconDrawable.forName(
                    itemView.getContext(), name));
        }

        /** Primary icon tint (adapts to light/dark). */
        private int primaryIconColor() {
            return resolveColor(R.color.icon, 0xFFCCCCCC);
        }

        /** Muted icon tint for secondary actions and generic file icons. */
        private int mutedIconColor() {
            return resolveColor(R.color.icon_muted, 0xFFCCCCCC);
        }

        private int resolveColor(int resId, int fallback) {
            try {
                return ContextCompat.getColor(itemView.getContext(), resId);
            } catch (Exception ignored) {
                return fallback;
            }
        }

        private int getActionIconForExtension(String ext) {
            if (isAudioExt(ext) || isVideoExt(ext)) {
                return R.drawable.ic_play_circle_outline;
            }
            if ("apk".equals(ext)) {
                return R.drawable.ic_android;
            }
            return R.drawable.ic_open_in_new;
        }

        private String buildMeta(FileEntry file, String ext) {
            String extLabel = ext.isEmpty()
                    ? itemView.getContext().getString(R.string.kit_file_file) : ext.toUpperCase();
            long size = file.size;
            if (size <= 0) {
                return extLabel;
            }
            return extLabel + " · " + formatFileSize(size);
        }

        private String formatFileSize(long bytes) {
            if (bytes < KILOBYTE) {
                return bytes + " B";
            }
            if (bytes < MEGABYTE) {
                return String.format(java.util.Locale.US, "%.1f KB", bytes / (double) KILOBYTE);
            }
            if (bytes < GIGABYTE) {
                return String.format(java.util.Locale.US, "%.1f MB", bytes / (double) MEGABYTE);
            }
            return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) GIGABYTE);
        }

        private boolean isImageExt(String ext) {
            return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext) ||
                "gif".equals(ext) || "webp".equals(ext) || "bmp".equals(ext) || "svg".equals(ext);
        }

        private boolean isAudioExt(String ext) {
            return "mp3".equals(ext) || "wav".equals(ext) || "ogg".equals(ext) || "m4a".equals(ext)
                    || "aac".equals(ext) || "flac".equals(ext) || "opus".equals(ext);
        }

        private boolean isVideoExt(String ext) {
            return "mp4".equals(ext) || "mkv".equals(ext) || "mov".equals(ext) || "webm".equals(ext) || "avi".equals(ext);
        }

        private boolean isCodeExt(String ext) {
            return "java".equals(ext) || "kt".equals(ext) || "kts".equals(ext) || "xml".equals(ext) ||
                "js".equals(ext) || "ts".equals(ext) || "tsx".equals(ext) || "jsx".equals(ext) ||
                "py".equals(ext) || "json".equals(ext) || "md".equals(ext) || "gradle".equals(ext) ||
                "txt".equals(ext) || "sh".equals(ext) || "yml".equals(ext) || "yaml".equals(ext) ||
                "html".equals(ext) || "css".equals(ext) || "c".equals(ext) || "cpp".equals(ext) ||
                "h".equals(ext) || "hpp".equals(ext) || "go".equals(ext) || "rs".equals(ext) ||
                "php".equals(ext) || "rb".equals(ext) || "swift".equals(ext);
        }

        private String getExtension(String name) {
            int dot = name.lastIndexOf('.');
            if (dot < 0 || dot == name.length() - 1) {
                return "";
            }
            return name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        }
    }
}
