package app.multiai.mobile.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Structured, sanitized error for every MultiAi transport failure.
 *
 * <p>Pure Java (no Android imports) so it is unit-testable on the JVM and can
 * be reused by Work Mode retry logic. Two use cases:</p>
 *
 * <ol>
 *   <li><b>Producer</b> — {@code MultiAiClient} builds these in every error
 *       path and delivers {@link #toWireString()} through the existing
 *       {@code StreamCallback.onError(String)} contract (no signature change).</li>
 *   <li><b>Consumer</b> — an Activity classifies the failure:
 *       <pre>{@code
 * MultiAiError error = MultiAiError.parse(errorString);
 * switch (error.getCategory()) { case AUTH: ... case RATE_LIMIT: ... }
 * if (RetryPolicy.defaults().shouldRetry(error, retriesDone)) {
 *     long delay = RetryPolicy.defaults().delayMs(error, retriesDone + 1);
 *     // schedule retry after delay
 * }
 * }</pre></li>
 * </ol>
 *
 * <p>Wire format (single line, pipe-separated, message is the free-text
 * remainder so it may itself contain {@code '|'}):
 * {@code MAERR|<code>|<CATEGORY>|<httpStatus>|<retryAfterMs or ->|<message>}</p>
 *
 * <p>Retryability (matches the approved Work Mode repair spec):
 * retryable = network I/O, read/first-chunk/deadline/semantic timeouts,
 * truncated SSE stream, HTTP 408/425/429/5xx; everything else
 * (400/401/403/404, schema/JSON violations, size limits, cancel, local
 * errors) is non-retryable.</p>
 */
public final class MultiAiError {

    /** Coarse classification for UI decisions (retry button, auth prompt…). */
    public enum Category {
        NETWORK, AUTH, RATE_LIMIT, SERVER, TIMEOUT, PROTOCOL, LIMIT, CANCELED, UNKNOWN
    }

    /** Which of the three client-side stream deadlines fired. */
    public enum TimeoutKind { FIRST_CHUNK, TOTAL_DEADLINE, SEMANTIC_IDLE }

    public static final String WIRE_PREFIX = "MAERR|";
    /** Hard cap for any human-readable error message (already-sanitized input too). */
    public static final int MAX_MESSAGE_CHARS = 300;
    /** Hard cap for a Retry-After hint parsed from a header, in milliseconds. */
    public static final long MAX_RETRY_AFTER_MS = 3_600_000L;

    private static final String RETRY_AFTER_NONE = "-";

    private final Category category;
    private final String code;
    private final int httpStatus;
    private final String message;
    private final Long retryAfterMs;
    private final boolean structured;

    private MultiAiError(Category category, String code, int httpStatus,
                         String message, Long retryAfterMs, boolean structured) {
        this.category = category == null ? Category.UNKNOWN : category;
        this.code = code == null || code.isEmpty() ? "unknown" : code;
        this.httpStatus = httpStatus;
        this.message = message == null ? "" : message;
        this.retryAfterMs = retryAfterMs;
        this.structured = structured;
    }

    public Category getCategory() { return category; }
    /** Stable machine-readable id, e.g. {@code http_429}, {@code stream_truncated}. */
    public String getCode() { return code; }
    /** HTTP status or 0 when the failure never produced an HTTP response. */
    public int getHttpStatus() { return httpStatus; }
    /** Sanitized single-line human-readable message for the UI. */
    public String getMessage() { return message; }
    /** Server-provided hint from Retry-After, in ms; null when absent/invalid. */
    public Long getRetryAfterMs() { return retryAfterMs; }
    /** False when the string was produced by legacy code without classification. */
    public boolean isStructured() { return structured; }

    /** Deterministic retryability of this exact failure (see class doc). */
    public boolean isRetryable() {
        return computeRetryable(category, code, httpStatus);
    }

    /** Single-line wire representation delivered through onError(String). */
    public String toWireString() {
        return WIRE_PREFIX + code + '|' + category.name() + '|' + httpStatus + '|'
                + (retryAfterMs == null ? RETRY_AFTER_NONE : retryAfterMs.toString())
                + '|' + message;
    }

    /**
     * Parse a wire string back into a structured error. Never returns null;
     * unknown/legacy inputs map to {@link Category#UNKNOWN} with the original
     * text preserved as the message.
     */
    public static MultiAiError parse(String wire) {
        if (wire == null || !wire.startsWith(WIRE_PREFIX)) {
            return unstructured(wire);
        }
        int[] sep = new int[4];
        int from = WIRE_PREFIX.length();
        for (int i = 0; i < sep.length; i++) {
            sep[i] = wire.indexOf('|', from);
            if (sep[i] < 0) return unstructured(wire);
            from = sep[i] + 1;
        }
        String code = wire.substring(WIRE_PREFIX.length(), sep[0]);
        Category category;
        try {
            category = Category.valueOf(wire.substring(sep[0] + 1, sep[1]));
        } catch (IllegalArgumentException e) {
            category = Category.UNKNOWN;
        }
        int status;
        try {
            status = Integer.parseInt(wire.substring(sep[1] + 1, sep[2]).trim());
        } catch (NumberFormatException e) {
            status = 0;
        }
        Long retryAfter;
        String rawRetryAfter = wire.substring(sep[2] + 1, sep[3]);
        if (rawRetryAfter.isEmpty() || RETRY_AFTER_NONE.equals(rawRetryAfter)) {
            retryAfter = null;
        } else {
            try {
                retryAfter = Long.parseLong(rawRetryAfter);
            } catch (NumberFormatException e) {
                retryAfter = null;
            }
        }
        String message = wire.substring(sep[3] + 1);
        return new MultiAiError(category, code, status, message, retryAfter, true);
    }

    // ══════════════════ Factories ══════════════════

    /** Transport-level IOException (connection reset, DNS, read failure…). */
    public static MultiAiError network(Throwable t) {
        String raw = t == null ? null : t.getMessage();
        if (raw == null || raw.isEmpty()) {
            raw = t == null ? "network error" : t.getClass().getSimpleName();
        }
        String detail = sanitize(raw, MAX_MESSAGE_CHARS);
        if (t instanceof java.net.SocketTimeoutException) {
            return new MultiAiError(Category.TIMEOUT, "network_read_timeout", 0,
                    "Таймаут сети: " + detail, null, true);
        }
        return new MultiAiError(Category.NETWORK, "network_io", 0,
                "Ошибка сети: " + detail, null, true);
    }

    /** User-visible cancel (Stop button, new generation superseding an old one). */
    public static MultiAiError canceled() {
        return new MultiAiError(Category.CANCELED, "canceled", 0,
                "Запрос отменен", null, true);
    }

    /** One of the three stream watchdogs fired before any HTTP status existed. */
    public static MultiAiError timeout(TimeoutKind kind) {
        switch (kind) {
            case FIRST_CHUNK:
                return new MultiAiError(Category.TIMEOUT, "timeout_first_chunk", 0,
                        "Таймаут: сервер не начал присылать ответ", null, true);
            case TOTAL_DEADLINE:
                return new MultiAiError(Category.TIMEOUT, "timeout_total_deadline", 0,
                        "Таймаут: превышен общий лимит времени запроса", null, true);
            default:
                return new MultiAiError(Category.TIMEOUT, "timeout_semantic_idle", 0,
                        "Таймаут: поток ответа завис без прогресса", null, true);
        }
    }

    /**
     * Non-2xx HTTP status. {@code serverDetail} is an already bounded, raw
     * excerpt of the response body (or empty); it is sanitized here again and
     * replaces the generic message only when non-empty.
     */
    public static MultiAiError http(int status, String serverDetail, Long retryAfterMs) {
        String detail = sanitize(serverDetail, MAX_MESSAGE_CHARS);
        String message = detail.isEmpty() ? defaultHttpMessage(status) : detail;
        Long retryAfter = retryAfterMs == null ? null
                : (retryAfterMs < 0 ? null
                : Math.min(retryAfterMs, MAX_RETRY_AFTER_MS));
        return new MultiAiError(categoryForStatus(status), "http_" + status,
                status, message, retryAfter, true);
    }

    /** Error object embedded by the provider inside an SSE data event. */
    public static MultiAiError upstream(String serverMessage) {
        String detail = sanitize(serverMessage, MAX_MESSAGE_CHARS);
        if (detail.isEmpty()) detail = "Ошибка апстрим-модели";
        return new MultiAiError(Category.SERVER, "upstream_error", 0, detail, null, true);
    }

    /** Retryable protocol failure: SSE ended without [DONE]/finish_reason. */
    public static MultiAiError streamTruncated() {
        return new MultiAiError(Category.PROTOCOL, "stream_truncated", 0,
                "Ответ оборвался: стрим закончился без завершения от сервера", null, true);
    }

    /** Non-retryable protocol/schema violation (malformed JSON, empty body…). */
    public static MultiAiError protocol(String code, String message) {
        return new MultiAiError(Category.PROTOCOL, code, 0,
                sanitize(message, MAX_MESSAGE_CHARS), null, true);
    }

    /** Non-retryable size/count limit hit locally (request, response, tools). */
    public static MultiAiError limit(String code, String message) {
        return new MultiAiError(Category.LIMIT, code, 0,
                sanitize(message, MAX_MESSAGE_CHARS), null, true);
    }

    /** Non-retryable authorization failure (no token after refresh, 401 post-refresh). */
    public static MultiAiError auth(String code, String message) {
        return new MultiAiError(Category.AUTH, code, 0,
                sanitize(message, MAX_MESSAGE_CHARS), null, true);
    }

    /** Local precondition failed (request build error etc.), non-retryable. */
    public static MultiAiError local(String code, String message) {
        return new MultiAiError(Category.UNKNOWN, code, 0,
                sanitize(message, MAX_MESSAGE_CHARS), null, true);
    }

    // ══════════════════ Body reading / sanitizing ══════════════════

    /**
     * Read at most {@code maxBytes} bytes from the stream and decode as UTF-8
     * (invalid sequences become replacement chars). Never throws on malformed
     * input; propagates only genuine I/O errors. Does not close the stream.
     */
    public static String readBounded(InputStream in, int maxBytes) throws IOException {
        if (in == null || maxBytes <= 0) return "";
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] chunk = new byte[2048];
        while (buffer.size() < maxBytes) {
            int want = Math.min(chunk.length, maxBytes - buffer.size());
            int read = in.read(chunk, 0, want);
            if (read < 0) break;
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Collapse all whitespace/control characters into single spaces, trim the
     * result and cap it at {@code maxChars} code points (an ellipsis marks the
     * cut). Guaranteed single-line, so wire strings stay parseable.
     */
    public static String sanitize(String raw, int maxChars) {
        if (raw == null || raw.isEmpty() || maxChars <= 0) return "";
        StringBuilder out = new StringBuilder(Math.min(raw.length(), maxChars + 8));
        int count = 0;
        boolean lastWasSpace = true;
        int i = 0;
        boolean truncated = false;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            boolean whitespace = cp <= 0x20 || cp == 0x7F || Character.isWhitespace(cp);
            if (whitespace) {
                if (!lastWasSpace && count < maxChars) {
                    out.append(' ');
                    count++;
                    lastWasSpace = true;
                }
            } else {
                if (count >= maxChars) {
                    truncated = true;
                    break;
                }
                out.appendCodePoint(cp);
                count++;
                lastWasSpace = false;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        out.setLength(end);
        if (truncated && out.length() > 0) out.append('…');
        return out.toString();
    }

    /**
     * Parse a Retry-After header: delta-seconds ("120") or an HTTP-date
     * (RFC 1123) resolved against {@code nowMs}. Returns the delay in
     * milliseconds capped at {@link #MAX_RETRY_AFTER_MS}, or null when the
     * header is absent/invalid/in the past. Pass {@code nowMs} explicitly so
     * tests stay deterministic.
     */
    public static Long parseRetryAfter(String headerValue, long nowMs) {
        if (headerValue == null) return null;
        String value = headerValue.trim();
        if (value.isEmpty()) return null;
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) return null;
            if (seconds > MAX_RETRY_AFTER_MS / 1000L) return MAX_RETRY_AFTER_MS;
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            // fall through to HTTP-date
        }
        try {
            Instant date = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value));
            long delay = date.toEpochMilli() - nowMs;
            if (delay <= 0) return null;
            return Math.min(delay, MAX_RETRY_AFTER_MS);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Best-effort extraction of the human message from a bounded error body
     * without a JSON dependency: returns the value of the first {@code
     * "message"} (fallback: string-valued {@code "error"}) key, or null when
     * the body has no readable message. Unescapes standard JSON escapes.
     */
    public static String extractJsonMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        String found = firstStringValueForKey(body, "message");
        if (found == null || found.trim().isEmpty()) {
            found = firstStringValueForKey(body, "error");
        }
        return found == null || found.trim().isEmpty() ? null : found;
    }

    // ══════════════════ Internals ══════════════════

    private static MultiAiError unstructured(String text) {
        String message = text == null ? "" : text;
        if (message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS) + '…';
        }
        return new MultiAiError(Category.UNKNOWN, "unknown", 0, message, null, false);
    }

    private static Category categoryForStatus(int status) {
        if (status == 401 || status == 403) return Category.AUTH;
        if (status == 429) return Category.RATE_LIMIT;
        if (status == 408) return Category.TIMEOUT;
        if (status == 425 || status >= 500) return Category.SERVER;
        return Category.PROTOCOL;
    }

    private static String defaultHttpMessage(int status) {
        switch (status) {
            case 401: return "Требуется повторный вход в MultiAI (HTTP 401)";
            case 403: return "Доступ к модели запрещён (HTTP 403)";
            case 404: return "Модель или адрес не найдены (HTTP 404)";
            case 429: return "Слишком много запросов, попробуйте позже (HTTP 429)";
            default:
                if (status >= 500) return "Сервер MultiAI временно недоступен (HTTP " + status + ")";
                return "Запрос отклонён сервером (HTTP " + status + ")";
        }
    }

    private static boolean computeRetryable(Category category, String code, int httpStatus) {
        switch (category) {
            case NETWORK:
            case TIMEOUT:
            case RATE_LIMIT:
                return true;
            case SERVER:
                return httpStatus == 425 || (httpStatus >= 500 && httpStatus <= 599);
            case PROTOCOL:
                return "stream_truncated".equals(code);
            default:
                return false;
        }
    }

    private static String firstStringValueForKey(String body, String key) {
        String needle = "\"" + key + "\"";
        int idx = body.indexOf(needle);
        while (idx >= 0) {
            int cursor = idx + needle.length();
            cursor = skipWhitespace(body, cursor);
            if (cursor < body.length() && body.charAt(cursor) == ':') {
                int valueStart = skipWhitespace(body, cursor + 1);
                if (valueStart < body.length() && body.charAt(valueStart) == '"') {
                    String value = readJsonString(body, valueStart);
                    if (value != null) return value;
                }
            }
            idx = body.indexOf(needle, idx + needle.length());
        }
        return null;
    }

    private static int skipWhitespace(String text, int from) {
        while (from < text.length()) {
            char c = text.charAt(from);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
            from++;
        }
        return from;
    }

    /** Reads one JSON string starting at the opening quote; null when malformed. */
    private static String readJsonString(String text, int openQuoteIndex) {
        StringBuilder out = new StringBuilder();
        int i = openQuoteIndex + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') return out.toString();
            if (c == '\\' && i + 1 < text.length()) {
                char esc = text.charAt(++i);
                switch (esc) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < text.length()) {
                            try {
                                out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        } else {
                            return null;
                        }
                        break;
                    default:
                        return null;
                }
                i++;
            } else if (c < 0x20) {
                return null;
            } else {
                out.append(c);
                i++;
            }
        }
        return null;
    }
}
