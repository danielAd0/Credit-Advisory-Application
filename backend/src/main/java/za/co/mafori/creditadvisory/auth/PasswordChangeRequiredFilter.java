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
 * A user whose password was set by an admin (new account or reset) must choose
 * their own before using the application. Until then every request except the
 * ones below is answered 403 with type auth:password-change-required, so the
 * frontend knows to show the change-password screen.
 *
 * <p>
 * Enforced here, on the server, so it cannot be skipped by calling the API
 * directly. Not a @Component for the same reason as AccountStillActiveFilter:
 * it
 * is placed in SecurityConfig.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** The only things a user with an admin-set password may still do. */
    private static final Set<String> ALLOWED = Set.of(
            "GET /api/auth/me",
            "GET /api/auth/csrf",
            "POST /api/auth/change-password",
            "POST /api/auth/logout");

    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserPrincipal principal
                && principal.mustChangePassword()
                && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/problem+json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"type\":\"urn:credit-advisory:auth:password-change-required\","
                    + "\"title\":\"Password change required\",\"status\":403,"
                    + "\"detail\":\"Your password was set by an administrator. Choose your own password first.\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}
