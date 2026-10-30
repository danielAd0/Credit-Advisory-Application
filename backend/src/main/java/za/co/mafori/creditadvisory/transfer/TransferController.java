package za.co.mafori.creditadvisory.transfer;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Transfers and assignment history - MANAGER and ADMIN only (contract section
 * 5).
 */
@RestController
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/api/client-transfers")
    public TransferResponse transfer(@AuthenticationPrincipal AppUserPrincipal caller,
            @Valid @RequestBody TransferRequest request) {
        return service.transfer(caller.userId(), request);
    }

    @GetMapping("/api/clients/{id}/assignments")
    public List<AssignmentResponse> assignments(@PathVariable long id) {
        return service.history(id);
    }
}
