package za.co.mafori.creditadvisory.user;

import za.co.mafori.creditadvisory.consultant.ConsultantResponse;

/** Response of POST /api/consultant-accounts: the consultant and its login. */
public record ConsultantAccountResponse(ConsultantResponse consultant, UserResponse user) {
}
