package za.co.mafori.creditadvisory.sale;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
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

    public SaleController(SaleService service) {
        this.service = service;
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
    @GetMapping(path = "/api/transactions", params = "clientId")
    public List<SavedSale> list(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam long clientId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(caller, clientId, from, to);
    }

    /** The Sales history page: typed-in sales across clients, a page at a time, with totals. */
    @GetMapping(path = "/api/transactions", params = "!clientId")
    public SalesPage history(@AuthenticationPrincipal AppUserPrincipal caller,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long consultantId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.history(caller, from, to, consultantId, page, size);
    }
}
