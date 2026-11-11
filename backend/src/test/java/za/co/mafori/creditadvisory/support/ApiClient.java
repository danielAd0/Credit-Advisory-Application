package za.co.mafori.creditadvisory.support;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A tiny browser for tests: it keeps cookies and sends the X-XSRF-TOKEN header
 * exactly like the
 * frontend does, so tests go through the real session, CSRF and security
 * filters.
 */
public final class ApiClient {

    /**
     * Status code, body and headers of one response, with JsonPath helpers for
     * reading the body.
     */
    public record Response(int status, String body, HttpHeaders headers) {

        /** First value of a response header (names are case-insensitive), or null. */
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String text(String path) {
            return JsonPath.read(body, path);
        }

        public long number(String path) {
            return ((Number) JsonPath.read(body, path)).longValue();
        }

        public List<Long> numbers(String path) {
            List<Number> values = JsonPath.read(body, path);
            return values.stream().map(Number::longValue).toList();
        }

        public List<String> texts(String path) {
            return JsonPath.read(body, path);
        }

        public Object value(String path) {
            return JsonPath.read(body, path);
        }

        public int size(String path) {
            List<?> values = JsonPath.read(body, path);
            return values.size();
        }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private final String baseUrl;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private boolean sendCsrfHeader = true;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Fetches a CSRF cookie, then signs in. */
    public Response signIn(String email, String password) {
        get("/api/auth/csrf");
        return post("/api/auth/login", """
                {"email":"%s","password":"%s"}""".formatted(email, password));
    }

    /** Used to prove that a POST without the CSRF header is rejected. */
    public void setSendCsrfHeader(boolean sendCsrfHeader) {
        this.sendCsrfHeader = sendCsrfHeader;
    }

    public Response get(String path) {
        return send("GET", path, null);
    }

    public Response post(String path, String json) {
        return send("POST", path, json);
    }

    public Response patch(String path, String json) {
        return send("PATCH", path, json);
    }

    public Response send(String method, String path, String json) {
        return sendWithHeader(method, path, json, null, null);
    }

    /** Like send, plus one extra request header (e.g. Idempotency-Key). */
    public Response sendWithHeader(String method, String path, String json, String headerName, String headerValue) {
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        Map<String, String> extra = headerName == null ? Map.of() : Map.of(headerName, headerValue);
        return send(method, path, body, json == null ? null : "application/json", extra);
    }

    /**
     * POSTs one file as multipart/form-data (field "file"), e.g. an Excel upload.
     * Extra
     * form fields, such as weekStarting, go in the query string of {@code path}.
     */
    public Response upload(String path, String fileName, byte[] content) {
        String boundary = "----credit-advisory-test-" + System.nanoTime();
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return send("POST", path, HttpRequest.BodyPublishers.ofByteArray(body),
                "multipart/form-data; boundary=" + boundary, Map.of());
    }

    private Response send(String method, String path, HttpRequest.BodyPublisher body, String contentType,
            Map<String, String> extraHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).method(method, body);
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        extraHeaders.forEach(request::header);
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; ")));
        }
        String csrfToken = cookies.get("XSRF-TOKEN");
        if (sendCsrfHeader && csrfToken != null) {
            request.header("X-XSRF-TOKEN", csrfToken);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").forEach(this::remember);
            return new Response(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void remember(String setCookie) {
        String pair = setCookie.split(";", 2)[0];
        int equals = pair.indexOf('=');
        if (equals <= 0) {
            return;
        }
        String name = pair.substring(0, equals).trim();
        String value = pair.substring(equals + 1).trim();
        boolean deleted = value.isEmpty() || setCookie.toLowerCase(Locale.ROOT).contains("max-age=0");
        if (deleted) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }
}
