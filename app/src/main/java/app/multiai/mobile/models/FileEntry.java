package app.multiai.mobile.models;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;

/**
 * Lightweight, fully-resolved representation of one folder entry, populated from
 * a single batched {@code DocumentsContract} query. Holding the name / mime /
 * size / directory flag inline avoids the per-row ContentResolver queries that
 * {@link DocumentFile#getName()}, {@link DocumentFile#isDirectory()} and
 * {@link DocumentFile#length()} would otherwise trigger during RecyclerView
 * binding (the main reason large folders render slowly).
 *
 * <p>The backing {@link DocumentFile} is created lazily on demand (e.g. when the
 * user actually opens / copies / deletes the entry), so listing performance
 * isn't tied to DocumentFile construction.</p>
 */
public class FileEntry {
    public final Uri uri;
    public final String name;
    public final String mimeType;
    public final long size;
    public final boolean isDirectory;

    public FileEntry(Uri uri, String name, String mimeType, long size, boolean isDirectory) {
        this.uri = uri;
        this.name = name != null ? name : "";
        this.mimeType = mimeType;
        this.size = size;
        this.isDirectory = isDirectory;
    }

    /** Lazily wrap the entry in a DocumentFile for mutating operations. */
    public DocumentFile asDocumentFile(android.content.Context context) {
        if (uri == null) return null;
        return DocumentFile.fromSingleUri(context, uri);
    }
}
