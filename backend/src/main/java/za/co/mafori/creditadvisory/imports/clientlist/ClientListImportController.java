package za.co.mafori.creditadvisory.imports.clientlist;

import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.common.web.ApiException;
import za.co.mafori.creditadvisory.common.web.PageResponse;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ClientListSummary;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ConfirmRequest;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ConfirmResult;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.IssueFile;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.IssueView;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.ListItem;
import za.co.mafori.creditadvisory.imports.clientlist.ClientListViews.StagedRowView;

/**
 * The first load of the full client list (docs/api-client-list-import.md).
 * ADMIN only, like every import: only the admin runs imports (decision, 9
 * November 2026).
 */
@RestController
@RequestMapping("/api/imports/client-list")
@PreAuthorize("hasRole('ADMIN')")
public class ClientListImportController {

    private final ClientListImportService service;

    public ClientListImportController(ClientListImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ClientListSummary> preview(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestPart("file") MultipartFile file) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file:unreadable", "Upload failed",
                    "The uploaded file could not be read. Try again.");
        }
        ClientListSummary summary = service.preview(caller.userId(), bytes, file.getOriginalFilename());
        return ResponseEntity.created(URI.create("/api/imports/client-list/" + summary.id())).body(summary);
    }

    @GetMapping
    public PageResponse<ListItem> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public ClientListSummary summary(@PathVariable long id) {
        return service.summary(id);
    }

    @GetMapping("/{id}/rows")
    public PageResponse<StagedRowView> rows(@PathVariable long id, @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.rows(id, action, page, size);
    }

    @GetMapping("/{id}/issues")
    public PageResponse<IssueView> issues(@PathVariable long id, @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.issues(id, severity, page, size);
    }

    @GetMapping("/{id}/issues/export")
    public ResponseEntity<byte[]> exportIssues(@PathVariable long id,
            @RequestParam(required = false) String severity) {
        IssueFile file = service.exportIssues(id, severity);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"" + file.fileName() + "\"")
                .body(file.content());
    }

    @PostMapping("/{id}/confirm")
    public ConfirmResult confirm(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(caller.userId(), id, request);
    }

    @PostMapping("/{id}/cancel")
    public ClientListSummary cancel(@PathVariable long id) {
        return service.cancel(id);
    }
}
