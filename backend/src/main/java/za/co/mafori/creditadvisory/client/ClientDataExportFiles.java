package za.co.mafori.creditadvisory.client;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import za.co.mafori.creditadvisory.client.ClientDataExport.Access;
import za.co.mafori.creditadvisory.client.ClientDataExport.Assignment;
import za.co.mafori.creditadvisory.client.ClientDataExport.Consultant;
import za.co.mafori.creditadvisory.client.ClientDataExport.CreditCase;
import za.co.mafori.creditadvisory.client.ClientDataExport.Details;
import za.co.mafori.creditadvisory.client.ClientDataExport.Extra;
import za.co.mafori.creditadvisory.client.ClientDataExport.ImportRow;
import za.co.mafori.creditadvisory.client.ClientDataExport.Line;
import za.co.mafori.creditadvisory.client.ClientDataExport.Note;
import za.co.mafori.creditadvisory.client.ClientDataExport.Sale;
import za.co.mafori.creditadvisory.client.ClientDataExport.Source;
import za.co.mafori.creditadvisory.common.pdf.SimplePdf;
import za.co.mafori.creditadvisory.report.ReportCsv;

/**
 * The client data export (ClientDataExport) as a PDF to read or a CSV for a
 * spreadsheet. Both are made from the same list of entries (section, record,
 * field, value), so they always hold the same data as each other and as the
 * JSON.
 *
 * <p>
 * Times are shown in South African time. Empty values are left out of both
 * files.
 */
final class ClientDataExportFiles {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Johannesburg");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String CONFIDENTIAL = "Confidential. This file contains the client's full ID number. "
            + "Hand it over as the POPIA procedure says, never by ordinary email, and delete your copy afterwards.";

    private ClientDataExportFiles() {
    }

    /**
     * One value in the export. record names one item inside a section (a sale,
     * a case); it is empty for the client's own details.
     */
    record Entry(String section, String record, String field, Object value) {
    }

    // ---------- the two files ----------

    /**
     * UTF-8 CSV with the byte-order mark Excel needs; cells starting with = + - @
     * are guarded.
     */
    static byte[] csv(ClientDataExport export) {
        List<List<Object>> rows = new ArrayList<>();
        for (Entry e : entries(export)) {
            rows.add(List.of(e.section(), e.record(), e.field(), e.value()));
        }
        return ReportCsv.document(List.of("Section", "Record", "Field", "Value"), rows)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] pdf(ClientDataExport export) {
        Details c = export.client();
        SimplePdf pdf = new SimplePdf()
                .title("Client data export: " + c.firstName() + " " + c.lastName())
                .text("Exported " + time(export.exportedAt()) + " by " + export.exportedBy() + ".")
                .space(4)
                .text(CONFIDENTIAL);
        String section = null;
        String record = null;
        for (Entry e : entries(export)) {
            if (e.section().equals("Export")) {
                continue; // already in the title lines
            }
            if (!e.section().equals(section)) {
                section = e.section();
                record = null;
                pdf.heading(section);
            }
            if (!e.record().isEmpty() && !e.record().equals(record)) {
                record = e.record();
                pdf.subheading(record);
            }
            if (e.field().equals(NONE)) {
                pdf.text(String.valueOf(e.value()));
            } else {
                pdf.field(e.field(), text(e.value()));
            }
        }
        return pdf.build();
    }

    // ---------- what goes in ----------

    /** Field name of an entry that only says a section is empty. */
    private static final String NONE = "";

