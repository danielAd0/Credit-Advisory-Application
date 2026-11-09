package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * After the password, a session must finish the second step (authenticator
 * code) before it can do anything else. Until then every request except the
 * ones below is answered 403 with type auth:mfa-required, so the frontend knows
 * to show the code (or set-up) screen.
 *
 * <p>
 * Runs before PasswordChangeRequiredFilter: a user with an admin-set password
 * proves who they are with MFA first, then chooses their password. Enforced on
 * the server, so it cannot be skipped by calling the API directly. Not a
 * {@literal @}Component for the same reason as AccountStillActiveFilter.
 */
public class MfaRequiredFilter extends OncePerRequestFilter {

    /** The only things a session may do before the second step. */
    private static final Set<String> ALLOWED = Set.of(
            "GET /api/auth/me",
            "GET /api/auth/csrf",
            "POST /api/auth/logout",
            "POST /api/auth/mfa/setup",
            "POST /api/auth/mfa/enable",
            "POST /api/auth/mfa/verify",
            "POST /api/auth/mfa/recovery");

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserPrincipal principal
                && principal.mfaPending()
                && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"type\":\"urn:credit-advisory:auth:mfa-required\","
                    + "\"title\":\"Second step required\",\"status\":403,"
                    + "\"detail\":\"Enter the code from your authenticator app first.\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
