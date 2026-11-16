package za.co.mafori.creditadvisory.imports.weekly;

import static za.co.mafori.creditadvisory.audit.AuditLogWriter.meta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.audit.AuditLogWriter;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;
import za.co.mafori.creditadvisory.imports.register.RegisterField;
import za.co.mafori.creditadvisory.product.Product;

/**
 * The product list comes from the register (V27: a new install starts with no
 * products). Before a preview is parsed, every day sheet's product columns are
 * read: each header between QUOTATION and the next known field (AMOUNT TO CLIENT
 * in the real workbook) that is no known product becomes a new product.
 *
 * <p>
 * Its quotation and banked flags follow the sheet's own QUOTATION and BANKED
 * formulas: a product counts toward banked if a BANKED formula adds up its
 * column (so CREDIT REPAIR does not). Where no formula covers it, the flag is
 * on; an admin can change both on the Products page.
 *
 * <p>
 * Runs inside the preview's transaction and the products stay even if the
 * preview is cancelled: the preview's rows and report figures already refer to
 * them. An admin deactivates one that is not wanted.
 */
@Component
public class ProductDiscovery {

    private static final int HEADER_SEARCH_ROWS = 10;
    private static final int MAX_NAME = 100;
    private static final int MAX_CODE = 40;
    private static final Pattern CELL_OR_RANGE = Pattern
            .compile("(?<![A-Z0-9_!])\\$?([A-Z]{1,3})\\$?\\d+(?::\\$?([A-Z]{1,3})\\$?\\d+)?(?![A-Z0-9_(])");

    private final JdbcTemplate jdbc;
    private final AuditLogWriter audit;

