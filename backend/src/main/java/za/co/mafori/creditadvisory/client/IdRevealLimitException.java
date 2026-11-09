package za.co.mafori.creditadvisory.client;

/**
 * A user asked for more full ID numbers in one hour than allowed. Answered with
 * 429 and a Retry-After header by ClientController.
 */
public class IdRevealLimitException extends RuntimeException {

    private final long retryAfterSeconds;

    public IdRevealLimitException(long retryAfterSeconds) {
        super("Too many ID numbers shown");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
