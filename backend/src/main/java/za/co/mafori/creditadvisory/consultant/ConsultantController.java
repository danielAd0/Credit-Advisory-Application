package za.co.mafori.creditadvisory.consultant;

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

/** Consultant endpoints (contract section 3). */
@RestController
@RequestMapping("/api/consultants")
public class ConsultantController {

    private final ConsultantService service;

    public ConsultantController(ConsultantService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public PageResponse<ConsultantResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ConsultantStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.search(search, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public ConsultantResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ConsultantResponse> create(@Valid @RequestBody CreateConsultantRequest request) {
        ConsultantResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/consultants/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ConsultantResponse rename(@PathVariable long id, @Valid @RequestBody RenameConsultantRequest request) {
        return service.rename(id, request);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ConsultantResponse changeStatus(@PathVariable long id,
            @Valid @RequestBody ChangeConsultantStatusRequest request) {
        return service.changeStatus(id, request);
    }
}
