package com.democode.mlmsittu.onboarding.internal.nic;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * Protects national identity numbers (architecture §2.3, P4-07).
 *
 * <p>Two separate operations on the same value, for two different needs:
 *
 * <ul>
 *   <li><b>HMAC-SHA256 for uniqueness.</b> Deterministic, so "has this NIC registered before?" is
 *       an index lookup. Never reversible.
 *   <li><b>AES-256-GCM for retrieval.</b> Randomised, authenticated, so a reviewer can be shown
 *       the number and a tampered ciphertext fails rather than decrypting to nonsense.
 * </ul>
 *
 * <p><b>The pepper must live outside the database.</b> A Sri Lankan NIC is nine digits and a letter,
 * or twelve digits — a space small enough to enumerate completely. An unpeppered hash of one is
 * reversible from a stolen dump alone, in minutes. The pepper is what makes the dump useless
 * without a second compromise.
 *
 * <p>Plaintext is never stored, never logged, and never queried.
 */
@Service
public class NicProtection {

    private static final Logger log = LoggerFactory.getLogger(NicProtection.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Obvious placeholders, so an unconfigured deployment is loud rather than quietly insecure. */
    private static final String DEV_PEPPER = "dev-only-pepper-change-me";
    private static final String DEV_KEY = "dev-only-encryption-key-change-me";

    private final SecretKeySpec pepper;
    private final SecretKeySpec encryptionKey;

    public NicProtection(
            @Value("${security.nic.pepper:" + DEV_PEPPER + "}") String pepperValue,
            @Value("${security.nic.encryption-key:" + DEV_KEY + "}") String keyValue,
            Environment environment) {

        boolean usingDevSecrets = DEV_PEPPER.equals(pepperValue) || DEV_KEY.equals(keyValue);
        boolean production = environment.matchesProfiles("prod");

        if (usingDevSecrets && production) {
            // Refuse to start rather than warn.
            //
            // A warning was enough while this only ever ran on a laptop. In a container it is not:
            // nobody reads a container's startup log, the application would come up looking
            // healthy, and it would encrypt real identity numbers with a key published in this
            // repository. Worse than the encryption is the hash — nic_hash is an HMAC used for the
            // uniqueness index, and a known pepper makes it brute-forceable, because a Sri Lankan
            // NIC has far too little entropy to survive an attacker who knows the key.
            //
            // Failing here costs a deployment that stops with a clear message. Not failing costs
            // an identity-data breach that nobody notices for months.
            throw new IllegalStateException(
                    """
                    NIC protection is still using development secrets under the 'prod' profile.

                    Set both before starting:
                      security.nic.pepper          (SECURITY_NIC_PEPPER)
                      security.nic.encryption-key  (SECURITY_NIC_ENCRYPTION_KEY)

                    Generate them with:  openssl rand -base64 48

                    These must never change once real NIC data exists — the pepper is baked into
                    every stored hash, and rotating it silently orphans every row.""");
        }

        if (usingDevSecrets) {
            log.warn(
                    """

                    ============================================================================
                      NIC protection is using DEVELOPMENT defaults.
                      Fine locally. Under the 'prod' profile this is a startup failure, not a
                      warning — see the message in NicProtection.
                    ============================================================================""");
        }

        this.pepper = new SecretKeySpec(pepperValue.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        // SHA-256 of the configured secret, so any passphrase length yields a valid AES-256 key.
        this.encryptionKey = new SecretKeySpec(sha256(keyValue), "AES");
    }

    /**
     * Deterministic keyed digest, used only for the uniqueness index.
     *
     * <p>Normalised first so "199012345678", " 199012345678 " and a lower-case suffix are one
     * identity rather than three that each register successfully.
     */
    public byte[] hash(String nic) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(pepper);
            return mac.doFinal(normalise(nic).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Could not hash NIC", e);
        }
    }

    /** @return IV prefixed to the ciphertext, so decryption needs nothing else stored alongside */
    public byte[] encrypt(String nic) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(normalise(nic).getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return combined;
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt NIC", e);
        }
    }

    /** Throws when the ciphertext has been altered — GCM authenticates as well as encrypts. */
    public String decrypt(byte[] combined) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_BYTES);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext =
                    cipher.doFinal(combined, GCM_IV_BYTES, combined.length - GCM_IV_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not decrypt NIC — wrong key or altered data", e);
        }
    }

    /** Safe to display and to log: identifies a record without revealing the number. */
    public String last4(String nic) {
        String normalised = normalise(nic);
        return normalised.length() <= 4
                ? normalised
                : normalised.substring(normalised.length() - 4);
    }

    private String normalise(String nic) {
        return nic.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
