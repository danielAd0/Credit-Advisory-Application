package za.co.mafori.creditadvisory.common.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import jakarta.servlet.FilterChain;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;

/** What the upload filter lets through, refuses, and writes down. */
class UploadScanFilterTest {

    private static final byte[] EICAR_LIKE = "pretend this is a virus".getBytes(StandardCharsets.US_ASCII);

    /** A scanner that calls anything containing "virus" infected and anything containing "slow" unscannable. */
    private static final UploadScanner FAKE = in -> {
        try {
            String content = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
            if (content.contains("slow")) {
                throw new UploadScanner.UploadScanUnavailableException("timeout");
            }
            return content.contains("virus") ? UploadScanner.Verdict.infected("Eicar-Test-Signature")
                    : UploadScanner.Verdict.ok();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    };

    private static MockMultipartHttpServletRequest upload(byte[] content) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/api/imports/weekly-register/preview");
        request.setContentType("multipart/form-data; boundary=x");
        request.addPart(new MockPart("file", "Register (09 - 13 Nov 2026).xlsx", content));
        return request;
    }

    @Test
    void aCleanFileGoesThrough() throws Exception {
        AuditLogWriter audit = mock(AuditLogWriter.class);
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UploadScanFilter(FAKE, audit).doFilter(upload("PK fine".getBytes()), response, chain);

        assertThat(chain.getRequest()).as("the controller is reached").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(audit, never()).record(any(), any(), any(), anyMap());
    }

    @Test
    void anInfectedFileIsRefusedAndAudited() throws Exception {
        AuditLogWriter audit = mock(AuditLogWriter.class);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UploadScanFilter(FAKE, audit).doFilter(upload(EICAR_LIKE), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("urn:credit-advisory:upload:infected",
                "Eicar-Test-Signature");
        verify(audit).record(eq("UPLOAD_REJECTED"), isNull(), isNull(), anyMap());
    }

    @Test
    void aFileThatCannotBeScannedIsRefused() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new UploadScanFilter(FAKE, mock(AuditLogWriter.class)).doFilter(upload("slow".getBytes()), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("urn:credit-advisory:upload:scan-unavailable");
    }

    @Test
    void offAndNonUploadRequestsAreNotScanned() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new UploadScanFilter(null, mock(AuditLogWriter.class)).doFilter(upload(EICAR_LIKE),
                new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("mode off: through").isNotNull();

        MockHttpServletRequest json = new MockHttpServletRequest("POST", "/api/clients");
        json.setContentType("application/json");
        MockFilterChain chain2 = new MockFilterChain();
        new UploadScanFilter(FAKE, mock(AuditLogWriter.class)).doFilter(json, new MockHttpServletResponse(), chain2);
        assertThat(chain2.getRequest()).isNotNull();
    }

    @Test
    void theFirstBadFileDecidesWhenSeveralAreSent() {
        UploadScanFilter.Outcome outcome = UploadScanFilter.check(FAKE, List.of(
                new UploadScanFilter.Upload("a.xlsx", 3, () -> new ByteArrayInputStream("ok".getBytes())),
                new UploadScanFilter.Upload("b.xlsx", 5, () -> new ByteArrayInputStream(EICAR_LIKE))));
        assertThat(outcome.kind()).isEqualTo(UploadScanFilter.Kind.INFECTED);
        assertThat(outcome.upload().fileName()).isEqualTo("b.xlsx");
    }

    @Test
    void settingsAndSmallHelpers() {
        assertThat(UploadScanFilter.scannerFor("off", "h", 1, Duration.ofSeconds(1))).isNull();
        assertThat(UploadScanFilter.scannerFor("CLAMD", "h", 1, Duration.ofSeconds(1))).isInstanceOf(ClamdScanner.class);
        assertThatThrownBy(() -> UploadScanFilter.scannerFor("maybe", "h", 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(UploadScanFilter.safe("Eicar-Test-Signature\"<script>")).isEqualTo("Eicar-Test-Signaturescript");
        assertThat(UploadScanFilter.extension("Register (09 - 13 Nov 2026).XLSX")).isEqualTo("xlsx");
        assertThat(UploadScanFilter.extension("no-extension")).isEmpty();
    }
}
