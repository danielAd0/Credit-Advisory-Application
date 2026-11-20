package za.co.mafori.creditadvisory.common.upload;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;

/**
 * Every uploaded file is scanned for viruses BEFORE any code reads it
 * (docs/security-accounts-and-uploads.md). One place for every upload, now and
 * later: the weekly register, the client list, and anything added after.
 *
 * <ul>
 * <li>Runs after Spring Security, so only signed-in users' uploads are
 * scanned (a signed-out upload is already answered 401).</li>
 * <li>Infected: 422 {@code upload:infected}, and an UPLOAD_REJECTED audit entry
 * with the signature, the file's size and extension (not its name).</li>
 * <li>Not scannable (scanner down, timeout, too big for the scanner): 503
 * {@code upload:scan-unavailable}. The file is refused, never let through
 * unscanned ("fail closed").</li>
 * <li>{@code app.upload-scan.mode=off} (laptops, tests): nothing is scanned.
 * Production refuses to start with it (SecuritySettingsCheck).</li>
 * </ul>
 */
@Component
public class UploadScanFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UploadScanFilter.class);

    private final UploadScanner scanner;
    private final AuditLogWriter audit;

    @Autowired
    public UploadScanFilter(@Value("${app.upload-scan.mode:off}") String mode,
            @Value("${app.upload-scan.clamd-host:127.0.0.1}") String host,
            @Value("${app.upload-scan.clamd-port:3310}") int port,
            @Value("${app.upload-scan.timeout:PT30S}") Duration timeout, AuditLogWriter audit) {
        this(scannerFor(mode, host, port, timeout), audit);
    }

    /** For tests: null scanner = off. */
    UploadScanFilter(UploadScanner scanner, AuditLogWriter audit) {
        this.scanner = scanner;
        this.audit = audit;
    }

    static UploadScanner scannerFor(String mode, String host, int port, Duration timeout) {
        return switch (mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT)) {
            case "clamd" -> new ClamdScanner(host, port, timeout);
            case "off", "" -> null;
            default -> throw new IllegalStateException("app.upload-scan.mode must be clamd or off");
        };
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String type = request.getContentType();
        return scanner == null || type == null
                || !type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Collection<Part> parts;
        try {
            parts = request.getParts();
        } catch (IllegalStateException | ServletException e) {
            // Too large or malformed: the controller answers with the usual error (413,
            // 400). Nothing was read.
            chain.doFilter(request, response);
            return;
        }
        List<Upload> uploads = new ArrayList<>();
        for (Part part : parts) {
            if (part.getSubmittedFileName() != null) {
                uploads.add(new Upload(part.getSubmittedFileName(), part.getSize(), part::getInputStream));
            }
        }
        Outcome outcome = check(scanner, uploads);
        switch (outcome.kind()) {
            case CLEAN -> chain.doFilter(request, response);
            case INFECTED -> {
                Upload u = outcome.upload();
                audit.record("UPLOAD_REJECTED", null, null, meta("reason", "INFECTED", "signature",
                        outcome.signature(), "path", request.getRequestURI(), "sizeBytes", u.size(), "extension",
                        extension(u.fileName())));
                log.warn("Upload refused: virus {} found in a file sent to {}", outcome.signature(),
                        request.getRequestURI());
                problem(response, 422, "upload:infected", "File refused",
                        "The scanner found a virus in this file (" + outcome.signature() + "). It was not read or"
                                + " stored. Tell IT, and do not open the file.");
            }
            case UNAVAILABLE -> {
                log.error("Upload refused: the virus scanner could not scan a file sent to {}",
                        request.getRequestURI(), outcome.error());
                problem(response, 503, "upload:scan-unavailable", "File not scanned",
                        "The file could not be checked for viruses, so it was not accepted. Try again in a few"
                                + " minutes; if it keeps happening, tell IT.");
            }
        }
    }

    // ---------- the decision, without the servlet API (UploadScanFilterTest)
    // ----------

    @FunctionalInterface
    interface Opener {
        InputStream open() throws IOException;
    }

    record Upload(String fileName, long size, Opener content) {
    }

    enum Kind {
        CLEAN, INFECTED, UNAVAILABLE
    }

    record Outcome(Kind kind, Upload upload, String signature, RuntimeException error) {
    }

    /** Scans every file; the first infected or unscannable one decides. */
    static Outcome check(UploadScanner scanner, List<Upload> uploads) {
        for (Upload u : uploads) {
            try (InputStream in = u.content().open()) {
                UploadScanner.Verdict verdict = scanner.scan(in);
                if (!verdict.clean()) {
                    return new Outcome(Kind.INFECTED, u, safe(verdict.signature()), null);
                }
            } catch (UploadScanner.UploadScanUnavailableException e) {
                return new Outcome(Kind.UNAVAILABLE, u, null, e);
            } catch (IOException e) {
                return new Outcome(Kind.UNAVAILABLE, u, null,
                        new UploadScanner.UploadScanUnavailableException("The upload could not be read.", e));
            }
        }
        return new Outcome(Kind.CLEAN, null, null, null);
    }

    /**
     * A signature name as clamd gives it ("Eicar-Test-Signature"); anything odd is
     * cut out.
     */
    static String safe(String signature) {
        String s = signature == null ? "unknown" : signature.replaceAll("[^A-Za-z0-9._:/-]", "");
        return s.isEmpty() ? "unknown" : (s.length() > 100 ? s.substring(0, 100) : s);
    }

    static String extension(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "";
    }

    private static void problem(HttpServletResponse response, int status, String type, String title, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"urn:credit-advisory:" + type + "\",\"title\":\"" + title
                + "\",\"status\":" + status + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
