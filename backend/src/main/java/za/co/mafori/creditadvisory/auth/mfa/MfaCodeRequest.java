package za.co.mafori.creditadvisory.auth.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/mfa/enable and /verify: the 6 digits the app shows.
 */
public record MfaCodeRequest(@NotBlank @Size(max = 20) String code) {

    @Override
    public String toString() {
        return "MfaCodeRequest[code=****]";
    }
}
