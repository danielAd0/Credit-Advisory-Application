package za.co.mafori.creditadvisory.client;

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

    public ClientController(ClientQueryService queries, ClientService service) {
        this.queries = queries;
        this.service = service;
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
     * One client. The full ID number only with ?include=idNumber - and only that
     * is audited (contract section 4).
     */
    @GetMapping("/{id}")
    public ClientDetail get(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @RequestParam(required = false) String include) {
        if (include != null && !include.equals("idNumber")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation:invalid-parameter", "Invalid parameter",
                    "include only accepts the value idNumber.");
        }
        return service.view(caller, id, include != null).orElseThrow(() -> notFound(id));
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
     * Same answer whether the client does not exist or the caller may not see it.
     */
    private static ApiException notFound(long id) {
        return ApiException.notFound("client:not-found", "Client " + id + " does not exist.");
    }
}
