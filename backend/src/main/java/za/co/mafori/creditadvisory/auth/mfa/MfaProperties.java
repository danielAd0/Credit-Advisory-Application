package za.co.mafori.creditadvisory.auth.mfa;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Two-step sign-in settings.
 *
 * <pre>
 * app.mfa.required=true                 (every user; production refuses false)
 * app.mfa.encryption-key=...            (32 random bytes as base64; env MFA_ENCRYPTION_KEY)
 * app.mfa.issuer=Credit Advisory        (the name the authenticator app shows)
 * </pre>
 */
@ConfigurationProperties(prefix = "app.mfa")
public record MfaProperties(
        @DefaultValue("true") boolean required,
        String encryptionKey,
        @DefaultValue("Credit Advisory") String issuer) {

    /** Never print the key, even by accident. */
    @Override
    public String toString() {
        return "MfaProperties[required=" + required + ", key hidden, issuer=" + issuer + "]";
    }
}
