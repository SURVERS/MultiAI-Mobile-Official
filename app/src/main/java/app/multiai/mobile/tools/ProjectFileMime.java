package app.multiai.mobile.tools;

final class ProjectFileMime {
    private ProjectFileMime() {}

    static String forExtension(String extension, String detectedMime) {
        // Unknown source formats must not acquire the provider's canonical .txt suffix.
        if (detectedMime == null || ("text/plain".equals(detectedMime) && !"txt".equals(extension))) {
            return "application/octet-stream";
        }
        return detectedMime;
    }
}
