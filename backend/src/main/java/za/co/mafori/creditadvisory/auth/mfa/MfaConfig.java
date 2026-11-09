package za.co.mafori.creditadvisory.auth.mfa;

import java.util.Base64;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the MfaSecretCipher. A key that is set but wrong stops start-up. */
@Configuration
@EnableConfigurationProperties(MfaProperties.class)
public class MfaConfig {

    @Bean
    public MfaSecretCipher mfaSecretCipher(MfaProperties properties) {
        String key = properties.encryptionKey();
        if (key == null || key.isBlank()) {
            return new MfaSecretCipher(null);
        }
        try {
            return new MfaSecretCipher(Base64.getDecoder().decode(key.strip()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.mfa.encryption-key is not valid base64.");
        }
    }
}
