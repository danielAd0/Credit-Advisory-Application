package za.co.mafori.creditadvisory.creditcase;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/**
 * Credit case endpoints (contract section 9). Any signed-in user; what each
 * caller may see is decided in CreditCaseService, never here or in the
 * frontend.
 */
@RestController
@RequestMapping("/api/cases")
public class CreditCaseController {

    private final CreditCaseService service;

    public CreditCaseController(CreditCaseService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CaseListItem> list(
            @AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) Long clientId,
            @RequestParam(required = false) Long consultantId,
            @RequestParam(required = false) CaseStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(caller, clientId, consultantId, status, page, size);
    }

    /** The case with all its notes - audited. */
    @GetMapping("/{id}")
    public CaseDetail get(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id) {
        return service.view(caller, id).orElseThrow(() -> notFound(id));
    }

    @PostMapping
    public ResponseEntity<CaseDetail> create(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody CreateCaseRequest request) {
        CaseDetail created = service.create(caller, request)
                .orElseThrow(() -> ApiException.notFound("client:not-found",
                        "Client " + request.clientId() + " does not exist."));
        return ResponseEntity.created(URI.create("/api/cases/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    public CaseDetail update(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody UpdateCaseRequest request) {
        return service.update(caller, id, request).orElseThrow(() -> notFound(id));
    }

    @PostMapping("/{id}/notes")
    public ResponseEntity<CaseNoteResponse> addNote(@AuthenticationPrincipal AppUserPrincipal caller,
            @PathVariable long id, @Valid @RequestBody AddCaseNoteRequest request) {
        CaseNoteResponse note = service.addNote(caller, id, request).orElseThrow(() -> notFound(id));
        return ResponseEntity.status(HttpStatus.CREATED).body(note);
    }

    /** Same answer whether the case does not exist or the caller may not see it. */
    private static ApiException notFound(long id) {
        return ApiException.notFound("case:not-found", "Case " + id + " does not exist.");
    }
}
