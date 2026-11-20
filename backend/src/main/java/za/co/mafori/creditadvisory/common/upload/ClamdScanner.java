package za.co.mafori.creditadvisory.common.upload;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Talks to the ClamAV daemon (clamd) over TCP with its INSTREAM command: the
 * file is streamed in chunks and never written to disk. clamd normally runs on
 * the same server (127.0.0.1:3310), so no file leaves the machine.
 *
 * <p>
 * Protocol (clamd(8)): send {@code zINSTREAM\0}, then chunks of
 * {@code <4-byte big-endian length><bytes>}, then a zero length; the answer is
 * {@code stream: OK}, {@code stream: <signature> FOUND} or {@code ... ERROR},
 * ended by a null byte.
 */
public class ClamdScanner implements UploadScanner {

    static final int CHUNK = 64 * 1024;
    private static final int MAX_REPLY = 4096;

    private final String host;
    private final int port;
    private final int timeoutMillis;

    public ClamdScanner(String host, int port, Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    @Override
    public Verdict scan(InputStream content) {
        String reply;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream raw = socket.getOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            byte[] buffer = new byte[CHUNK];
            int n;
            while ((n = content.read(buffer)) > 0) {
                out.writeInt(n);
                out.write(buffer, 0, n);
            }
            out.writeInt(0);
            out.flush();
            reply = readReply(socket.getInputStream());
        } catch (IOException e) {
            // clamd closes the connection when the file is over its StreamMaxLength: also
            // "could not scan".
            throw new UploadScanUnavailableException("The virus scanner could not be reached or did not answer.", e);
        }
        return verdict(reply);
    }

    /**
     * "stream: OK" -> clean; "stream: X FOUND" -> infected with X; anything else ->
     * could not scan.
     */
    static Verdict verdict(String reply) {
        String r = reply.strip();
        if (r.startsWith("stream:")) {
            r = r.substring("stream:".length()).strip();
        }
        if (r.equals("OK")) {
            return Verdict.ok();
        }
        if (r.endsWith(" FOUND")) {
            String signature = r.substring(0, r.length() - " FOUND".length()).strip();
            return Verdict.infected(signature.isEmpty() ? "unknown" : signature);
        }
        throw new UploadScanUnavailableException("The virus scanner could not scan the file"
                + (r.contains("size limit") ? ": it is larger than the scanner accepts." : "."));
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0) {
            reply.write(b);
            if (reply.size() > MAX_REPLY) {
                break;
            }
        }
        return reply.toString(StandardCharsets.US_ASCII);
    }
}
