package za.co.mafori.creditadvisory.common.idnumber;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ID-number keys, each 32 random bytes written as base64 (44 characters).
 *
 * <pre>
 * app.idnumber.lookup-key=...              (env: APP_IDNUMBER_LOOKUP_KEY)
 * app.idnumber.encryption-key=...          (env: APP_IDNUMBER_ENCRYPTION_KEY)
 * app.idnumber.encryption-key-version=1    (env: APP_IDNUMBER_ENCRYPTION_KEY_VERSION)
 * app.idnumber.previous-encryption-keys.1=...  (only after a key rotation)
 * </pre>
 *
 * Locally they live in application-local.properties (gitignored). In production
 * IT
 * sets them as environment variables. They are never committed.
 */
@ConfigurationProperties(prefix = "app.idnumber")
public record IdNumberProperties(
        String lookupKey,
        String encryptionKey,
        @DefaultValue("1") int encryptionKeyVersion,
        Map<Integer, String> previousEncryptionKeys) {

    /** Never print the keys, even by accident. */
    @Override
    public String toString() {
        return "IdNumberProperties[keys hidden, encryptionKeyVersion=" + encryptionKeyVersion + "]";
    }
}
