package za.co.mafori.creditadvisory.imports.register;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import za.co.mafori.creditadvisory.common.idnumber.SaIdNumber;


public record ParsedRow(
        String sheetName,
        DayOfWeek day,
        int rowNumber,
        LocalDate transactionDate,
        String firstName,
        String lastName,
        SaIdNumber idNumber,
        String consultantName,
        String employerName,
        String structureName,
        String paymentTermName,
        BigDecimal quotation,
        BigDecimal banked,
        BigDecimal quotationByRule,
        BigDecimal bankedByRule,
        BigDecimal amountToClient,
        BigDecimal thirdPartySettlement,
        String primaryBudgetList,
        BigDecimal primaryLoanAmount,
        String secondaryBudgetList,
        BigDecimal secondaryLoanAmount,
        Map<String, BigDecimal> productAmounts,
        Map<String, Object> extras,
        List<ParseIssue> issues) {

    public ParsedRow {
        productAmounts = Map.copyOf(productAmounts);
        extras = Map.copyOf(extras);
        issues = List.copyOf(issues);
    }

    /** A row with an ERROR is not imported (design 4.2 / 7). */
    public boolean rejected() {
        return issues.stream().anyMatch(ParseIssue::isError);
    }

    public boolean quotationDiffersFromRule() {
        return quotation != null && quotation.compareTo(quotationByRule) != 0;
    }

    public boolean bankedDiffersFromRule() {
        return banked != null && banked.compareTo(bankedByRule) != 0;
    }

    /** "*********1234", or null. */
    public String idNumberMasked() {
        return idNumber == null ? null : idNumber.masked();
    }

    @Override
    public String toString() {
        return "ParsedRow[" + sheetName + "!" + rowNumber + ", id=" + idNumberMasked() + ", issues=" + issues.size()
                + "]";
    }
}
