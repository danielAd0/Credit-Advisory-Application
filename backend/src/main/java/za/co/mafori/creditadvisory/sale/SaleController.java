package za.co.mafori.creditadvisory.sale;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

/**
 * The Consultant actions page (docs/api-sales.md). Everyone signed in: a
 * CONSULTANT for their own clients, MANAGER and ADMIN for every client.
 */
@RestController
public class SaleController {

    private final SaleService service;
    private final SaleHistoryService history;
    private final SaleEditService edits;

    public SaleController(SaleService service, SaleHistoryService history, SaleEditService edits) {
        this.service = service;
        this.history = history;
        this.edits = edits;
    }

    @GetMapping("/api/payment-terms")
    public List<NamedOption> paymentTerms() {
        return service.paymentTerms();
    }

    @GetMapping("/api/structures")
    public List<NamedOption> structures() {
        return service.structures();
    }

    /**
     * 201 when the sale is saved; 200 with the same sale when this
     * Idempotency-Key was used before (nothing is saved twice).
     */
    @PostMapping("/api/transactions")
    public ResponseEntity<SavedSale> capture(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CaptureSaleRequest request) {
        SaleService.Outcome outcome = service.capture(caller, idempotencyKey, request);
        if (!outcome.created()) {
            return ResponseEntity.ok(outcome.sale());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(outcome.sale());
    }

    /** A client's sales, newest first (at most 200), e.g. to confirm a save. */
    @GetMapping(value = "/api/transactions", params = "clientId")
    public List<SavedSale> list(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam long clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(caller, clientId, from, to);
    }

    /**
     * The Sales history page: every sale between from and to (both required),
     * newest first, a page
     * at a time, with the totals of the whole range. Without clientId, so it does
     * not clash with
     * the list above.
     */
    @GetMapping(value = "/api/transactions", params = "!clientId")
    public SaleHistoryPage history(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long consultantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return history.history(caller, from, to, consultantId, page, size);
    }

    /** One sale with every field, for the edit form. */
    @GetMapping("/api/transactions/{id}")
    public SaleDetail detail(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id) {
        return edits.detail(caller, id);
    }

    /** Changes a typed-in sale until its week's spreadsheet is imported. */
    @PatchMapping("/api/transactions/{id}")
    public SaleDetail edit(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @Valid @RequestBody EditSaleRequest request) {
        return edits.edit(caller, id, request);
    }

    /**
     * Deletes a typed-in sale until its week's spreadsheet is imported; 204 when
     * done.
     */
    @DeleteMapping("/api/transactions/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
            @RequestParam long version) {
        edits.delete(caller, id, version);
        return ResponseEntity.noContent().build();
    }
}
