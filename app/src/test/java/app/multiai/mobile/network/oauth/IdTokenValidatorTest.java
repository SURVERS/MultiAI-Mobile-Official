package app.multiai.mobile.network.oauth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Date;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

/**
 * Negative-scenario unit tests for {@link IdTokenValidator} per §14 of the
 * integration document: invalid state/iss/nonce handling, one-time code use,
 * ID token signature validation against JWKS, etc.
 *
 * <p>Generates its own RSA key pair + signs sample RS256 JWTs, so the test is
 * self-contained (no network, no MultiAI server).
 */
public class IdTokenValidatorTest {

    private static final String ISSUER = "https://multiai.store";
    private static final String CLIENT = "app.multiai.mobile.oauth";
    private static final String NONCE = "n-nonce-deadbeef-cafe-1234";
    private static final long NOW = 1_730_000_000L;

    private RSAPrivateKey priv;
    private RSAPublicKey pub;
    private String kid;
    private JSONObject jwks;

    @Before
    public void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        priv = (RSAPrivateKey) kp.getPrivate();
        pub = (RSAPublicKey) kp.getPublic();
        kid = "test-key-1";
        jwks = new JSONObject()
                .put("keys", new JSONArray()
                        .put(new JSONObject()
                                .put("kty", "RSA")
                                .put("kid", kid)
                                .put("use", "sig")
                                .put("n", b64Url(pub.getModulus().toByteArray()))
                                .put("e", b64Url(pub.getPublicExponent().toByteArray()))));
    }

    @Test
    public void happyPathValidates() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, CLIENT, NONCE, NOW, NOW + 600));
        IdTokenValidator.Result r = IdTokenValidator.validate(jwt, jwks, ISSUER, CLIENT, NONCE, NOW);
        assertTrue("expected valid; got " + r.error, r.valid);
        assertEquals(CLIENT, r.claims.optJSONArray("aud").getString(0));
    }

    @Test public void rejectsWrongAlg() throws Exception {
        String jwt = sign(header(kid, "none"), claims(ISSUER, CLIENT, NONCE, NOW, NOW + 600));
        assertError("unsupported_alg", jwt);
    }

    @Test public void rejectsUnknownKid() throws Exception {
        String jwt = sign(header("other-kid", "RS256"), claims(ISSUER, CLIENT, NONCE, NOW, NOW + 600));
        assertError("unknown_kid", jwt);
    }

    @Test public void rejectsBadSignature() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, CLIENT, NONCE, NOW, NOW + 600));
        // Flip a char in the signature segment
        jwt = jwt.substring(0, jwt.length() - 2)
                + (jwt.charAt(jwt.length() - 2) == 'A' ? 'B' : 'A')
                + jwt.substring(jwt.length() - 1);
        assertError("bad_signature", jwt);
    }

    @Test public void rejectsWrongIssuer() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims("https://evil.example", CLIENT, NONCE, NOW, NOW + 600));
        assertError("bad_iss", jwt);
    }

    @Test public void rejectsWrongAudience() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, "someone-else", NONCE, NOW, NOW + 600));
        assertError("bad_aud", jwt);
    }

    @Test public void rejectsExpired() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, CLIENT, NONCE, NOW - 10_000, NOW - 9000));
        assertError("expired", jwt);
    }

    @Test public void rejectsFutureIat() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, CLIENT, NONCE, NOW + 5_000, NOW + 5600));
        // auth_time omitted; iat far in the future -> future_iat
        assertError("future_iat", jwt);
    }

    @Test public void rejectsBadNonce() throws Exception {
        String jwt = sign(header(kid, "RS256"), claims(ISSUER, CLIENT, "wrong-nonce", NOW, NOW + 600));
        assertError("bad_nonce", jwt);
    }

    @Test public void skipsNonceWhenExpectedNull() throws Exception {
        // Device flow (§6) has no nonce in the id_token.
        String jwt = sign(header(kid, "RS256"), claimsNoNonce(ISSUER, CLIENT, NOW, NOW + 600));
        IdTokenValidator.Result r = IdTokenValidator.validate(jwt, jwks, ISSUER, CLIENT, null, NOW);
        assertTrue("device-flow token must validate without nonce", r.valid);
    }

    @Test public void rejectsMalformedJwt() {
        assertError("malformed_jwt", "not.a.jwt");
        assertError("malformed_jwt", "two.parts");
        IdTokenValidator.Result r = IdTokenValidator.validate("xxx.yyy.zzz", jwks, ISSUER, CLIENT, NONCE, NOW);
        assertTrue("garbage decodes -> malformed or bad_*", !r.valid);
    }

    // ─────────────────── helpers ───────────────────

    private void assertError(String expected, String jwt) {
        IdTokenValidator.Result r = IdTokenValidator.validate(jwt, jwks, ISSUER, CLIENT, NONCE, NOW);
        assertEquals("expected error '" + expected + "', got: " + r.error,
                expected, r.error);
    }

    private JSONObject header(String kid, String alg) throws Exception {
        JSONObject h = new JSONObject();
        if (kid != null) h.put("kid", kid);
        h.put("alg", alg);
        return h;
    }

    private JSONObject claims(String iss, String aud, String nonce, long iat, long exp) throws Exception {
        JSONObject c = baseClaims(iss, iat, exp);
        c.put("aud", new JSONArray().put(aud));
        c.put("nonce", nonce);
        return c;
    }

    private JSONObject claimsNoNonce(String iss, String aud, long iat, long exp) throws Exception {
        JSONObject c = baseClaims(iss, iat, exp);
        c.put("aud", new JSONArray().put(aud));
        return c;
    }

    private JSONObject baseClaims(String iss, long iat, long exp) throws Exception {
        JSONObject c = new JSONObject();
        c.put("iss", iss);
        c.put("sub", "usr_test");
        c.put("iat", iat);
        c.put("exp", exp);
        c.put("auth_time", iat);
        c.put("acr", "urn:multiai:aal:2");
        c.put("amr", new JSONArray().put("totp").put("mfa"));
        return c;
    }

    private String sign(JSONObject header, JSONObject claims) throws Exception {
        String h = b64Url(header.toString().getBytes(StandardCharsets.UTF_8));
        String p = b64Url(claims.toString().getBytes(StandardCharsets.UTF_8));
        byte[] signingInput = (h + "." + p).getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(priv);
        s.update(signingInput);
        String sig = b64Url(s.sign());
        return h + "." + p + "." + sig;
    }

    private static String b64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static String b64Url(java.math.BigInteger i) {
        return b64Url(i.toByteArray());
    }
}