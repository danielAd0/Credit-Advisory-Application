package za.co.mafori.creditadvisory.client;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.consultant.ConsultantStatus;

/**
 * Client endpoints (contract section 4). Create, lookup and full-ID view follow
 * once the
 * ID-number protection code is on main.
 */
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    private final ClientQueryService queries;

    public ClientController(ClientQueryService queries) {
        this.queries = queries;
    }

    /** Any signed-in user; results are scoped by role in ClientQueryService. */
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
}
