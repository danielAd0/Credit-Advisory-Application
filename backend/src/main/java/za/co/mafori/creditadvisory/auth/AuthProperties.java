package za.co.mafori.creditadvisory.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in settings.
 *
 * <pre>
 * app.auth.max-failed-attempts=5
 * app.auth.lock-duration=15m
 * app.auth.cors-allowed-origins=https://localhost:5173 (local profile only)
 * </pre>
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15m") Duration lockDuration,
        List<String> corsAllowedOrigins) {

    public List<String> corsAllowedOriginsOrEmpty() {
        return corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
    }
}
