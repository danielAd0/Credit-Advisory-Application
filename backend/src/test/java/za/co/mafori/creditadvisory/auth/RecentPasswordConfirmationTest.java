package za.co.mafori.creditadvisory.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import za.co.mafori.creditadvisory.common.web.ApiException;

/** The 5-minute window after re-entering the password, with fixed clocks. */
class RecentPasswordConfirmationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-09T08:00:00Z");

    private final AuthProperties properties = new AuthProperties(5, Duration.ofMinutes(15), 20,
            Duration.ofMinutes(5), null, Duration.ofMinutes(5), 20);

    private RecentPasswordConfirmation at(Instant now) {
        return new RecentPasswordConfirmation(properties, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void refusedWhenThePasswordWasNeverConfirmed() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> at(CONFIRMED_AT).require(request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("password again");
    }

    @Test
    void allowedInsideTheWindowAndRefusedOnceItEnds() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Instant until = at(CONFIRMED_AT).remember(request);
        assertThat(until).isEqualTo(CONFIRMED_AT.plus(Duration.ofMinutes(5)));

        at(CONFIRMED_AT.plus(Duration.ofMinutes(4))).require(request);

        assertThatThrownBy(() -> at(CONFIRMED_AT.plus(Duration.ofMinutes(5))).require(request))
                .isInstanceOf(ApiException.class);
    }
}
