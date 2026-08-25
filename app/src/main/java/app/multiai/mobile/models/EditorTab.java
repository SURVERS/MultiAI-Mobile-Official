package app.multiai.mobile.models;

import android.net.Uri;

public class EditorTab {
    private Uri uri;
    private String name;
    private String path;
    private String extension;
    private final boolean textFile;
    private String content = "";
    private String originalContent = "";
    private boolean dirty = false;
    private boolean readFailed = false;

    public EditorTab(Uri uri, String name, String path, String extension, boolean textFile) {
        this.uri = uri;
        this.name = name;
        this.path = path;
        this.extension = extension;
        this.textFile = textFile;
    }

    public Uri getUri() {
        return uri;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getExtension() {
        return extension;
    }

    public boolean isTextFile() {
        return textFile;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content != null ? content : "";
    }

    public String getOriginalContent() {
        return originalContent;
    }

    public void setOriginalContent(String originalContent) {
        this.originalContent = originalContent != null ? originalContent : "";
    }

    public boolean isDirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isReadFailed() {
        return readFailed;
    }

    public void setReadFailed(boolean readFailed) {
        this.readFailed = readFailed;
    }

    /**
     * Retarget this tab onto a different file in the same folder — used by the
     * editor audio player's prev/next controls to move to the neighbouring
     * audio file without creating a brand-new tab. Resets text/content state
     * (the caller re-loads binary previews as needed).
     */
    public void replaceUriName(Uri newUri, String newName, String newExtension) {
        this.uri = newUri;
        this.name = newName;
        this.path = newName;
        this.extension = newExtension != null ? newExtension : "";
        this.content = "";
        this.originalContent = "";
        this.dirty = false;
    }
}
