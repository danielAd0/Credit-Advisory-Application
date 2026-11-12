package za.co.mafori.creditadvisory.onboarding;

/**
 * Sends one consultant their login details. The real one is
 * GraphLoginDetailsMailer (Microsoft 365); the tests replace it with a fake.
 */
public interface LoginDetailsMailer {

    /** One email to one person. {@code html} is the whole body. */
    record Message(String to, String subject, String html) {

        @Override
        public String toString() {
            // The body holds a password: never print it.
            return "Message[to=" + to + ", subject=" + subject + "]";
        }
    }

    /** False until IT has filled in the mail settings; nothing can be sent then. */
    boolean isConfigured();

    /** Where the emails come from, for the admin screen (or null). */
    String senderAddress();

    
    void send(Message message);

    class MailDeliveryException extends RuntimeException {

        private final boolean permanent;
        private final boolean throttled;

        public MailDeliveryException(String safeMessage, boolean permanent, boolean throttled) {
            super(safeMessage);
            this.permanent = permanent;
            this.throttled = throttled;
        }

        /** Retrying the same message will not help (for example a bad address). */
        public boolean permanent() {
            return permanent;
        }

        /** The mail service asked us to slow down: stop this round. */
        public boolean throttled() {
            return throttled;
        }
    }
}
