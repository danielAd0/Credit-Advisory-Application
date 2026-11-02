package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in endpoints (contract section 2). Logout is handled by Spring
 * Security's logout filter.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CurrentAccountService accounts;
    private final LoginRateLimiter rateLimiter;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, CurrentAccountService accounts,
            LoginRateLimiter rateLimiter) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Makes sure the XSRF-TOKEN cookie is set. The frontend calls this once on
     * start-up.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        // Checked before the password, so a blocked address learns nothing - not even
        // whether a password was right.
        String address = request.getRemoteAddr();
        rateLimiter.checkAllowed(address);

        Authentication result;
        try {
            result = authenticationManager
                    .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));
        } catch (AuthenticationException e) {
            rateLimiter.recordFailure(address);
            throw e;
        }

        // New session id on sign-in, so a session id planted before login is useless
        // (session fixation).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }

        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(result);
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        AppUserPrincipal principal = (AppUserPrincipal) result.getPrincipal();
        return accounts.describe(principal.userId());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AppUserPrincipal principal) {
        return accounts.describe(principal.userId());
    }

    /**
     * Every sign-in failure gets the same 401, whatever the reason (contract
     * section 2)
     */
    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail signInFailed(AuthenticationException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, DatabaseAuthenticationProvider.GENERIC_FAILURE);
        problem.setTitle("Sign-in failed");
        problem.setType(URI.create("urn:credit-advisory:auth:sign-in-failed"));
        return problem;
    }

    /** Too many failed sign-ins from this address (contract section 2). */
    @ExceptionHandler(TooManySignInAttemptsException.class)
    public ResponseEntity<ProblemDetail> tooManyAttempts(TooManySignInAttemptsException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "Too many failed sign-in attempts from this network. Try again in a few minutes.");
        problem.setTitle("Too many attempts");
        problem.setType(URI.create("urn:credit-advisory:auth:too-many-attempts"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem);
    }
}
