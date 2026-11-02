package za.co.mafori.creditadvisory.common.idnumber;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import za.co.mafori.creditadvisory.support.ApiTestBase;

/**
 * The running application builds a working IdNumberProtector from its
 * configured keys.
 */
class IdNumberConfigTest extends ApiTestBase {

    @Autowired
    private IdNumberProtector protector;

    @Test
    void protectorIsConfiguredFromProperties() {
        SaIdNumber id = SaIdNumber.parse("9001014800089");

        ProtectedIdNumber stored = protector.protect(id);

        assertThat(stored.lookupHash()).hasSize(32);
        assertThat(protector.decrypt(stored.encrypted())).isEqualTo(id);
    }
}
