package app.multiai.mobile.tools;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative-cancellation primitive shared by Work Mode tool executions.
 *
 * <p>Pure Java (no Android imports) so the cancellation logic is unit-testable
 * on the JVM. One token represents one in-flight {@code WorkModeTools.execute}
 * operation (or one approval wait). Long-running loops call {@link #check()}
 * (or {@link #throwIfCancelled()}) at regular intervals; process-backed steps
 * register a {@link Listener} so cancelling also kills the child process.
 *
 * <p>Usage from ChatActivity (integration contract):
 * <pre>
 *   OperationCancelToken token = new OperationCancelToken();
 *   workModeTools.execute(name, args, token);          // cancellable overload
 *   // user hit "stop":
 *   token.cancel("user requested");                    // unblocks everything
 * </pre>
 *
 * The token is one-shot: once cancelled it stays cancelled. Cancelling is
 * idempotent and thread-safe.
 */
public final class OperationCancelToken {

    /** Reason reported by {@link #check()} / carried in the exception. */
    private volatile String reason;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Notified (on the cancelling thread) when the token is cancelled. */
    public interface Listener {
        void onCancelled();
    }

    /** Thrown by {@link #throwIfCancelled()} — caught by WorkModeTools.execute. */
    public static final class CancelledException extends RuntimeException {
        public CancelledException(String reason) {
            super(reason == null || reason.isEmpty() ? "operation cancelled" : reason);
        }
    }

    public OperationCancelToken() {
        this(null);
    }

    public OperationCancelToken(String reason) {
        this.reason = reason;
    }

    /** Cancel the operation. Idempotent; listeners run at most once. */
    public boolean cancel(String why) {
        if (!cancelled.compareAndSet(false, true)) return false;
        if (why != null && !why.trim().isEmpty()) reason = why.trim();
        for (Listener listener : listeners) {
            try {
                listener.onCancelled();
            } catch (Exception ignored) {
                // A misbehaving listener must never break cancellation.
            }
        }
        return true;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String reason() {
        return reason;
    }

    /**
     * Non-throwing checkpoint: returns true when the operation must stop.
     * Long loops should call this every iteration or every bounded batch.
     */
    public boolean check() {
        return cancelled.get();
    }

    /** Throwing checkpoint for recursive walks (find/grep/list_dir). */
    public void throwIfCancelled() {
        if (cancelled.get()) throw new CancelledException(reason);
    }

    /**
     * Wait until cancelled, the deadline passes, or the thread is interrupted.
     * Implemented as short poll slices so no lock is held that could delay
     * {@link #cancel(String)} from the UI thread.
     *
     * @param timeoutSec maximum wait in seconds; values &lt;= 0 return immediately
     * @return true if cancelled, false on timeout
     */
    public boolean awaitCancellation(long timeoutSec) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(0, timeoutSec));
        while (!cancelled.get()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) return false;
            long sleepNanos = Math.min(remainingNanos, TimeUnit.MILLISECONDS.toNanos(50));
            try {
                TimeUnit.NANOSECONDS.sleep(sleepNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** Register a listener notified on cancel; runs immediately if already cancelled. */
    public void addListener(Listener listener) {
        if (listener == null) return;
        listeners.addIfAbsent(listener);
        if (cancelled.get()) {
            try {
                listener.onCancelled();
            } catch (Exception ignored) {
            }
        }
    }

    public void removeListener(Listener listener) {
        if (listener == null) return;
        listeners.remove(listener);
    }

    @Override
    public String toString() {
        return "OperationCancelToken{cancelled=" + cancelled.get()
                + ", reason=" + reason + "}";
    }
}
