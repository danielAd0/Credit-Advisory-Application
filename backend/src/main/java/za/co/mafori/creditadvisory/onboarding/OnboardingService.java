package za.co.mafori.creditadvisory.onboarding;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.common.idnumber.IdNumberProtector;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.BlockedConsultant;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.ConsultantEmail;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.GenerateResult;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.Invite;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.QueueResult;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.Skipped;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.Summary;
import za.co.mafori.creditadvisory.user.CreateUserRequest;
import za.co.mafori.creditadvisory.user.ResetPasswordRequest;
import za.co.mafori.creditadvisory.user.RoleName;
import za.co.mafori.creditadvisory.user.UserResponse;
import za.co.mafori.creditadvisory.user.UserService;

/**
 * Bulk consultant logins (docs/consultant-onboarding.md, sections 3 and 4).
 *
 * <ol>
 * <li>{@link #generate}: every eligible consultant (active, has an email, no
 * login) gets a CONSULTANT login with a random temporary password, through
 * UserService.create, so every existing rule applies: password policy, unique
 * email, one login per consultant, "must change password" at first sign-in.
 * Consultants who already have a login are never touched, so their password
 * is never overwritten.</li>
 * <li>The temporary password is kept ONLY sealed (AES-256-GCM) in
 * consultant_login_invites, until its email is sent. The users table only
 * ever has the BCrypt hash.</li>
 * <li>{@link #queue}: the admin's "Send login details" puts the emails in the
 * queue; LoginDetailsSender sends them in the background and deletes each
 * sealed password once Microsoft 365 has accepted the email.</li>
 * <li>{@link #retry}: failed emails go back in the queue with the SAME
 * password; nothing is generated again.</li>
 * </ol>
 */
@Service
public class OnboardingService {

    /**
     * The purpose the temporary passwords are sealed for
     * (IdNumberProtector.sealText).
     */
    static final String SEAL_PURPOSE = "consultant-temporary-password:v1";

    /**
     * Logins made per call. Each needs one BCrypt hash (about 0.1 s), so a call
     * stays well under a browser's timeout; the admin screen calls again until
     * nothing remains.
     */
    static final int BATCH = 25;

    private static final int MAX_PAGE_SIZE = 200;

    private final OnboardingStore store;
    private final UserService users;
    private final TemporaryPasswordGenerator passwords;
    private final IdNumberProtector protector;
    private final LoginDetailsMailer mailer;
    private final AuditLogWriter audit;
    private final TransactionTemplate tx;
    private final String loginUrl;
    private final int passwordDays;

    public OnboardingService(OnboardingStore store, UserService users, TemporaryPasswordGenerator passwords,
            IdNumberProtector protector, LoginDetailsMailer mailer, AuditLogWriter audit,
            PlatformTransactionManager transactions,
            @Value("${app.onboarding.login-url:}") String loginUrl,
            @Value("${app.onboarding.temporary-password-days:7}") int passwordDays) {
        this.store = store;
        this.users = users;
        this.passwords = passwords;
        this.protector = protector;
        this.mailer = mailer;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
        this.loginUrl = loginUrl.strip();
        this.passwordDays = Math.max(1, passwordDays);
    }

    public Summary summary() {
        OnboardingStore.ConsultantCounts c = store.consultantCounts();
        return new Summary(c.total(), c.active(), c.withEmail(), c.withLogin(), store.countEligible(),
                store.countBlocked(), store.inviteCounts(), mailer.isConfigured(), mailer.senderAddress(),
                loginUrl.isEmpty() ? null : loginUrl, store.countExpired(), store.countRegeneratable(), passwordDays);
    }

