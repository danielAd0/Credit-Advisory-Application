package za.co.mafori.creditadvisory.common.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The clamd INSTREAM conversation, against a small fake clamd on a local port
 * (the real ClamAV is not needed for the tests; IT checks the real one with the
 * EICAR test file, docs/security-accounts-and-uploads.md).
 */
class ClamdScannerTest {

    /** Starts a fake clamd that answers {@code reply} once and records what it received. */
    private static int fakeClamd(String reply, AtomicReference<byte[]> received) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread t = new Thread(() -> {
            try (server; Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                byte[] command = new byte[10];
                in.readFully(command);
                assertThat(new String(command, StandardCharsets.US_ASCII)).isEqualTo("zINSTREAM\0");
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                int length;
                while ((length = in.readInt()) > 0) {
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    data.write(chunk);
                }
                received.set(data.toByteArray());
                s.getOutputStream().write((reply + "\0").getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
            } catch (IOException e) {
                received.set(null);
            }
        });
        t.setDaemon(true);
        t.start();
        return server.getLocalPort();
    }

    private static ClamdScanner scanner(int port) {
        return new ClamdScanner("127.0.0.1", port, Duration.ofSeconds(5));
    }

    @Test
    void aCleanFileIsStreamedWholeInChunks() throws IOException {
        AtomicReference<byte[]> received = new AtomicReference<>();
        byte[] file = new byte[ClamdScanner.CHUNK * 2 + 123];
        Arrays.fill(file, (byte) 'x');
        int port = fakeClamd("stream: OK", received);

        UploadScanner.Verdict verdict = scanner(port).scan(new ByteArrayInputStream(file));

        assertThat(verdict.clean()).isTrue();
        assertThat(received.get()).isEqualTo(file);
    }

    @Test
    void aVirusIsReportedWithItsSignature() throws IOException {
        int port = fakeClamd("stream: Eicar-Test-Signature FOUND", new AtomicReference<>());

        UploadScanner.Verdict verdict = scanner(port).scan(new ByteArrayInputStream(new byte[] { 1, 2, 3 }));

        assertThat(verdict.clean()).isFalse();
        assertThat(verdict.signature()).isEqualTo("Eicar-Test-Signature");
    }

    @Test
    void aScannerErrorMeansTheFileWasNotScanned() throws IOException {
        int port = fakeClamd("INSTREAM size limit exceeded. ERROR", new AtomicReference<>());

        assertThatThrownBy(() -> scanner(port).scan(new ByteArrayInputStream(new byte[] { 1 })))
                .isInstanceOf(UploadScanner.UploadScanUnavailableException.class)
                .hasMessageContaining("larger than the scanner accepts");
    }

    @Test
    void noScannerListeningMeansTheFileWasNotScanned() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }

        assertThatThrownBy(() -> scanner(closedPort).scan(new ByteArrayInputStream(new byte[] { 1 })))
                .isInstanceOf(UploadScanner.UploadScanUnavailableException.class);
    }

    @Test
    void repliesAreReadTheWayClamdWritesThem() {
        assertThat(ClamdScanner.verdict("stream: OK").clean()).isTrue();
        assertThat(ClamdScanner.verdict("OK\n").clean()).isTrue();
        assertThat(ClamdScanner.verdict("stream: Win.Test.EICAR_HDB-1 FOUND").signature())
                .isEqualTo("Win.Test.EICAR_HDB-1");
        assertThatThrownBy(() -> ClamdScanner.verdict(""))
                .isInstanceOf(UploadScanner.UploadScanUnavailableException.class);
        assertThatThrownBy(() -> ClamdScanner.verdict("stream: Can't allocate memory ERROR"))
                .isInstanceOf(UploadScanner.UploadScanUnavailableException.class);
    }
}
