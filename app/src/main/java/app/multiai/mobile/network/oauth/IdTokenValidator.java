package app.multiai.mobile.network.oauth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;

/**
 * Strict RS256 ID-token validator per §7 of the integration document.
 *
 * <p>ID tokens are NOT simply decoded. The validator checks:
 * <ol>
 *   <li>JWT {@code alg} is exactly {@code RS256} (reject {@code none});
 *   <li>{@code kid} is present in the JWKS (caller resolves/refreshes);
 *   <li>signature verifies against the matching RSA public key;
 *   <li>{@code iss} equals the trusted issuer;
 *   <li>{@code aud} contains the current {@code client_id};
 *   <li>{@code exp} not expired and {@code iat} within an acceptable clock window; *
 *   <li>for browser (Authorization Code) flow, {@code nonce} matches the saved nonce.
 * </ol>
 *
 * <p>JWT segments use a small pure-Java base64url decoder so the exact same
 * validator runs on Android and in local JVM unit tests.
 *
 * <p>Small JSON-only dependency aside from Android's platform decoder.
 */
public final class IdTokenValidator {

    private static final long SKEW_SECONDS = 60;

    private IdTokenValidator() {}

    /** Result of {@link #validate} — never null; {@code valid} gates the others. */
    public static final class Result {
        public final boolean valid;
        public final String error;
        public final JSONObject claims;
        private Result(boolean v, String e, JSONObject c) {
            valid = v; error = e; claims = c;
        }
        static Result ok(@NonNull JSONObject claims)    { return new Result(true,  null, claims); }
        static Result err(@NonNull String e)           { return new Result(false, e,    null); }
    }

    /**
     * Validate an RS256 ID token against a JWKS.
     *
     * @param idToken   raw JWT string
     * @param jwks      parsed JWKS document: {@code {"keys":[{"kty":"RSA","kid","use":"sig","n","e"}, ...]}}
     * @param issuer    trusted issuer ({@link OAuthConfig#getIssuer()})
     * @param clientId  expected {@code aud} entry
     * @param expectedNonce  nonce saved for this login attempt, or null (device flow — §6 states id_token has no nonce)
     * @param nowSeconds current time; passes in for testability
     */
    @NonNull
    public static Result validate(@NonNull String idToken,
                                  @NonNull JSONObject jwks,
                                  @NonNull String issuer,
                                  @NonNull String clientId,
                                  @Nullable String expectedNonce,
                                  long nowSeconds) {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) return Result.err("malformed_jwt");
        if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty())
            return Result.err("malformed_jwt");

        JSONObject header;
        JSONObject claims;
        try {
            header = new JSONObject(new String(base64UrlDecode(parts[0]), StandardCharsets.UTF_8));
            claims = new JSONObject(new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Result.err("malformed_jwt");
        }

        // 1. alg
        Object alg = header.opt("alg");
        if (!"RS256".equals(alg)) return Result.err("unsupported_alg");

        // 2. kid lookup
        String kid = header.optString("kid", null);
        if (kid == null) return Result.err("missing_kid");
        PublicKey key = findKey(jwks, kid);
        if (key == null) return Result.err("unknown_kid");

        // 3. signature
        byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
        byte[] sig;
        try {
            sig = base64UrlDecode(parts[2]);
        } catch (Exception e) {
            return Result.err("bad_signature");
        }
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(signingInput);
            if (!verifier.verify(sig)) return Result.err("bad_signature");
        } catch (Exception e) {
            return Result.err("bad_signature");
        }

        // 4. iss
        if (!issuer.equals(claims.optString("iss", ""))) return Result.err("bad_iss");

        // 5. aud contains client_id
        String audErr = checkAud(claims, clientId);
        if (audErr != null) return Result.err(audErr);

        // 6. exp / iat
        long exp = claims.optLong("exp", 0);
        if (exp <= 0) return Result.err("missing_exp");
        if (nowSeconds > exp + SKEW_SECONDS) return Result.err("expired");

        long iat = claims.optLong("iat", 0);
        if (iat > 0 && iat > nowSeconds + SKEW_SECONDS) return Result.err("future_iat");

        // 7. nonce (browser flow only — null expectedNonce skips, e.g. device flow §6)
        if (expectedNonce != null) {
            String nonce = claims.optString("nonce", "");
            if (!PkceUtil.constantTimeEquals(nonce, expectedNonce))
                return Result.err("bad_nonce");
        }

        return Result.ok(claims);
    }

    @Nullable
    private static PublicKey findKey(@NonNull JSONObject jwks, @NonNull String kid) {
        JSONArray keys = jwks.optJSONArray("keys");
        if (keys == null) return null;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject k = keys.optJSONObject(i);
            if (k == null) continue;
            if (!"RSA".equals(k.optString("kty"))) continue;
            if (!kid.equals(k.optString("kid"))) continue;
            try {
                BigInteger n = new BigInteger(1, base64UrlDecode(k.optString("n")));
                BigInteger e = new BigInteger(1, base64UrlDecode(k.optString("e")));
                return KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(n, e));
            } catch (Exception ex) {
                continue;
            }
        }
        return null;
    }

    @Nullable
    private static String checkAud(@NonNull JSONObject claims, @NonNull String clientId) {
        Object aud = claims.opt("aud");
        if (aud instanceof JSONArray) {
            JSONArray arr = (JSONArray) aud;
            for (int i = 0; i < arr.length(); i++) {
                if (clientId.equals(arr.optString(i))) return null;
            }
            return "bad_aud";
        }
        if (aud instanceof String) {
            return clientId.equals(aud) ? null : "bad_aud";
        }
        return "missing_aud";
    }

    /** Pure-Java RFC 4648 base64url decoder (unpadded input is accepted). */
    @NonNull
    static byte[] base64UrlDecode(@NonNull String value) throws IllegalArgumentException {
        if (value.indexOf('=') >= 0 && !value.matches("[A-Za-z0-9_-]*={0,2}")) {
            throw new IllegalArgumentException("invalid_base64url");
        }
        String normalized = value.replace('-', '+').replace('_', '/');
        int remainder = normalized.length() & 3;
        if (remainder == 1) throw new IllegalArgumentException("invalid_base64url_length");
        if (remainder > 0) normalized += remainder == 2 ? "==" : "=";
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(normalized.length() * 3 / 4);
        int accumulator = 0;
        int bits = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '=') break;
            int digit;
            if (c >= 'A' && c <= 'Z') digit = c - 'A';
            else if (c >= 'a' && c <= 'z') digit = c - 'a' + 26;
            else if (c >= '0' && c <= '9') digit = c - '0' + 52;
            else if (c == '+') digit = 62;
            else if (c == '/') digit = 63;
            else throw new IllegalArgumentException("invalid_base64url_character");
            accumulator = (accumulator << 6) | digit;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                output.write((accumulator >> bits) & 0xff);
            }
        }
        return output.toByteArray();
    }

    /** Quiet helper so tests can read a header claim without going through full validation. */
    @Nullable
    public static String headerKid(@NonNull String idToken) {
        try {
            String[] p = idToken.split("\\.");
            JSONObject h = new JSONObject(new String(base64UrlDecode(p[0]), StandardCharsets.UTF_8));
            return h.optString("kid", null);
        } catch (Exception e) {
            return null;
        }
    }
}
