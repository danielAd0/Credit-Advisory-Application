package za.co.mafori.creditadvisory.client;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.RecentPasswordConfirmation;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * POPIA access request: everything held about one client, as a JSON file
 * (docs/api-auth-consultants-clients.md, section 4, "Client data export").
 * ADMIN only. It holds the full ID number, so it has the same guards as
 * showing one: the password re-entered in the last few minutes, the hourly
 * limit, and the audit log. POST, so nothing caches or prefetches it.
 */
@RestController
@RequestMapping("/api/clients")
@PreAuthorize("hasRole('ADMIN')")
public class ClientDataExportController {

    private final ClientDataExportService service;
    private final RecentPasswordConfirmation passwordConfirmation;
    private final IdRevealLimiter revealLimiter;

    public ClientDataExportController(ClientDataExportService service,
            RecentPasswordConfirmation passwordConfirmation, IdRevealLimiter revealLimiter) {
        this.service = service;
        this.passwordConfirmation = passwordConfirmation;
        this.revealLimiter = revealLimiter;
    }

    @PostMapping("/{id}/data-export")
    public ResponseEntity<ClientDataExport> export(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable long id, HttpServletRequest request) {
        // Both before the client is looked up, so the answer is the same whether it
        // exists or not.
        passwordConfirmation.require(request, "Enter your password again before exporting a client's data.");
        revealLimiter.recordReveal(caller.userId());
        ClientDataExport export = service.export(caller, id).orElseThrow(() -> ApiException.notFound(
                "client:not-found", "Client " + id + " does not exist."));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"client-" + id + "-data.json\"")
                .cacheControl(CacheControl.noStore())
                .body(export);
    }

    /**
     * Too many full ID numbers this hour: the same answer as showing one
     * (ClientController).
     */
    @ExceptionHandler(IdRevealLimitException.class)
    public ResponseEntity<ProblemDetail> tooManyReveals(IdRevealLimitException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                "You have viewed the maximum number of full ID numbers for this hour. Try again later.");
        problem.setTitle("Too many ID numbers viewed");
        problem.setType(URI.create("urn:credit-advisory:client:id-reveal-limit"));
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(problem);
    }
}
