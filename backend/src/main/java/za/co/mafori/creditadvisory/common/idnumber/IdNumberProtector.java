package za.co.mafori.creditadvisory.common.idnumber;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Turns an ID number into what may be stored, and back (SRS 8). Used by the
 * client
 * endpoints AND the Excel import, so both produce exactly the same hash for the
 * same person.
 *
 * <ul>
 * <li><b>Lookup hash</b>: HMAC-SHA256 with a secret key. The same ID always
 * gives
 * the same 32 bytes, so duplicates are found without storing the number.
 * Without
 * the key, nobody can work out the hash for a given ID.</li>
 * <li><b>Encrypted copy</b>: AES-256-GCM with a fresh random nonce every time.
 * GCM also detects any change to the stored bytes. Layout:
 * {@code [key version: 1 byte][nonce: 12 bytes][ciphertext + 16-byte tag]}.</li>
 * </ul>
 *
 * <p>
 * <b>Keys</b> come from configuration (never from git) - see IdNumberConfig.
 * <ul>
 * <li>The <b>encryption</b> key can be rotated: add a new key with a higher
 * version, keep the old one for decrypting, re-encrypt in the background.</li>
 * <li>The <b>lookup</b> key can NOT simply be changed: every stored hash
 * depends on
 * it. Changing it means decrypting and re-hashing every client. IT must keep it
 * safe and backed up - losing it, or the encryption key, makes the stored ID
 * numbers unusable.</li>
 * </ul>
 *
 * <p>
 * Thread-safe: a new Mac / Cipher is created per call.
 */
public class IdNumberProtector {

    public static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    /**
     * Domain separation: these hashes can never collide with any other use of the
     * key.
     */
    private static final byte[] HASH_PREFIX = "credit-advisory:sa-id:v1:".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec lookupKey;
    private final int currentVersion;
    private final Map<Integer, SecretKeySpec> encryptionKeys;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param lookupKey                32 random bytes for the HMAC
     * @param currentEncryptionVersion version used for new encryptions (1 to 255)
     * @param encryptionKeysByVersion  every encryption key that may still be needed
     *                                 to
     *                                 decrypt, by version; must include the current
     *                                 one
     */
    public IdNumberProtector(byte[] lookupKey, int currentEncryptionVersion,
            Map<Integer, byte[]> encryptionKeysByVersion) {
        requireKey(lookupKey, "lookup key");
        if (currentEncryptionVersion < 1 || currentEncryptionVersion > 255) {
            throw new IllegalArgumentException("Encryption key version must be 1 to 255");
        }
        if (!encryptionKeysByVersion.containsKey(currentEncryptionVersion)) {
            throw new IllegalArgumentException("No encryption key for current version " + currentEncryptionVersion);
        }
        Map<Integer, SecretKeySpec> keys = new TreeMap<>();
        encryptionKeysByVersion.forEach((version, key) -> {
            requireKey(key, "encryption key v" + version);
            if (MessageDigest.isEqual(key, lookupKey)) {
                throw new IllegalArgumentException("The lookup key and an encryption key must be different");
            }
            keys.put(version, new SecretKeySpec(key, "AES"));
        });
        this.lookupKey = new SecretKeySpec(lookupKey, "HmacSHA256");
        this.currentVersion = currentEncryptionVersion;
        this.encryptionKeys = Map.copyOf(keys);
    }

    /** Everything that is stored for a new client. */
    public ProtectedIdNumber protect(SaIdNumber id) {
        return new ProtectedIdNumber(lookupHash(id), encrypt(id), id.last4());
    }

    /**
     * 32 bytes. Same ID number in, same hash out - use it to find an existing
     * client.
     */
    public byte[] lookupHash(SaIdNumber id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(lookupKey);
            mac.update(HASH_PREFIX);
            return mac.doFinal(id.value().getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ID number hashing failed", e);
        }
    }

    public byte[] encrypt(SaIdNumber id) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte version = (byte) currentVersion;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKeys.get(currentVersion), new GCMParameterSpec(TAG_BITS, nonce));
            // The version byte is authenticated too, so it cannot be swapped.
            cipher.updateAAD(new byte[] { version });
            byte[] sealed = cipher.doFinal(id.value().getBytes(StandardCharsets.US_ASCII));
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(version).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ID number encryption failed", e);
        }
    }

    /**
     * @throws IllegalStateException if the bytes were changed, belong to an unknown
     *                               key
     *                               version, or were made with a different key. The
     *                               message never contains data.
     */
    public SaIdNumber decrypt(byte[] stored) {
        if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8 + 1) {
            throw new IllegalStateException("Stored ID number is too short to be valid");
        }
        int version = stored[0] & 0xFF;
        SecretKeySpec key = encryptionKeys.get(version);
        if (key == null) {
            throw new IllegalStateException("No encryption key configured for version " + version);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(new byte[] { stored[0] });
            byte[] plain = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
            return SaIdNumber.parse(new String(plain, StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException | InvalidIdNumberException e) {
            throw new IllegalStateException("Stored ID number could not be decrypted (tampered, or wrong key)");
        }
    }

    /** True if this was encrypted with an older key and should be re-encrypted. */
    public boolean needsReEncryption(byte[] stored) {
        return stored != null && stored.length > 0 && (stored[0] & 0xFF) != currentVersion;
    }

    private static void requireKey(byte[] key, String name) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("The ID-number " + name + " must be exactly " + KEY_BYTES
                    + " bytes (44 characters of base64)");
        }
    }
}
