package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
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
    private SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository, CurrentAccountService accounts) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
    }

    /**
     * Makes sure the XSRF-TOKEN cookies iset. The frontend calls this once on
     * start-up.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf() {
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        Authentication result = authenticationManager
                .authenticate(UsernamePasswordAuthenticationToken.unauthenticated(body.email(), body.password()));

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
        problem.setType(URI.create("urn:credut-advisory:auth:sign-in-failed"));
        return problem;
    }
}
