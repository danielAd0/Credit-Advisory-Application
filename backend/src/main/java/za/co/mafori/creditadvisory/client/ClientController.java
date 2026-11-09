package za.co.mafori.creditadvisory.client;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.RecentPasswordConfirmation;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

/**
 * Client endpoints (contract section 4). Any signed-in user; what each caller
 * may
 * see is decided in ClientQueryService and ClientService, never here or in the
 * frontend.
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientQueryService queries;
    private final ClientService service;
    private final RecentPasswordConfirmation passwordConfirmation;
    private final IdRevealLimiter revealLimiter;

    public ClientController(ClientQueryService queries, ClientService service,
            RecentPasswordConfirmation passwordConfirmation, IdRevealLimiter revealLimiter) {
        this.queries = queries;
        this.service = service;
        this.passwordConfirmation = passwordConfirmation;
        this.revealLimiter = revealLimiter;
    }

    @GetMapping
    public PageResponse<ClientListItem> list(
            @AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long consultantId,
            @RequestParam(required = false) ConsultantStatus consultantStatus,
            @RequestParam(defaultValue = "false") boolean unassigned,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queries.search(caller, search, consultantId, consultantStatus, unassigned, page, size);
    }

    /**
     * One client. The full ID number only with ?include=idNumber, and only after
     * the user re-entered their password in the last few minutes (POST
     * /api/auth/reauthenticate), within an hourly limit, and audited (contract
     * section 4).
     */
    @GetMapping("/{id}")
    public ClientDetail get(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @RequestParam(required = false) String include, HttpServletRequest request) {
        if (include != null && !include.equals("idNumber")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-parameter", "Invalid parameter",
                    "include only accepts the value idNumber.");
        }
        boolean includeIdNumber = include != null;
        if (includeIdNumber) {
            // Both before the client is even looked up, so the answer is the same
            // whether the client exists or not.
            passwordConfirmation.require(request);
            revealLimiter.recordReveal(caller.userId());
        }
        return service.view(caller, id, includeIdNumber).orElseThrow(() -> notFound(id));
    }

    /** Find by ID number. POST so the number is never in a URL or a server log. */
    @PostMapping("/lookup")
    public ClientListItem lookup(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody LookupClientRequest request) {
        return service.lookup(caller, request.idNumber())
                .orElseThrow(() -> ApiException.notFound("client:not-found", "No client found with this ID number."));
    }

    @PostMapping
    public ResponseEntity<ClientDetail> create(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody CreateClientRequest request) {
        ClientDetail created = service.create(caller, request);
        return ResponseEntity.created(URI.create("/api/clients/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public ClientDetail update(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody UpdateClientRequest request) {
        return service.update(caller, id, request).orElseThrow(() -> notFound(id));
    }

    /**
     * Correct a wrongly captured ID number (contract section 4). MANAGER / ADMIN
     * only. POST, so the number is in the body and never in a URL or a log.
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @PostMapping("/{id}/id-number-correction")
    public ClientDetail correctIdNumber(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody CorrectIdNumberRequest request) {
        return service.correctIdNumber(caller, id, request).orElseThrow(() -> notFound(id));
    }

    /** Too many full ID numbers this hour (contract section 4). */
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

    /**
     * Same answer whether the client does not exist or the caller may not see it.
     */
    private static ApiException notFound(long id) {
        return ApiException.notFound("client:not-found", "Client " + id + " does not exist.");
    }
}
