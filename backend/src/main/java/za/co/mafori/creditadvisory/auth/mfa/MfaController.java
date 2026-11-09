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
import za.co.mafori.creditadvisory.auth.MeResponse;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * The second sign-in step (contract section 2). Only usable while the session
 * is waiting for it (GET /api/auth/me says mfa SETUP_REQUIRED or
 * CODE_REQUIRED); MfaRequiredFilter blocks everything else until then.
 */
@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {

    /** The secret from /setup, until /enable confirms it. Never sent anywhere. */
    static final String PENDING_SECRET = "credit-advisory.mfa-pending-secret";

    private final MfaService mfa;
    private final CurrentAccountService accounts;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public MfaController(MfaService mfa, CurrentAccountService accounts,
            SecurityContextRepository securityContextRepository) {
        this.mfa = mfa;
        this.accounts = accounts;
        this.securityContextRepository = securityContextRepository;
    }

    /** First time: a new secret for the authenticator app (shown as a QR code). */
    @PostMapping("/setup")
    public MfaSetupResponse setup(@AuthenticationPrincipal AppUserPrincipal principal, HttpServletRequest request) {
        requirePending(principal);
        MfaService.Setup setup = mfa.startSetup(principal);
        request.getSession().setAttribute(PENDING_SECRET, setup.secret());
        return new MfaSetupResponse(setup.secretBase32(), setup.otpauthUri());
    }

    /** First time: the code the app now shows, proving it was set up correctly. */
    @PostMapping("/enable")
    public MfaEnabledResponse enable(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody MfaCodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        requirePending(principal);
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PENDING_SECRET);
        if (!(pending instanceof byte[] secret)) {
            throw ApiException.conflict("auth:mfa-setup-not-started", "Set-up not started",
                    "Start the set-up first (POST /api/auth/mfa/setup).");
        }

        MfaService.Enabled enabled = mfa.enable(principal.userId(), secret, body.code());
        return switch (enabled.result()) {
            case OK -> {
                session.removeAttribute(PENDING_SECRET);
                AppUserPrincipal verified = markVerified(principal, request, response);
                yield new MfaEnabledResponse(enabled.recoveryCodes(), accounts.describe(verified));
            }
            case ALREADY_SET_UP -> throw ApiException.conflict("auth:mfa-already-set-up", "Already set up",
                    "An authenticator is already set up for this account. Enter its code instead.");
            default -> throw wrongCode();
        };
    }

    /** Every sign-in: the code the authenticator app shows now. */
    @PostMapping("/verify")
    public MeResponse verify(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody MfaCodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        requirePending(principal);
        return finish(mfa.verify(principal.userId(), body.code()), principal, request, response);
    }

    /** Lost phone: a recovery code instead of the app's code. */
    @PostMapping("/recovery")
    public MeResponse recovery(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody RecoveryCodeRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        requirePending(principal);
        return finish(mfa.recover(principal.userId(), body.recoveryCode()), principal, request, response);
    }

    private MeResponse finish(MfaService.Result result, AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response) {
        return switch (result) {
            case OK -> accounts.describe(markVerified(principal, request, response));
            case NOT_SET_UP -> throw ApiException.conflict("auth:mfa-setup-required", "Set-up required",
                    "No authenticator is set up yet. Set one up first (POST /api/auth/mfa/setup).");
            case LOCKED -> {
                // Too many wrong codes: whoever holds this session may not be the user.
                signOut(request);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "auth:account-locked", "Signed out",
                        "Too many wrong codes. You have been signed out; try again later.");
            }
            default -> throw wrongCode();
        };
    }

    private static void requirePending(AppUserPrincipal principal) {
        if (!principal.mfaPending()) {
            throw ApiException.conflict("auth:mfa-not-pending", "Nothing to do",
                    "This session has already completed the second sign-in step.");
        }
    }

    private static ApiException wrongCode() {
        return new ApiException(HttpStatus.BAD_REQUEST, "auth:wrong-code", "Wrong code",
                "The code is not correct. Enter the code your authenticator app shows now.");
    }

    /** Both steps done: a new session id, and the session is no longer pending. */
    private AppUserPrincipal markVerified(AppUserPrincipal principal, HttpServletRequest request,
            HttpServletResponse response) {
        AppUserPrincipal verified = principal.afterMfa();
        request.changeSessionId();
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(verified, null, verified.authorities()));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return verified;
    }

    private static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
