package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * Remembers, in the session, that the user re-entered their password a moment
 * ago (POST /api/auth/reauthenticate), and checks it before a full ID number is
 * shown or two-step sign-in is moved to a new phone.
 *
 * <p>
 * Kept in the session on purpose: signing out, a new sign-in or an ended
 * session forgets it, and it never reaches the browser.
 */
@Component
public class RecentPasswordConfirmation {

    static final String SESSION_KEY = "credit-advisory.password-confirmed-until";

    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public RecentPasswordConfirmation(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    /** For the unit test, which needs to move time forward. */
    RecentPasswordConfirmation(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** The password was just confirmed. Returns until when that counts. */
    public Instant remember(HttpServletRequest request) {
        Instant until = Instant.now(clock).plus(properties.passwordConfirmationWindow());
        request.getSession().setAttribute(SESSION_KEY, until);
        return until;
    }

    /**
     * Throws 403 auth:reauthentication-required unless the password was confirmed
     * within the window.
     */
    public void require(HttpServletRequest request) {
        require(request, "Enter your password again to see a full ID number.");
    }

    /**
     * The same check for another sensitive action.
     *
     * @param detail what the user is told, e.g. "Enter your password again to ..."
     */
    public void require(HttpServletRequest request, String detail) {
        HttpSession session = request.getSession(false);
        Object until = session == null ? null : session.getAttribute(SESSION_KEY);
        if (!(until instanceof Instant confirmedUntil) || !confirmedUntil.isAfter(Instant.now(clock))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "auth:reauthentication-required",
                    "Confirm your password", detail);
        }
    }
}
