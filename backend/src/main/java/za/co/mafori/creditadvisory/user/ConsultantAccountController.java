package za.co.mafori.creditadvisory.user;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One-step consultant onboarding - ADMIN only. Later changes use the existing
 * endpoints: /api/consultants/{id} (name, status) and /api/users/{id}
 * (password-reset, roles, enabled, unlock).
 */
@RestController
@RequestMapping("/api/consultant-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class ConsultantAccountController {

    private final ConsultantAccountService service;

    public ConsultantAccountController(ConsultantAccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ConsultantAccountResponse> create(
            @Valid @RequestBody CreateConsultantAccountRequest request) {
        ConsultantAccountResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/consultants/" + created.consultant().id())).body(created);
    }
}
