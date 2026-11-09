package za.co.mafori.creditadvisory.imports.alias;

import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.imports.alias.ConsultantAliasService.AliasView;
import za.co.mafori.creditadvisory.imports.alias.ConsultantAliasService.CreateAliasRequest;

@RestController
@RequestMapping("/api/consultant-aliases")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class ConsultantAliasController {

    private final ConsultantAliasService service;

    public ConsultantAliasController(ConsultantAliasService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AliasView> list(@RequestParam(required = false) Long consultantId,
            @RequestParam(required = false) String search, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.list(consultantId, search, page, size);
    }

    @PostMapping
    public ResponseEntity<AliasView> create(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestBody CreateAliasRequest request) {
        AliasView created = service.create(caller.userId(), request);
        return ResponseEntity.created(URI.create("/api/consultant-aliases/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable long id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }

}
