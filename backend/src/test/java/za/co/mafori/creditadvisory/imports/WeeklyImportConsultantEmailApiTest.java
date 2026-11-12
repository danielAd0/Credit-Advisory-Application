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

    /** consultant name, consultant email (or null). One client per row. */
    private record Line(String consultant, String email) {
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
                r.createCell(0).setCellValue("Client" + (char) ('A' + i));
                r.createCell(1).setCellValue("Test");
                r.createCell(2).setCellValue(fakeIdNumber(500 + i));
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
        assertThat(preview.texts("$.blockers")).isEmpty();
        assertThat(preview.size("$.unmappedConsultants")).isZero();
        assertThat(preview.texts("$.consultantRegistration.toRegister[*].email"))
                .containsExactly("naledi@test.local");
        assertThat(preview.texts("$.consultantRegistration.emailsToAdd[*].fullName"))
                .containsExactly("Thandi Xundu");
        assertThat(preview.number("$.assignment.newClientRows")).isEqualTo(4);
        assertThat(preview.number("$.assignment.willBeAssigned")).isEqualTo(4);
        assertThat(count("select count(*) from consultants")).as("preview creates no consultant").isEqualTo(2);

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

        ApiClient.Response confirmed = admin.post("/api/imports/" + preview.number("$.id") + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).isEqualTo(422);
        assertThat(confirmed.text("$.type")).isEqualTo("urn:credit-advisory:import:consultant-conflicts");
        assertThat(count("select count(*) from clients")).isZero();
    }

    @Test
    void aBadEmailIsAWarningAndTheNameStillMatches() {
        byte[] file = workbook(List.of(new Line("Thandi Xundu", "not-an-email")));

        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, file);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        assertThat(preview.texts("$.blockers")).isEmpty();
        ApiClient.Response issues = admin.get("/api/imports/" + preview.number("$.id") + "/issues?severity=WARNING");
        assertThat(issues.texts("$.items[*].code")).contains("row:invalid-consultant-email");
    }
}
