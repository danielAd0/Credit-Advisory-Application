package za.co.mafori.creditadvisory.auth;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.user.UserService;

/** Lets any signed-in user change their own password. */
@RestController
@RequestMapping("/api/auth")
public class PasswordController {

    private final UserService users;

    public PasswordController(UserService users) {
        this.users = users;
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        users.changeOwnPassword(principal.userId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }
}
