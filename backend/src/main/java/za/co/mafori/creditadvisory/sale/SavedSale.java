package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A sale as saved: the answer to POST /api/transactions, and each entry of
 * GET /api/transactions.
 *
 * @param quotationTotal the lines that count toward the quotation
 * @param bankedTotal    the lines that count toward banked
 * @param replaced       true once that week's spreadsheet has been imported:
 *                       the imported rows count instead of this sale
 * @param typedIn        true for a sale typed into the app, false for one
 *                       from a weekly register import
 */
public record SavedSale(
        long id,
        long clientId,
        Long consultantId,
        LocalDate transactionDate,
        LocalDate weekStarting,
        Integer dayOfWeek,
        List<Line> lines,
        BigDecimal quotationTotal,
        BigDecimal bankedTotal,
        boolean replaced,
        boolean typedIn) {

    public record Line(long productId, String name, BigDecimal amount, boolean countsTowardQuotation,
            boolean countsTowardBanked) {
    }
}