    /**
     * Makes logins for up to {@link #BATCH} eligible consultants. Each consultant
     * is its own transaction: one that fails (for example an email another login
     * took a moment ago) is reported in {@code skipped} and the rest go ahead.
     *
     * @param consultantIds only these, or null for every eligible consultant
     */
    public GenerateResult generate(long adminId, List<Long> consultantIds) {
        Set<Long> only = consultantIds == null ? null : new LinkedHashSet<>(consultantIds);
        List<Skipped> skipped = new ArrayList<>();
        if (only != null) {
            // Ids that are not eligible get a reason instead of silently doing nothing.
            Set<Long> eligibleIds = new HashSet<>();
            store.eligible(only, only.size()).forEach(e -> eligibleIds.add(e.id()));
            for (Long id : only) {
                if (!eligibleIds.contains(id)) {
                    var c = store.consultant(id);
                    skipped.add(new Skipped(id, c.map(OnboardingStore.ConsultantEmailRow::code).orElse(null),
                            c.map(OnboardingStore.ConsultantEmailRow::fullName).orElse(null),
                            c.isEmpty() ? "Consultant does not exist."
                                    : c.get().hasLogin() ? "Already has a login (its password is not changed)."
                                            : c.get().email() == null ? "Has no email address."
                                                    : "Not active, or the email is used by another login."));
                }
            }
        }

        Set<String> usedInBatch = new HashSet<>();
        int created = 0;
        for (OnboardingStore.EligibleConsultant c : store.eligible(only, BATCH)) {
            String password = passwords.next();
            while (!usedInBatch.add(password)) {
                password = passwords.next(); // never the same password twice
            }
            String temporary = password;
            try {
                tx.executeWithoutResult(status -> {
                    UserResponse user = users.create(new CreateUserRequest(c.email(), temporary,
                            Set.of(RoleName.CONSULTANT), c.id()));
                    long inviteId = store.insertInvite(c.id(), user.id(), c.email(),
                            protector.sealText(SEAL_PURPOSE, temporary), adminId);
                    audit.record("LOGIN_INVITE_CREATED", "CONSULTANT", c.id(),
                            meta("userId", user.id(), "inviteId", inviteId, "email", c.email()));
                });
                created++;
            } catch (ApiException e) {
                skipped.add(new Skipped(c.id(), c.code(), c.fullName(), e.getMessage()));
            } catch (DataIntegrityViolationException e) {
                // For example the same email taken by another login a moment ago.
                skipped.add(new Skipped(c.id(), c.code(), c.fullName(),
                        "Conflicts with a change made at the same time. Try again."));
            }
        }
        return new GenerateResult(created, store.countEligible(), skipped);
    }

    /**
     * "Regenerate": a new temporary password for logins whose emailed one expired
     * unused, or whose email FAILED (docs/consultant-onboarding.md, section 3).
     * The login gets the new password through UserService.resetPassword (which
     * also clears a lockout and its old time limit), the invite goes back to
     * PENDING with the new password sealed, and "Send login details" emails it.
     * Up to {@link #BATCH} per call, each in its own transaction.
     *
     * @param inviteIds only these, or null for every one that can be regenerated
     */
    public GenerateResult regenerate(long adminId, List<Long> inviteIds) {
        List<Skipped> skipped = new ArrayList<>();
        if (inviteIds != null) {
            Set<Long> ok = new HashSet<>();
            store.regeneratable(inviteIds, inviteIds.size()).forEach(r -> ok.add(r.inviteId()));
            for (Long id : new LinkedHashSet<>(inviteIds)) {
                if (!ok.contains(id)) {
                    skipped.add(new Skipped(id, null, null, "Not expired or failed, already signed in, "
                            + "or the login or consultant is not active (invite " + id + ")."));
                }
            }
        }
        Set<String> usedInBatch = new HashSet<>();
        int created = 0;
        for (OnboardingStore.Regeneratable r : store.regeneratable(inviteIds, BATCH)) {
            String password = passwords.next();
            while (!usedInBatch.add(password)) {
                password = passwords.next();
            }
            String temporary = password;
            try {
                tx.executeWithoutResult(status -> {
                    users.resetPassword(r.userId(), new ResetPasswordRequest(temporary));
                    store.resetInvite(r.inviteId(), protector.sealText(SEAL_PURPOSE, temporary), adminId);
                    audit.record("LOGIN_INVITE_REGENERATED", "CONSULTANT", r.consultantId(),
                            meta("userId", r.userId(), "inviteId", r.inviteId(), "email", r.email()));
                });
                created++;
            } catch (ApiException e) {
                skipped.add(new Skipped(r.consultantId(), r.consultantCode(), r.consultantName(), e.getMessage()));
            } catch (DataIntegrityViolationException e) {
                skipped.add(new Skipped(r.consultantId(), r.consultantCode(), r.consultantName(),
                        "Conflicts with a change made at the same time. Try again."));
            }
        }
        return new GenerateResult(created, store.countRegeneratable(), skipped);
    }

