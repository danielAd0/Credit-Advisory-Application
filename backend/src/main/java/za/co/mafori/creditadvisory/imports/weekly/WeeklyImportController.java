package za.co.mafori.creditadvisory.imports.weekly;

import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
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
import za.co.mafori.creditadvisory.imports.register.WeekStarting;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConfirmRequest;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConfirmResult;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ConsultantMappingRequest;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ImportListItem;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.ImportSummary;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.IssueView;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.StagedRowView;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportViews.WeekGuess;
import za.co.mafori.creditadvisory.user.RoleName;

@RestController
@RequestMapping("/api/imports")
@PreAuthorize("hasAnyRole('ADMIN','MANAGER')")
public class WeeklyImportController {

    private final WeeklyImportService service;

    public WeeklyImportController(WeeklyImportService service) {
        this.service = service;
    }

    @PostMapping(path = "/weekly-register/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportSummary> preview(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekStarting) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file:unreadable", "Upload failed",
                    "The uploaded file could not be read. Try again.");
        }
        ImportSummary summary = service.preview(caller.userId(), bytes, file.getOriginalFilename(), weekStarting);
        return ResponseEntity.created(URI.create("/api/imports" + summary.id())).body(summary);

    }

    @GetMapping("/weekly-register/week-guess")
    public WeekGuess weekGuess(@RequestParam String fileName) {
        return new WeekGuess(WeekStarting.guessFromFileName(fileName).orElse(null));
    }

    @GetMapping
    public PageResponse<ImportListItem> list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    public ImportSummary summary(@PathVariable long id) {
        return service.summary(id);
    }

    @GetMapping("/{id}/rows")
    public PageResponse<StagedRowView> rows(@PathVariable long id, @RequestParam(required = false) String sheet,
            @RequestParam(required = false) String action, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.rows(id, sheet, action, page, size);
    }

    @GetMapping("/{id}/issues")
    public PageResponse<IssueView> issues(@PathVariable long id, @RequestParam(required = false) String severity,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.issues(id, severity, page, size);
    }

    /**
     * Matches a consultant name from the sheet to a consultant; remembered for
     * later imports.
     */
    @PostMapping("/{id}/consultant-mappings")
    public ImportSummary mapConsultant(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody ConsultantMappingRequest request) {
        return service.mapConsultant(caller.userId(), id, request.nameInSheet(), request.consultantId());
    }

    @PostMapping("/{id}/confirm")
    public ConfirmResult confirm(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody ConfirmRequest request) {
        return service.confirm(caller.userId(), caller.hasRole(RoleName.ADMIN), id, request.fileDigest(),
                request.replaceReason());
    }

    @PostMapping("/{id}/cancel")
    public ImportSummary cancel(@PathVariable long id) {
        return service.cancel(id);
    }

}
