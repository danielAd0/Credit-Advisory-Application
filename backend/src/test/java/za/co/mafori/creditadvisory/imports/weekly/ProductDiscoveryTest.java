package za.co.mafori.creditadvisory.imports.weekly;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.excel.RawCell;

class ProductDiscoveryTest {

    @Test
    void aHeaderBecomesAValidCodeAndAReadableName() {
        assertThat(ProductDiscovery.codeOf("MAFORI CARD")).isEqualTo("MAFORI_CARD");
        assertThat(ProductDiscovery.codeOf(" Cash Advance-Lite! ")).isEqualTo("CASH_ADVANCE_LITE");
        assertThat(ProductDiscovery.codeOf("3RD PARTY COVER")).isEqualTo("P_3RD_PARTY_COVER");
        assertThat(ProductDiscovery.codeOf("123")).as("no letters: stays an extra column").isNull();
        assertThat(ProductDiscovery.codeOf("X".repeat(60))).hasSize(40);

        assertThat(ProductDiscovery.nameOf("MAFORI CARD")).isEqualTo("Mafori Card");
        assertThat(ProductDiscovery.nameOf("IVC")).isEqualTo("IVC");
        assertThat(ProductDiscovery.nameOf("CREDIT REPAIR DEBIT ORDER")).isEqualTo("Credit Repair Debit Order");
    }

    @Test
    void aFormulasColumnsAreReadIncludingRanges() {
        RawCell banked = RawCell.number(BigDecimal.ONE, "F2+G2+$J$2+SUM(K2:N2)");
        assertThat(ProductDiscovery.referencedColumns(banked)).containsExactlyInAnyOrder(5, 6, 9, 10, 11, 12, 13);
        assertThat(ProductDiscovery.referencedColumns(RawCell.number(BigDecimal.ONE, null)))
                .as("a typed-in value says nothing").isNull();
        assertThat(ProductDiscovery.referencedColumns(RawCell.number(BigDecimal.ONE, "Report!F2+AB2")))
                .as("another sheet's cell is not this row's column").containsExactly(27);
    }
}
