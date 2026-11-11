package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A typed-in sale as saved (the answer to POST /api/transactions).
 *
 * @param quotationTotal the lines that count toward the quotation
 * @param bankedTotal    the lines that count toward banked
 * @param replaced       true once that week's spreadsheet has been imported:
 *                       the imported rows count instead of this sale
 */
public record SavedSale(
        long id,
        long clientId,
        Long consultantId,
        LocalDate transactionDate,
        LocalDate weekStarting,
        int dayOfWeek,
        List<Line> lines,
        BigDecimal quotationTotal,
        BigDecimal bankedTotal,
        boolean replaced) {

    public record Line(long productId, String name, BigDecimal amount, boolean countsTowardQuotation,
            boolean countsTowardBanked) {
    }
}
