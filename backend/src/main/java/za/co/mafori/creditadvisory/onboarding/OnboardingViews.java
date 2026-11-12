package za.co.mafori.creditadvisory.onboarding;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response bodies of /api/onboarding (docs/consultant-onboarding.md). */
public final class OnboardingViews {

    private OnboardingViews() {
    }

    
    public record Summary(long consultants, long activeConsultants, long withEmail, long withLogin,
            long eligibleForLogin, long needAttention, Map<String, Long> invites, boolean mailConfigured,
            String mailSender, String loginUrl) {
    }

    /** POST /api/onboarding/logins. Leave consultantIds out for "every eligible consultant". */
    public record GenerateRequest(@Size(max = 500) List<Long> consultantIds) {
    }

    
    public record GenerateResult(int created, long remaining, List<Skipped> skipped) {
    }

    public record Skipped(long consultantId, String consultantCode, String fullName, String reason) {
    }

    /** POST /api/onboarding/invites/send and /retry. Leave inviteIds out for "all of them". */
    public record InviteIdsRequest(@Size(max = 1000) List<Long> inviteIds) {
    }

    public record QueueResult(int queued) {
    }

    public record Invite(long id, long consultantId, String consultantCode, String consultantName, long userId,
            String email, String status, int attempts, String lastError, Instant generatedAt, Instant queuedAt,
            Instant lastAttemptAt, Instant nextAttemptAt, Instant sentAt) {

        static Invite from(OnboardingStore.InviteRow r) {
            return new Invite(r.id(), r.consultantId(), r.consultantCode(), r.consultantName(), r.userId(), r.email(),
                    r.status(), r.attempts(), r.lastError(), r.generatedAt(), r.queuedAt(), r.lastAttemptAt(),
                    r.nextAttemptAt(), r.sentAt());
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