    static List<Entry> entries(ClientDataExport export) {
        List<Entry> out = new ArrayList<>();
        add(out, "Export", "", "Exported at", time(export.exportedAt()));
        add(out, "Export", "", "Exported by", export.exportedBy());
        add(out, "Export", "", "Note", CONFIDENTIAL);

        Details c = export.client();
        String s = "Client details";
        add(out, s, "", "Client number", c.id());
        add(out, s, "", "First name", c.firstName());
        add(out, s, "", "Last name", c.lastName());
        add(out, s, "", "ID number", c.idNumber());
        add(out, s, "", "Email", c.email());
        add(out, s, "", "Phone", c.phone());
        add(out, s, "", "Work phone", c.workPhone());
        add(out, s, "", "Home phone", c.homePhone());
        add(out, s, "", "Employer", c.employer());
        add(out, s, "", "Branch", c.branch());
        add(out, s, "", "File number", c.fileNumber());
        add(out, s, "", "File ID", c.fileId());
        add(out, s, "", "Payment method", c.paymentMethod());
        add(out, s, "", "Current consultant", consultant(c.currentConsultant()));
        add(out, s, "", "Added", time(c.createdAt()));
        add(out, s, "", "Last changed", time(c.updatedAt()));
        add(out, s, "", "Added by spreadsheet", source(c.createdByImport()));

        s = "Consultant history";
        none(out, s, export.assignmentHistory().isEmpty(), "No changes of consultant.");
        for (Assignment a : export.assignmentHistory()) {
            String r = time(a.changedAt()) + " " + a.changeType();
            add(out, s, r, "From", consultant(a.from()));
            add(out, s, r, "To", consultant(a.to()));
            add(out, s, r, "Reason", a.reason());
            add(out, s, r, "Changed by", a.changedBy());
        }

        s = "Sales";
        none(out, s, export.sales().isEmpty(), "No sales.");
        for (Sale sale : export.sales()) {
            String r = "Sale " + sale.id() + " on " + sale.transactionDate();
            add(out, s, r, "Consultant", consultant(sale.consultant()));
            add(out, s, r, "Source", sale.typedIn()
                    ? (sale.replaced() ? "Typed in, then replaced by the weekly spreadsheet" : "Typed in")
                    : "Weekly spreadsheet: " + source(sale.importedFrom()));
            for (Line line : sale.lines()) {
                add(out, s, r, "Product: " + line.product(), line.amount());
            }
            add(out, s, r, "Quotation amount", sale.quotationAmount());
            add(out, s, r, "Banked amount", sale.bankedAmount());
            add(out, s, r, "Employer", sale.employer());
            add(out, s, r, "Structure", sale.structure());
            add(out, s, r, "Payment term", sale.paymentTerm());
            add(out, s, r, "Amount to client", sale.amountToClient());
            add(out, s, r, "Third-party settlement", sale.thirdPartySettlement());
            add(out, s, r, "Primary budget list", sale.primaryStructureBudgetList());
            add(out, s, r, "Primary loan amount", sale.primaryStructureLoanAmount());
            add(out, s, r, "Secondary budget list", sale.secondaryStructureBudgetList());
            add(out, s, r, "Secondary loan amount", sale.secondaryStructureLoanAmount());
            for (Extra extra : sale.otherColumns()) {
                add(out, s, r, "Spreadsheet column: " + extra.column(),
                        extra.number() != null ? extra.number() : extra.text());
            }
            add(out, s, r, "Recorded", time(sale.createdAt()));
        }

        s = "Credit cases";
        none(out, s, export.creditCases().isEmpty(), "No credit cases.");
        for (CreditCase cc : export.creditCases()) {
            String r = "Case " + cc.id() + ": " + cc.title();
            add(out, s, r, "Status", cc.status());
            add(out, s, r, "Opened", time(cc.openedAt()));
            add(out, s, r, "Closed", time(cc.closedAt()));
            add(out, s, r, "Opened by", cc.createdBy());
            for (Note n : cc.notes()) {
                add(out, s, r, "Note " + time(n.createdAt()) + (n.author() == null ? "" : " by " + n.author()),
                        n.note());
            }
        }

        s = "Spreadsheet rows";
        none(out, s, export.importRows().isEmpty(), "No spreadsheet rows were matched to this client.");
        for (ImportRow row : export.importRows()) {
            String r = source(row.importedFrom()) + ", sheet " + row.sheet() + " row " + row.rowNumber();
            add(out, s, r, "Action", row.action());
            add(out, s, r, "Outcome", row.outcome());
        }

        s = "Access log";
        none(out, s, export.accessLog().isEmpty(), "Nothing recorded.");
        for (Access a : export.accessLog()) {
            add(out, s, "", time(a.at()), a.action() + " by " + (a.by() == null ? "the system" : a.by()));
        }
        return out;
    }

    // ---------- helpers ----------

    /**
     * Keeps empty values out (the PDF skips them; the CSV would only repeat
     * blanks).
     */
    private static void add(List<Entry> out, String section, String record, String field, Object value) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            return;
        }
        out.add(new Entry(section, record, field, value));
    }

    private static void none(List<Entry> out, String section, boolean empty, String message) {
        if (empty) {
            out.add(new Entry(section, "", NONE, message));
        }
    }

    private static String consultant(Consultant c) {
        return c == null ? null : c.code() + " " + Objects.requireNonNullElse(c.name(), "");
    }

    private static String source(Source s) {
        return s == null ? null : s.fileName() + " (week of " + s.weekStarting() + ")";
    }

    private static String time(OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(BUSINESS_ZONE).format(TIME);
    }

    private static String text(Object value) {
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        return String.valueOf(value);
    }
}
