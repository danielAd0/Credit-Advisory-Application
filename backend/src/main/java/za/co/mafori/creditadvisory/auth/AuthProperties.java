package za.co.mafori.creditadvisory.auth;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Sign-in settings.
 *
 * <pre>
 * app.auth.max-failed-attempts=5            (per account, then the account locks)
 * app.auth.lock-duration=15m
 * app.auth.max-failed-sign-ins-per-ip=20    (per network address, then sign-in is refused)
 * app.auth.ip-window=5m
 * app.auth.cors-allowed-origins=https://localhost:5173 (local profile only)
 * </pre>
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("5") int maxFailedAttempts,
        @DefaultValue("15m") Duration lockDuration,
        @DefaultValue("20") int maxFailedSignInsPerIp,
        @DefaultValue("5m") Duration ipWindow,
        List<String> corsAllowedOrigins) {

    public List<String> corsAllowedOriginsOrEmpty() {
        return corsAllowedOrigins == null ? List.of() : corsAllowedOrigins;
    }
}
