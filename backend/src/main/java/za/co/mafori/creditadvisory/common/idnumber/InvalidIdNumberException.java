package za.co.mafori.creditadvisory.common.idnumber;

/**
 * The text is not a valid South African ID number. The message says WHY, but
 * never
 * contains the number itself, so it is safe to show to the user or put in an
 * import
 * error report.
 */
public class InvalidIdNumberException extends RuntimeException {

    /** Why the number was rejected. */
    public enum Reason {
        NOT_13_DIGITS("must be exactly 13 digits"),
        INVALID_DATE_OF_BIRTH("the first six digits are not a real date (YYMMDD)"),
        INVALID_CITIZENSHIP("the 11th digit must be 0, 1 or 2"),
        INVALID_CHECK_DIGIT("the check digit is wrong - probably a typing mistake");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public InvalidIdNumberException(Reason reason) {
        super("Invalid SA ID number: " + reason.message());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
