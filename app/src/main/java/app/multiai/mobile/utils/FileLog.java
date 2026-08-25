package app.multiai.mobile.utils;

import android.util.Log;

import app.multiai.mobile.BuildConfig;

/**
 * Privacy-safe debug event sink.
 *
 * <p>Events are never persisted. Only short operation codes are accepted, so accidental
 * payloads, commands, paths, tokens, and provider responses are redacted before Logcat.</p>
 */
public final class FileLog {
    private static final String SAFE_TAG = "MultiAI";
    private static final String SAFE_EVENT = "event_redacted";

    private FileLog() {}

    public static void d(String tag, String event) {
        if (!BuildConfig.DEBUG) return;
        String safeTag = tag != null && tag.matches("[A-Za-z0-9_.-]{1,23}")
                ? tag : SAFE_TAG;
        String safeEvent = event != null && event.matches("[A-Za-z0-9_.:-]{1,80}")
                ? event : SAFE_EVENT;
        Log.d(safeTag, safeEvent);
    }
}
