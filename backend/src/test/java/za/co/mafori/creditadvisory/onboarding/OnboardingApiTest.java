package za.co.mafori.creditadvisory.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * /api/onboarding end to end: generate logins, send them through the fake
 * mailer, retry failures, and prove the emailed password really signs in.
 * All people and addresses are made up; no email leaves the test.
 */
class OnboardingApiTest extends ApiTestBase {

    /** The password cell of LoginDetailsEmail (the only monospace cell). */
    private static final Pattern PASSWORD_IN_EMAIL = Pattern.compile("monospace;[^>]*>([^<]+)</td>");

    @Autowired
    private LoginDetailsSender sender;

    private ApiClient admin;
    private long naledi;
    private long sipho;
    private long noEmail;

    @BeforeEach
    void createConsultants() {
        naledi = consultantWithEmail("C0101", "Naledi Mokoena", "naledi@test.local");
        sipho = consultantWithEmail("C0102", "Sipho Dlamini", "sipho@test.local");
        noEmail = createConsultant("C0103", "No Email Person");
        long hasLogin = consultantWithEmail("C0104", "Has Login", "has.login@test.local");
        createUser("has.login@test.local", hasLogin, RoleName.CONSULTANT);
        setConsultantStatus(consultantWithEmail("C0105", "Suspended Person", "suspended@test.local"),
                za.co.mafori.creditadvisory.consultant.ConsultantStatus.SUSPENDED);
        createUser("manager@test.local", null, RoleName.MANAGER);
        admin = signIn(ADMIN_EMAIL);
    }

    private long consultantWithEmail(String code, String name, String email) {
        long id = createConsultant(code, name);
        jdbc.update("update consultants set email = ? where id = ?", email, id);
        return id;
    }

    private ApiClient.Response generate() {
        return admin.post("/api/onboarding/logins", "{}");
    }

    private String status(long consultantId) {
        return jdbc.queryForObject("select status from consultant_login_invites where consultant_id = ?",
                String.class, consultantId);
    }

    private static String passwordIn(String html) {
        Matcher m = PASSWORD_IN_EMAIL.matcher(html);
        assertThat(m.find()).as("password cell in the email").isTrue();
        return m.group(1).replace("&amp;", "&");
    }

