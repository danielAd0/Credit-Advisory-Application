package za.co.mafori.creditadvisory.common.idnumber;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the IdNumberProtector from IdNumberProperties. If a key is missing or
 * wrong, the application refuses to START - better than finding out when the
 * first
 * client is saved. The error names the property, never the key.
 */
@Configuration
@EnableConfigurationProperties(IdNumberProperties.class)
public class IdNumberConfig {

    @Bean
    public IdNumberProtector idNumberProtector(IdNumberProperties properties) {
        byte[] current = decode(properties.encryptionKey(), "app.idnumber.encryption-key");
        Map<Integer, byte[]> encryptionKeys = new HashMap<>();
        encryptionKeys.put(properties.encryptionKeyVersion(), current);

        // During a key rotation the old key stays, only to read what is not
        // re-encrypted yet.
        String previousKey = properties.previousEncryptionKey();
        if (previousKey != null && !previousKey.isBlank()) {
            int previousVersion = properties.previousEncryptionKeyVersion();
            if (previousVersion < 1 || previousVersion > 255 || previousVersion == properties.encryptionKeyVersion()) {
                throw new IllegalStateException("app.idnumber.previous-encryption-key-version must be 1 to 255 and"
                        + " differ from app.idnumber.encryption-key-version ("
                        + properties.encryptionKeyVersion() + ").");
            }
            byte[] previous = decode(previousKey, "app.idnumber.previous-encryption-key");
            if (MessageDigest.isEqual(previous, current)) {
                throw new IllegalStateException("app.idnumber.previous-encryption-key is the same as"
                        + " app.idnumber.encryption-key: a rotation needs a new key.");
            }
            encryptionKeys.put(previousVersion, previous);
        }

        return new IdNumberProtector(decode(properties.lookupKey(), "app.idnumber.lookup-key"),
                properties.encryptionKeyVersion(), encryptionKeys);
    }

    private static byte[] decode(String base64, String property) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(property + " is not set. Generate keys as described in the README"
                    + " (section 3) and put them in application-local.properties.");
        }
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(property + " is not valid base64.");
        }
    }
}
