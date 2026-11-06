package za.co.mafori.creditadvisory.client;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/clients/{id}/id-number-correction. The new number is in the
 * body, never the URL, so it is not logged.
 *
 * @param idNumber the correct 13-digit SA ID number
 * @param reason   why it is being corrected; stored in the audit log (any
 *                 13-digit number in it is stored as [redacted])
 * @param version  the client's current version (optimistic locking)
 */
public record CorrectIdNumberRequest(
        @NotBlank @Size(max = 30) String idNumber,
        @NotBlank @Size(max = 500) String reason,
        @NotNull Long version) {

    /**
     * Never print the number, for example in a log line or an error message. The
     * reason is left out too: someone may have typed the number into it.
     */
    @Override
    public String toString() {
        return "CorrectIdNumberRequest[idNumber=****, reason=****, version=" + version + "]";
    }
}