    @Test
    void generateSendAndSignInWithTheEmailedPassword() {
        ApiClient.Response summary = admin.get("/api/onboarding/summary");
        assertThat(summary.status()).as(summary.body()).isEqualTo(200);
        assertThat(summary.number("$.eligibleForLogin")).isEqualTo(2);
        assertThat(summary.number("$.needAttention")).as("the consultant without an email").isEqualTo(1);
        assertThat(summary.value("$.mailConfigured")).isEqualTo(true);

        assertThat(generate().status()).as("needs the admin's password again").isEqualTo(403);
        confirmPassword(admin);
        ApiClient.Response generated = generate();
        assertThat(generated.status()).as(generated.body()).isEqualTo(200);
        assertThat(generated.number("$.created")).isEqualTo(2);
        assertThat(generated.number("$.remaining")).isZero();

        assertThat(jdbc.queryForObject("""
                select count(*) from users u join user_roles ur on ur.user_id = u.id join roles r on r.id = ur.role_id
                where u.linked_consultant_id in (?, ?) and u.must_change_password and r.role_name = 'CONSULTANT'
                """, Long.class, naledi, sipho)).isEqualTo(2);
        assertThat(status(naledi)).isEqualTo("PENDING");
        assertThat(mailer.sent()).as("nothing is sent before the admin says so").isEmpty();
        assertThat(sender.sendDue()).as("PENDING is not sent by the job").isZero();

        ApiClient.Response queued = admin.post("/api/onboarding/invites/send", "{}");
        assertThat(queued.status()).as(queued.body()).isEqualTo(200);
        assertThat(queued.number("$.queued")).isEqualTo(2);
        assertThat(sender.sendDue()).isEqualTo(2);

        List<LoginDetailsMailer.Message> sent = mailer.sent();
        assertThat(sent).extracting(LoginDetailsMailer.Message::to)
                .containsExactlyInAnyOrder("naledi@test.local", "sipho@test.local");
        LoginDetailsMailer.Message toNaledi = sent.stream().filter(m -> m.to().equals("naledi@test.local"))
                .findFirst().orElseThrow();
        assertThat(toNaledi.html()).contains("naledi@test.local").contains("https://credit-advisory.test.local")
                .as("only her own details").doesNotContain("sipho@test.local");
        String nalediPassword = passwordIn(toNaledi.html());
        String siphoPassword = passwordIn(sent.stream().filter(m -> m.to().equals("sipho@test.local"))
                .findFirst().orElseThrow().html());
        assertThat(nalediPassword).isNotEqualTo(siphoPassword);

        ApiClient.Response login = browser().signIn("naledi@test.local", nalediPassword);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(login.value("$.mustChangePassword")).isEqualTo(true);

        assertThat(status(naledi)).isEqualTo("SENT");
        assertThat(
                jdbc.queryForObject("select count(*) from consultant_login_invites where password_sealed is not null",
                        Long.class))
                .as("sealed passwords deleted once sent").isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where password_hash = ?", Long.class,
                nalediPassword)).as("only the hash is stored").isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where position(? in metadata::text) > 0",
                Long.class, nalediPassword)).as("no password in the audit log").isZero();

        // Running it again changes nothing: existing logins (and their passwords) are
        // left alone.
        ApiClient.Response again = generate();
        assertThat(again.number("$.created")).isZero();
        assertThat(signIn("has.login@test.local").get("/api/auth/me").status()).isEqualTo(200);
    }

    @Test
    void failedEmailsAreRetriedWithTheSamePassword() {
        confirmPassword(admin);
        assertThat(generate().number("$.created")).isEqualTo(2);
        mailer.refuse("naledi@test.local"); // temporary
        mailer.refusePermanently("sipho@test.local"); // bad mailbox
        admin.post("/api/onboarding/invites/send", "{}");

        assertThat(sender.sendDue()).isZero();
        assertThat(status(sipho)).isEqualTo("FAILED");
        assertThat(status(naledi)).as("a temporary failure waits for the next try").isEqualTo("QUEUED");
        assertThat(jdbc.queryForObject(
                "select next_attempt_at > now() from consultant_login_invites where consultant_id = ?", Boolean.class,
                naledi)).isTrue();

        mailer.accept("naledi@test.local");
        jdbc.update("update consultant_login_invites set next_attempt_at = now() where consultant_id = ?", naledi);
        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(status(naledi)).isEqualTo("SENT");

        byte[] sealedBefore = jdbc.queryForObject(
                "select password_sealed from consultant_login_invites where consultant_id = ?", byte[].class, sipho);
        mailer.accept("sipho@test.local");
        ApiClient.Response failed = admin.get("/api/onboarding/invites?status=FAILED");
        assertThat(failed.number("$.totalItems")).isEqualTo(1);
        assertThat(failed.text("$.items[0].lastError")).contains("400");

        ApiClient.Response retried = admin.post("/api/onboarding/invites/retry", "{}");
        assertThat(retried.status()).as(retried.body()).isEqualTo(200);
        assertThat(retried.number("$.queued")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select password_sealed from consultant_login_invites where consultant_id = ?",
                byte[].class, sipho)).as("no new password").isEqualTo(sealedBefore);
        assertThat(sender.sendDue()).isEqualTo(1);

        String siphoPassword = passwordIn(mailer.sent().stream().filter(m -> m.to().equals("sipho@test.local"))
                .findFirst().orElseThrow().html());
        assertThat(browser().signIn("sipho@test.local", siphoPassword).status()).isEqualTo(200);
    }

    @Test
    void aPasswordResetMeanwhileIsNeverEmailed() {
        confirmPassword(admin);
        generate();
        long nalediUser = jdbc.queryForObject("select id from users where linked_consultant_id = ?", Long.class,
                naledi);
        assertThat(admin.post("/api/users/" + nalediUser + "/password-reset", """
                {"temporaryPassword":"Another-Temp-Pass-2026"}""").status()).isEqualTo(200);
        admin.post("/api/onboarding/invites/send", "{}");

        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(mailer.sent()).extracting(LoginDetailsMailer.Message::to).containsExactly("sipho@test.local");
        assertThat(status(naledi)).isEqualTo("FAILED");
    }

    private static final String EXPIRED_TYPE = "urn:credit-advisory:auth:temporary-password-expired";

    private String lastPasswordSentTo(String address) {
        List<LoginDetailsMailer.Message> to = mailer.sent().stream().filter(m -> m.to().equals(address)).toList();
        assertThat(to).as("emails to " + address).isNotEmpty();
        return passwordIn(to.get(to.size() - 1).html());
    }

    private void sendAll() {
        assertThat(admin.post("/api/onboarding/invites/send", "{}").status()).isEqualTo(200);
        sender.sendDue();
    }

    @Test
    void anEmailedPasswordExpiresAndIsRegenerated() {
        confirmPassword(admin);
        generate();
        sendAll();
        String first = lastPasswordSentTo("naledi@test.local");
        assertThat(jdbc.queryForObject("""
                select temporary_password_expires_at between now() + interval '6 days 23 hours'
                                                         and now() + interval '7 days 1 minute'
                from users where linked_consultant_id = ?""", Boolean.class, naledi))
                .as("7 days from sending").isTrue();
        assertThat(mailer.sent().get(0).html()).contains("works for 7 days");

        // Eight days later, never used.
        jdbc.update("update users set temporary_password_expires_at = now() - interval '1 day' "
                + "where linked_consultant_id = ?", naledi);
        ApiClient.Response refused = browser().signIn("naledi@test.local", first);
        assertThat(refused.status()).isEqualTo(401);
        assertThat(refused.text("$.type")).isEqualTo(EXPIRED_TYPE);
        assertThat(browser().signIn("naledi@test.local", "Not-The-Password-2026!").body())
                .as("a wrong password is told nothing about expiry").doesNotContain("temporary-password-expired");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action = 'TEMPORARY_PASSWORD_EXPIRED'",
                Long.class)).isEqualTo(1);

        ApiClient.Response summary = admin.get("/api/onboarding/summary");
        assertThat(summary.number("$.expiredLogins")).isEqualTo(1);
        assertThat(summary.number("$.canRegenerate")).isEqualTo(1);
        ApiClient.Response expired = admin.get("/api/onboarding/invites?status=EXPIRED");
        assertThat(expired.number("$.totalItems")).isEqualTo(1);
        assertThat(expired.value("$.items[0].expired")).isEqualTo(true);

        ApiClient.Response regenerated = admin.post("/api/onboarding/invites/regenerate", "{}");
        assertThat(regenerated.status()).as(regenerated.body()).isEqualTo(200);
        assertThat(regenerated.number("$.created")).isEqualTo(1);
        assertThat(status(naledi)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select temporary_password_expires_at is null from users "
                + "where linked_consultant_id = ?", Boolean.class, naledi)).isTrue();

        sendAll();
        String second = lastPasswordSentTo("naledi@test.local");
        assertThat(second).isNotEqualTo(first);
        assertThat(browser().signIn("naledi@test.local", first).status()).isEqualTo(401);
        ApiClient.Response login = browser().signIn("naledi@test.local", second);
        assertThat(login.status()).as(login.body()).isEqualTo(200);
        assertThat(login.value("$.mustChangePassword")).isEqualTo(true);
    }

    @Test
    void aPasswordAnAdminResetsNeverExpires() {
        confirmPassword(admin);
        generate();
        sendAll();
        long nalediUser = jdbc.queryForObject("select id from users where linked_consultant_id = ?", Long.class,
                naledi);
        jdbc.update("update users set temporary_password_expires_at = now() - interval '1 day' where id = ?",
                nalediUser);

        assertThat(admin.post("/api/users/" + nalediUser + "/password-reset", """
                {"temporaryPassword":"Handed-Over-In-Person-2026"}""").status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select temporary_password_expires_at is null from users where id = ?",
                Boolean.class, nalediUser)).isTrue();
        assertThat(browser().signIn("naledi@test.local", "Handed-Over-In-Person-2026").status()).isEqualTo(200);
        assertThat(admin.get("/api/onboarding/summary").number("$.expiredLogins")).isZero();
    }

    @Test
    void onlyExpiredOrFailedLoginsAreRegenerated() {
        confirmPassword(admin);
        generate();
        sendAll();
        ApiClient.Response nothing = admin.post("/api/onboarding/invites/regenerate", "{}");
        assertThat(nothing.number("$.created")).as("sent and still valid").isZero();
        assertThat(mailer.sent()).hasSize(2);
    }

    @Test
    void aConsultantSuspendedMeanwhileGetsNoEmail() {
        confirmPassword(admin);
        generate();
        setConsultantStatus(sipho, za.co.mafori.creditadvisory.consultant.ConsultantStatus.SUSPENDED);
        admin.post("/api/onboarding/invites/send", "{}");

        assertThat(sender.sendDue()).isEqualTo(1);
        assertThat(mailer.sent()).extracting(LoginDetailsMailer.Message::to).containsExactly("naledi@test.local");
        assertThat(status(sipho)).isEqualTo("FAILED");
    }

    @Test
    void throttlingStopsTheRoundAndKeepsTheRestQueued() {
        confirmPassword(admin);
        generate();
        admin.post("/api/onboarding/invites/send", "{}");
        mailer.setThrottle(true);

        assertThat(sender.sendDue()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from consultant_login_invites where status = 'QUEUED'",
                Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from consultant_login_invites where status = 'SENDING'",
                Long.class)).isZero();
    }

    @Test
    void onlyAnAdminAndOnlyWithEmailSetUp() {
        ApiClient manager = signIn("manager@test.local");
        assertThat(manager.get("/api/onboarding/summary").status()).isEqualTo(403);
        assertThat(manager.post("/api/onboarding/logins", "{}").status()).isEqualTo(403);
        assertThat(manager.post("/api/onboarding/invites/send", "{}").status()).isEqualTo(403);

        confirmPassword(admin);
        generate();
        mailer.setConfigured(false);
        ApiClient.Response send = admin.post("/api/onboarding/invites/send", "{}");
        assertThat(send.status()).isEqualTo(503);
        assertThat(send.text("$.type")).isEqualTo("urn:credit-advisory:mail:not-configured");
        assertThat(status(naledi)).isEqualTo("PENDING");
    }

    // ---------- launch checklist (review 12 Nov): one test per point ----------

    private static final String REAUTH_TYPE = "urn:credit-advisory:auth:reauthentication-required";

    /** Every onboarding endpoint, as {method, path, body}. */
    private List<String[]> everyEndpoint() {
        return List.of(
                new String[] { "GET", "/api/onboarding/summary", null },
                new String[] { "GET", "/api/onboarding/blocked", null },
                new String[] { "GET", "/api/onboarding/invites", null },
                new String[] { "POST", "/api/onboarding/logins", "{}" },
                new String[] { "POST", "/api/onboarding/invites/send", "{}" },
                new String[] { "POST", "/api/onboarding/invites/retry", "{}" },
                new String[] { "POST", "/api/onboarding/invites/regenerate", "{}" },
                new String[] { "PUT", "/api/onboarding/consultants/" + noEmail + "/email",
                        "{\"email\":\"someone@test.local\"}" });
    }

    @Test
    void onlyAnAdminCanUseAnyOfIt() {
        ApiClient manager = signIn("manager@test.local");
        ApiClient consultant = signIn("has.login@test.local");
        confirmPassword(manager);
        confirmPassword(consultant);
        for (String[] e : everyEndpoint()) {
            assertThat(manager.send(e[0], e[1], e[2]).status()).as("MANAGER " + e[0] + " " + e[1]).isEqualTo(403);
            assertThat(consultant.send(e[0], e[1], e[2]).status()).as("CONSULTANT " + e[0] + " " + e[1])
                    .isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("select count(*) from consultant_login_invites", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select email from consultants where id = ?", String.class, noEmail))
                .as("the email was not changed").isNull();
    }

    @Test
    void makingAndSendingPasswordsNeedTheAdminsPasswordAgain() {
        for (String path : List.of("/api/onboarding/logins", "/api/onboarding/invites/send",
                "/api/onboarding/invites/retry", "/api/onboarding/invites/regenerate")) {
            ApiClient.Response r = admin.post(path, "{}");
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.text("$.type")).as(path).isEqualTo(REAUTH_TYPE);
        }
        assertThat(jdbc.queryForObject("select count(*) from consultant_login_invites", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where linked_consultant_id in (?, ?)", Long.class,
                naledi, sipho)).as("no login was made").isZero();

        // Reading needs no password again.
        assertThat(admin.get("/api/onboarding/summary").status()).isEqualTo(200);
        assertThat(admin.get("/api/onboarding/invites").status()).isEqualTo(200);
        confirmPassword(admin);
        assertThat(generate().status()).isEqualTo(200);
    }

    @Test
    void aConsultantWithoutAUsableEmailIsSkipped() {
        // Another login (a manager) already uses this consultant's email.
        long clash = consultantWithEmail("C0106", "Email Clash", "clash@test.local");
        createUser("clash@test.local", null, RoleName.MANAGER);

        ApiClient.Response blocked = admin.get("/api/onboarding/blocked");
        assertThat(blocked.texts("$.items[*].reason"))
                .containsExactlyInAnyOrder("NO_EMAIL", "EMAIL_USED_BY_ANOTHER_LOGIN");
        assertThat(admin.get("/api/onboarding/summary").number("$.eligibleForLogin")).isEqualTo(2);

        confirmPassword(admin);
        ApiClient.Response all = generate();
        assertThat(all.number("$.created")).as("only Naledi and Sipho").isEqualTo(2);

        ApiClient.Response named = admin.post("/api/onboarding/logins",
                "{\"consultantIds\":[%d,%d]}".formatted(noEmail, clash));
        assertThat(named.status()).as(named.body()).isEqualTo(200);
        assertThat(named.number("$.created")).isZero();
        assertThat(named.texts("$.skipped[*].reason")).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from users where linked_consultant_id in (?, ?)", Long.class,
                noEmail, clash)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where email = 'clash@test.local'", Long.class))
                .as("the manager's login is untouched").isEqualTo(1);
    }

    @Test
    void sendQueuesThePendingEmailsAndASentOneKeepsNoPassword() {
        confirmPassword(admin);
        generate();
        assertThat(jdbc.queryForObject("""
                select count(*) from consultant_login_invites where status = 'PENDING' and password_sealed is not null
                """, Long.class)).isEqualTo(2);

        assertThat(admin.post("/api/onboarding/invites/send", "{}").number("$.queued")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from consultant_login_invites
                where status = 'QUEUED' and password_sealed is not null and queued_at is not null
                """, Long.class)).as("queued, password still sealed until sent").isEqualTo(2);
        assertThat(admin.post("/api/onboarding/invites/send", "{}").number("$.queued"))
                .as("nothing PENDING is left").isZero();

        assertThat(sender.sendDue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from consultant_login_invites
                where status = 'SENT' and password_sealed is null and sent_at is not null
                """, Long.class)).isEqualTo(2);
    }

    @Test
    void noPasswordEverShowsInTheAuditLogOrTheDeliveryReport() {
        confirmPassword(admin);
        generate();
        mailer.refusePermanently("sipho@test.local");
        sendAll(); // Naledi sent, Sipho failed
        mailer.accept("sipho@test.local");
        admin.post("/api/onboarding/invites/retry", "{}");
        sender.sendDue();
        List<String> passwords = List.of(lastPasswordSentTo("naledi@test.local"),
                lastPasswordSentTo("sipho@test.local"));

        StringBuilder everything = new StringBuilder();
        for (String path : List.of("/api/onboarding/summary", "/api/onboarding/blocked", "/api/onboarding/invites",
                "/api/onboarding/invites?status=SENT", "/api/onboarding/invites?status=FAILED",
                "/api/audit-logs?size=100")) {
            ApiClient.Response r = admin.get(path);
            assertThat(r.status()).as(path).isEqualTo(200);
            everything.append(r.body());
        }
        String audit = String.join("\n", jdbc.queryForList(
                "select coalesce(metadata::text, '') || ' ' || action from audit_logs", String.class));
        for (String p : passwords) {
            assertThat(everything.toString()).as("API responses").doesNotContain(p);
            assertThat(audit).as("audit log").doesNotContain(p);
            assertThat(jdbc.queryForObject("select count(*) from users where password_hash = ?", Long.class, p))
                    .as("stored only as a hash").isZero();
        }
    }

    @Test
    void anAdminCanFillInAMissingEmail() {
        ApiClient.Response blocked = admin.get("/api/onboarding/blocked");
        assertThat(blocked.texts("$.items[*].reason")).containsExactly("NO_EMAIL");

        ApiClient.Response set = admin.send("PUT", "/api/onboarding/consultants/" + noEmail + "/email", """
                {"email":"New.Person@Test.Local"}""");
        assertThat(set.status()).as(set.body()).isEqualTo(200);
        assertThat(set.text("$.email")).isEqualTo("new.person@test.local");
        assertThat(admin.get("/api/onboarding/summary").number("$.eligibleForLogin")).isEqualTo(3);

        assertThat(admin.send("PUT", "/api/onboarding/consultants/" + noEmail + "/email", """
                {"email":"naledi@test.local"}""").status()).as("another consultant's email").isEqualTo(409);
        long hasLogin = jdbc.queryForObject("select id from consultants where consultant_code = 'C0104'",
                Long.class);
        assertThat(admin.send("PUT", "/api/onboarding/consultants/" + hasLogin + "/email", """
                {"email":"changed@test.local"}""").status()).as("has a login").isEqualTo(409);
    }
}
