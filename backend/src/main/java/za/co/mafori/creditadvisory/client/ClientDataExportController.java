package za.co.mafori.creditadvisory.client;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;
import za.co.mafori.creditadvisory.auth.RecentPasswordConfirmation;
import za.co.mafori.creditadvisory.common.web.ApiException;

/**
 * POPIA access request: everything held about one client, as a file to hand
 * over (docs/api-auth-consultants-clients.md, section 4, "Client data
 * export"): a PDF to read, a CSV for a spreadsheet, or JSON (the default).
 * ADMIN only. It holds the full ID number, so it has the same guards as
 * showing one: the password re-entered in the last few minutes, the hourly
 * limit, and the audit log. POST, so nothing caches or prefetches it.
 */
@RestController
@RequestMapping("/api/clients")
@PreAuthorize("hasRole('ADMIN')")
public class ClientDataExportController {

        static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");

        private final ClientDataExportService service;
        private final RecentPasswordConfirmation passwordConfirmation;
        private final IdRevealLimiter revealLimiter;

        public ClientDataExportController(ClientDataExportService service,
                        RecentPasswordConfirmation passwordConfirmation, IdRevealLimiter revealLimiter) {
                this.service = service;
                this.passwordConfirmation = passwordConfirmation;
                this.revealLimiter = revealLimiter;
        }

        @PostMapping("/{id}/data-export")
        public ResponseEntity<?> export(@AuthenticationPrincipal AppUserPrincipal caller, @PathVariable long id,
                        @RequestParam(required = false) String format, HttpServletRequest request) {
                String chosen = format(format);
                // Both before the client is looked up, so the answer is the same whether it
                // exists or not.
                passwordConfirmation.require(request, "Enter your password again before exporting a client's data.");
                revealLimiter.recordReveal(caller.userId());
                ClientDataExport export = service.export(caller, id, chosen).orElseThrow(() -> ApiException.notFound(
                                "client:not-found", "Client " + id + " does not exist."));

                ResponseEntity.BodyBuilder answer = ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                                "attachment; filename=\"client-" + id + "-data." + chosen + "\"")
                                .cacheControl(CacheControl.noStore());
                return switch (chosen) {
                        case "pdf" ->
                                answer.contentType(MediaType.APPLICATION_PDF).body(ClientDataExportFiles.pdf(export));
                        case "csv" -> answer.contentType(CSV).body(ClientDataExportFiles.csv(export));
                        default -> answer.contentType(MediaType.APPLICATION_JSON).body(export);
                };
        }

        /** json (also when none is given), pdf or csv; anything else is a 400. */
        private static String format(String requested) {
                if (requested == null || requested.isBlank()) {
                        return "json";
                }
                String format = requested.strip().toLowerCase(Locale.ROOT);
                if (!format.equals("json") && !format.equals("pdf") && !format.equals("csv")) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "client:export-format-invalid", "Unknown format",
                                        "Choose format=pdf, format=csv or format=json.");
                }
                return format;
        }

        /**
         * Too many full ID numbers this hour: the same answer as showing one
         * (ClientController).
         */
        @ExceptionHandler(IdRevealLimitException.class)
        public ResponseEntity<ProblemDetail> tooManyReveals(IdRevealLimitException e) {
                ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                                "You have viewed the maximum number of full ID numbers for this hour. Try again later.");
                problem.setTitle("Too many ID numbers viewed");
                problem.setType(URI.create("urn:credit-advisory:client:id-reveal-limit"));
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                                .body(problem);
        }
}
