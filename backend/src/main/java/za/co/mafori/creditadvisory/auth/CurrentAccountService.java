package za.co.mafori.creditadvisory.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.auth.mfa.MfaService;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/** Builds the "who am I" response. Read-only. */
@Service
public class CurrentAccountService {
    private final UserAccountRepository users;
    private final MfaService mfa;

    public CurrentAccountService(UserAccountRepository users, MfaService mfa) {
        this.users = users;
        this.mfa = mfa;
    }

    /** The user behind this session, including where it stands with MFA. */
    @Transactional(readOnly = true)
    public MeResponse describe(AppUserPrincipal principal) {
        String mfaStatus = mfa.statusOf(principal).name();
        return users.findWithRolesAndConsultantById(principal.userId())
                .map(user -> MeResponse.from(user, mfaStatus))
                .orElseThrow(() -> new IllegalStateException(
                        "Signed-in user " + principal.userId() + " no longer exists"));
    }
}