    public ProductDiscovery(JdbcTemplate jdbc, AuditLogWriter audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** A product column found in the sheet and what its sheet's formulas say about it. */
    private static final class Found {
        final String code;
        final String name;
        final String header;
        boolean quotationFormulaSeen;
        boolean inQuotation;
        boolean bankedFormulaSeen;
        boolean inBanked;

        Found(String code, String name, String header) {
            this.code = code;
            this.name = name;
            this.header = header;
        }
    }

    /**
     * Creates the sheet's products that do not exist yet.
     *
     * @return the codes created, in the order their columns first appear
     *         (Monday first); empty when every product column is known
     */
    public List<String> createMissing(RawWorkbook workbook, ImportDictionary dictionary) {
        Map<String, Found> found = new LinkedHashMap<>();
        List<RawSheet> days = new ArrayList<>();
        for (RawSheet sheet : workbook.sheets()) {
            dictionary.day(sheet.name()).ifPresent(d -> days.add(sheet));
        }
        days.sort((a, b) -> dictionary.day(a.name()).orElseThrow().compareTo(dictionary.day(b.name()).orElseThrow()));
        for (RawSheet sheet : days) {
            readSheet(sheet, dictionary, found);
        }
        if (found.isEmpty()) {
            return List.of();
        }

        Integer maxOrder = jdbc.queryForObject("select coalesce(max(display_order), 0) from products", Integer.class);
        int order = maxOrder == null ? 0 : maxOrder;
        List<String> created = new ArrayList<>();
        for (Found f : found.values()) {
            boolean quotation = !f.quotationFormulaSeen || f.inQuotation;
            boolean banked = !f.bankedFormulaSeen || f.inBanked;
            order += 10;
            Long id = jdbc.query("""
                    insert into products (code, name, counts_toward_quotation, counts_toward_banked, display_order)
                    values (?, ?, ?, ?, ?) on conflict (code) do nothing returning id
                    """, rs -> rs.next() ? rs.getLong(1) : null, f.code, f.name, quotation, banked, order);
            // The header is recognised from now on even if it is spelled unlike the
            // code and name ("CREDIT-REPAIR").
            String alias = ImportDictionary.normalize(f.header);
            if (alias.length() <= 100 && !alias.equals(ImportDictionary.normalize(f.code.replace('_', ' ')))
                    && !alias.equals(ImportDictionary.normalize(f.name))) {
                jdbc.update("""
                        insert into import_header_aliases (alias, canonical, kind) values (?, ?, 'PRODUCT')
                        on conflict (alias) do nothing
                        """, alias, f.code);
            }
            if (id != null) {
                created.add(f.code);
                audit.record("PRODUCT_CREATED", "PRODUCT", id, meta("code", f.code, "name", f.name,
                        "countsTowardQuotation", quotation, "countsTowardBanked", banked, "source",
                        "WEEKLY_REGISTER_IMPORT"));
            }
        }
        return created;
    }

    private static void readSheet(RawSheet sheet, ImportDictionary dictionary, Map<String, Found> found) {
        Integer headerRow = headerRow(sheet, dictionary);
        if (headerRow == null) {
            return;
        }
        Map<Integer, String> labels = new TreeMap<>();
        Map<RegisterField, Integer> fields = new java.util.EnumMap<>(RegisterField.class);
        for (var e : sheet.rows().get(headerRow).entrySet()) {
            String label = label(e.getValue());
            if (label != null) {
                labels.put(e.getKey(), label);
                dictionary.field(label).ifPresent(f -> fields.putIfAbsent(f, e.getKey()));
            }
        }
        Integer quotationColumn = fields.get(RegisterField.QUOTATION);
        if (quotationColumn == null) {
            return;
        }
        // The products sit between QUOTATION and the next known field.
        int end = fields.values().stream().filter(c -> c > quotationColumn).min(Integer::compare)
                .orElse(Integer.MAX_VALUE);

        Map<Integer, Found> columns = new TreeMap<>();
        for (var e : labels.entrySet()) {
            int column = e.getKey();
            String label = e.getValue();
            if (column <= quotationColumn || column >= end || dictionary.product(label).isPresent()) {
                continue;
            }
            String code = dictionary.productCodeOf(label).orElseGet(() -> codeOf(label));
            if (code == null) {
                continue; // no letters at all: stays an extra column
            }
            String name = dictionary.productCodeOf(label).isPresent() ? nameOf(code.replace('_', ' '))
                    : nameOf(label);
            columns.put(column, found.computeIfAbsent(code, c -> new Found(c, name, label)));
        }
        if (columns.isEmpty()) {
            return;
        }

        // The first data row's formulas say which columns QUOTATION and BANKED add up.
        Integer bankedColumn = fields.get(RegisterField.BANKED);
        Set<Integer> inQuotation = null;
        Set<Integer> inBanked = null;
        for (var row : sheet.rows().tailMap(headerRow, false).values()) {
            if (inQuotation == null) {
                inQuotation = referencedColumns(row.get(quotationColumn));
            }
            if (inBanked == null && bankedColumn != null) {
                inBanked = referencedColumns(row.get(bankedColumn));
            }
            if (inQuotation != null && (inBanked != null || bankedColumn == null)) {
                break;
            }
        }
        for (var e : columns.entrySet()) {
            Found f = e.getValue();
            if (inQuotation != null) {
                f.quotationFormulaSeen = true;
                f.inQuotation |= inQuotation.contains(e.getKey());
            }
            if (inBanked != null) {
                f.bankedFormulaSeen = true;
                f.inBanked |= inBanked.contains(e.getKey());
            }
        }
    }

    private static Integer headerRow(RawSheet sheet, ImportDictionary dictionary) {
        for (var entry : sheet.rows().headMap(HEADER_SEARCH_ROWS, false).entrySet()) {
            Set<RegisterField> seen = new HashSet<>();
            for (RawCell cell : entry.getValue().values()) {
                if (cell.kind() == RawCell.Kind.TEXT) {
                    dictionary.field(cell.text()).ifPresent(seen::add);
                }
            }
            if (seen.containsAll(Set.of(RegisterField.NAME, RegisterField.SURNAME, RegisterField.ID_NUMBER))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String label(RawCell cell) {
        if (cell == null || cell.kind() != RawCell.Kind.TEXT) {
            return null;
        }
        String s = cell.text().strip().replaceAll("\\s+", " ");
        return s.isEmpty() ? null : s;
    }

    /** The 0-based columns a formula refers to, or null when the cell has no formula. */
    static Set<Integer> referencedColumns(RawCell cell) {
        if (cell == null || !cell.isFormula()) {
            return null;
        }
        Set<Integer> columns = new HashSet<>();
        Matcher m = CELL_OR_RANGE.matcher(cell.formula().toUpperCase(Locale.ROOT));
        while (m.find()) {
            int from = columnIndex(m.group(1));
            int to = m.group(2) == null ? from : columnIndex(m.group(2));
            for (int c = Math.min(from, to); c <= Math.max(from, to); c++) {
                columns.add(c);
            }
        }
        return columns;
    }

    private static int columnIndex(String letters) {
        int n = 0;
        for (char ch : letters.toCharArray()) {
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }

    /** "Cash Advance-Lite!" becomes CASH_ADVANCE_LITE; null if nothing usable is left. */
    static String codeOf(String header) {
        String code = Product.normalizeCode(header).replaceAll("[^A-Z0-9_]", "").replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (code.isEmpty() || !code.matches(".*[A-Z].*")) {
            return null;
        }
        if (!Character.isLetter(code.charAt(0))) {
            code = "P_" + code;
        }
        if (code.length() < 2) {
            code = code + "_P";
        }
        return code.length() > MAX_CODE ? code.substring(0, MAX_CODE).replaceAll("_$", "") : code;
    }

    /** "MAFORI CARD" becomes "Mafori Card"; short words such as IVC stay as written. */
    static String nameOf(String header) {
        StringBuilder sb = new StringBuilder();
        for (String word : header.strip().split("\\s+")) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(word.length() <= 3 ? word
                    : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT));
        }
        String name = sb.toString();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).strip() : name;
    }
}
