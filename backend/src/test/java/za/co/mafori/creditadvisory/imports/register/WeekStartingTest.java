package za.co.mafori.creditadvisory.imports.register;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WeekStartingTest {

    private static final Optional<LocalDate> SEPT_14 = Optional.of(LocalDate.of(2026, 9, 14));

    @Test
    void readsTheWeekFromTheWorkbookName() {
        assertEquals(SEPT_14, WeekStarting.guessFromFileName("Credit Advisory Online Dashboard (14 - 18 Sept 2026).xlsx"));
        assertEquals(SEPT_14, WeekStarting.guessFromFileName("Dashboard 14-18 September 2026 (4).xlsx"));
        assertEquals(SEPT_14, WeekStarting.guessFromFileName("register 2026-09-16.xlsx"));
        assertEquals(SEPT_14, WeekStarting.guessFromFileName("Register 17 Sep 2026.xlsx"), "a mid-week date gives its Monday");
    }

    @Test
    void noDateInTheName() {
        assertTrue(WeekStarting.guessFromFileName("register.xlsx").isEmpty());
        assertTrue(WeekStarting.guessFromFileName("31 Feb 2026.xlsx").isEmpty());
        assertTrue(WeekStarting.guessFromFileName(null).isEmpty());
    }

    @Test
    void weekMustBeAMonday() {
        assertEquals(LocalDate.of(2026, 9, 14), WeekStarting.requireMonday(LocalDate.of(2026, 9, 14)));
        assertThrows(IllegalArgumentException.class, () -> WeekStarting.requireMonday(LocalDate.of(2026, 9, 15)));
    }
}
