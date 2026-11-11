package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One page of GET /api/transactions?from=&to= (the Sales history page). The
 * paging fields are
 * the same as every other list ({ items, page, size, totalItems, totalPages });
 * quotationTotal
 * and bankedTotal are for the whole date range, not only this page.
 */
public record SaleHistoryPage(List<Row> items, int page, int size, long totalItems, int totalPages,
        BigDecimal quotationTotal, BigDecimal bankedTotal) {

    /**
     * One sale. Never an ID number: the client is shown by name and linked by id.
     *
     * @param consultantName null for a sale without a consultant (an imported row
     *                       whose name
     *                       matched nobody)
     * @param typedIn        true for a sale typed into the app, false for one from
     *                       a weekly
     *                       register import
     */
    public record Row(long id, long clientId, Long consultantId, LocalDate transactionDate,
            LocalDate weekStarting, Integer dayOfWeek, List<SavedSale.Line> lines, BigDecimal quotationTotal,
            BigDecimal bankedTotal, boolean typedIn, String clientName, String consultantName) {
    }
}
