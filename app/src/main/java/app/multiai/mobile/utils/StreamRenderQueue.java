package app.multiai.mobile.utils;

import android.os.Handler;
import android.os.Looper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread, latest-value rendering; model updates remain synchronous. */
public final class StreamRenderQueue {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Object, Runnable> pending = new LinkedHashMap<>();
    private boolean scheduled;

    public void submit(Object key, Runnable render) {
        pending.put(key, render);
        if (scheduled) return;
        scheduled = true;
        handler.postDelayed(() -> {
            scheduled = false;
            Runnable[] work = pending.values().toArray(new Runnable[0]);
            pending.clear();
            for (Runnable task : work) task.run();
        }, 50L);
    }
}
