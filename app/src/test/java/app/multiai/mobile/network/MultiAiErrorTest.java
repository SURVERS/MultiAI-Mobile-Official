package app.multiai.mobile.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

/**
 * JVM unit tests for {@link MultiAiError}: wire round-trip, retryability
 * matrix, Retry-After parsing, bounded body reading and sanitization.
 * All tests are deterministic (explicit nowMs / no randomness).
 */
public class MultiAiErrorTest {

    // ══════════════════ Wire round-trip ══════════════════

    @Test
    public void wireRoundTripPreservesAllFields() {
        MultiAiError original = MultiAiError.http(429, "Слишком много запросов", 15_000L);
        String wire = original.toWireString();
        assertTrue(wire.startsWith("MAERR|"));

        MultiAiError parsed = MultiAiError.parse(wire);
        assertTrue(parsed.isStructured());
        assertEquals(MultiAiError.Category.RATE_LIMIT, parsed.getCategory());
        assertEquals("http_429", parsed.getCode());
        assertEquals(429, parsed.getHttpStatus());
        assertEquals(Long.valueOf(15_000L), parsed.getRetryAfterMs());
        assertEquals("Слишком много запросов", parsed.getMessage());
        assertEquals(wire, parsed.toWireString());
    }

    @Test
    public void wireRoundTripWithoutRetryAfter() {
        MultiAiError original = MultiAiError.streamTruncated();
        MultiAiError parsed = MultiAiError.parse(original.toWireString());
        assertNull(parsed.getRetryAfterMs());
        assertEquals(MultiAiError.Category.PROTOCOL, parsed.getCategory());
        assertTrue(parsed.isRetryable());
    }

    @Test
    public void messageMayContainPipeAndStillParses() {
        MultiAiError original = MultiAiError.upstream("a|b|c");
        MultiAiError parsed = MultiAiError.parse(original.toWireString());
        assertEquals("a|b|c", parsed.getMessage());
    }

    @Test
    public void parseLegacyStringYieldsUnstructuredUnknown() {
        MultiAiError parsed = MultiAiError.parse("HTTP 500");
        assertFalse(parsed.isStructured());
        assertEquals(MultiAiError.Category.UNKNOWN, parsed.getCategory());
        assertEquals("HTTP 500", parsed.getMessage());
        assertFalse(parsed.isRetryable());
    }

    @Test
    public void parseNullAndGarbageNeverThrows() {
        assertEquals(MultiAiError.Category.UNKNOWN, MultiAiError.parse(null).getCategory());
        assertEquals(MultiAiError.Category.UNKNOWN,
                MultiAiError.parse("MAERR|only|two").getCategory());
        // Truncated wire with missing separators degrades gracefully.
        MultiAiError parsed = MultiAiError.parse("MAERR|http_429");
        assertFalse(parsed.isStructured());
    }

    // ══════════════════ Retryability matrix ══════════════════

    @Test
    public void transientHttpStatusesAreRetryable() {
        int[] retryable = {408, 425, 429, 500, 502, 503, 504};
        for (int status : retryable) {
            MultiAiError e = MultiAiError.http(status, null, null);
            assertTrue("HTTP " + status + " must be retryable", e.isRetryable());
        }
    }

    @Test
    public void permanentHttpStatusesAreNotRetryable() {
        int[] fatal = {400, 401, 403, 404, 405, 422};
        for (int status : fatal) {
            MultiAiError e = MultiAiError.http(status, null, null);
            assertFalse("HTTP " + status + " must NOT be retryable", e.isRetryable());
        }
    }

    @Test
    public void timeoutsNetworkAndTruncationAreRetryable() {
        assertTrue(MultiAiError.network(new IOException("connection reset")).isRetryable());
        assertTrue(MultiAiError.network(new SocketTimeoutException("read timed out")).isRetryable());
        assertTrue(MultiAiError.timeout(MultiAiError.TimeoutKind.FIRST_CHUNK).isRetryable());
        assertTrue(MultiAiError.timeout(MultiAiError.TimeoutKind.TOTAL_DEADLINE).isRetryable());
        assertTrue(MultiAiError.timeout(MultiAiError.TimeoutKind.SEMANTIC_IDLE).isRetryable());
        assertTrue(MultiAiError.streamTruncated().isRetryable());
    }

