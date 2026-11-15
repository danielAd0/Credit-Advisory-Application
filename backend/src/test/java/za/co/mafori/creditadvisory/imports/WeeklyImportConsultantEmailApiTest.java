package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * The weekly register with a CONSULTANT EMAIL column
 * (docs/consultant-onboarding.md, section 2): new consultants are registered
 * from their email, existing ones are matched by it, and a name that points at
 * a different consultant than its email blocks the import. Made-up people.
 */
class WeeklyImportConsultantEmailApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";
    private static final String PREVIEW = "/api/imports/weekly-register/preview";

    private ApiClient admin;
    private long thandi;
    private long sipho;

    /**
     * consultant name, consultant email (or null), and which made-up client the
     * row is for (-1: a new client per row).
     */
    private record Line(String consultant, String email, int person) {

        Line(String consultant, String email) {
            this(consultant, email, -1);
        }
    }

    @BeforeEach
    void createStaff() {
        thandi = createConsultant("C0001", "Thandi Xundu"); // no email yet
        sipho = createConsultant("C0002", "Sipho Dlamini");
        jdbc.update("update consultants set email = 'sipho@test.local' where id = ?", sipho);
        admin = signIn(ADMIN_EMAIL);
    }

    private static byte[] workbook(List<Line> lines) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet monday = wb.createSheet("Monday");
            String[] headers = { "NAME", "SURNAME", "ID NUMBER", "QUOTATION", "BANKED", "CONSULTANT",
                    "CONSULTANT EMAIL" };
            Row h = monday.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                h.createCell(c).setCellValue(headers[c]);
            }
            for (int i = 0; i < lines.size(); i++) {
                Row r = monday.createRow(i + 1);
                int person = lines.get(i).person() < 0 ? i : lines.get(i).person();
                r.createCell(0).setCellValue("Client" + (char) ('A' + person));
                r.createCell(1).setCellValue("Test");
                r.createCell(2).setCellValue(fakeIdNumber(500 + person));
                r.createCell(3).setCellValue(100);
                r.createCell(4).setCellValue(100);
                r.createCell(5).setCellValue(lines.get(i).consultant());
                if (lines.get(i).email() != null) {
                    r.createCell(6).setCellValue(lines.get(i).email());
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void emailsRegisterMatchAndAssign() {
        byte[] file = workbook(List.of(
                new Line("Naledi Mokoena", "Naledi@Test.Local"), // new: registered
                new Line("Naledi Mokoena", "naledi@test.local"),
                new Line("Thandi Xundu", "thandi@test.local"), // known name, no email yet: email added
                new Line("S. Dlamini", "sipho@test.local"))); // known email, new spelling: alias

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.texts("$.blockers")).containsExactly("CONSULTANTS_NOT_CONFIRMED");
        assertThat(preview.texts("$.consultants.items[*].outcome"))
                .containsExactly("NEW", "EMAIL_ADDED", "EXISTING");
        assertThat(preview.texts("$.consultants.items[?(@.outcome == 'EXISTING')].matchedBy"))
                .containsExactly("EMAIL");
        assertThat(preview.texts("$.consultantRegistration.toRegister[*].email"))
                .containsExactly("naledi@test.local");
        assertThat(preview.texts("$.consultantRegistration.emailsToAdd[*].fullName"))
                .containsExactly("Thandi Xundu");
        assertThat(preview.number("$.assignment.newClientRows")).isEqualTo(4);
        assertThat(preview.number("$.assignment.willBeAssigned")).isEqualTo(4);
        assertThat(count("select count(*) from consultants")).as("preview creates no consultant").isEqualTo(2);
        assertThat(confirmConsultants(admin, preview.number("$.id")).status()).isEqualTo(200);

        ApiClient.Response confirmed = admin.post("/api/imports/" + preview.number("$.id") + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.number("$.consultantsRegistered")).isEqualTo(1);
        assertThat(confirmed.number("$.consultantEmailsAdded")).isEqualTo(1);
        assertThat(confirmed.number("$.clientsAssigned")).isEqualTo(4);
        assertThat(confirmed.number("$.newClientsUnassigned")).isZero();

        long naledi = count("select id from consultants where email = 'naledi@test.local'");
        assertThat(count("select count(*) from clients where current_consultant_id = ?", naledi)).isEqualTo(2);
        assertThat(count("select count(*) from clients where current_consultant_id = ?", thandi)).isEqualTo(1);
        assertThat(count("select count(*) from clients where current_consultant_id = ?", sipho)).isEqualTo(1);
        assertThat(count("select count(*) from consultants where id = ? and email = 'thandi@test.local'", thandi))
                .isEqualTo(1);
        assertThat(count("select count(*) from consultant_aliases where consultant_id = ? and alias = 'S. Dlamini'",
                sipho)).isEqualTo(1);
        assertThat(count("select count(*) from users")).as("the import makes no logins").isEqualTo(1);

        // The new consultant is now eligible for a login on the onboarding page.
        assertThat(admin.get("/api/onboarding/summary").number("$.eligibleForLogin")).isEqualTo(3);
    }

    @Test
    void aNameAndEmailOfDifferentConsultantsBlockTheImport() {
        jdbc.update("update consultants set email = 'thandi@test.local' where id = ?", thandi);
        byte[] file = workbook(List.of(new Line("Thandi Xundu", "sipho@test.local")));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.texts("$.blockers")).containsExactly("CONSULTANT_CONFLICTS");
        assertThat(preview.texts("$.consultantRegistration.conflicts[*].nameInSheet"))
                .containsExactly("Thandi Xundu");
        assertThat(preview.number("$.assignment.waitingForConsultant")).isEqualTo(1);
        assertThat(preview.texts("$.consultants.items[*].outcome")).containsExactly("CONFLICT");
        ApiClient.Response listRefused = confirmConsultants(admin, preview.number("$.id"));
        assertThat(listRefused.status()).as("a list with a conflict cannot be confirmed").isEqualTo(422);

        ApiClient.Response confirmed = admin.post("/api/imports/" + preview.number("$.id") + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).isEqualTo(422);
        assertThat(confirmed.text("$.type")).isEqualTo("urn:credit-advisory:import:consultant-conflicts");
        assertThat(count("select count(*) from clients")).isZero();
    }

    @Test
    void aNewNameWithoutAnEmailIsRegisteredWithoutManualMatching() {
        byte[] file = workbook(List.of(new Line("Naledi  Mokoena", null), new Line("NALEDI MOKOENA", null),
                new Line("Thandi Xundu", null)));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        long id = preview.number("$.id");
        assertThat(preview.texts("$.consultants.items[*].outcome")).containsExactly("NEW", "EXISTING");
        assertThat(preview.texts("$.consultants.items[0].attention[*]")).containsExactly("NO_EMAIL");
        assertThat(preview.number("$.consultants.items[0].rows")).isEqualTo(2);
        assertThat(preview.number("$.consultants.items[0].newClients")).isEqualTo(2);
        assertThat(preview.text("$.consultants.items[1].matchedBy")).isEqualTo("NAME");
        assertThat(preview.number("$.assignment.willBeAssigned")).isEqualTo(3);

        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        ApiClient.Response confirmed = admin.post("/api/imports/" + id + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.number("$.consultantsRegistered")).isEqualTo(1);
        assertThat(confirmed.number("$.consultantsMatched")).isEqualTo(1);

        long naledi = count("select id from consultants where full_name = 'Naledi Mokoena' and email is null");
        assertThat(count("select count(*) from clients where current_consultant_id = ?", naledi)).isEqualTo(2);
        assertThat(count("select count(*) from client_transactions where consultant_id = ?", naledi)).isEqualTo(2);
        assertThat(count("select count(*) from audit_logs where action = 'CONSULTANT_CREATED'"
                + " and metadata ->> 'source' = 'WEEKLY_REGISTER_NAME'")).isEqualTo(1);
        assertThat(count("select count(*) from consultants")).as("no duplicate of Thandi").isEqualTo(3);
    }

    @Test
    void aNameSharedByTwoConsultantsIsNeverGuessed() {
        createConsultant("C0003", "Thandi Xundu"); // a second Thandi Xundu
        byte[] file = workbook(List.of(new Line("Thandi Xundu", null)));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.texts("$.blockers")).contains("CONSULTANT_CONFLICTS");
        assertThat(preview.text("$.consultants.items[0].outcome")).isEqualTo("CONFLICT");
        assertThat(preview.text("$.consultants.items[0].reason")).contains("C0001").contains("C0003");

        // With an email, the sheet says which one it is.
        jdbc.update("update consultants set email = 'thandi@test.local' where id = ?", thandi);
        ApiClient.Response withEmail = admin.upload(PREVIEW, FILE_NAME,
                workbook(List.of(new Line("Thandi Xundu", "thandi@test.local"))));
        assertThat(withEmail.status()).as(withEmail.body()).isEqualTo(201);
        assertThat(withEmail.texts("$.blockers")).containsExactly("CONSULTANTS_NOT_CONFIRMED");
        assertThat(withEmail.number("$.consultants.items[0].consultantId")).isEqualTo(thandi);
        long id = withEmail.number("$.id");
        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        assertThat(admin.post("/api/imports/" + id + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(withEmail.text("$.fileDigest"))).status()).isEqualTo(200);
        assertThat(count("select count(*) from client_transactions where consultant_id = ?", thandi)).isEqualTo(1);
    }

    @Test
    void theSameSaleTwiceIsFlaggedAndStillImported() {
        byte[] file = workbook(List.of(new Line("Thandi Xundu", null, 0), new Line("Thandi Xundu", null, 0),
                new Line("Thandi Xundu", null, 1)));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.number("$.possibleDuplicateRows")).isEqualTo(1);
        ApiClient.Response issues = admin.get("/api/imports/" + preview.number("$.id") + "/issues?severity=WARNING");
        assertThat(issues.texts("$.items[*].code")).contains("row:possible-duplicate-sale");

        long id = preview.number("$.id");
        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        ApiClient.Response confirmed = admin.post("/api/imports/" + id + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);
        assertThat(confirmed.number("$.transactions")).isEqualTo(3);
        assertThat(confirmed.number("$.clientsLinked")).isEqualTo(2);
        assertThat(confirmed.number("$.possibleDuplicateRows")).isEqualTo(1);
        assertThat(confirmed.number("$.rowsNeedingAttention")).isEqualTo(1);
    }

    @Test
    void historicalSalesKeepTheirConsultantAfterTheClientIsReassigned() {
        long id = admin.upload(PREVIEW, FILE_NAME, workbook(List.of(new Line("Thandi Xundu", null)))).number("$.id");
        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        assertThat(admin.post("/api/imports/" + id + "/confirm", "{\"fileDigest\":\"%s\"}"
                .formatted(admin.get("/api/imports/" + id).text("$.fileDigest"))).status()).isEqualTo(200);
        long client = count("select id from clients");

        createUser("manager@test.local", null, RoleName.MANAGER);
        ApiClient manager = signIn("manager@test.local");
        ApiClient.Response transfer = manager.post("/api/client-transfers", """
                {"fromConsultantId":%d,"toConsultantId":%d,"clientIds":[%d],"reason":"Handover"}"""
                .formatted(thandi, sipho, client));
        assertThat(transfer.status()).as(transfer.body()).isEqualTo(200);

        // The 2026 week still belongs to Thandi, by the sale's own date and consultant.
        ApiClient.Response report = manager.get("/api/reports/range/consultants?from=2026-09-14&to=2026-09-20");
        assertThat(report.status()).as(report.body()).isEqualTo(200);
        assertThat(report.texts("$.consultants[*].consultant.consultantCode")).containsExactly("C0001");
        assertThat(count("select consultant_id from client_transactions where client_id = ?", client))
                .isEqualTo(thandi);
        assertThat(count("select current_consultant_id from clients where id = ?", client)).isEqualTo(sipho);
    }

    @Test
    void aBadEmailIsAWarningAndTheNameStillMatches() {
        byte[] file = workbook(List.of(new Line("Thandi Xundu", "not-an-email")));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.texts("$.blockers")).containsExactly("CONSULTANTS_NOT_CONFIRMED");
        ApiClient.Response issues = admin.get("/api/imports/" + preview.number("$.id") + "/issues?severity=WARNING");
        assertThat(issues.texts("$.items[*].code")).contains("row:invalid-consultant-email");
    }
}
