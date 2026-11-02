package za.co.mafori.creditadvisory.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;
import za.co.mafori.creditadvisory.user.UserAccountRepository;
import za.co.mafori.creditadvisory.user.UserService;

/**
 * Lets any signed-in user change their own password. The session that made the
 * change stays signed in; every other session of that user is signed out on its
 * next request.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordController {

    private final UserService users;
    private final UserAccountRepository accounts;
    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public PasswordController(UserService users, UserAccountRepository accounts,
            SecurityContextRepository securityContextRepository) {
        this.users = users;
        this.accounts = accounts;
        this.securityContextRepository = securityContextRepository;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        users.changeOwnPassword(principal.userId(), body.currentPassword(), body.newPassword());

        // The change raised the account version, which signs out every session of this
        // user -
        // including this one. Keep THIS session by giving it the new version, with a
        // fresh
        // session id.
        accounts.findVersionIfMayStaySignedIn(principal.userId(), ConsultantStatus.ACTIVE)
                .ifPresent(
                        newVersion -> keepThisSession(principal.afterOwnPasswordChange(newVersion), request, response));

        return ResponseEntity.noContent().build();
    }

    private void keepThisSession(AppUserPrincipal refreshed, HttpServletRequest request,
            HttpServletResponse response) {
        request.changeSessionId();
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(refreshed, null, refreshed.authorities()));
        contextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
