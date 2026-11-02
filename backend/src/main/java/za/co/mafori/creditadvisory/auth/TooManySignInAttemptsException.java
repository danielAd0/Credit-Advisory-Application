package za.co.mafori.creditadvisory.auth;

/**
 * Thrown before the password is even checked when one network address has
 * failed
 * to sign in too often recently. Answered with 429 by AuthController.
 *
 * <p>
 * Deliberately NOT an AuthenticationException, so it is not turned into the
 * generic 401.
 */
public class TooManySignInAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManySignInAttemptsException(long retryAfterSeconds) {
        super("Too many failed sign-in attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
