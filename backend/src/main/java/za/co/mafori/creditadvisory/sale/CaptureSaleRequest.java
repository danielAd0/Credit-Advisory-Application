package za.co.mafori.creditadvisory.sale;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Body of POST /api/transactions: one sale, as one row of the weekly register.
 * Amounts are in rand with at most two decimals (1500.50). Every field after
 * lines may be null.
 *
 * @param consultantId MANAGER / ADMIN: who the sale belongs to (default: the
 *                     client's consultant). A CONSULTANT leaves it out: the
 *                     sale is always theirs.
 */
public record CaptureSaleRequest(
        @NotNull Long clientId,
        Long consultantId,
        @NotNull LocalDate transactionDate,
        @NotEmpty @Size(max = 30) List<@Valid @NotNull Line> lines,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal amountToClient,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal thirdPartySettlement,
        Long paymentTermId,
        Long employerId,
        Long structureId,
        @Size(max = 500) String primaryStructureBudgetList,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal primaryStructureLoanAmount,
        @Size(max = 500) String secondaryStructureBudgetList,
        @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal secondaryStructureLoanAmount) {

    /** One product on the sale. Each product at most once per sale. */
    public record Line(
            @NotNull Long productId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {
    }

    /** Blank budget lists mean none; spaces around them are trimmed. */
    public CaptureSaleRequest {
        primaryStructureBudgetList = trimToNull(primaryStructureBudgetList);
        secondaryStructureBudgetList = trimToNull(secondaryStructureBudgetList);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
