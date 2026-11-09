package za.co.mafori.creditadvisory.auth.mfa;

import java.util.List;
import za.co.mafori.creditadvisory.auth.MeResponse;

/**
 * Answer of POST /api/auth/mfa/enable.
 *
 * @param recoveryCodes shown to the user ONCE: they cannot be fetched again
 * @param account       the signed-in user, now with mfa VERIFIED
 */
public record MfaEnabledResponse(List<String> recoveryCodes, MeResponse account) {
}
