package za.co.mafori.creditadvisory.imports.register;

import java.time.LocalDate;
import java.util.List;


public record ParseResult(LocalDate weekStarting, List<DaySheetResult> days, List<ParsedRow> rows,
        List<ParseIssue> issues) {

    public ParseResult {
        days = List.copyOf(days);
        rows = List.copyOf(rows);
        issues = List.copyOf(issues);
    }

    /** A sheet- or file-level ERROR: the workbook cannot be imported until it is fixed. */
    public boolean hasBlockingErrors() {
        return issues.stream().anyMatch(ParseIssue::blocksImport);
    }

    public List<ParsedRow> validRows() {
        return rows.stream().filter(r -> !r.rejected()).toList();
    }

    public List<ParsedRow> rejectedRows() {
        return rows.stream().filter(ParsedRow::rejected).toList();
    }

    public long count(ParseIssue.Severity severity) {
        return issues.stream().filter(i -> i.severity() == severity).count();
    }

    public List<ParseIssue> issues(String code) {
        return issues.stream().filter(i -> i.code().equals(code)).toList();
    }
}
