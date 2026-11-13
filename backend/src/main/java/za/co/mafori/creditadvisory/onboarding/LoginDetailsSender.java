package za.co.mafori.creditadvisory.onboarding;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.onboarding.LoginDetailsMailer.MailDeliveryException;

/**
 * The background job that emails queued login details, a few at a time.
 *
 * <p>
 * Microsoft 365 accepts about 30 messages a minute from one mailbox, so the
 * default is {@code app.onboarding.send-batch} = 10 every
 * {@code app.onboarding.send-interval} = 30 s (20 a minute): 300 consultants
 * take about a quarter of an hour. No request waits for this, so nothing
 * times out.
 *
 * <p>
 * Before each email it checks the login is still enabled and still has the
 * generated password (an admin may have reset it meanwhile): an outdated
 * password is never sent. After Microsoft 365 accepts the email, the sealed
 * password is deleted. A temporary failure is tried again later (after 2, 4
 * and 8 minutes); after {@code app.onboarding.max-attempts} the email is
 * FAILED and an admin can retry it.
 *
 * <p>
 * Once sent, the temporary password works for
 * {@code app.onboarding.temporary-password-days} (default 7); after that
 * sign-in refuses it and the admin regenerates it
 * (OnboardingService.regenerate).
 */
@Component
public class LoginDetailsSender {

    private static final Logger log = LoggerFactory.getLogger(LoginDetailsSender.class);
    /**
     * A SENDING invite older than this belonged to a server that stopped mid-send.
     */
    private static final Duration STUCK = Duration.ofMinutes(10);

    private final OnboardingStore store;
    private final LoginDetailsMailer mailer;
    private final IdNumberProtector protector;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogWriter audit;
    private final int batch;
    private final int maxAttempts;
    private final String loginUrl;
    private final String companyName;
    private final String supportContact;
    private final int passwordDays;

    public LoginDetailsSender(OnboardingStore store, LoginDetailsMailer mailer, IdNumberProtector protector,
            PasswordEncoder passwordEncoder, AuditLogWriter audit,
            @Value("${app.onboarding.send-batch:10}") int batch,
            @Value("${app.onboarding.max-attempts:4}") int maxAttempts,
            @Value("${app.onboarding.login-url:}") String loginUrl,
            @Value("${app.onboarding.company-name:Mafori}") String companyName,
            @Value("${app.onboarding.support-contact:your manager}") String supportContact,
            @Value("${app.onboarding.temporary-password-days:7}") int passwordDays) {
        this.store = store;
        this.mailer = mailer;
        this.protector = protector;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.batch = Math.max(1, batch);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.loginUrl = loginUrl.strip();
        this.companyName = companyName.strip();
        this.supportContact = supportContact.strip();
        this.passwordDays = Math.max(1, passwordDays);
    }

    @Scheduled(initialDelayString = "${app.onboarding.send-initial-delay:PT30S}", fixedDelayString = "${app.onboarding.send-interval:PT30S}")
    public void scheduled() {
        try {
            int sent = sendDue();
            if (sent > 0) {
                log.info("Sent {} consultant login email(s)", sent);
            }
        } catch (RuntimeException e) {
            // Never let one bad round stop the schedule. No password is in this message.
            log.error("Sending login emails failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * One round. Returns how many emails Microsoft 365 accepted. Public for the
     * tests.
     */
    public int sendDue() {
        if (!mailer.isConfigured() || loginUrl.isEmpty()) {
            return 0;
        }
        int released = store.releaseStuck(STUCK);
        if (released > 0) {
            log.warn("{} login email(s) were stuck while sending and were put back in the queue", released);
        }
        List<OnboardingStore.Claimed> claimed = store.claimDue(batch);
        int sent = 0;
        for (int i = 0; i < claimed.size(); i++) {
            OnboardingStore.Claimed invite = claimed.get(i);
            Outcome outcome = sendOne(invite);
            if (outcome == Outcome.SENT) {
                sent++;
            } else if (outcome == Outcome.THROTTLED) {
                // Microsoft 365 wants us to slow down: the rest wait for the next round.
                for (int j = i + 1; j < claimed.size(); j++) {
                    store.unclaim(claimed.get(j).id());
                }
                break;
            }
        }
        return sent;
    }

    private enum Outcome {
        SENT, NOT_SENT, THROTTLED
    }

    private Outcome sendOne(OnboardingStore.Claimed invite) {
        String password;
        try {
            password = protector.openText(OnboardingService.SEAL_PURPOSE, invite.passwordSealed());
        } catch (IllegalStateException e) {
            fail(invite, "The stored password could not be read (encryption key changed?). Reset the password "
                    + "on the Users page instead.");
            return Outcome.NOT_SENT;
        }

        var login = store.loginState(invite.userId());
        if (login.isEmpty() || !login.get().enabled()) {
            fail(invite, "The login is disabled or was removed; the email was not sent.");
            return Outcome.NOT_SENT;
        }
        if (!"ACTIVE".equals(login.get().consultantStatus())) {
            fail(invite, "The consultant is no longer active (or no longer linked to this login); "
                    + "the email was not sent.");
            return Outcome.NOT_SENT;
        }
        if (!login.get().mustChangePassword() || !passwordEncoder.matches(password, login.get().passwordHash())) {
            fail(invite, "The login's password was changed after this email was prepared, so it was not sent. "
                    + "Reset the password on the Users page to give the consultant a new one.");
            return Outcome.NOT_SENT;
        }

        try {
            mailer.send(LoginDetailsEmail.build(invite.email(), invite.consultantName(), loginUrl, password,
                    companyName, supportContact, passwordDays));
        } catch (MailDeliveryException e) {
            if (e.permanent() || invite.attempts() >= maxAttempts) {
                fail(invite, e.getMessage());
            } else {
                // 2, 4, 8 ... minutes
                Instant next = Instant.now().plus(Duration.ofMinutes(1L << Math.min(invite.attempts(), 6)));
                store.markRetry(invite.id(), e.getMessage(), next);
            }
            return e.throttled() ? Outcome.THROTTLED : Outcome.NOT_SENT;
        }
        store.markSent(invite.id());
        // From now on the emailed password has passwordDays to be used (V24).
        store.startPasswordClock(invite.userId(), passwordDays);
        audit.record("LOGIN_DETAILS_SENT", "CONSULTANT", invite.consultantId(),
                meta("inviteId", invite.id(), "userId", invite.userId(), "email", invite.email(), "attempts",
                        invite.attempts()));
        return Outcome.SENT;
    }

    private void fail(OnboardingStore.Claimed invite, String reason) {
        store.markFailed(invite.id(), reason);
        audit.record("LOGIN_DETAILS_FAILED", "CONSULTANT", invite.consultantId(),
                meta("inviteId", invite.id(), "attempts", invite.attempts(), "reason", reason));
    }
}
