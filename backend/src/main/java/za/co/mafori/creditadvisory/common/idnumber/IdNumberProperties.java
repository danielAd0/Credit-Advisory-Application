package za.co.mafori.creditadvisory.common.idnumber;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * ID-number keys, each 32 random bytes written as base64 (44 characters).
 *
 * <pre>
 * app.idnumber.lookup-key=...                        (prod env: ID_NUMBER_LOOKUP_KEY)
 * app.idnumber.encryption-key=...                    (prod env: ID_NUMBER_ENCRYPTION_KEY)
 * app.idnumber.encryption-key-version=1              (prod env: ID_NUMBER_ENCRYPTION_KEY_VERSION)
 * app.idnumber.previous-encryption-key=...           (only during a key rotation)
 * app.idnumber.previous-encryption-key-version=1     (only during a key rotation)
 * </pre>
 *
 * A rotation (docs/it-handover.md section 4.1) moves the current key to
 * previous-encryption-key, sets a new current key with a higher version, and
 * lets the background job re-encrypt everything. One rotation at a time: the
 * previous key is removed again once nothing uses it.
 *
 * Locally they live in application-local.properties (gitignored). In production
 * IT sets them as environment variables. They are never committed.
 */
@ConfigurationProperties(prefix = "app.idnumber")
public record IdNumberProperties(
        String lookupKey,
        String encryptionKey,
        @DefaultValue("1") int encryptionKeyVersion,
        String previousEncryptionKey,
        @DefaultValue("0") int previousEncryptionKeyVersion) {

    /** Never print the keys, even by accident. */
    @Override
    public String toString() {
        return "IdNumberProperties[keys hidden, encryptionKeyVersion=" + encryptionKeyVersion
                + ", previousEncryptionKeyVersion=" + previousEncryptionKeyVersion + "]";
    }
}