    /** "Send login details": PENDING emails go into the background queue. */
    public QueueResult queue(long adminId, List<Long> inviteIds) {
        requireMail();
        int queued = store.queuePending(inviteIds, adminId);
        audit.record("LOGIN_DETAILS_QUEUED", null, null, meta("invites", queued, "selected",
                inviteIds == null ? "ALL" : inviteIds.size()));
        return new QueueResult(queued);
    }

    /** FAILED emails go back into the queue with the same password. */
    public QueueResult retry(long adminId, List<Long> inviteIds) {
        requireMail();
        int queued = store.retryFailed(inviteIds, adminId);
        audit.record("LOGIN_DETAILS_RETRIED", null, null, meta("invites", queued, "selected",
                inviteIds == null ? "ALL" : inviteIds.size()));
        return new QueueResult(queued);
    }

    public PageResponse<Invite> invites(String status, int page, int size) {
        String s = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (s != null && !List.of("PENDING", "QUEUED", "SENDING", "SENT", "FAILED", "EXPIRED").contains(s)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-request", "Unknown status",
                    "status must be PENDING, QUEUED, SENDING, SENT, FAILED or EXPIRED.");
        }
        int pageSize = clamp(size);
        int p = Math.max(page, 0);
        List<Invite> items = store.invites(s, pageSize, (long) p * pageSize).stream().map(Invite::from).toList();
        return page(items, p, pageSize, store.countInvites(s));
    }

    public PageResponse<BlockedConsultant> blocked(int page, int size) {
        int pageSize = clamp(size);
        int p = Math.max(page, 0);
        List<BlockedConsultant> items = store.blocked(pageSize, (long) p * pageSize).stream()
                .map(b -> new BlockedConsultant(b.id(), b.code(), b.fullName(), b.email(), b.reason())).toList();
        return page(items, p, pageSize, store.countBlocked());
    }

    /**
     * Sets a consultant's email, for example to fix a conflict the import
     * reported. Not for a consultant who already has a login: their login's email
     * is changed on the user instead.
     */
    public ConsultantEmail setConsultantEmail(long consultantId, String email) {
        tx.executeWithoutResult(status -> {
            var c = store.consultant(consultantId).orElseThrow(() -> ApiException.notFound("consultant:not-found",
                    "Consultant " + consultantId + " does not exist."));
            String normalized = email.strip().toLowerCase(Locale.ROOT);
            if (c.hasLogin()) {
                throw ApiException.conflict("consultant:has-login", "Consultant has a login",
                        "This consultant already has a login. Their email cannot be changed here.");
            }
            if (store.consultantEmailTaken(normalized, consultantId)) {
                throw ApiException.conflict("consultant:duplicate-email", "Email already in use",
                        "Another consultant already has this email.");
            }
            store.setConsultantEmail(consultantId, normalized);
            audit.record("CONSULTANT_EMAIL_SET", "CONSULTANT", consultantId,
                    meta("from", c.email(), "to", normalized));
        });
        var c = store.consultant(consultantId).orElseThrow();
        return new ConsultantEmail(c.id(), c.code(), c.fullName(), c.email());
    }

    private void requireMail() {
        if (!mailer.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail:not-configured", "Email is not set up",
                    "The Microsoft 365 email settings have not been filled in yet. Ask IT to set them up "
                            + "(docs/consultant-onboarding.md, \"Mail set-up\").");
        }
        if (loginUrl.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "mail:no-login-url", "Login address missing",
                    "app.onboarding.login-url is not set, so the emails would not say where to sign in.");
        }
    }

    private static int clamp(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    private static <T> PageResponse<T> page(List<T> items, int page, int size, long total) {
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }
}
