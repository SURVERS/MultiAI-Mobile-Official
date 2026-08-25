package app.multiai.mobile.network.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Unit tests for {@link PkceUtil} — PKCE/state/nonce generation per §5.1.
 *
 * <p>NOTE: {@link PkceUtil} uses a local base64url encoder (not android.util.Base64)
 * so it runs unchanged in a plain JVM unit test.
 */
public class PkceUtilTest {

    @Test
    public void verifierIs43CharsAndAlphabetOk() {
        String v = PkceUtil.generateVerifier();
        assertEquals("32 bytes -> 43 base64url chars", 43, v.length());
        assertTrue("verifier constraints (§5.1)", PkceUtil.isValidVerifier(v));
        // Only RFC 4648 base64url chars (unpadded)
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            assertTrue("char '" + c + "' illegal",
                    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                            || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.'
                            || c == '~');
            // Note: '.' and '~' are part of the verifier alphabet per RFC 7636 §4.1
            // even though plain base64url doesn't emit them; the encoder here emits only
            // A–Z a–z 0–9 '-' '_'. That still satisfies isValidVerifier's alphabet check.
        }
    }

    @Test
    public void challengeIsS256OfVerifier() throws Exception {
        String v = PkceUtil.generateVerifier();
        String c = PkceUtil.computeChallenge(v);
        assertEquals(43, c.length());
        // Recompute independently
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] expected = sha.digest(v.getBytes(StandardCharsets.UTF_8));
        String manual = manualBase64Url(expected);
        assertEquals(manual, c);
    }

    @Test
    public void stateAndNonceAreSafe() {
        String s = PkceUtil.generateToken();
        String n = PkceUtil.generateToken();
        assertEquals(43, s.length());
        assertEquals(43, n.length());
        assertTrue(PkceUtil.isValidStateOrNonce(s));
        assertTrue(PkceUtil.isValidStateOrNonce(n));
        assertNotEquals("state != nonce", s, n);
    }

    @Test
    public void differentEachCall() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            seen.add(PkceUtil.generateVerifier());
            seen.add(PkceUtil.generateToken());
        }
        assertTrue("no collisions in 128 draws", seen.size() == 128);
    }

    @Test
    public void rejectsBadVerifier() {
        assertFalse(PkceUtil.isValidVerifier(""));           // too short
        assertFalse(PkceUtil.isValidVerifier("$"));         // illegal char
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 129; i++) tooLong.append('a');
        assertFalse(PkceUtil.isValidVerifier(tooLong.toString()));
    }

    @Test
    public void rejectsBadState() {
        assertFalse(PkceUtil.isValidStateOrNonce(""));      // too short
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 513; i++) tooLong.append('a');
        assertFalse(PkceUtil.isValidStateOrNonce(tooLong.toString()));
        assertTrue(PkceUtil.isValidStateOrNonce("abcdef0123456789-_")); // 20 ok
    }

    @Test
    public void constantTimeEquality() {
        String a = "abcdef0123456789-_";
        assertTrue(PkceUtil.constantTimeEquals(a, a));
        assertFalse(PkceUtil.constantTimeEquals(a, "bcdef0123456789-_1"));
        assertFalse(PkceUtil.constantTimeEquals(a, "shorter"));
    }

    // ── helpers ──

    private static String manualBase64Url(byte[] data) {
        java.util.Base64.Encoder e = java.util.Base64.getUrlEncoder().withoutPadding();
        return e.encodeToString(data);
    }
}