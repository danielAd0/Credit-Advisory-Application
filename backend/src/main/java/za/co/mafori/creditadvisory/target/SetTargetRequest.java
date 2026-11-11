package za.co.mafori.creditadvisory.target;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of POST /api/targets. effectiveFrom must be today or later (South
 * African time). The daily target applies to each day from that date; the
 * weekly target applies to each week that starts on or after it.
 */
public record SetTargetRequest(
        @NotNull LocalDate effectiveFrom,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal dailyTarget,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 10, fraction = 2) BigDecimal weeklyTarget) {
}
