package za.co.mafori.creditadvisory.employer;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.common.web.PageResponse;

/**
 * Employers from the Mafori Employer Schedule. Read: everyone signed in.
 * Change: ADMIN.
 */
@RestController
@RequestMapping("/api/employers")
public class EmployerController {

    private final EmployerService service;
    private final EmployerMergeService merges;

    public EmployerController(EmployerService service, EmployerMergeService merges) {
        this.service = service;
        this.merges = merges;
    }

    @GetMapping
    public PageResponse<EmployerResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String employerType,
            @RequestParam(required = false) Boolean needsReview,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.search(search, active, employerType, needsReview, page, size);
    }

    @GetMapping("/{id}/aliases")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public java.util.List<EmployerAliasResponse> aliases(@PathVariable long id) {
        return merges.aliases(id);
    }

    @PostMapping("/{id}/merge")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployerMergeResponse merge(@PathVariable long id, @Valid @RequestBody EmployerMergeRequest request) {
        return merges.merge(id, request);
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployerResponse markReviewed(@PathVariable long id, @Valid @RequestBody EmployerReviewRequest request) {
        return merges.markReviewed(id, request);
    }

    @GetMapping("/{id}")
    public EmployerResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<EmployerResponse> create(@Valid @RequestBody EmployerRequest request) {
        EmployerResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/employers/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployerResponse update(@PathVariable long id, @Valid @RequestBody UpdateEmployerRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/active")
    @PreAuthorize("hasRole('ADMIN')")
    public EmployerResponse changeActive(@PathVariable long id,
            @Valid @RequestBody ChangeEmployerActiveRequest request) {
        return service.changeActive(id, request);
    }
}
