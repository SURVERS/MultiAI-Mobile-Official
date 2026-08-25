package app.multiai.mobile.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Random;

import org.junit.Test;

/**
 * JVM unit tests for {@link RetryPolicy}: attempt budget, exponential delay
 * bounds, Retry-After precedence and jitter. Deterministic — jitter cases use
 * a seeded {@link Random} and jitter-free policies use fraction 0.
 */
public class RetryPolicyTest {

    private static final MultiAiError NETWORK =
            MultiAiError.network(new IOException("reset"));
    private static final MultiAiError HTTP_400 =
            MultiAiError.http(400, "bad request", null);
    private static final MultiAiError AUTH_401 =
            MultiAiError.http(401, null, null);

    @Test
    public void retryableErrorsRetryWithinBudget() {
        RetryPolicy policy = RetryPolicy.defaults(); // 3 attempts
        assertTrue(policy.shouldRetry(NETWORK, 0));
        assertTrue(policy.shouldRetry(NETWORK, 1));
        assertTrue(policy.shouldRetry(NETWORK, 2));
        assertFalse("budget exhausted", policy.shouldRetry(NETWORK, 3));
        assertFalse(policy.shouldRetry(NETWORK, 99));
    }

    @Test
    public void nonRetryableErrorsNeverRetry() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertFalse(policy.shouldRetry(HTTP_400, 0));
        assertFalse(policy.shouldRetry(AUTH_401, 0));
        assertFalse(policy.shouldRetry(MultiAiError.canceled(), 0));
        assertFalse(policy.shouldRetry(
                MultiAiError.limit("request_too_large", "big"), 0));
        assertFalse(policy.shouldRetry(
                MultiAiError.protocol("malformed_json", "bad"), 0));
    }

    @Test
    public void transientHttpStatusesRetry() {
        RetryPolicy policy = RetryPolicy.defaults();
        int[] transientStatuses = {408, 425, 429, 500, 502, 503, 504};
        for (int status : transientStatuses) {
            assertTrue("HTTP " + status,
                    policy.shouldRetry(MultiAiError.http(status, null, null), 0));
        }
    }

    @Test
    public void retryStopsForFatalEvenWithBudgetLeft() {
        RetryPolicy policy = new RetryPolicy(5, 100, 1000, 0.0, new Random());
        assertFalse(policy.shouldRetry(HTTP_400, 4));
    }

    @Test
    public void nullAndInvalidArgumentsAreNotRetryable() {
        RetryPolicy policy = RetryPolicy.defaults();
        assertFalse(policy.shouldRetry(null, 0));
        assertFalse(policy.shouldRetry(NETWORK, -1));
    }

    // ══════════════════ Delay: bounded exponential ══════════════════

    @Test
    public void exponentialDelayDoublesAndCaps() {
        RetryPolicy policy = new RetryPolicy(10, 1_000, 30_000, 0.0, new Random());
        assertEquals(1_000L, policy.delayMs(NETWORK, 1));
        assertEquals(2_000L, policy.delayMs(NETWORK, 2));
        assertEquals(4_000L, policy.delayMs(NETWORK, 3));
        assertEquals(8_000L, policy.delayMs(NETWORK, 4));
        assertEquals(16_000L, policy.delayMs(NETWORK, 5));
        assertEquals(30_000L, policy.delayMs(NETWORK, 6)); // 32s capped
        assertEquals(30_000L, policy.delayMs(NETWORK, 50)); // stays capped
    }

    @Test
    public void defaultsAreBounded() {
        RetryPolicy policy = RetryPolicy.defaults();
        for (int attempt = 1; attempt <= 20; attempt++) {
            long d = policy.delayMs(NETWORK, attempt);
            assertTrue("delay must be >= 0", d >= 0);
            assertTrue("delay must not exceed max", d <= 30_000L);
        }
    }

    @Test
    public void retryAfterWinsOverBackoffButIsCapped() {
        RetryPolicy policy = new RetryPolicy(3, 1_000, 30_000, 0.0, new Random());
        MultiAiError withHint = MultiAiError.http(429, "slow down", 60_000L);
        assertEquals("server hint capped at maxDelay", 30_000L, policy.delayMs(withHint, 1));

        MultiAiError smallHint = MultiAiError.http(429, "slow down", 2_000L);
        assertEquals("small hint beats backoff", 2_000L, policy.delayMs(smallHint, 5));
    }

    @Test
    public void zeroBaseDelayAllowed() {
        RetryPolicy policy = new RetryPolicy(3, 0, 1_000, 0.0, new Random());
        assertEquals(0L, policy.delayMs(NETWORK, 1));
    }

    @Test
    public void delayGuardsAgainstOverflow() {
        RetryPolicy policy = new RetryPolicy(100, 1_000, Long.MAX_VALUE / 2, 0.0, new Random());
        long d = policy.delayMs(NETWORK, 70); // 2^69 without the saturation guard
        assertTrue(d > 0);
    }

    // ══════════════════ Jitter (seeded, deterministic) ══════════════════

    @Test
    public void jitterKeepsDelayWithinBoundedCorridor() {
        // Fraction 0.25 => jitter in [0, 25%] of the exponential delay.
        RetryPolicy policy = new RetryPolicy(10, 8_000, 100_000, 0.25, new Random(42));
        for (int attempt = 1; attempt <= 8; attempt++) {
            long base = policy.delayMs(NETWORK, attempt);
            long min = Math.min(8_000L << (attempt - 1), 100_000L);
            assertTrue("delay too small: " + base, base >= min);
            assertTrue("delay too large: " + base, base <= min + min / 4);
        }
    }

    @Test
    public void jitterIsDeterministicWithSeed() {
        RetryPolicy a = new RetryPolicy(10, 1_000, 30_000, 0.3, new Random(7));
        RetryPolicy b = new RetryPolicy(10, 1_000, 30_000, 0.3, new Random(7));
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(a.delayMs(NETWORK, attempt), b.delayMs(NETWORK, attempt));
        }
    }

    // ══════════════════ Constructor validation ══════════════════

    @Test
    public void constructorRejectsInvalidConfig() {
        assertThrows(() -> new RetryPolicy(0, 100, 1000, 0, new Random()));
        assertThrows(() -> new RetryPolicy(1, -1, 1000, 0, new Random()));
        assertThrows(() -> new RetryPolicy(1, 500, 100, 0, new Random())); // max < base
        assertThrows(() -> new RetryPolicy(1, 100, 1000, -0.1, new Random()));
        assertThrows(() -> new RetryPolicy(1, 100, 1000, 0.6, new Random()));
        assertThrows(() -> new RetryPolicy(1, 100, 1000, 0, null));
    }

    private static void assertThrows(Runnable runnable) {
        try {
            runnable.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    // ══════════════════ Wire integration ══════════════════

    @Test
    public void policyConsumesWireStringFromCallback() {
        // Simulates the Activity-side flow: onError(String) -> parse -> decide.
        String wire = MultiAiError.http(503, null, null).toWireString();
        MultiAiError parsed = MultiAiError.parse(wire);
        RetryPolicy policy = RetryPolicy.defaults();
        assertTrue(policy.shouldRetry(parsed, 0));
        assertTrue(policy.delayMs(parsed, 1) <= policy.getMaxDelayMs());
    }
}
