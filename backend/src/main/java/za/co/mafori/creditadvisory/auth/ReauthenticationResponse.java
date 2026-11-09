package za.co.mafori.creditadvisory.auth;

import java.time.Instant;

/**
 * Answer of POST /api/auth/reauthenticate.
 *
 * @param confirmedUntil until when full ID numbers may be shown without asking
 *                       for the password again
 */
public record ReauthenticationResponse(Instant confirmedUntil) {
}
