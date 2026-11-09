package za.co.mafori.creditadvisory.auth.mfa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/auth/mfa/recovery: one of the recovery codes, e.g.
 * K7QM2-XW4PD.
 */
public record RecoveryCodeRequest(@NotBlank @Size(max = 40) String recoveryCode) {

    @Override
    public String toString() {
        return "RecoveryCodeRequest[recoveryCode=****]";
    }
}
