package za.co.mafori.creditadvisory.onboarding;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.RecentPasswordConfirmation;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.BlockedConsultant;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.ConsultantEmail;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.ConsultantEmailRequest;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.GenerateRequest;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.GenerateResult;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.Invite;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.InviteIdsRequest;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.QueueResult;
import za.co.mafori.creditadvisory.onboarding.OnboardingViews.Summary;

/**
 * Consultant onboarding - ADMIN only (docs/consultant-onboarding.md).
 * Generating passwords and sending them also need the admin's password to have
 * been confirmed in the last few minutes (POST /api/auth/reauthenticate).
 */
@RestController
@RequestMapping("/api/onboarding")
@PreAuthorize("hasRole('ADMIN')")
public class OnboardingController {

    private final OnboardingService service;
    private final RecentPasswordConfirmation recentPassword;

    public OnboardingController(OnboardingService service, RecentPasswordConfirmation recentPassword) {
        this.service = service;
        this.recentPassword = recentPassword;
    }

    @GetMapping("/summary")
    public Summary summary() {
        return service.summary();
    }

    /**
     * Active consultants without a login who cannot get one yet, with the reason.
     */
    @GetMapping("/blocked")
    public PageResponse<BlockedConsultant> blocked(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.blocked(page, size);
    }

    /**
     * "Generate temporary passwords": up to 25 logins per call; call again while
     * remaining > 0.
     */
    @PostMapping("/logins")
    public GenerateResult generate(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody(required = false) GenerateRequest request, HttpServletRequest http) {
        recentPassword.require(http, "Enter your password again to generate consultant passwords.");
        return service.generate(caller.userId(), request == null ? null : request.consultantIds());
    }

    @GetMapping("/invites")
    public PageResponse<Invite> invites(@RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.invites(status, page, size);
    }

    /** "Send login details": queues the PENDING emails (or only these ids). */
    @PostMapping("/invites/send")
    public QueueResult send(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody(required = false) InviteIdsRequest request, HttpServletRequest http) {
        recentPassword.require(http, "Enter your password again to send login details.");
        return service.queue(caller.userId(), request == null ? null : request.inviteIds());
    }

    /** Puts FAILED emails back in the queue with the same password. */
    @PostMapping("/invites/retry")
    public QueueResult retry(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody(required = false) InviteIdsRequest request, HttpServletRequest http) {
        recentPassword.require(http, "Enter your password again to resend login details.");
        return service.retry(caller.userId(), request == null ? null : request.inviteIds());
    }

    /**
     * "Regenerate": new temporary passwords for expired or failed login emails (up
     * to 25 per call; call again while remaining > 0). Then "Send login details".
     */
    @PostMapping("/invites/regenerate")
    public GenerateResult regenerate(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody(required = false) InviteIdsRequest request, HttpServletRequest http) {
        recentPassword.require(http, "Enter your password again to make new consultant passwords.");
        return service.regenerate(caller.userId(), request == null ? null : request.inviteIds());
    }

    @PutMapping("/consultants/{id}/email")
    public ConsultantEmail setConsultantEmail(@PathVariable long id,
            @Valid @RequestBody ConsultantEmailRequest request) {
        return service.setConsultantEmail(id, request.email());
    }
}
