package app.multiai.mobile.network;

import java.util.Random;

/**
 * Bounded exponential backoff policy for Work Mode retries.
 *
 * <p>Pure Java, no Android imports. The retry <b>loop itself lives in the
 * Activity/controller layer</b> — this class only answers two questions:</p>
 *
 * <ol>
 *   <li>{@link #shouldRetry(MultiAiError, int)} — is this failure retryable
 *       and is the attempt budget not exhausted?</li>
 *   <li>{@link #delayMs(MultiAiError, int)} — how long to wait before the
 *       next attempt?</li>
 * </ol>
 *
 * <p>Delay rules, in priority order:</p>
 * <ol>
 *   <li>A valid server {@code Retry-After} hint on the error (already capped
 *       by {@link MultiAiError#MAX_RETRY_AFTER_MS}) wins over backoff.</li>
 *   <li>Otherwise {@code baseDelayMs * 2^(attempt-1)}, capped at
 *       {@code maxDelayMs}.</li>
 *   <li>Optional jitter (fraction of the computed delay, {@code [0, fraction)}
 *       uniform) is <b>added after</b> the cap so waits never exceed the
 *       policy maximum by more than jitter of it; pass 0 for deterministic
 *       tests.</li>
 * </ol>
 *
 * <p>Retryable failures (delegated to {@link MultiAiError#isRetryable()}):
 * network I/O, read/first-chunk/total/semantic timeouts, truncated SSE,
 * HTTP 408/425/429/5xx. Non-retryable: 400/401/403/404, schema violations,
 * size limits, cancel, local errors.</p>
 */
public final class RetryPolicy {

    /** Reasonable defaults for mobile chat traffic. */
    public static RetryPolicy defaults() {
        // No jitter by default: retries are user-visible in Work Mode and the
        // exact wait is easier to reason about. Callers may build a variant
        // with jitter if thundering-herd becomes a concern.
        return new RetryPolicy(3, 1_000L, 30_000L, 0.0, new Random());
    }

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFraction;
    private final Random random;

    /**
     * @param maxAttempts     total attempts allowed including the first one
     * @param baseDelayMs     delay before the 1st retry
     * @param maxDelayMs      upper bound for the exponential part
     * @param jitterFraction  0..0.5; 0 disables jitter (deterministic)
     * @param random          source for jitter; inject a seeded one in tests
     */
    public RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs,
                       double jitterFraction, Random random) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts >= 1 required");
        if (baseDelayMs < 0) throw new IllegalArgumentException("baseDelayMs >= 0 required");
        if (maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("maxDelayMs must be >= baseDelayMs");
        }
        if (jitterFraction < 0 || jitterFraction > 0.5) {
            throw new IllegalArgumentException("jitterFraction must be in [0, 0.5]");
        }
        if (random == null) throw new IllegalArgumentException("random required");
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterFraction = jitterFraction;
        this.random = random;
    }

    public int getMaxAttempts() { return maxAttempts; }
    public long getBaseDelayMs() { return baseDelayMs; }
    public long getMaxDelayMs() { return maxDelayMs; }

    /**
     * Whether one more attempt is allowed for this failure.
     *
     * @param error          failure delivered through the client callback
     * @param attemptsUsed   attempts already made (the initial one counts),
     *                       so {@code attemptsUsed >= maxAttempts} stops
     */
    public boolean shouldRetry(MultiAiError error, int attemptsUsed) {
        if (error == null || attemptsUsed < 0) return false;
        return error.isRetryable() && attemptsUsed < maxAttempts;
    }

    /**
     * Wait before the next attempt.
     *
     * @param error         failure that triggers the retry
     * @param nextAttempt   1-based index of the upcoming retry (1 = first retry)
     * @return delay in ms; {@code 0} is possible for a zero base/backoff
     */
    public long delayMs(MultiAiError error, int nextAttempt) {
        if (error == null || nextAttempt < 1) return 0;
        Long retryAfter = error.getRetryAfterMs();
        long delay;
        if (retryAfter != null) {
            // Server hint wins, but never wait longer than the policy cap.
            delay = Math.min(retryAfter, maxDelayMs);
        } else {
            int exp = Math.min(nextAttempt - 1, 30); // guard against 2^overflow
            long scaled = saturatedMultiply(baseDelayMs, 1L << exp);
            delay = Math.min(scaled, maxDelayMs);
        }
        if (jitterFraction <= 0) return delay;
        long jitter = (long) (delay * jitterFraction);
        if (jitter <= 0) return delay;
        return delay + random.nextInt((int) Math.min(jitter + 1, Integer.MAX_VALUE));
    }

    private static long saturatedMultiply(long a, long b) {
        long result = a * b;
        if (a != 0 && result / a != b) return Long.MAX_VALUE;
        return result;
    }
}
