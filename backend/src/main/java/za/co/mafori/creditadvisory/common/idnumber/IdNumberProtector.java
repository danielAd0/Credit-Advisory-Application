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


public class IdNumberProtector {

    public static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    
    private static final byte[] HASH_PREFIX = "credit-advisory:sa-id:v1:".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec lookupKey;
    private final int currentVersion;
    private final Map<Integer, SecretKeySpec> encryptionKeys;
    private final SecureRandom random = new SecureRandom();

   
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

    
    public byte[] sealText(String purpose, String text) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte version = (byte) currentVersion;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKeys.get(currentVersion), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(textAad(version, purpose));
            byte[] sealed = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + NONCE_BYTES + sealed.length).put(version).put(nonce).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    
    public String openText(String purpose, byte[] stored) {
        if (stored == null || stored.length < 1 + NONCE_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Sealed text is too short to be valid");
        }
        SecretKeySpec key = encryptionKeys.get(stored[0] & 0xFF);
        if (key == null) {
            throw new IllegalStateException("No encryption key configured for version " + (stored[0] & 0xFF));
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, NONCE_BYTES));
            cipher.updateAAD(textAad(stored[0], purpose));
            byte[] plain = cipher.doFinal(stored, 1 + NONCE_BYTES, stored.length - 1 - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Sealed text could not be decrypted (tampered, wrong purpose or key)");
        }
    }

    /** version byte + "credit-advisory:text:" + purpose: never equal to the ID numbers' AAD (one byte). */
    private static byte[] textAad(byte version, String purpose) {
        byte[] label = ("credit-advisory:text:" + purpose).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + label.length).put(version).put(label).array();
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
