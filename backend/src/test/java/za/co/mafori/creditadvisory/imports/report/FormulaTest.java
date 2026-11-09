package za.co.mafori.creditadvisory.imports.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.report.Formula.Binary;
import za.co.mafori.creditadvisory.imports.report.Formula.Call;
import za.co.mafori.creditadvisory.imports.report.Formula.Err;
import za.co.mafori.creditadvisory.imports.report.Formula.Num;
import za.co.mafori.creditadvisory.imports.report.Formula.Ref;
import za.co.mafori.creditadvisory.imports.report.Formula.Text;
import za.co.mafori.creditadvisory.imports.report.Formula.Unary;

/** The formula shapes found in the real Report and Breakdown sheets. */
class FormulaTest {

    private static Formula.Node parse(String f) {
        return Formula.parse(f).orElseThrow(() -> new AssertionError("not parsed: " + f));
    }

    @Test
    void sumifWithSheetsAndWholeColumns() {
        Call c = (Call) parse("=SUMIF(Monday!AB:AB,Report!B14,Monday!P:P)");
        assertThat(c.name()).isEqualTo("SUMIF");
        assertThat(c.args()).containsExactly(
                new Ref("Monday", 27, -1, 27, -1, false),
                new Ref("Report", 1, 13, 1, 13, false),
                new Ref("Monday", 15, -1, 15, -1, false));
    }

    @Test
    void twoColumnCriteriaRangeIsKept() {
        Call c = (Call) parse("SUMIF(Thursday!Y:Z,Report!G14,Thursday!P:P)");
        Ref criteria = (Ref) c.args().get(0);
        assertThat(criteria.width()).isEqualTo(2);
        assertThat(criteria.wholeColumns()).isTrue();
    }

    @Test
    void countifWithTextAndNestedSums() {
        Call c = (Call) parse("COUNTIF(Friday!Z:Z,\"SOME NAME\")");
        assertThat(c.args().get(1)).isEqualTo(new Text("SOME NAME"));
        Call nested = (Call) parse(
                "SUM(SUMIF(Monday!Z:Z,Report!B165,Monday!AA:AA),SUMIF(Monday!AB:AB,Report!B165,Monday!AC:AC))");
        assertThat(nested.args()).allMatch(a -> a instanceof Call s && s.name().equals("SUMIF"));
    }

    @Test
    void arithmeticPercentAndComparison() {
        assertThat(parse("D156*15%")).isEqualTo(new Binary("*", new Ref(null, 3, 155, 3, 155, false),
                new Unary('%', new Num(new BigDecimal("15")))));
        Call target = (Call) parse("IF(E3>120000,\"Target Reached\",\"Target Not Reached\")");
        assertThat(target.args().get(0)).isInstanceOf(Binary.class);
        assertThat(((Binary) target.args().get(0)).op()).isEqualTo(">");
        assertThat(parse("C14+D14+E14+H14+I14+J14+C27")).isInstanceOf(Binary.class);
        assertThat(parse("$C$3:C9")).isEqualTo(new Ref(null, 2, 2, 2, 8, false));
    }

    @Test
    void refErrorsAreRecognised() {
        assertThat(Formula.containsRefError(parse("SUM(#REF!)"))).isTrue();
        Call c = (Call) parse("SUMIF(Wednsday!Z:Z,Breakdown!A101,Wednsday!#REF!)");
        assertThat(((Ref) c.args().get(2)).refError()).isTrue();
        assertThat(Formula.containsRefError(c)).isTrue();
        assertThat(parse("#REF!+1")).isEqualTo(new Binary("+", new Err("#REF!"), new Num(BigDecimal.ONE)));
        assertThat(Formula.containsRefError(parse("SUM(C3:C9)"))).isFalse();
    }

    @Test
    void unknownSyntaxIsNotGuessed() {
        assertThat(Formula.parse("SUM(C3:C9")).isEmpty();
        assertThat(Formula.parse("{1,2}")).isEmpty();
        assertThat(Formula.parse("")).isEmpty();
    }

    @Test
    void columnLettersRoundTrip() {
        for (String letters : List.of("A", "Z", "AA", "AB", "AZ", "BA")) {
            assertThat(Formula.columnLetter(Formula.column(letters))).isEqualTo(letters);
        }
        assertThat(Formula.cellRef(13, 2)).isEqualTo("C14");
    }
}
