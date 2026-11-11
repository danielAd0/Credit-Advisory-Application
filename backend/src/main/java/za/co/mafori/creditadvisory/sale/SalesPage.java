package za.co.mafori.creditadvisory.sale;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.math.BigDecimal;
import java.util.List;

/**
 * One page of the Sales history page (GET /api/transactions without
 * clientId): the usual paging shape plus the totals of every sale that
 * matched, not just this page.
 */
public record SalesPage(List<Row> items, int page, int size, long totalItems, int totalPages,
        BigDecimal quotationTotal, BigDecimal bankedTotal) {

    /** A saved sale with the names the table shows. */
    public record Row(@JsonUnwrapped SavedSale sale, String clientName, String consultantName) {
    }
}
