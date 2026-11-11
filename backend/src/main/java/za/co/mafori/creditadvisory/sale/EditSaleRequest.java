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
import za.co.mafori.creditadvisory.sale.CaptureSaleRequest.Line;

/**
 * Body of PATCH /api/transactions/{id}: the whole sale as it should be now,
 * with the same rules as POST, plus the version the page loaded. The client
 * cannot change; a sale for the wrong client is deleted and captured again.
 *
 * @param version      from GET /api/transactions/{id}; someone else saving
 *                     first gives 409 conflict:stale-version
 * @param consultantId MANAGER / ADMIN: who the sale belongs to; null keeps it.
 *                     A CONSULTANT leaves it out.
 */
public record EditSaleRequest(
        @NotNull Long version,
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

    /** Blank budget lists mean none; spaces around them are trimmed. */
    public EditSaleRequest {
        primaryStructureBudgetList = trimToNull(primaryStructureBudgetList);
        secondaryStructureBudgetList = trimToNull(secondaryStructureBudgetList);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
