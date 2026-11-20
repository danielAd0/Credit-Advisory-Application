package za.co.mafori.creditadvisory.employer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.employer.EmployerDuplicates.Candidate;
import za.co.mafori.creditadvisory.employer.EmployerDuplicates.Group;

/**
 * Which employer names are suggested as the same employer (EmployerDuplicates).
 * Made-up and public employer names only.
 */
class EmployerDuplicatesTest {

    private long nextId = 1;

    private Candidate employer(String name) {
        return employer(name, true, true, 0);
    }

    private Candidate employer(String name, boolean active, boolean needsReview, long clients) {
        return new Candidate(nextId++, name, active, needsReview, clients, 0);
    }

    private static List<String> names(Group g) {
        return g.employers().stream().map(Candidate::name).toList();
    }

    @Test
    void caseSpacesPunctuationAndCompanyEndingsDoNotMatter() {
        assertThat(EmployerDuplicates.keys("Shoprite (Pty) Ltd."))
                .containsAnyElementsOf(EmployerDuplicates.keys("SHOPRITE"));
        assertThat(EmployerDuplicates.keys("SHOP RITE PTY LTD"))
                .containsAnyElementsOf(EmployerDuplicates.keys("Shoprite"));
        assertThat(EmployerDuplicates.keys("TRANSNET SOC LTD"))
                .containsAnyElementsOf(EmployerDuplicates.keys("Transnet"));
        assertThat(EmployerDuplicates.keys("The Test Company"))
                .containsAnyElementsOf(EmployerDuplicates.keys("TEST COMPANY"));
        assertThat(EmployerDuplicates.keys("A & B TRADING"))
                .containsAnyElementsOf(EmployerDuplicates.keys("A AND B TRADING"));
    }

    @Test
    void shortFormsAreWrittenOut() {
        assertThat(EmployerDuplicates.keys("PHUMELELA LOCAL MUNIC"))
                .containsAnyElementsOf(EmployerDuplicates.keys("Phumelela Local Municipality"));
        assertThat(EmployerDuplicates.keys("DEPT OF HEALTH"))
                .containsAnyElementsOf(EmployerDuplicates.keys("DEPARTMENT OF HEALTH"));
    }

    @Test
    void differentEmployersStayApart() {
        assertThat(EmployerDuplicates.keys("SHOPRITE CHECKERS"))
                .doesNotContainAnyElementsOf(EmployerDuplicates.keys("SHOPRITE"));
        assertThat(EmployerDuplicates.keys("TEST MUNICIPALITY NORTH"))
                .doesNotContainAnyElementsOf(EmployerDuplicates.keys("TEST MUNICIPALITY SOUTH"));
        assertThat(EmployerDuplicates.keys("PTY LTD")).as("an ending alone is still a name").isNotEmpty();
    }

    @Test
    void aGarbledSpellingLandsNextToTheAccentedAndThePlainOne() {
        // "AIRLOOM DECOR" with the accented E garbled by a wrong text encoding, as in
        // the real client list.
        Candidate garbled = employer("AIRLOOM D\u00C3\u0192\u00E2\u20AC\u0161\u00C3\u201A\u00B0COR PTY LTD");
        Candidate accented = employer("AIRLOOM D\u00C9COR");
        Candidate plain = employer("Airloom Decor (Pty) Ltd");

        List<Group> groups = EmployerDuplicates.groups(List.of(garbled, accented, plain));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).employers()).containsExactlyInAnyOrder(garbled, accented, plain);
    }

    @Test
    void theSuggestedTargetIsActiveCheckedAndHasTheMostClients() {
        Candidate inactive = employer("TEST RETAIL", false, false, 50);
        Candidate checked = employer("Test Retail (Pty) Ltd", true, false, 3);
        Candidate fromImport = employer("TEST RETAIL PTY LTD.", true, true, 40);
        Candidate alone = employer("SOMETHING ELSE");

        List<Group> groups = EmployerDuplicates.groups(List.of(inactive, checked, fromImport, alone));

        assertThat(groups).hasSize(1);
        Group g = groups.get(0);
        assertThat(g.suggestedTarget()).as("not the inactive one, and checked beats more clients")
                .isEqualTo(checked);
        assertThat(g.employers().get(0)).as("the target comes first").isEqualTo(checked);
        assertThat(names(g)).doesNotContain("SOMETHING ELSE");
    }

    @Test
    void withNoActiveEmployerThereIsNoSuggestedTarget() {
        List<Group> groups = EmployerDuplicates.groups(List.of(employer("OLD CO", false, false, 1),
                employer("Old Co (Pty) Ltd", false, true, 0)));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).suggestedTarget()).as("a merge needs an active target").isNull();
    }

    @Test
    void biggestGroupsComeFirst() {
        List<Candidate> all = new ArrayList<>();
        all.add(employer("BETA STORES"));
        all.add(employer("Beta Stores (Pty) Ltd"));
        all.add(employer("ALPHA MINING"));
        all.add(employer("Alpha Mining Ltd"));
        all.add(employer("ALPHA MINING PTY LTD"));
        all.add(employer("GAMMA"));

        List<Group> groups = EmployerDuplicates.groups(all);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).employers()).hasSize(3);
        assertThat(groups.get(1).employers()).hasSize(2);
    }
}