    @Test
    public void schemaLimitAuthCancelAreNotRetryable() {
        assertFalse(MultiAiError.protocol("malformed_json", "bad").isRetryable());
        assertFalse(MultiAiError.protocol("empty_body", "bad").isRetryable());
        assertFalse(MultiAiError.protocol("empty_completion", "bad").isRetryable());
        assertFalse(MultiAiError.limit("request_too_large", "big").isRetryable());
        assertFalse(MultiAiError.limit("response_too_large", "big").isRetryable());
        assertFalse(MultiAiError.limit("tool_arguments_too_large", "big").isRetryable());
        assertFalse(MultiAiError.auth("not_authorized", "no token").isRetryable());
        assertFalse(MultiAiError.canceled().isRetryable());
        assertFalse(MultiAiError.local("request_build_failed", "oops").isRetryable());
    }

    // ══════════════════ Categories ══════════════════

    @Test
    public void categoriesMatchHttpStatus() {
        assertEquals(MultiAiError.Category.AUTH, MultiAiError.http(401, null, null).getCategory());
        assertEquals(MultiAiError.Category.AUTH, MultiAiError.http(403, null, null).getCategory());
        assertEquals(MultiAiError.Category.RATE_LIMIT,
                MultiAiError.http(429, null, null).getCategory());
        assertEquals(MultiAiError.Category.TIMEOUT,
                MultiAiError.http(408, null, null).getCategory());
        assertEquals(MultiAiError.Category.SERVER, MultiAiError.http(503, null, null).getCategory());
        assertEquals(MultiAiError.Category.SERVER, MultiAiError.http(425, null, null).getCategory());
        assertEquals(MultiAiError.Category.PROTOCOL,
                MultiAiError.http(400, null, null).getCategory());
    }

    @Test
    public void httpDefaultsUsedWhenServerDetailEmpty() {
        assertTrue(MultiAiError.http(429, null, null).getMessage().contains("429"));
        assertTrue(MultiAiError.http(429, "", null).getMessage().contains("429"));
    }

    // ══════════════════ Retry-After ══════════════════

    @Test
    public void retryAfterDeltaSeconds() {
        assertEquals(Long.valueOf(120_000L),
                MultiAiError.parseRetryAfter("120", 1_000_000L));
        assertEquals(Long.valueOf(0L), MultiAiError.parseRetryAfter("0", 1_000_000L));
    }

