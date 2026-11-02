package za.co.mafori.creditadvisory.dashboard;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * Dashboard (contract section 7). Any signed-in user; the numbers are scoped by
 * role.
 */
@RestController
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/api/dashboard/summary")
    public DashboardSummary summary(@AuthenticationPrincipal AppUserPrincipal caller) {
        return service.summary(caller);
    }
}
