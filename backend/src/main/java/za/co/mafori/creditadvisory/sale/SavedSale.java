package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.time.Instant;
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
 * @param flag           a manager's request to correct it, or null. Only a
 *                       typed-in sale that can still change carries one
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
                boolean typedIn,
                Flag flag) {

        /**
         * A manager asked the consultant to correct the sale (POST
         * /api/transactions/{id}/flag). Cleared when the consultant saves a change,
         * or when a manager withdraws it.
         *
         * @param flaggedBy the email of the manager or administrator who asked
         */
        public record Flag(String note, String flaggedBy, Instant flaggedAt) {
        }

        public record Line(long productId, String name, BigDecimal amount, boolean countsTowardQuotation,
                        boolean countsTowardBanked) {
        }
}
