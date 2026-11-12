package za.co.mafori.creditadvisory.imports.weekly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.ConsultantRef;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.SheetConsultant;

/** Every rule of ConsultantResolver, without a database. All names and emails are made up. */
class ConsultantResolverTest {

    private static final ConsultantRef THANDI = new ConsultantRef(1, "C0001", "Thandi Xundu", "ACTIVE");
    private static final ConsultantRef SIPHO = new ConsultantRef(2, "C0002", "Sipho Dlamini", "ACTIVE");

    private final Map<String, ConsultantRef> byName = new HashMap<>();
    private final Map<String, ConsultantRef> byEmail = new HashMap<>();
    private final Map<Long, String> emails = new HashMap<>();

    private void existing(ConsultantRef c, String email) {
        byName.put(WeeklyImportStore.key(c.fullName()), c);
        if (email != null) {
            byEmail.put(email, c);
            emails.put(c.id(), email);
        }
    }

    private ConsultantResolver.Plan plan(SheetConsultant... sheet) {
        return ConsultantResolver.plan(List.of(sheet), byName, byEmail, emails);
    }

    @Test
    void aNewEmailAndANewNameRegistersOneConsultant() {
        var p = plan(new SheetConsultant("Naledi Mokoena", "naledi@test.local", 4));

        assertThat(p.registrations()).singleElement().satisfies(r -> {
            assertThat(r.fullName()).isEqualTo("Naledi Mokoena");
            assertThat(r.email()).isEqualTo("naledi@test.local");
            assertThat(r.rows()).isEqualTo(4);
        });
        assertThat(p.conflicts()).isEmpty();
        assertThat(p.decidedByEmail("NALEDI  MOKOENA")).isTrue();
        assertThat(p.registers("naledi mokoena")).isTrue();
    }

    @Test
    void twoSpellingsWithOneEmailAreOnePersonNamedByTheMostRows() {
        var p = plan(new SheetConsultant("Naledi M", "naledi@test.local", 1),
                new SheetConsultant("Naledi Mokoena", "naledi@test.local", 5));

        assertThat(p.registrations()).singleElement().satisfies(r -> {
            assertThat(r.fullName()).isEqualTo("Naledi Mokoena");
            assertThat(r.otherNames()).containsExactly("Naledi M");
            assertThat(r.rows()).isEqualTo(6);
        });
    }

    @Test
    void aKnownEmailMatchesItsConsultantAndTheNewSpellingBecomesAnAlias() {
        existing(THANDI, "thandi@test.local");

        var p = plan(new SheetConsultant("T Xundu", "thandi@test.local", 3));

        assertThat(p.registrations()).isEmpty();
        assertThat(p.conflicts()).isEmpty();
        assertThat(p.aliases()).singleElement().satisfies(a -> {
            assertThat(a.consultant().id()).isEqualTo(1);
            assertThat(a.alias()).isEqualTo("T Xundu");
        });
        assertThat(p.namesMatchedByEmail()).isEqualTo(1);
    }

    @Test
    void aConsultantWithoutAnEmailGetsTheSheetsEmail() {
        existing(THANDI, null);

        var p = plan(new SheetConsultant("Thandi Xundu", "thandi@test.local", 2));

        assertThat(p.emailUpdates()).singleElement().satisfies(u -> {
            assertThat(u.consultant().id()).isEqualTo(1);
            assertThat(u.email()).isEqualTo("thandi@test.local");
        });
        assertThat(p.registrations()).isEmpty();
        assertThat(p.aliases()).isEmpty();
    }

    @Test
    void aNameOfOneConsultantWithTheEmailOfAnotherIsAConflict() {
        existing(THANDI, "thandi@test.local");
        existing(SIPHO, "sipho@test.local");

        var p = plan(new SheetConsultant("Thandi Xundu", "sipho@test.local", 2));

        assertThat(p.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.nameInSheet()).isEqualTo("Thandi Xundu");
            assertThat(c.reason()).contains("Sipho Dlamini").contains("Thandi Xundu");
        });
        assertThat(p.inConflict("thandi xundu")).isTrue();
        assertThat(p.aliases()).isEmpty();
        assertThat(p.registrations()).isEmpty();
    }

    @Test
    void aNameWhoseConsultantHasADifferentEmailIsAConflict() {
        existing(THANDI, "thandi@test.local");

        var p = plan(new SheetConsultant("Thandi Xundu", "thandi.new@test.local", 1));

        assertThat(p.conflicts()).singleElement().extracting(ConsultantResolver.Conflict::reason)
                .asString().contains("thandi@test.local");
        assertThat(p.emailUpdates()).isEmpty();
    }

    @Test
    void oneNameWithTwoEmailsIsAConflict() {
        var p = plan(new SheetConsultant("Naledi Mokoena", "a@test.local", 1),
                new SheetConsultant("Naledi Mokoena", "b@test.local", 1));

        assertThat(p.conflicts()).singleElement().satisfies(c -> {
            assertThat(c.email()).contains("a@test.local").contains("b@test.local");
            assertThat(c.rows()).isEqualTo(2);
        });
        assertThat(p.registrations()).isEmpty();
    }

    @Test
    void oneEmailForNamesOfTwoDifferentConsultantsIsAConflict() {
        existing(THANDI, null);
        existing(SIPHO, null);

        var p = plan(new SheetConsultant("Thandi Xundu", "shared@test.local", 1),
                new SheetConsultant("Sipho Dlamini", "shared@test.local", 1));

        assertThat(p.conflicts()).hasSize(2);
        assertThat(p.emailUpdates()).isEmpty();
    }

    @Test
    void namesWithoutAnEmailAreLeftToTheNameRules() {
        existing(THANDI, null);

        var p = plan(new SheetConsultant("Thandi Xundu", null, 3), new SheetConsultant("Somebody New", null, 1));

        assertThat(p.registrations()).isEmpty();
        assertThat(p.conflicts()).isEmpty();
        assertThat(p.decidedByEmail("Thandi Xundu")).isFalse();
        assertThat(p.decidedByEmail("Somebody New")).as("still unmapped: an admin decides").isFalse();
    }

    @Test
    void rowsWithAndWithoutTheEmailFollowTheEmail() {
        var p = plan(new SheetConsultant("Naledi Mokoena", null, 2),
                new SheetConsultant("Naledi Mokoena", "naledi@test.local", 3));

        assertThat(p.registrations()).singleElement().extracting(ConsultantResolver.Registration::fullName)
                .isEqualTo("Naledi Mokoena");
        assertThat(p.decidedByEmail("Naledi Mokoena")).isTrue();
    }
}
