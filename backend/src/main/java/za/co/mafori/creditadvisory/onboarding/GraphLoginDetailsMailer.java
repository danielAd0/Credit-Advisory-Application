package za.co.mafori.creditadvisory.onboarding;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Sends email through Microsoft 365 with the Microsoft Graph API
 * ({@code POST /users/{sender}/sendMail}), signed in as an Entra ID app
 * registration (OAuth client credentials).
 *
 * <p>
 * Why not SMTP with a username and password: Microsoft turns off "basic"
 * SMTP authentication for Microsoft 365 tenants by default from the end of
 * December 2026, and will remove it completely later. Graph needs no mail
 * library either: this is plain HTTPS from the JDK.
 *
 * <p>
 * IT sets these (docs/consultant-onboarding.md, "Mail set-up"):
 * <ul>
 * <li>{@code app.mail.graph.tenant-id}, {@code app.mail.graph.client-id},
 * {@code app.mail.graph.client-secret}: the app registration, with the
 * application permission Mail.Send (admin-consented), limited to the sender
 * mailbox with an application access policy.</li>
 * <li>{@code app.mail.sender}: the mailbox the emails come from, for example
 * no-reply@mafori.co.za.</li>
 * </ul>
 * Until all four are set, {@link #isConfigured()} is false and nothing is sent.
 */
@Component
public class GraphLoginDetailsMailer implements LoginDetailsMailer {

    private static final Logger log = LoggerFactory.getLogger(GraphLoginDetailsMailer.class);
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern EXPIRES_IN = Pattern.compile("\"expires_in\"\\s*:\\s*\"?(\\d+)");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String tenantId;
    private final String clientId;
    private final String clientSecret;
    private final String sender;
    private final String loginHost;
    private final String graphHost;
    private final HttpClient http;

    private String token;
    private Instant tokenExpires = Instant.EPOCH;

    public GraphLoginDetailsMailer(
            @Value("${app.mail.graph.tenant-id:}") String tenantId,
            @Value("${app.mail.graph.client-id:}") String clientId,
            @Value("${app.mail.graph.client-secret:}") String clientSecret,
            @Value("${app.mail.sender:}") String sender,
            @Value("${app.mail.graph.login-host:https://login.microsoftonline.com}") String loginHost,
            @Value("${app.mail.graph.api-host:https://graph.microsoft.com}") String graphHost) {
        this.tenantId = tenantId.strip();
        this.clientId = clientId.strip();
        this.clientSecret = clientSecret.strip();
        this.sender = sender.strip();
        this.loginHost = loginHost.strip();
        this.graphHost = graphHost.strip();
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public boolean isConfigured() {
        return !tenantId.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty() && !sender.isEmpty();
    }

    @Override
    public String senderAddress() {
        return sender.isEmpty() ? null : sender;
    }

    @Override
    public void send(Message message) {
        if (!isConfigured()) {
            throw new MailDeliveryException("Email is not set up yet (app.mail settings).", false, false);
        }
        String body = """
                {"message":{"subject":%s,"body":{"contentType":"HTML","content":%s},\
                "toRecipients":[{"emailAddress":{"address":%s}}]},"saveToSentItems":false}"""
                .formatted(json(message.subject()), json(message.html()), json(message.to()));
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(graphHost + "/v1.0/users/" + URLEncoder.encode(sender, StandardCharsets.UTF_8)
                        + "/sendMail"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = call(request);
        int status = response.statusCode();
        if (status == 202 || status == 200) {
            return;
        }
        if (status == 401) {
            clearToken(); // maybe expired early: the next attempt signs in again
        }
        // Graph's error text can echo the request; keep only the status and code.
        String code = errorCode(response.body());
        log.warn("Microsoft Graph refused a login-details email: HTTP {} {}", status, code);
        boolean throttled = status == 429 || status == 503;
        // 400 = this message is wrong (bad address); retrying will not help.
        boolean permanent = status == 400;
        throw new MailDeliveryException("Microsoft 365 refused the email (HTTP " + status
                + (code.isEmpty() ? "" : ", " + code) + ").", permanent, throttled);
    }

    private synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpires)) {
            return token;
        }
        String form = "client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + "&scope=" + enc("https://graph.microsoft.com/.default") + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(
                        loginHost + "/" + URLEncoder.encode(tenantId, StandardCharsets.UTF_8) + "/oauth2/v2.0/token"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = call(request);
        Matcher t = ACCESS_TOKEN.matcher(response.body() == null ? "" : response.body());
        if (response.statusCode() != 200 || !t.find()) {
            log.warn("Microsoft 365 sign-in for sending email failed: HTTP {} {}", response.statusCode(),
                    errorCode(response.body()));
            // Wrong tenant, client id or secret: every email would fail the same way.
            throw new MailDeliveryException("Could not sign in to Microsoft 365 to send email (HTTP "
                    + response.statusCode() + "). Check the app.mail.graph settings.", false, true);
        }
        Matcher e = EXPIRES_IN.matcher(response.body());
        long seconds = e.find() ? Long.parseLong(e.group(1)) : 3600;
        token = t.group(1);
        tokenExpires = Instant.now().plusSeconds(Math.max(60, seconds - 300));
        return token;
    }

    private synchronized void clearToken() {
        token = null;
        tokenExpires = Instant.EPOCH;
    }

    private HttpResponse<String> call(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MailDeliveryException("Could not reach Microsoft 365: " + e.getClass().getSimpleName() + ".",
                    false, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailDeliveryException("Sending was interrupted.", false, true);
        }
    }

    /**
     * {"error":{"code":"ErrorInvalidRecipients",...}} -> ErrorInvalidRecipients; ""
     * if none.
     */
    static String errorCode(String body) {
        if (body == null) {
            return "";
        }
        Matcher m = Pattern.compile("\"(?:code|error)\"\\s*:\\s*\"([A-Za-z0-9_.-]{1,80})\"").matcher(body);
        return m.find() ? m.group(1) : "";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** A JSON string literal. */
    static String json(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
