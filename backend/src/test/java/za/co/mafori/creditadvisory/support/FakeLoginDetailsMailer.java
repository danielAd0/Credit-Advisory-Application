package za.co.mafori.creditadvisory.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.onboarding.LoginDetailsMailer;

/**
 * Takes the place of GraphLoginDetailsMailer in every test (it is @Primary and
 * only exists in src/test), so no test ever talks to Microsoft 365. Keeps the
 * messages it was given, and can be told to refuse some addresses.
 */
@Component
@Primary
public class FakeLoginDetailsMailer implements LoginDetailsMailer {

    private final List<Message> sent = new ArrayList<>();
    private final Set<String> refuse = new HashSet<>();
    private final Set<String> refusePermanently = new HashSet<>();
    private boolean configured = true;
    private boolean throttle;

    /** Back to "configured, accepts everything, nothing sent". ApiTestBase calls it before every test. */
    public synchronized void reset() {
        sent.clear();
        refuse.clear();
        refusePermanently.clear();
        configured = true;
        throttle = false;
    }

    public synchronized List<Message> sent() {
        return List.copyOf(sent);
    }

    /** A temporary failure (for example Microsoft 365 is down) for this address. */
    public synchronized void refuse(String address) {
        refuse.add(address);
    }

    /** A permanent failure (for example the mailbox does not exist) for this address. */
    public synchronized void refusePermanently(String address) {
        refusePermanently.add(address);
    }

    public synchronized void accept(String address) {
        refuse.remove(address);
        refusePermanently.remove(address);
    }

    public synchronized void setConfigured(boolean configured) {
        this.configured = configured;
    }

    /** Every send answers "slow down" (HTTP 429). */
    public synchronized void setThrottle(boolean throttle) {
        this.throttle = throttle;
    }

    @Override
    public synchronized boolean isConfigured() {
        return configured;
    }

    @Override
    public String senderAddress() {
        return "no-reply@test.local";
    }

    @Override
    public synchronized void send(Message message) {
        if (throttle) {
            throw new MailDeliveryException("Microsoft 365 refused the email (HTTP 429).", false, true);
        }
        if (refusePermanently.contains(message.to())) {
            throw new MailDeliveryException("Microsoft 365 refused the email (HTTP 400, ErrorInvalidRecipients).",
                    true, false);
        }
        if (refuse.contains(message.to())) {
            throw new MailDeliveryException("Could not reach Microsoft 365: ConnectException.", false, false);
        }
        sent.add(message);
    }
}
