package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * POST /api/auth/reauthenticate (contract section 2): the signed-in user enters
 * their password again before seeing a full ID number.
 */
@RestController
@RequestMapping("/api/auth")
public class ReauthenticationController {

    private final ReauthenticationService service;
    private final RecentPasswordConfirmation confirmation;

    public ReauthenticationController(ReauthenticationService service, RecentPasswordConfirmation confirmation) {
        this.service = service;
        this.confirmation = confirmation;
    }

    @PostMapping("/reauthenticate")
    public ReauthenticationResponse reauthenticate(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ReauthenticateRequest body, HttpServletRequest request) {
        return switch (service.confirm(principal.userId(), body.password())) {
            case CONFIRMED -> new ReauthenticationResponse(confirmation.remember(request));
            case WRONG_PASSWORD -> throw new ApiException(HttpStatus.BAD_REQUEST, "auth:wrong-password",
                    "Wrong password", "The password is not correct.");
            case LOCKED -> {
                // Too many wrong passwords: whoever holds this session may not be the user.
                signOut(request);
                throw new ApiException(HttpStatus.UNAUTHORIZED, "auth:account-locked", "Signed out",
                        "Too many wrong passwords. You have been signed out; try again later.");
            }
        };
    }

    private static void signOut(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
