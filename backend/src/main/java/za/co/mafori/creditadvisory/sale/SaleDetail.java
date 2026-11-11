package za.co.mafori.creditadvisory.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One sale with every field, for the edit form: the answer to GET and PATCH
 * /api/transactions/{id}.
 *
 * @param editable true while the sale can still be changed or deleted: typed
 *                 in, and its week's spreadsheet not imported yet
 * @param version  send it back with PATCH and DELETE
 */
public record SaleDetail(
        long id,
        long clientId,
        Long consultantId,
        LocalDate transactionDate,
        LocalDate weekStarting,
        Integer dayOfWeek,
        List<SavedSale.Line> lines,
        BigDecimal quotationTotal,
        BigDecimal bankedTotal,
        BigDecimal amountToClient,
        BigDecimal thirdPartySettlement,
        Long paymentTermId,
        Long employerId,
        Long structureId,
        String primaryStructureBudgetList,
        BigDecimal primaryStructureLoanAmount,
        String secondaryStructureBudgetList,
        BigDecimal secondaryStructureLoanAmount,
        boolean typedIn,
        boolean replaced,
        boolean editable,
        long version) {
}
