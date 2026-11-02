package za.co.mafori.creditadvisory.common.idnumber;

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
        Map<Integer, byte[]> encryptionKeys = new HashMap<>();
        if (properties.previousEncryptionKeys() != null) {
            properties.previousEncryptionKeys().forEach((version, key) -> encryptionKeys.put(version,
                    decode(key, "app.idnumber.previous-encryption-keys." + version)));
        }
        encryptionKeys.put(properties.encryptionKeyVersion(),
                decode(properties.encryptionKey(), "app.idnumber.encryption-key"));

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
