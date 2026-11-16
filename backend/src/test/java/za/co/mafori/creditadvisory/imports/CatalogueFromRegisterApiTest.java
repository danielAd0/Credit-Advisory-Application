package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.support.FakeWeeklyRegisterWorkbook;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * V27: a new install has no products and no employers. The first import takes
 * both from the sheet; the admin adds the rest by hand.
 */
class CatalogueFromRegisterApiTest extends ApiTestBase {

    private static final String FILE_NAME = "Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx";
    private static final String PREVIEW = "/api/imports/weekly-register/preview";
    private static final byte[] WORKBOOK = FakeWeeklyRegisterWorkbook.build();

    private final FakeWeeklyRegisterWorkbook.Expected expected = FakeWeeklyRegisterWorkbook.expected();
    private ApiClient admin;

    @Override
    protected boolean standardCatalogue() {
        return false; // as on a new machine
    }

    @BeforeEach
    void createStaff() {
        createConsultant("C001", "Test Consultant One");
        createConsultant("C002", "TEST CONSULTANT TWO");
        createConsultant("C003", "TEST CONSULTANT THREE");
        createConsultant("C004", "TEST CONSULTANT FOUR");
        createConsultant("C005", "TESTLABEL");
        admin = signIn(ADMIN_EMAIL);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private boolean flag(String column, String code) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("select " + column + " from products where code = ?", Boolean.class, code));
    }

    @Test
    void aNewInstallHasNoProductsAndNoEmployers() {
        assertThat(count("select count(*) from products")).isZero();
        assertThat(count("select count(*) from employers")).isZero();
        ApiClient.Response products = admin.get("/api/products");
        assertThat(products.status()).as(products.body()).isEqualTo(200);
        assertThat(products.number("$.totalItems")).isZero();
    }

    @Test
    void aDatabaseAlreadyInUseKeepsItsProductsAndEmployers() {
        flyway.clean();
        Flyway.configure().configuration(flyway.getConfiguration()).target("26").load().migrate();
        createClient("Existing", "Client", null);
        flyway.migrate();

        assertThat(count("select count(*) from products")).isEqualTo(15);
        assertThat(count("select count(*) from employers")).isEqualTo(63);
    }

    @Test
    void theFirstImportTakesTheProductsAndEmployersFromTheSheet() {
        ApiClient.Response preview = admin.upload(PREVIEW, FILE_NAME, WORKBOOK);
        assertThat(preview.status()).as(preview.body()).isEqualTo(201);
        long id = preview.number("$.id");

        // Every column between QUOTATION and AMOUNT TO CLIENT is a product; the
        // known misspellings become the right product.
        List<String> codes = jdbc.queryForList("select code from products order by display_order", String.class);
        assertThat(codes).containsExactly("COMBO", "IVC", "INSURANCE", "MAFORI_CARD", "MAFORI_VOUCHER",
                "CREDIT_RESCUE", "CREDIT_REPAIR", "CREDIT_REPAIR_DEBIT_ORDER", "SPEED_SERVICE", "CASH_ADVANCE_LITE",
                "GROCERY", "HOUSING_ADMIN", "MAFORI_WELLNESS", "VOLUNTARY_DISTRIBUTION", "POLICY_REPLACEMENT");
        assertThat(jdbc.queryForObject("select name from products where code = 'MAFORI_CARD'", String.class))
                .isEqualTo("Mafori Card");
        assertThat(jdbc.queryForObject("select name from products where code = 'IVC'", String.class))
                .isEqualTo("IVC");
        assertThat(jdbc.queryForObject("select name from products where code = 'GROCERY'", String.class))
                .as("named after the product, not the misspelt header").isEqualTo("Grocery");

        // The flags follow the sheet's own formulas: Credit Repair is never in BANKED.
        assertThat(flag("counts_toward_quotation", "CREDIT_REPAIR")).isTrue();
        assertThat(flag("counts_toward_banked", "CREDIT_REPAIR")).isFalse();
        assertThat(flag("counts_toward_banked", "CREDIT_REPAIR_DEBIT_ORDER")).isFalse();
        assertThat(flag("counts_toward_quotation", "COMBO")).isTrue();
        assertThat(flag("counts_toward_banked", "COMBO")).isTrue();
        assertThat(flag("counts_toward_banked", "MAFORI_CARD")).isTrue();
        assertThat(count("select count(*) from audit_logs where action = 'PRODUCT_CREATED'")).isEqualTo(15);

        ApiClient.Response issues = admin.get("/api/imports/" + id + "/issues?size=200");
        assertThat(issues.texts("$.items[*].code")).contains("lookup:new-product", "lookup:new-employer")
                .doesNotContain("sheet:inactive-product");
        assertThat(issues.texts("$.items[?(@.code == 'sheet:unknown-column')].field"))
                .as("only the header-less column is unknown; every product column was recognised")
                .allMatch(f -> f == null || f.equals("(no header)"));
        assertThat(count("select count(*) from import_row_lines l join import_rows r on r.id = l.import_row_id"
                + " where r.import_job_id = ?", id)).as("the product amounts are staged").isPositive();
        assertThat(count("select count(*) from employers")).as("employers wait for confirm").isZero();

        assertThat(confirmConsultants(admin, id).status()).isEqualTo(200);
        ApiClient.Response confirmed = admin.post("/api/imports/" + id + "/confirm",
                "{\"fileDigest\":\"%s\"}".formatted(preview.text("$.fileDigest")));
        assertThat(confirmed.status()).as(confirmed.body()).isEqualTo(200);

        assertThat(count("select count(*) from employers")).as("the sheet's employers").isPositive();
        assertThat(count("select count(*) from client_transactions"
                + " where source_employer_name is not null and employer_id is null"))
                .as("every row with an employer has it").isZero();
        assertThat(count("select count(*) from client_transactions where employer_id is not null")).isPositive();
        assertThat(count("select count(*) from client_transaction_lines")).isPositive();
        assertThat(count("select banked_total::bigint from v_week_daily_summary where day_of_week = 1"))
                .isEqualTo(expected.sheetBanked().get("Monday"));
    }

    @Test
    void aSecondImportAddsNothingAndTheAdminAddsWhatTheSheetLacks() {
        ApiClient.Response first = admin.upload(PREVIEW, FILE_NAME, WORKBOOK);
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        assertThat(admin.post("/api/imports/" + first.number("$.id") + "/cancel", "{}").status()).isEqualTo(200);
        assertThat(count("select count(*) from products")).as("kept: the preview's figures use them").isEqualTo(15);

        ApiClient.Response second = admin.upload(PREVIEW, FILE_NAME, WORKBOOK);
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        assertThat(count("select count(*) from products")).as("nothing added twice").isEqualTo(15);
        assertThat(admin.get("/api/imports/" + second.number("$.id") + "/issues?size=200").texts("$.items[*].code"))
                .doesNotContain("lookup:new-product");

        ApiClient.Response added = admin.post("/api/products", """
                {"code":"FUNERAL_COVER","name":"Funeral Cover","countsTowardQuotation":true,"countsTowardBanked":true}
                """);
        assertThat(added.status()).as(added.body()).isEqualTo(201);
        ApiClient.Response employer = admin.post("/api/employers", """
                {"name":"NEW EMPLOYER NOT IN THE SHEET","company":"Mafori","employerType":"Payroll"}
                """);
        assertThat(employer.status()).as(employer.body()).isEqualTo(201);
    }
}
