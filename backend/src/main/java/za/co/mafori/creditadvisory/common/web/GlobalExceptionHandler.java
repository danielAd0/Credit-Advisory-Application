package za.co.mafori.creditadvisory.common.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into ProblemDetail JSON (contract section 1).
 *
 * <p>
 * Extends ResponseEntityExceptionHandler, so Spring's own errors (bad JSON,
 * wrong parameter
 * type, unsupported method...) are ProblemDetail too. Security errors (401/403)
 * are handled by
 * SecurityConfig, not here.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    public static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> apiException(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(e.toProblemDetail());
    }

    /** Hibernate's own version check failed at commit: someone saved in between */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> optimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiException.staleVersion().toProblemDetail());
    }

    /**
     * A database constraint rejected the change (e.g. a duplicate slipped past our
     * check in a race).
     * The database message can contain row values, so it is never sent to the
     * client.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> dataIntegrity(DataIntegrityViolationException e) {
        log.warn("Database constraint violation ({})", e.getClass().getSimpleName());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The change conflicts with existing data. Reload and try again.");
        problem.setTitle("Conflict");
        problem.setType(URI.create("urn:credit-advisory:conflict:constraint"));
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    /** @Valid failed on a request body: 400 with one entry per bad field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> Map.of(
                        "field", fieldError.getField(),
                        "message", Objects.requireNonNullElse(fieldError.getDefaultMessage(), "is invalid")))
                .toList();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "One or more fields are invalid");
        problem.setTitle("Invalid request");
        problem.setType(URI.create("urn:credit-advisory:validation:invalid-request"));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }
}
