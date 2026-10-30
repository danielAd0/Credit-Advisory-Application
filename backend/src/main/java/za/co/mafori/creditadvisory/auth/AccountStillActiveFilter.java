package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * FR-18: ends the session of a user who was disabled, or whose consultant
 * became INACTIVE or
 * SUSPENDED, on their very next request. The request then continues as "not
 * signed in", so
 * protected endpoints answer 401.
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
                && !users.mayStaySignedIn(principal.userId(), ConsultantStatus.ACTIVE)) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            contextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}
