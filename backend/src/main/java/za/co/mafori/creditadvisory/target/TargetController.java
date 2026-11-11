package za.co.mafori.creditadvisory.target;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Daily and weekly quotation targets. MANAGER and ADMIN only. */
@RestController
@RequestMapping("/api/targets")
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
public class TargetController {

    private final TargetService service;

    public TargetController(TargetService service) {
        this.service = service;
    }

    @GetMapping
    public TargetsResponse list() {
        return service.list();
    }

    @PostMapping
    public TargetResponse set(@Valid @RequestBody SetTargetRequest request) {
        return service.set(request);
    }
}
