package za.co.mafori.creditadvisory.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/** /api/products - read by everyone, maintained by ADMIN. */
class ProductApiTest extends ApiTestBase {

    private ApiClient admin;
    private ApiClient consultant;

    @BeforeEach
    void signInUsers() {
        admin = signIn(ADMIN_EMAIL);
        long consultantId = createConsultant("C001", "Thandi Test");
        createUser("consultant@test.local", consultantId, RoleName.CONSULTANT);
        consultant = signIn("consultant@test.local");
    }

    private ApiClient.Response createFuneralCover() {
        return admin.post("/api/products", """
                {"code":"funeral cover","name":"Funeral Cover","standardPrice":99.50,
                 "countsTowardQuotation":true,"countsTowardBanked":false,"displayOrder":160}""");
    }

    @Test
    void everyoneSeesTheSeededCatalogue() {
        ApiClient.Response response = consultant.get("/api/products?size=100");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.number("$.totalItems")).isEqualTo(15);
        assertThat(response.texts("$.items[*].code")).contains("COMBO", "MAFORI_CARD", "POLICY_REPLACEMENT");
    }

    @Test
    void adminCreatesProductWithNormalisedCode() {
        ApiClient.Response response = createFuneralCover();

        assertThat(response.status()).as(response.body()).isEqualTo(201);
        assertThat(response.text("$.code")).isEqualTo("FUNERAL_COVER");
        assertThat(response.value("$.active")).isEqualTo(true);
        assertThat(response.value("$.countsTowardBanked")).isEqualTo(false);

        assertThat(createFuneralCover().status()).isEqualTo(409);
    }

    @Test
    void updateNeedsCurrentVersion() {
        ApiClient.Response created = createFuneralCover();
        long id = created.number("$.id");
        long version = created.number("$.version");
        String body = """
                {"name":"Funeral Cover Plus","standardPrice":120.00,"countsTowardQuotation":true,
                 "countsTowardBanked":true,"displayOrder":160,"version":%d}""";

        ApiClient.Response updated = admin.patch("/api/products/" + id, body.formatted(version));
        assertThat(updated.status()).as(updated.body()).isEqualTo(200);
        assertThat(updated.text("$.name")).isEqualTo("Funeral Cover Plus");
        assertThat(updated.text("$.code")).isEqualTo("FUNERAL_COVER");

        assertThat(admin.patch("/api/products/" + id, body.formatted(version)).status()).isEqualTo(409);
    }

    @Test
    void deactivatedProductsCanBeFilteredOut() {
        ApiClient.Response created = createFuneralCover();
        long id = created.number("$.id");

        ApiClient.Response response = admin.patch("/api/products/" + id + "/active",
                """
                        {"active":false,"version":%d}""".formatted(created.number("$.version")));

        assertThat(response.status()).isEqualTo(200);
        assertThat(consultant.get("/api/products?active=true&size=100").texts("$.items[*].code"))
                .doesNotContain("FUNERAL_COVER");
        assertThat(consultant.get("/api/products?active=false").texts("$.items[*].code"))
                .containsExactly("FUNERAL_COVER");
    }

    @Test
    void consultantCannotChangeTheCatalogue() {
        ApiClient.Response response = consultant.post("/api/products", """
                {"code":"HACK","name":"Hack","countsTowardQuotation":true,"countsTowardBanked":true}""");

        assertThat(response.status()).isEqualTo(403);
    }
}
