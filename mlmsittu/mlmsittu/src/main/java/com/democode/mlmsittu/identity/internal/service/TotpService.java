package com.democode.mlmsittu.identity.internal.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * TOTP (RFC 6238) for administrator 2FA — architecture §3.2, development plan P1-05.
 *
 * <p>Written out rather than pulled from a library: the algorithm is forty lines, the RFC is
 * unambiguous, and it saves carrying a transitive QR-code dependency we do not need.
 *
 * <p><b>Not to be reused for mobile verification.</b> Phase 4's SMS OTP is a stored random code
 * with its own TTL and lockout. The architecture is explicit that the two must not share an
 * implementation, because TOTP is derived from a long-lived shared secret while an SMS OTP is a
 * one-shot value — conflating them would let a leaked secret mint valid SMS codes forever.
 */
@Service
public class TotpService {

    /** RFC 4648 base32, the alphabet every authenticator app expects. */
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final int SECRET_BYTES = 20;
    private static final int DIGITS = 6;
    private static final int PERIOD_SECONDS = 30;

    /**
     * How many 30-second steps either side of "now" are accepted. One step tolerates a clock a
     * little ahead or behind; more than that meaningfully widens the window for a stolen code.
     */
    private static final int TOLERANCE_STEPS = 1;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String issuer;

    public TotpService(@Value("${security.totp.issuer:MLM Sittu}") String issuer) {
        this.issuer = issuer;
    }

    /** A fresh 160-bit shared secret, base32 encoded for entry into an authenticator app. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * The URI an authenticator app consumes, normally via QR code. Rendering the QR is the
     * frontend's job; the backend never needs an image library.
     */
    public String otpauthUri(String secret, String accountName) {
        String label = encode(issuer) + ":" + encode(accountName);
        return "otpauth://totp/"
                + label
                + "?secret="
                + secret
                + "&issuer="
                + encode(issuer)
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + PERIOD_SECONDS;
    }

    /** The code valid right now. Used by the CLI helper and by tests, never by an endpoint. */
    public String currentCode(String secret) {
        return codeAt(secret, Instant.now().getEpochSecond() / PERIOD_SECONDS);
    }

    /**
     * @return true if {@code code} is valid for {@code secret} within the tolerance window.
     *     Comparison is constant-time so response timing cannot be used to guess digits.
     */
    public boolean verify(String secret, String code) {
        if (secret == null || code == null) {
            return false;
        }
        String candidate = code.trim();
        if (candidate.length() != DIGITS || !candidate.chars().allMatch(Character::isDigit)) {
            return false;
        }

        long currentStep = Instant.now().getEpochSecond() / PERIOD_SECONDS;
        boolean matched = false;
        for (long step = currentStep - TOLERANCE_STEPS;
                step <= currentStep + TOLERANCE_STEPS;
                step++) {
            // No early exit: checking every step keeps the work constant regardless of which
            // one matches.
            matched |=
                    MessageDigest.isEqual(
                            codeAt(secret, step).getBytes(StandardCharsets.US_ASCII),
                            candidate.getBytes(StandardCharsets.US_ASCII));
        }
        return matched;
    }

    // ------------------------------------------------------------------ RFC 6238 / RFC 4226

    private String codeAt(String secret, long step) {
        byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }

        byte[] hash = hmacSha1(base32Decode(secret), counter);

        // Dynamic truncation, RFC 4226 §5.4.
        int offset = hash[hash.length - 1] & 0x0F;
        int binary =
                ((hash[offset] & 0x7F) << 24)
                        | ((hash[offset + 1] & 0xFF) << 16)
                        | ((hash[offset + 2] & 0xFF) << 8)
                        | (hash[offset + 3] & 0xFF);

        return String.format("%0" + DIGITS + "d", binary % 1_000_000);
    }

    private byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA1 unavailable", e);
        }
    }

    // ------------------------------------------------------------------ base32

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String cleaned = encoded.trim().replace("=", "").replace(" ", "").toUpperCase();
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int index = 0;
        for (char c : cleaned.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Secret is not valid base32");
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
