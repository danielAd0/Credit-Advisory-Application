package za.co.mafori.creditadvisory.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * FR-21: sensitive changes leave an audit entry - with the right actor and
 * target,
 * and never a password, password hash or full ID number.
 */
class AuditTrailApiTest extends ApiTestBase {

        private static final String TEMP_PASSWORD = "Temp-Password-2026";
        private static final String RESET_PASSWORD = "Reset-Password-2026";

        // ---------- helpers that read audit_logs directly ----------

        private long count(String action) {
                return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
        }

        /** The newest entry for this action: actor_user_id, target_type, target_id. */
        private Map<String, Object> latest(String action) {
                return jdbc.queryForMap("""
                                select actor_user_id, target_type, target_id from audit_logs
                                where action = ? order by id desc limit 1
                                """, action);
        }

        /** One metadata value of the newest entry for this action, as text. */
        private String metadata(String action, String key) {
                return jdbc.queryForObject("""
                                select metadata ->> ? from audit_logs where action = ? order by id desc limit 1
                                """, String.class, key, action);
        }

        /** Every metadata object in the audit log, as JSON text. */
        private List<String> allMetadata() {
                return jdbc.queryForList("select metadata::text from audit_logs", String.class);
        }

        private static Number actor(Map<String, Object> row) {
                return (Number) row.get("actor_user_id");
        }

        // ---------- consultants ----------

        @Test
        void consultantCreateRenameAndStatusChangeAreAudited() {
                ApiClient admin = signIn(ADMIN_EMAIL);

                ApiClient.Response created = admin.post("/api/consultants", """
                                {"consultantCode":"C001","fullName":"Thandi Test"}""");
                assertThat(created.status()).as(created.body()).isEqualTo(201);
                long id = created.number("$.id");
                assertThat(count("CONSULTANT_CREATED")).isEqualTo(1);
                assertThat(actor(latest("CONSULTANT_CREATED")).longValue()).isEqualTo(adminId);
                assertThat(latest("CONSULTANT_CREATED").get("target_id")).isEqualTo(String.valueOf(id));

                ApiClient.Response renamed = admin.patch("/api/consultants/" + id, """
                                {"fullName":"Thandi Renamed","version":%d}""".formatted(created.number("$.version")));
                assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
                assertThat(metadata("CONSULTANT_RENAMED", "from")).isEqualTo("Thandi Test");
                assertThat(metadata("CONSULTANT_RENAMED", "to")).isEqualTo("Thandi Renamed");

                createClient("Test", "Alpha", id);
                createClient("Test", "Bravo", id);
                ApiClient.Response inactive = admin.patch("/api/consultants/" + id + "/status", """
                                {"status":"INACTIVE","reason":"Left the company","version":%d}"""
                                .formatted(renamed.number("$.version")));
                assertThat(inactive.status()).as(inactive.body()).isEqualTo(200);
                assertThat(metadata("CONSULTANT_STATUS_CHANGED", "from")).isEqualTo("ACTIVE");
                assertThat(metadata("CONSULTANT_STATUS_CHANGED", "to")).isEqualTo("INACTIVE");
                assertThat(metadata("CONSULTANT_STATUS_CHANGED", "reason")).isEqualTo("Left the company");
                assertThat(metadata("CONSULTANT_STATUS_CHANGED", "clientCount")).isEqualTo("2");
        }

        @Test
        void reasonsThatContainAnIdNumberAreRedacted() {
                ApiClient admin = signIn(ADMIN_EMAIL);
                long id = createConsultant("C001", "Thandi Test");
                long version = admin.get("/api/consultants/" + id).number("$.version");

                admin.patch("/api/consultants/" + id + "/status", """
                                {"status":"SUSPENDED","reason":"Complaint about client 1234567890123","version":%d}"""
                                .formatted(version));

                assertThat(metadata("CONSULTANT_STATUS_CHANGED", "reason"))
                                .isEqualTo("Complaint about client [redacted]");
        }

        // ---------- users ----------

