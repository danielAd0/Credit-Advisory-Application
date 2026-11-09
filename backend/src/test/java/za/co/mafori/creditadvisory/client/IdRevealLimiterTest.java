package za.co.mafori.creditadvisory.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;

/**
 * The hourly cap on full ID numbers, with a clock the test can move forward.
 */
class IdRevealLimiterTest {

    /** A clock that only moves when the test says so. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-09T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final TestClock clock = new TestClock();
    private final AuditLogWriter audit = mock(AuditLogWriter.class);
    private final IdRevealLimiter limiter = new IdRevealLimiter(3, audit, clock);

    @Test
    void allowsTheLimitThenRefusesUntilTheHourIsOver() {
        limiter.recordReveal(7);
        limiter.recordReveal(7);
        limiter.recordReveal(7);

        assertThatThrownBy(() -> limiter.recordReveal(7))
                .isInstanceOfSatisfying(IdRevealLimitException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(3600));

        clock.advance(Duration.ofMinutes(59));
        assertThatThrownBy(() -> limiter.recordReveal(7))
                .isInstanceOfSatisfying(IdRevealLimitException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(60));

        clock.advance(Duration.ofMinutes(1));
        limiter.recordReveal(7); // a new hour
    }

    @Test
    void eachUserHasTheirOwnAllowance() {
        limiter.recordReveal(7);
        limiter.recordReveal(7);
        limiter.recordReveal(7);

        limiter.recordReveal(8);
    }

    @Test
    void theLimitIsReportedOncePerHour() {
        for (int i = 0; i < 3; i++) {
            limiter.recordReveal(7);
        }
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> limiter.recordReveal(7)).isInstanceOf(IdRevealLimitException.class);
        }
        verify(audit, times(1)).record(eq("ID_REVEAL_LIMIT_REACHED"), eq("USER"), eq(7L), any());

        clock.advance(Duration.ofHours(1));
        for (int i = 0; i < 3; i++) {
            limiter.recordReveal(7);
        }
        assertThatThrownBy(() -> limiter.recordReveal(7)).isInstanceOf(IdRevealLimitException.class);
        verify(audit, times(2)).record(eq("ID_REVEAL_LIMIT_REACHED"), eq("USER"), eq(7L), any());
    }
}
