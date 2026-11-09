package za.co.mafori.creditadvisory.auth;

import java.util.List;
import za.co.mafori.creditadvisory.consultant.Consultant;
import za.co.mafori.creditadvisory.user.UserAccount;

/**
 * Response of GET /api/auth/me and POST /api/auth/login (contract section 2).
 *
 * @param mfa the second sign-in step: NOT_REQUIRED, SETUP_REQUIRED,
 *            CODE_REQUIRED or VERIFIED
 */
public record MeResponse(long id, String email, List<String> roles, ConsultantSummary consultant,
        boolean mustChangePassword, String mfa) {

    public record ConsultantSummary(long id, String consultantCode, String fullName) {
    }

    static MeResponse from(UserAccount user, String mfa) {
        Consultant c = user.getConsultant();
        ConsultantSummary consultant = c == null ? null
                : new ConsultantSummary(c.getId(), c.getConsultantCode(), c.getFullName());
        List<String> roles = user.getRoleNames().stream().map(Enum::name).sorted().toList();
        return new MeResponse(user.getId(), user.getEmail(), roles, consultant, user.mustChangePassword(), mfa);
    }
}
