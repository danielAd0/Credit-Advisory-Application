package za.co.mafori.creditadvisory.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Request and response bodies of /api/onboarding
 * (docs/consultant-onboarding.md).
 */
public final class OnboardingViews {

        private OnboardingViews() {
        }

        /**
         * @param consultants       all consultants
         * @param activeConsultants status ACTIVE
         * @param withEmail         have an email on their record
         * @param withLogin         already have a login
         * @param eligibleForLogin  can get a login now (active, email, no login)
         * @param needAttention     active, no login, and cannot get one yet (see
         *                          /blocked)
         * @param invites           login emails by status: PENDING, QUEUED, SENDING,
         *                          SENT, FAILED
         * @param mailConfigured    false until IT fills in the Microsoft 365 settings
         * @param mailSender        the mailbox the emails come from
         * @param loginUrl          the address put in the emails
         * @param expiredLogins     emails sent whose password expired unused (V24)
         * @param canRegenerate     expired or failed logins that "Regenerate" gives a
         *                          new password
         * @param passwordDays      how long an emailed temporary password works
         */
        public record Summary(long consultants, long activeConsultants, long withEmail, long withLogin,
                        long eligibleForLogin, long needAttention, Map<String, Long> invites, boolean mailConfigured,
                        String mailSender, String loginUrl, long expiredLogins, long canRegenerate, int passwordDays) {
        }

        /**
         * POST /api/onboarding/logins. Leave consultantIds out for "every eligible
         * consultant".
         */
        public record GenerateRequest(@Size(max = 500) List<Long> consultantIds) {
        }

        /**
         * @param created   logins made in this call
         * @param remaining eligible consultants still without a login (call again)
         * @param skipped   consultants that could not get a login, with the reason
         */
        public record GenerateResult(int created, long remaining, List<Skipped> skipped) {
        }

        public record Skipped(long consultantId, String consultantCode, String fullName, String reason) {
        }

        /**
         * POST /api/onboarding/invites/send and /retry. Leave inviteIds out for "all of
         * them".
         */
        public record InviteIdsRequest(@Size(max = 1000) List<Long> inviteIds) {
        }

        public record QueueResult(int queued) {
        }

        /**
         * @param passwordExpiresAt when the emailed password stops working, or null
         * @param expired           sent and never used before its time limit:
         *                          regenerate it
         * @param signedIn          the consultant has already chosen their own password
         */
        public record Invite(long id, long consultantId, String consultantCode, String consultantName, long userId,
                        String email, String status, int attempts, String lastError, Instant generatedAt,
                        Instant queuedAt,
                        Instant lastAttemptAt, Instant nextAttemptAt, Instant sentAt, Instant passwordExpiresAt,
                        boolean expired,
                        boolean signedIn) {

                static Invite from(OnboardingStore.InviteRow r) {
                        return new Invite(r.id(), r.consultantId(), r.consultantCode(), r.consultantName(), r.userId(),
                                        r.email(),
                                        r.status(), r.attempts(), r.lastError(), r.generatedAt(), r.queuedAt(),
                                        r.lastAttemptAt(),
                                        r.nextAttemptAt(), r.sentAt(), r.passwordExpiresAt(), r.expired(),
                                        r.signedIn());
                }
        }

        /** reason: NO_EMAIL or EMAIL_USED_BY_ANOTHER_LOGIN. */
        public record BlockedConsultant(long consultantId, String consultantCode, String fullName, String email,
                        String reason) {
        }

        /** PUT /api/onboarding/consultants/{id}/email. */
        public record ConsultantEmailRequest(@NotBlank @Email @Size(max = 254) String email) {
        }

        public record ConsultantEmail(long consultantId, String consultantCode, String fullName, String email) {
        }
}
