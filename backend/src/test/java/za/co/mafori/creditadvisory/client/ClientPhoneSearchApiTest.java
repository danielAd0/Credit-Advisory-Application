package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.support.ApiClient;
import za.co.mafori.creditadvisory.support.ApiTestBase;
import za.co.mafori.creditadvisory.user.RoleName;

/**
 * GET /api/clients?search=: a whole phone number, typed any usual way, finds
 * the client by their cell, work or home phone. Made-up numbers only.
 */
class ClientPhoneSearchApiTest extends ApiTestBase {

    private ApiClient manager;
    private long thandiId;
    private long alpha;
    private long bravo;

    @BeforeEach
    void createData() {
        thandiId = createConsultant("C001", "Thandi Test");
        long siphoId = createConsultant("C002", "Sipho Test");
        createUser("manager@test.local", null, RoleName.MANAGER);
        createUser("thandi@test.local", thandiId, RoleName.CONSULTANT);
        manager = signIn("manager@test.local");

        alpha = createClient("Alpha", "Person", thandiId);
        bravo = createClient("Bravo", "Person", siphoId);
        jdbc.update("update clients set phone = '+27821234567' where id = ?", alpha);
        jdbc.update("update clients set work_phone = '+27115550000', home_phone = '+27215550000' where id = ?",
                bravo);
    }

    private ApiClient.Response search(ApiClient as, String text) {
        return as.get("/api/clients?search=" + URLEncoder.encode(text, StandardCharsets.UTF_8));
    }

    @Test
    void theCellphoneFindsTheClientHoweverItIsTyped() {
        for (String typed : new String[] { "082 123 4567", "0821234567", "27821234567", "+27 82 123 4567",
                "821234567" }) {
            ApiClient.Response r = search(manager, typed);
            assertThat(r.status()).as(r.body()).isEqualTo(200);
            assertThat(r.numbers("$.items[*].id")).as(typed).containsExactly(alpha);
        }
    }

    @Test
    void theWorkAndHomePhoneFindTheClientToo() {
        assertThat(search(manager, "011 555 0000").numbers("$.items[*].id")).containsExactly(bravo);
        assertThat(search(manager, "(021) 555-0000").numbers("$.items[*].id")).containsExactly(bravo);
    }

    @Test
    void onlyAWholeNumberIsAPhoneSearch() {
        assertThat(search(manager, "1234567").numbers("$.items[*].id")).as("part of a number").isEmpty();
        assertThat(search(manager, "083 999 0000").numbers("$.items[*].id")).as("nobody's number").isEmpty();
        assertThat(search(manager, "Alpha").numbers("$.items[*].id")).as("names still work")
                .containsExactly(alpha);
    }

    @Test
    void aConsultantOnlyFindsTheirOwnClients() {
        ApiClient thandi = signIn("thandi@test.local");

        assertThat(search(thandi, "0821234567").numbers("$.items[*].id")).containsExactly(alpha);
        assertThat(search(thandi, "011 555 0000").numbers("$.items[*].id"))
                .as("Sipho's client stays hidden").isEmpty();
    }
}
