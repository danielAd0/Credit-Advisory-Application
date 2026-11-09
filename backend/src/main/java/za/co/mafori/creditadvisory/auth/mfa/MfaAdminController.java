package za.co.mafori.creditadvisory.auth.mfa;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/users/{id}/mfa-reset - ADMIN only (contract section 6): a user lost
 * their phone and their recovery codes. They set up a new authenticator at
 * their next sign-in; their open sessions end now.
 */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class MfaAdminController {

    private final MfaService mfa;

    public MfaAdminController(MfaService mfa) {
        this.mfa = mfa;
    }

    @PostMapping("/api/users/{id}/mfa-reset")
    public ResponseEntity<Void> reset(@PathVariable long id) {
        mfa.reset(id);
        return ResponseEntity.noContent().build();
    }
}
