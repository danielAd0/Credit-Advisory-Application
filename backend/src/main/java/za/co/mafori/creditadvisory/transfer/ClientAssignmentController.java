package za.co.mafori.creditadvisory.transfer;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Assigning unassigned clients to a consultant - MANAGER and ADMIN (SRS section
 * 4: "Reassign clients"). Find unassigned clients with GET
 * /api/clients?unassigned=true.
 */
@RestController
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class ClientAssignmentController {

    private final ClientAssignmentService service;

    public ClientAssignmentController(ClientAssignmentService service) {
        this.service = service;
    }

    @PostMapping("/api/client-assignments")
    public AssignClientsResponse assign(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody AssignClientsRequest request) {
        return service.assign(caller.userId(), request);
    }
}
