package za.co.mafori.creditadvisory.auth;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import za.co.mafori.creditadvisory.user.UserAccountRepository;

/** Builds the "who am I" response. Read-only. */
@Service
public class CurrentAccountService {
    private final UserAccountRepository users;

    public CurrentAccountService(UserAccountRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public MeResponse describe(long userId) {
        return users.findWithRolesAndConsultantById(userId)
                .map(MeResponse::from)
                .orElseThrow(() -> new IllegalStateException("Signed-in user " + userId + " no longer exists"));
    }
}