    @Test
    public void retryAfterHttpDate() {
        long now = 1_700_000_000_000L;
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.Instant.ofEpochMilli(now + 30_000L).atZone(java.time.ZoneOffset.UTC));
        assertEquals(Long.valueOf(30_000L), MultiAiError.parseRetryAfter(date, now));
    }

    @Test
    public void retryAfterPastDateYieldsNull() {
        long now = 1_700_000_000_000L;
        String past = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.Instant.ofEpochMilli(now - 60_000L).atZone(java.time.ZoneOffset.UTC));
        assertNull(MultiAiError.parseRetryAfter(past, now));
    }

    @Test
    public void retryAfterCappedAndInvalid() {
        assertEquals(Long.valueOf(MultiAiError.MAX_RETRY_AFTER_MS),
                MultiAiError.parseRetryAfter("99999999", 0L));
        assertNull(MultiAiError.parseRetryAfter(null, 0L));
        assertNull(MultiAiError.parseRetryAfter("", 0L));
        assertNull(MultiAiError.parseRetryAfter("soon", 0L));
        assertNull(MultiAiError.parseRetryAfter("-5", 0L));
        assertNull(MultiAiError.parseRetryAfter("not a date at all!!", 0L));
    }

    @Test
    public void httpFactoryClampsRetryAfter() {
        MultiAiError e = MultiAiError.http(503, null, Long.MAX_VALUE);
        assertEquals(Long.valueOf(MultiAiError.MAX_RETRY_AFTER_MS), e.getRetryAfterMs());
        MultiAiError negative = MultiAiError.http(503, null, -10L);
        assertNull(negative.getRetryAfterMs());
    }

    // ══════════════════ Bounded body reading ══════════════════

    @Test
    public void readBoundedStopsAtLimit() throws IOException {
        byte[] data = new byte[10_000];
        java.util.Arrays.fill(data, (byte) 'x');
        String out = MultiAiError.readBounded(new ByteArrayInputStream(data), 100);
        assertEquals(100, out.length());
    }

    @Test
    public void readBoundedShortStreamReturnsEverything() throws IOException {
        String out = MultiAiError.readBounded(
                new ByteArrayInputStream("short".getBytes(StandardCharsets.UTF_8)), 4096);
        assertEquals("short", out);
    }

    @Test
    public void readBoundedInvalidUtf8DoesNotThrow() throws IOException {
        byte[] garbage = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x41};
        String out = MultiAiError.readBounded(new ByteArrayInputStream(garbage), garbage.length);
        assertFalse(out.isEmpty());
    }

    @Test
    public void readBoundedNullAndZeroReturnEmpty() throws IOException {
        assertEquals("", MultiAiError.readBounded(null, 100));
        assertEquals("", MultiAiError.readBounded(new ByteArrayInputStream(new byte[10]), 0));
    }

    // ══════════════════ Sanitization ══════════════════

    @Test
    public void sanitizeCollapsesWhitespaceAndControls() {
        assertEquals("a b c", MultiAiError.sanitize("a\n\t b\r\nc ", 100));
        assertEquals("a b", MultiAiError.sanitize("  a\u0000b  ", 100));
    }

    @Test
    public void sanitizeTruncatesWithEllipsis() {
        String out = MultiAiError.sanitize("abcdef", 3);
        assertTrue(out.startsWith("abc"));
        assertTrue(out.endsWith("…"));
        assertEquals(4, out.length()); // 3 chars + ellipsis
    }

    @Test
    public void sanitizeRespectsCodePoints() {
        // Rocket emoji is one code point outside the BMP.
        String out = MultiAiError.sanitize("🚀🚀🚀", 2);
        assertEquals("🚀🚀…", out);
    }

    @Test
    public void sanitizeHandlesNullAndEmpty() {
        assertEquals("", MultiAiError.sanitize(null, 10));
        assertEquals("", MultiAiError.sanitize("", 10));
        assertEquals("", MultiAiError.sanitize("text", 0));
    }

    @Test
    public void networkFactorySanitizesAndFallsBack() {
        MultiAiError fromNull = MultiAiError.network(null);
        assertEquals(MultiAiError.Category.NETWORK, fromNull.getCategory());
        assertTrue(fromNull.getMessage().length() > 0);

        MultiAiError fromBlank = MultiAiError.network(new IOException(""));
        assertTrue(fromBlank.getMessage().contains("IOException"));

        MultiAiError timeout = MultiAiError.network(new SocketTimeoutException("timeout"));
        assertEquals(MultiAiError.Category.TIMEOUT, timeout.getCategory());
        assertEquals("network_read_timeout", timeout.getCode());
    }

    // ══════════════════ JSON message extraction ══════════════════

    @Test
    public void extractsMessageFromErrorBody() {
        String body = "{\"error\":{\"message\":\"Model overloaded\",\"type\":\"server_error\"}}";
        assertEquals("Model overloaded", MultiAiError.extractJsonMessage(body));
    }

    @Test
    public void extractsTopLevelErrorString() {
        assertEquals("boom", MultiAiError.extractJsonMessage("{\"error\":\"boom\"}"));
        assertEquals("direct", MultiAiError.extractJsonMessage("{\"message\":\"direct\"}"));
    }

    @Test
    public void unescapesJsonSequences() {
        assertEquals("line1\nline2 \"quoted\"",
                MultiAiError.extractJsonMessage("{\"message\":\"line1\\nline2 \\\"quoted\\\"\"}"));
        assertEquals("tab\there",
                MultiAiError.extractJsonMessage("{\"message\":\"tab\\there\"}"));
    }

    @Test
    public void returnsNullForBodiesWithoutMessage() {
        assertNull(MultiAiError.extractJsonMessage(null));
        assertNull(MultiAiError.extractJsonMessage(""));
        assertNull(MultiAiError.extractJsonMessage("<html>502 Bad Gateway</html>"));
        assertNull(MultiAiError.extractJsonMessage("{\"foo\":1}"));
        assertNull(MultiAiError.extractJsonMessage("{\"message\":\"\"}"));
    }

    @Test
    public void longServerMessageIsCappedInsideHttpFactory() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 500; i++) huge.append('x');
        MultiAiError e = MultiAiError.http(500, huge.toString(), null);
        assertTrue(e.getMessage().length() <= MultiAiError.MAX_MESSAGE_CHARS + 1);
        assertTrue(e.getMessage().endsWith("…"));
    }

    // ══════════════════ TimeoutKind messages ══════════════════

    @Test
    public void timeoutKindsHaveDistinctCodes() {
        assertEquals("timeout_first_chunk",
                MultiAiError.timeout(MultiAiError.TimeoutKind.FIRST_CHUNK).getCode());
        assertEquals("timeout_total_deadline",
                MultiAiError.timeout(MultiAiError.TimeoutKind.TOTAL_DEADLINE).getCode());
        assertEquals("timeout_semantic_idle",
                MultiAiError.timeout(MultiAiError.TimeoutKind.SEMANTIC_IDLE).getCode());
    }
}
