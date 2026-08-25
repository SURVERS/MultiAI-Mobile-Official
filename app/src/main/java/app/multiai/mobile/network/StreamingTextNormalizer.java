package app.multiai.mobile.network;

/**
 * Request-local helpers for normalizing inconsistent streaming providers.
 *
 * <p>The transport accumulator is the only place where cumulative snapshots or
 * meaningful textual overlaps are converted to fresh append-only fragments.
 * Punctuation and whitespace are never overlap-deduplicated, so Markdown,
 * indentation and blank lines remain byte-for-byte intact.</p>
 */
public final class StreamingTextNormalizer {
    private StreamingTextNormalizer() {
    }

    /** Converts delta, cumulative and conservatively overlapping chunks to fresh fragments. */
    public static final class Accumulator {
        private final StringBuilder value = new StringBuilder();
        private boolean cumulativeObserved;

        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            if (value.length() == 0) {
                value.append(chunk);
                return chunk;
            }

            String current = value.toString();

            // A growing full snapshot is unambiguous: only emit its new suffix.
            if (chunk.length() > current.length() && chunk.startsWith(current)) {
                cumulativeObserved = true;
                String fresh = chunk.substring(current.length());
                value.setLength(0);
                value.append(chunk);
                return fresh;
            }

            // Once cumulative mode was observed, repeated/older snapshots are stale.
            if (cumulativeObserved
                    && (chunk.equals(current)
                    || (chunk.length() < current.length() && current.startsWith(chunk)))) {
                return "";
            }

            // Mixed providers may switch from cumulative snapshots to overlapping deltas.
            // Require an extending overlap of at least three chars containing a letter or
            // digit. This deliberately excludes "*", "**", spaces and newlines.
            int overlap = meaningfulOverlap(current, chunk);
            if (overlap > 0) {
                String fresh = chunk.substring(overlap);
                value.append(fresh);
                return fresh;
            }

            // Ambiguous repetitions are data, not transport noise. Preserve them exactly.
            value.append(chunk);
            return chunk;
        }

        public String getText() {
            return value.toString();
        }

        public void reset() {
            value.setLength(0);
            cumulativeObserved = false;
        }

        private static int meaningfulOverlap(String existing, String chunk) {
            int max = Math.min(existing.length(), chunk.length() - 1);
            for (int length = max; length >= 3; length--) {
                int existingStart = existing.length() - length;
                if (!existing.regionMatches(existingStart, chunk, 0, length)) continue;
                if (containsLetterOrDigit(chunk, length)) return length;
            }
            return 0;
        }

        private static boolean containsLetterOrDigit(String text, int endExclusive) {
            for (int i = 0; i < endExclusive; ) {
                int codePoint = text.codePointAt(i);
                if (Character.isLetterOrDigit(codePoint)) return true;
                i += Character.charCount(codePoint);
            }
            return false;
        }
    }

    /**
     * Removes one expected prefix even when it is split across multiple callbacks.
     * A mismatch releases every held character verbatim; {@link #finish()} does the
     * same for an incomplete match at end-of-stream.
     */
    public static final class PrefixStripper {
        private final String expectedPrefix;
        private final StringBuilder held = new StringBuilder();
        private int matched;
        private boolean finished;

        public PrefixStripper(String expectedPrefix) {
            this.expectedPrefix = expectedPrefix != null ? expectedPrefix : "";
            this.finished = this.expectedPrefix.isEmpty();
        }

        public String accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            if (finished) return chunk;

            int index = 0;
            while (index < chunk.length() && !finished) {
                char current = chunk.charAt(index);
                if (current == expectedPrefix.charAt(matched)) {
                    held.append(current);
                    matched++;
                    index++;
                    if (matched == expectedPrefix.length()) {
                        held.setLength(0);
                        finished = true;
                    }
                } else {
                    String output = held.toString() + chunk.substring(index);
                    held.setLength(0);
                    finished = true;
                    return output;
                }
            }

            if (finished && index < chunk.length()) {
                return chunk.substring(index);
            }
            return "";
        }

        public String finish() {
            if (finished) return "";
            String output = held.toString();
            held.setLength(0);
            finished = true;
            return output;
        }
    }
}