        @Test
        void everyUserChangeIsAuditedAndNoPasswordIsEverStored() {
                ApiClient admin = signIn(ADMIN_EMAIL);
                long consultantId = createConsultant("C001", "Thandi Test");

                // An ADMIN, not a MANAGER: a manager login may not be linked to a consultant
                // record
                // (user:manager-cannot-be-consultant), and this test needs the consultant link
                // change.
                ApiClient.Response created = admin.post("/api/users", """
                                {"email":"second.admin@test.local","temporaryPassword":"%s","roles":["ADMIN"]}"""
                                .formatted(TEMP_PASSWORD));
                assertThat(created.status()).as(created.body()).isEqualTo(201);
                long userId = created.number("$.id");

                // Link first: the CONSULTANT role needs a consultant record already linked.
                long v = admin.patch("/api/users/" + userId + "/consultant",
                                """
                                                {"consultantId":%d,"version":%d}""".formatted(consultantId,
                                                created.number("$.version")))
                                .number("$.version");
                v = admin.patch("/api/users/" + userId + "/roles", """
                                {"roles":["ADMIN","CONSULTANT"],"version":%d}""".formatted(v)).number("$.version");
                v = admin.patch("/api/users/" + userId + "/enabled", """
                                {"enabled":false,"version":%d}""".formatted(v)).number("$.version");
                admin.patch("/api/users/" + userId + "/enabled", """
                                {"enabled":true,"version":%d}""".formatted(v));
                admin.post("/api/users/" + userId + "/password-reset", """
                                {"temporaryPassword":"%s"}""".formatted(RESET_PASSWORD));
                admin.post("/api/users/" + userId + "/unlock", null);

                for (String action : List.of("USER_CREATED", "USER_ROLES_CHANGED", "USER_CONSULTANT_LINK_CHANGED",
                                "USER_DISABLED", "USER_ENABLED", "USER_PASSWORD_RESET", "USER_UNLOCKED")) {
                        assertThat(count(action)).as(action).isEqualTo(1);
                        Map<String, Object> row = latest(action);
                        assertThat(actor(row).longValue()).as(action + " actor").isEqualTo(adminId);
                        assertThat(row.get("target_type")).as(action).isEqualTo("USER");
                        assertThat(row.get("target_id")).as(action).isEqualTo(String.valueOf(userId));
                }
                assertThat(metadata("USER_ROLES_CHANGED", "from")).isEqualTo("[\"ADMIN\"]");
                assertThat(metadata("USER_ROLES_CHANGED", "to")).isEqualTo("[\"CONSULTANT\", \"ADMIN\"]");
                assertThat(metadata("USER_CONSULTANT_LINK_CHANGED", "toConsultantId"))
                                .isEqualTo(String.valueOf(consultantId));

                // Neither password, nor any password hash, anywhere in the audit log.
                assertThat(allMetadata()).noneMatch(m -> m.contains(TEMP_PASSWORD) || m.contains(RESET_PASSWORD)
                                || m.contains("{bcrypt}") || m.contains("$2a$"));
        }

        @Test
        void changingYourOwnPasswordIsAuditedAgainstYou() {
                long userId = createUser("manager@test.local", null, RoleName.MANAGER);
                ApiClient manager = signIn("manager@test.local");

                ApiClient.Response changed = manager.post("/api/auth/change-password", """
                                {"currentPassword":"%s","newPassword":"Brand-New-Password-1"}""".formatted(PASSWORD));
                assertThat(changed.status()).as(changed.body()).isEqualTo(204);

                Map<String, Object> row = latest("PASSWORD_CHANGED");
                assertThat(actor(row).longValue()).isEqualTo(userId);
                assertThat(row.get("target_id")).isEqualTo(String.valueOf(userId));
                assertThat(allMetadata()).noneMatch(m -> m.contains(PASSWORD) || m.contains("Brand-New-Password-1"));
        }

        // ---------- sign-in security events ----------

        @Test
        void anAccountLockingItselfIsAuditedOnce() {
                long userId = createUser("locked@test.local", null, RoleName.MANAGER);

                for (int attempt = 1; attempt <= 7; attempt++) {
                        browser().signIn("locked@test.local", "Wrong-Password-000");
                }

                // 5 failures lock it; attempts 6 and 7 hit an already locked account.
                assertThat(count("ACCOUNT_LOCKED")).isEqualTo(1);
                Map<String, Object> row = latest("ACCOUNT_LOCKED");
                assertThat(row.get("actor_user_id")).as("nobody was signed in").isNull();
                assertThat(row.get("target_id")).isEqualTo(String.valueOf(userId));
                assertThat(metadata("ACCOUNT_LOCKED", "lockedUntil")).isNotBlank();
                assertThat(allMetadata())
                                .noneMatch(m -> m.contains("Wrong-Password-000") || m.contains("locked@test.local"));
        }

        @Test
        void anAddressHittingTheRateLimitIsAuditedOnce() {
                ApiClient attacker = browser();
                for (int i = 1; i <= 22; i++) {
                        attacker.signIn("user" + i + "@test.local", "Summer2026!");
                }

                assertThat(count("SIGN_IN_RATE_LIMITED")).isEqualTo(1);
                assertThat(metadata("SIGN_IN_RATE_LIMITED", "failures")).isEqualTo("20");
                assertThat(metadata("SIGN_IN_RATE_LIMITED", "address")).isNotBlank();
                assertThat(allMetadata()).noneMatch(m -> m.contains("Summer2026!") || m.contains("@test.local"));
        }

        // ---------- failed changes ----------

        @Test
        void aRefusedChangeLeavesNoAuditEntry() {
                ApiClient admin = signIn(ADMIN_EMAIL);
                long fromId = createConsultant("C001", "Thandi Test");
                long toId = createConsultant("C002", "Sipho Test");
                long client = createClient("Test", "Alpha", toId);

                // Stale version: refused, nothing recorded.
                ApiClient.Response stale = admin.patch("/api/consultants/" + fromId + "/status", """
                                {"status":"INACTIVE","reason":"Test","version":99}""");
                assertThat(stale.status()).isEqualTo(409);
                assertThat(count("CONSULTANT_STATUS_CHANGED")).isZero();

                // Client is not with the source consultant: the whole transfer is refused,
                // nothing recorded.
                ApiClient.Response transfer = admin.post("/api/client-transfers", """
                                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Test"}"""
                                .formatted(fromId, toId, client));
                assertThat(transfer.status()).isEqualTo(409);
                assertThat(count("CLIENTS_TRANSFERRED")).isZero();

                // Last admin cannot be disabled: refused, nothing recorded.
                long version = userVersion(admin, adminId);
                assertThat(admin.patch("/api/users/" + adminId + "/enabled", """
                                {"enabled":false,"version":%d}""".formatted(version)).status()).isEqualTo(409);
                assertThat(count("USER_DISABLED")).isZero();
        }
}
