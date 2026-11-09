package za.co.mafori.creditadvisory.auth.mfa;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts authenticator secrets for the database with AES-256-GCM, under their
 * own key (app.mfa.encryption-key / MFA_ENCRYPTION_KEY), separate from the ID
 * number keys. Anyone who copies the database cannot generate codes.
 *
 * <p>
 * Stored layout: 1 byte key version (1), 12 bytes nonce, ciphertext + 16 byte
 * tag. GCM also refuses any changed byte. A missing key only fails when MFA is
 * actually used, so a developer with app.mfa.required=false needs no key;
 * production refuses to start without one (ProductionSafetyCheck).
 */
public class MfaSecretCipher {

    static final int KEY_BYTES = 32;
    private static final byte KEY_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** @param key 32 bytes, or null when no key is configured */
    public MfaSecretCipher(byte[] key) {
        if (key != null && key.length != KEY_BYTES) {
            throw new IllegalStateException("app.mfa.encryption-key must be " + KEY_BYTES
                    + " bytes (44 base64 characters).");
        }
        this.key = key == null ? null : new SecretKeySpec(key, "AES");
    }

    public byte[] encrypt(byte[] secret) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(secret);
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length)
                    .put(KEY_VERSION).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the authenticator secret", e);
        }
    }

    public byte[] decrypt(byte[] stored) {
        if (stored.length < 1 + NONCE_BYTES + TAG_BITS / 8 || stored[0] != KEY_VERSION) {
            throw new IllegalStateException("Stored authenticator secret has an unknown format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            return cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
        } catch (GeneralSecurityException e) {
            // Wrong key or a changed byte. Never include the data in the message.
            throw new IllegalStateException("Could not decrypt the authenticator secret", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException("app.mfa.encryption-key is not set. Generate one as described in the"
                    + " README and put it in application-local.properties.");
        }
        return key;
    }
}
