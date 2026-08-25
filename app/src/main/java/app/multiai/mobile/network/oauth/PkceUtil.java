package app.multiai.mobile.network.oauth;

import androidx.annotation.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * PKCE / state / nonce generator on a cryptographic RNG.
 *
 * <p>Implements §5.1 of the MultiAI integration document and RFC 7636:
 * <pre>
 *   code_verifier  = BASE64URL_NO_PADDING(RANDOM_BYTES(32))
 *   code_challenge = BASE64URL_NO_PADDING(SHA256(UTF8(code_verifier)))
 *   state          = BASE64URL_NO_PADDING(RANDOM_BYTES(32))
 *   nonce          = BASE64URL_NO_PADDING(RANDOM_BYTES(32))
 * </pre>
 *
 * <p>32 random bytes give a 43-character verifier (the document's minimum). Server
 * accepts 43–128 chars from {@code [A-Za-z0-9._~-]}; the challenge must be exactly
 * 43 base64url chars. {@code state} and {@code nonce} must be 16–512 URL-safe chars.
 *
 * <p>Pure Java (Base64 from Android is a thin wrapper over a static encoder; the
 * unit tests run on JVM via the same {@code URL_NO_PADDING} flag). No Android
 * framework types beyond {@link Base64}.
 */
public final class PkceUtil {

    /** Allowed verifier charset per RFC 7636 §4.1. */
    private static final String VERIFIER_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._~-";

    private static final int RANDOM_BYTES = 32;

    private PkceUtil() {}

    /** Cryptographic RNG source — overridable in tests. */
    private static volatile SecureRandom sharedRandom;

    @NonNull
    private static SecureRandom rng() {
        SecureRandom r = sharedRandom;
        if (r == null) {
            synchronized (PkceUtil.class) {
                r = sharedRandom;
                if (r == null) {
                    r = new SecureRandom();
                    sharedRandom = r;
                }
            }
        }
        return r;
    }

    /** Test hook. */
    static void setRandomForTest(SecureRandom r) { sharedRandom = r; }

    /** 43-char URL-safe verifier (BASE64URL_NO_PADDING of 32 random bytes). */
    @NonNull
    public static String generateVerifier() {
        byte[] buf = new byte[RANDOM_BYTES];
        rng().nextBytes(buf);
        return base64UrlNoPadding(buf);
    }

    /** S256 challenge for a verifier: BASE64URL_NO_PADDING(SHA256(UTF8(verifier))). */
    @NonNull
    public static String computeChallenge(@NonNull String verifier) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest(verifier.getBytes(StandardCharsets.UTF_8));
            return base64UrlNoPadding(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM/Android.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 43-char URL-safe opaque state/nonce. */
    @NonNull
    public static String generateToken() {
        return generateVerifier();
    }

    /**
     * Validate a verifier matches the RFC 7636 constraints enforced by the server
     * (§5.1): length 43–128, only {@code [A-Za-z0-9._~-]}.
     */
    public static boolean isValidVerifier(@NonNull String v) {
        if (v.length() < 43 || v.length() > 128) return false;
        for (int i = 0; i < v.length(); i++) {
            if (VERIFIER_ALPHABET.indexOf(v.charAt(i)) < 0) return false;
        }
        return true;
    }

    /** Validate state/nonce are 16–512 URL-safe chars (§5.2). */
    public static boolean isValidStateOrNonce(@NonNull String s) {
        if (s.length() < 16 || s.length() > 512) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.'
                    || c == '~';
            if (!ok) return false;
        }
        return true;
    }

    /** Constant-time comparison (state/iss checks, §5.3). */
    public static boolean constantTimeEquals(@NonNull String a, @NonNull String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static final char[] B64URL =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    /**
     * RFC 4648 base64url encoder without padding. Implemented locally (rather than
     * {@code android.util.Base64} or {@code java.util.Base64}) so the same code runs
     * unchanged on API 24 (where {@code java.util.Base64} is missing) and in plain
     * JVM unit tests (where Android {@code android.util.Base64} is not mocked).
     */
    @NonNull
    private static String base64UrlNoPadding(@NonNull byte[] data) {
        int n = data.length;
        if (n == 0) return "";
        int fullGroups = n / 3;
        int remainder = n % 3;
        int outLen = fullGroups * 4 + (remainder == 0 ? 0 : remainder + 1);
        char[] out = new char[outLen];
        int o = 0;
        for (int i = 0; i < fullGroups; i++) {
            int b0 = data[i * 3] & 0xff;
            int b1 = data[i * 3 + 1] & 0xff;
            int b2 = data[i * 3 + 2] & 0xff;
            out[o++] = B64URL[b0 >>> 2];
            out[o++] = B64URL[((b0 & 0x03) << 4) | (b1 >>> 4)];
            out[o++] = B64URL[((b1 & 0x0f) << 2) | (b2 >>> 6)];
            out[o++] = B64URL[b2 & 0x3f];
        }
        if (remainder == 1) {
            int b0 = data[fullGroups * 3] & 0xff;
            out[o++] = B64URL[b0 >>> 2];
            out[o++] = B64URL[(b0 & 0x03) << 4];
        } else if (remainder == 2) {
            int b0 = data[fullGroups * 3] & 0xff;
            int b1 = data[fullGroups * 3 + 1] & 0xff;
            out[o++] = B64URL[b0 >>> 2];
            out[o++] = B64URL[((b0 & 0x03) << 4) | (b1 >>> 4)];
            out[o++] = B64URL[(b1 & 0x0f) << 2];
        }
        return new String(out);
    }
}
