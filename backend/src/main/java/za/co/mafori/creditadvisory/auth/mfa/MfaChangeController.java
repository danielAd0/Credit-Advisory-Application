package za.co.mafori.creditadvisory.auth.mfa;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.CurrentAccountService;
import za.co.mafori.creditadvisory.auth.RecentPasswordConfirmation;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/**
 * Moving two-step sign-in to a new phone, by the user themselves (contract
 * section 2, "New phone"). Only for a session that has passed both sign-in
 * steps (MfaRequiredFilter answers 403 auth:mfa-required to any other), and
 * only within a few minutes of re-entering the password, so an unattended
 * open session is not enough.
 */
@RestController
@RequestMapping("/api/auth/mfa/change")
public class MfaChangeController {

    /** The new phone's secret, until /confirm proves it works. Never sent again. */
    static final String PENDING_NEW_SECRET = "credit-advisory.mfa-pending-new-secret";

    private final MfaService mfa;
    private final CurrentAccountService accounts;
    private final UserAccountRepository users;
    private final RecentPasswordConfirmation passwordConfirmation;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public MfaChangeController(MfaService mfa, CurrentAccountService accounts, UserAccountRepository users,
            RecentPasswordConfirmation passwordConfirmation, SecurityContextRepository securityContextRepository) {
        this.mfa = mfa;
        this.accounts = accounts;
        this.users = users;
        this.passwordConfirmation = passwordConfirmation;
        this.securityContextRepository = securityContextRepository;
    }

    /**
     * A new secret for the new phone (shown as a QR code). The old phone still
     * works.
     */
    @PostMapping
    public MfaSetupResponse start(@AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request) {
        passwordConfirmation.require(request,
                "Enter your password again before moving two-step sign-in to a new phone.");
        MfaService.Setup setup = mfa.startChange(principal);
        request.getSession().setAttribute(PENDING_NEW_SECRET, setup.secret());
        return new MfaSetupResponse(setup.secretBase32(), setup.otpauthUri());
    }

    /**
     * The code the new phone shows. On success the new phone replaces the old
     * one, 10 new recovery codes replace the old ones, and the user's other
     * sessions are signed out; this one stays signed in.
     */
    @PostMapping("/confirm")
    public MfaEnabledResponse confirm(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody MfaCodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PENDING_NEW_SECRET);
        if (!(pending instanceof byte[] secret)) {
            throw ApiException.conflict("auth:mfa-change-not-started", "Not started",
                    "Start first (POST /api/auth/mfa/change).");
        }

        MfaService.Enabled changed = mfa.confirmChange(principal.userId(), secret, body.code());
        return switch (changed.result()) {
            case OK -> {
                session.removeAttribute(PENDING_NEW_SECRET);
                AppUserPrincipal kept = keepThisSession(principal, request, response);
                yield new MfaEnabledResponse(changed.recoveryCodes(), accounts.describe(kept));
            }
            case NOT_SET_UP -> throw ApiException.conflict("auth:mfa-setup-required", "Set-up required",
                    "No authenticator is set up for this account any more. Sign in again to set one up.");
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "auth:wrong-code", "Wrong code",
                    "The code is not correct. Enter the code the new phone shows now.");
        };
    }

    /**
     * The change raised the account version, which signs out every session of
     * this user, this one too. Keep THIS one: the new version, a new session id.
     */
    private AppUserPrincipal keepThisSession(AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response) {
        return users.findVersionIfMayStaySignedIn(principal.userId(), ConsultantStatus.ACTIVE)
                .map(newVersion -> {
                    AppUserPrincipal refreshed = new AppUserPrincipal(principal.userId(), principal.email(),
                            principal.roles(), principal.consultantId(), newVersion, principal.mustChangePassword(),
                            principal.mfaPending());
                    request.changeSessionId();
                    SecurityContext context = contextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(refreshed, null,
                            refreshed.authorities()));
                    contextHolder.setContext(context);
                    securityContextRepository.saveContext(context, request, response);
                    return refreshed;
                })
                .orElse(principal);
    }
}
