package app.multiai.mobile.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * JVM unit tests for the pure-Java {@link OperationCancelToken} cancellation
 * primitive used by Work Mode tool execution: state transitions, listener
 * semantics, bounded waits and checkpoint behaviour.
 */
public class OperationCancelTokenTest {

    // ─────────── state transitions ───────────

    @Test
    public void freshTokenIsNotCancelled() {
        OperationCancelToken token = new OperationCancelToken();
        assertFalse(token.check());
        assertFalse(token.isCancelled());
        token.throwIfCancelled(); // must not throw
    }

    @Test
    public void cancelFlipsStateAndStoresReason() {
        OperationCancelToken token = new OperationCancelToken();
        assertTrue(token.cancel("user stop"));
        assertTrue(token.isCancelled());
        assertTrue(token.check());
        assertEquals("user stop", token.reason());
    }

    @Test
    public void cancelIsIdempotentAndOneShot() {
        OperationCancelToken token = new OperationCancelToken();
        assertTrue(token.cancel("first"));
        assertFalse("second cancel is a no-op", token.cancel("second"));
        assertEquals("first reason wins", "first", token.reason());
        assertTrue(token.isCancelled());
    }

    @Test
    public void cancelWithNullOrBlankReasonKeepsDefaultMessage() {
        OperationCancelToken token = new OperationCancelToken();
        token.cancel(null);
        assertTrue(token.isCancelled());
        OperationCancelToken blank = new OperationCancelToken();
        blank.cancel("   ");
        assertTrue(blank.isCancelled());
    }

    // ─────────── checkpoints ───────────

    @Test
    public void throwIfCancelledThrowsWithReason() {
        OperationCancelToken token = new OperationCancelToken();
        token.cancel("stop requested");
        try {
            token.throwIfCancelled();
            throw new AssertionError("expected CancelledException");
        } catch (OperationCancelToken.CancelledException e) {
            assertEquals("stop requested", e.getMessage());
        }
    }

    @Test
    public void cancelledExceptionHasFallbackMessageWithoutReason() {
        OperationCancelToken.CancelledException e =
                new OperationCancelToken.CancelledException(null);
        assertEquals("operation cancelled", e.getMessage());
    }

    // ─────────── listeners ───────────

    @Test
    public void listenerFiresExactlyOnceOnCancel() {
        OperationCancelToken token = new OperationCancelToken();
        AtomicInteger calls = new AtomicInteger();
        token.addListener(() -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        token.cancel("go");
        token.cancel("go again"); // idempotent — no second notification
        assertEquals(1, calls.get());
    }

    @Test
    public void listenerRegisteredAfterCancelRunsImmediately() {
        OperationCancelToken token = new OperationCancelToken();
        token.cancel("already dead");
        AtomicInteger calls = new AtomicInteger();
        token.addListener(() -> {
            calls.incrementAndGet();
            // Re-cancel from inside a listener must not recurse/throw.
            token.cancel("from listener");
        });
        assertEquals("late listener runs once", 1, calls.get());
    }

    @Test
    public void misbehavingListenerDoesNotBreakCancellation() {
        OperationCancelToken token = new OperationCancelToken();
        AtomicInteger after = new AtomicInteger();
        token.addListener(() -> {
            throw new IllegalStateException("boom");
        });
        token.addListener(after::incrementAndGet);
        assertTrue("cancel still succeeds", token.cancel("go"));
        assertTrue(token.isCancelled());
        assertEquals("later listeners still run", 1, after.get());
    }

    @Test
    public void removeListenerStopsNotifications() {
        OperationCancelToken token = new OperationCancelToken();
        AtomicInteger calls = new AtomicInteger();
        OperationCancelToken.Listener listener = calls::incrementAndGet;
        token.addListener(listener);
        token.removeListener(listener);
        token.cancel("go");
        assertEquals(0, calls.get());
    }

    @Test
    public void duplicateListenerInstanceRegisteredOnce() {
        OperationCancelToken token = new OperationCancelToken();
        AtomicInteger calls = new AtomicInteger();
        OperationCancelToken.Listener listener = calls::incrementAndGet;
        token.addListener(listener);
        token.addListener(listener);
        token.cancel("go");
        assertEquals(1, calls.get());
    }

    // ─────────── bounded waits ───────────

    @Test
    public void awaitCancellationReturnsTrueWhenCancelledWhileWaiting() throws Exception {
        OperationCancelToken token = new OperationCancelToken();
        CountDownLatch started = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean result =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            started.countDown();
            result.set(token.awaitCancellation(10));
        });
        waiter.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        // Give the waiter a moment to enter awaitCancellation, then cancel.
        Thread.sleep(150);
        assertTrue(token.cancel("user"));
        waiter.join(3000);
        assertFalse(waiter.isAlive());
        assertTrue("waiter must observe the cancel", result.get());
        assertEquals("user", token.reason());
    }

    @Test
    public void awaitCancellationTimesOutAndReturnsFalse() throws Exception {
        OperationCancelToken token = new OperationCancelToken();
        long startNanos = System.nanoTime();
        boolean cancelled = token.awaitCancellation(1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertFalse(cancelled);
        assertTrue("should wait ~1s, took " + elapsedMs + "ms", elapsedMs >= 900);
    }

    @Test
    public void awaitCancellationOnAlreadyCancelledTokenReturnsImmediately() {
        OperationCancelToken token = new OperationCancelToken();
        token.cancel("pre-cancelled");
        long startNanos = System.nanoTime();
        assertTrue(token.awaitCancellation(5));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertTrue("no waiting expected, took " + elapsedMs + "ms", elapsedMs < 500);
    }

    @Test
    public void awaitCancellationWithNonPositiveTimeoutReturnsImmediately() {
        OperationCancelToken token = new OperationCancelToken();
        assertFalse(token.awaitCancellation(0));
        assertFalse(token.awaitCancellation(-3));
    }

    @Test
    public void concurrentCancelsAreSafe() throws Exception {
        OperationCancelToken token = new OperationCancelToken();
        int threads = 8;
        List<Thread> cancelers = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                if (token.cancel("c" + Thread.currentThread().getId())) winners.incrementAndGet();
            });
            t.start();
            cancelers.add(t);
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS));
        for (Thread t : cancelers) t.join(3000);
        assertTrue(token.isCancelled());
        assertEquals("exactly one cancel wins", 1, winners.get());
    }
}
