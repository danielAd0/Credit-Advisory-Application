package za.co.mafori.creditadvisory.common.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * An error the API reports on purpose (not found, duplicate, stake version,
 * business rule).
 * Turned into a ProblemDetail response by GlobalExceptionHandler
 *
 * The details text is shown to users. Never put an ID number or other personal
 * data in it.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;

    /**
     * @param type short code after "urn:credit-advisory:", e.g.
     *             "consultant:duplicate-code"
     */
    public ApiException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public static ApiException notFound(String type, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, type, "Not found", detail);
    }

    public static ApiException conflict(String type, String title, String detail) {
        return new ApiException(HttpStatus.CONFLICT, type, title, detail);
    }

    public static ApiException unprocessable(String type, String title, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, type, title, detail);
    }

    /**
     * The standard "someone else saved it first" error (contract: optimistic
     * locking).
     */
    public static ApiException staleVersion() {
        return conflict("conflict:stale-version", "Changed by someone else",
                "This record was changed by someone else. Reload it and try again");
    }

    public ProblemDetail toProblemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        problem.setTitle(title);
        problem.setType(URI.create("urn:credit-advisory:" + type));
        return problem;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
