package za.co.mafori.creditadvisory.productquotation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** /api/product-quotations: the list admins manage (the launch file's C_BRANCHNAME). */
class ProductQuotationApiTest extends ApiTestBase {

    private ApiClient admin;

    @BeforeEach
    void signInAdmin() {
        admin = signIn(ADMIN_EMAIL);
    }

    private ApiClient.Response create(String name) {
        return admin.post("/api/product-quotations", """
                {"name":"%s"}""".formatted(name));
    }

    private long auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where action = ?", Long.class, action);
    }

    @Test
    void startsEmpty() {
        ApiClient.Response all = admin.get("/api/product-quotations");

        assertThat(all.status()).isEqualTo(200);
        assertThat(all.number("$.totalItems")).isZero();
    }

    @Test
    void adminCreatesRenamesAndDeactivates() {
        ApiClient.Response created = create("  Test   Branch  North ");
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.text("$.name")).isEqualTo("Test Branch North");
        assertThat(created.value("$.active")).isEqualTo(true);
        long id = created.number("$.id");

        ApiClient.Response renamed = admin.patch("/api/product-quotations/" + id, """
                {"name":"Test Branch South","version":%d}""".formatted(created.number("$.version")));
        assertThat(renamed.status()).as(renamed.body()).isEqualTo(200);
        assertThat(renamed.text("$.name")).isEqualTo("Test Branch South");

        ApiClient.Response deactivated = admin.patch("/api/product-quotations/" + id + "/active", """
                {"active":false,"version":%d}""".formatted(renamed.number("$.version")));
        assertThat(deactivated.status()).as(deactivated.body()).isEqualTo(200);
        assertThat(deactivated.value("$.active")).isEqualTo(false);

        assertThat(auditCount("PRODUCT_QUOTATION_CREATED")).isEqualTo(1);
        assertThat(auditCount("PRODUCT_QUOTATION_UPDATED")).isEqualTo(1);
        assertThat(auditCount("PRODUCT_QUOTATION_DEACTIVATED")).isEqualTo(1);
    }

    @Test
    void renamingToTheSameNameIsNotAudited() {
        ApiClient.Response created = create("Test Branch");

        ApiClient.Response same = admin.patch("/api/product-quotations/" + created.number("$.id"), """
                {"name":" Test  Branch ","version":%d}""".formatted(created.number("$.version")));

        assertThat(same.status()).isEqualTo(200);
        assertThat(auditCount("PRODUCT_QUOTATION_UPDATED")).isZero();
    }

    @Test
    void duplicateNameIsRejectedWhateverTheCaseOrSpaces() {
        create("Test Branch");
        ApiClient.Response duplicate = create("  TEST   branch ");

        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.text("$.type")).isEqualTo("urn:credit-advisory:product-quotation:duplicate-name");

        ApiClient.Response other = create("Other Branch");
        ApiClient.Response renameOnto = admin.patch("/api/product-quotations/" + other.number("$.id"), """
                {"name":"test branch","version":%d}""".formatted(other.number("$.version")));
        assertThat(renameOnto.status()).isEqualTo(409);
    }

    @Test
    void blankNameIsRejected() {
        ApiClient.Response blank = create("   ");

        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.text("$.type")).isEqualTo("urn:credit-advisory:validation:invalid-request");
    }

    @Test
    void staleVersionIsRejected() {
        ApiClient.Response created = create("Test Branch");
        long id = created.number("$.id");
        long version = created.number("$.version");
        admin.patch("/api/product-quotations/" + id, """
                {"name":"Test Branch Two","version":%d}""".formatted(version));

        ApiClient.Response stale = admin.patch("/api/product-quotations/" + id, """
                {"name":"Test Branch Three","version":%d}""".formatted(version));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.text("$.type")).isEqualTo("urn:credit-advisory:conflict:stale-version");
    }

    @Test
    void searchAndActiveFilter() {
        create("Test Branch North");
        create("Test Branch South");
        ApiClient.Response east = create("Test Branch East");
        admin.patch("/api/product-quotations/" + east.number("$.id") + "/active", """
                {"active":false,"version":%d}""".formatted(east.number("$.version")));

        assertThat(admin.get("/api/product-quotations").texts("$.items[*].name"))
                .containsExactly("Test Branch East", "Test Branch North", "Test Branch South");
        assertThat(admin.get("/api/product-quotations?active=true").texts("$.items[*].name"))
                .containsExactly("Test Branch North", "Test Branch South");
        assertThat(admin.get("/api/product-quotations?search=sou").texts("$.items[*].name"))
                .containsExactly("Test Branch South");
    }

    @Test
    void unknownIdIs404() {
        ApiClient.Response response = admin.get("/api/product-quotations/999999");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.text("$.type")).isEqualTo("urn:credit-advisory:product-quotation:not-found");
    }

    @Test
    void consultantAndManagerCanReadButNotChange() {
        long consultantId = createConsultant("C001", "Thandi Test");
        createUser("thandi@test.local", consultantId, RoleName.CONSULTANT);
        createUser("manager@test.local", null, RoleName.MANAGER);
        long id = create("Test Branch").number("$.id");

        for (String email : new String[] { "thandi@test.local", "manager@test.local" }) {
            ApiClient other = signIn(email);
            assertThat(other.get("/api/product-quotations").status()).isEqualTo(200);
            assertThat(other.get("/api/product-quotations/" + id).status()).isEqualTo(200);
            assertThat(other.post("/api/product-quotations", """
                    {"name":"Not Allowed"}""").status()).isEqualTo(403);
            assertThat(other.patch("/api/product-quotations/" + id, """
                    {"name":"Not Allowed","version":0}""").status()).isEqualTo(403);
            assertThat(other.patch("/api/product-quotations/" + id + "/active", """
                    {"active":false,"version":0}""").status()).isEqualTo(403);
        }
    }
}
