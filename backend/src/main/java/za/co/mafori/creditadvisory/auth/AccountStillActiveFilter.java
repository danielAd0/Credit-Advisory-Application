package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Ends a signed-in session on its very next request when:
 * <ul>
 * <li>the user was disabled, or their consultant became INACTIVE or SUSPENDED
 * (FR-18), or</li>
 * <li>an admin changed their password, roles or consultant link since they
 * signed in (the account version no longer matches), so a reset password or a
 * removed role takes effect immediately.</li>
 * </ul>
 * The request then continues as "not signed in", so protected endpoints answer
 * 401.
 *
 * <p>
 * Deliberately not a @Component: Spring Boot would then also run it outside the
 * security
 * chain. It is created and placed in SecurityConfig instead.
 */
public class AccountStillActiveFilter extends OncePerRequestFilter {

    private final UserAccountRepository users;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AccountStillActiveFilter(UserAccountRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = contextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof AppUserPrincipal principal
                && !maySessionContinue(principal)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            contextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }

    /**
     * One small query per request: still allowed in, and nothing security-relevant
     * changed.
     */
    private boolean maySessionContinue(AppUserPrincipal principal) {
        Optional<Long> currentVersion = users.findVersionIfMayStaySignedIn(principal.userId(),
                ConsultantStatus.ACTIVE);
        return currentVersion.isPresent() && currentVersion.get() == principal.accountVersion();
    }
}
