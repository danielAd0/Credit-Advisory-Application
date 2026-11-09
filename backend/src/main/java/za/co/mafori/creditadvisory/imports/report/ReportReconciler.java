package za.co.mafori.creditadvisory.imports.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.imports.excel.RawCell;
import za.co.mafori.creditadvisory.imports.excel.RawSheet;
import za.co.mafori.creditadvisory.imports.excel.RawWorkbook;
import za.co.mafori.creditadvisory.imports.register.DaySheetResult;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;
import za.co.mafori.creditadvisory.imports.register.ParseResult;
import za.co.mafori.creditadvisory.imports.register.ParsedRow;
import za.co.mafori.creditadvisory.imports.report.Formula.Binary;
import za.co.mafori.creditadvisory.imports.report.Formula.Bool;
import za.co.mafori.creditadvisory.imports.report.Formula.Call;
import za.co.mafori.creditadvisory.imports.report.Formula.Err;
import za.co.mafori.creditadvisory.imports.report.Formula.Node;
import za.co.mafori.creditadvisory.imports.report.Formula.Num;
import za.co.mafori.creditadvisory.imports.report.Formula.Ref;
import za.co.mafori.creditadvisory.imports.report.Formula.Text;
import za.co.mafori.creditadvisory.imports.report.Formula.Unary;

@Component
public class ReportReconciler {

    public static final List<String> SHEETS = List.of("Report", "Breakdown");

    public static final String DAILY_BANKED = "DAILY_BANKED";
    public static final String DAILY_QUOTATION = "DAILY_QUOTATION";
    public static final String DAILY_TARGET = "DAILY_TARGET";
    public static final String DAILY_CLIENT_COUNT = "DAILY_CLIENT_COUNT";
    public static final String CONSULTANT_PRODUCT = "CONSULTANT_PRODUCT";
    public static final String CONSULTANT_CLIENT_COUNT = "CONSULTANT_CLIENT_COUNT";
    public static final String CONSULTANT_QUOTATION = "CONSULTANT_QUOTATION";
    public static final String CONSULTANT_TOTAL = "CONSULTANT_TOTAL";
    public static final String FINANCE = "FINANCE";
    public static final String SHEET_DIFFERENCE = "SHEET_DIFFERENCE";
    public static final String BUDGET_BY_STRUCTURE = "BUDGET_BY_STRUCTURE";
    public static final String BREAKDOWN = "BREAKDOWN";
    public static final String BREAKDOWN_SUMMARY = "BREAKDOWN_SUMMARY";
    public static final String OTHER = "OTHER";

    public static final String WRONG_COLUMN = "WRONG_COLUMN";
    public static final String WRONG_CRITERIA_COLUMN = "WRONG_CRITERIA_COLUMN";
    public static final String ROW_OFFSET = "ROW_OFFSET";
    public static final String WRONG_SHEET = "WRONG_SHEET";
    public static final String TEXT_ID_NOT_COUNTED = "TEXT_ID_NOT_COUNTED";
    public static final String RANGE_INCOMPLETE = "RANGE_INCOMPLETE";
    public static final String REF_ERROR = "REF_ERROR";
    public static final String VALUE_ERROR = "VALUE_ERROR";
    public static final String BLANK_CONSULTANT_EXCLUDED = "BLANK_CONSULTANT_EXCLUDED";
    public static final String THRESHOLD_GREATER_THAN = "THRESHOLD_GREATER_THAN";
    public static final String UNEXPLAINED = "UNEXPLAINED";

    private static final Set<DayOfWeek> ALL_DAYS = EnumSet.allOf(DayOfWeek.class);
    private static final int MAX_BLOCK_ROWS = 300;
    private static final int TITLE_SEARCH_ROWS = 8;
    private static final int MAX_RANGE_CELLS = 2000;

    public record Reconciliation(List<ReportFigure> figures, List<String> sheetsRead) {

        public Reconciliation {
            figures = List.copyOf(figures);
            sheetsRead = List.copyOf(sheetsRead);
        }

        public int differences() {
            return (int) figures.stream().filter(ReportFigure::isDifference).count();
        }

        public String status() {
            if (sheetsRead.isEmpty()) {
                return "NOT_CHECKED";
            }
            return differences() == 0 ? "MATCHED" : "DIFFERENCES";
        }
    }

    public Reconciliation reconcile(RawWorkbook workbook, ParseResult parsed, ImportDictionary dictionary) {
        Data data = new Data(parsed, dictionary);
        List<ReportFigure> figures = new ArrayList<>();
        List<String> read = new ArrayList<>();
        for (String name : SHEETS) {
            Optional<RawSheet> sheet = workbook.sheets().stream()
                    .filter(s -> ImportDictionary.normalize(s.name()).equals(name.toUpperCase(Locale.ROOT)))
                    .findFirst();
            if (sheet.isPresent()) {
                read.add(name);
                figures.addAll(new SheetModel(name, sheet.get(), data, dictionary).figures());
            }
        }
        return new Reconciliation(figures, read);
    }

    enum Kind {
        PRODUCT, QUOTATION, BANKED, COUNT, BUDGET, TARGET
    }

    enum LabelKind {
        CONSULTANT, BUDGET_LIST
    }

    record Measure(Kind kind, String productCode) {

        static Measure of(Kind kind) {
            return new Measure(kind, null);
        }

        boolean numeric() {
            return kind != Kind.TARGET;
        }

        LabelKind labelKind() {
            return kind == Kind.BUDGET ? LabelKind.BUDGET_LIST : LabelKind.CONSULTANT;
        }
    }

    record Intent(Measure measure, Set<DayOfWeek> days, String label) {

        Intent {
            days = days.isEmpty() ? Set.of() : EnumSet.copyOf(days);
        }

        Integer singleDay() {
            return days.size() == 1 ? days.iterator().next().getValue() : null;
        }
    }

    static final class Data {
        final Map<DayOfWeek, List<ParsedRow>> rows = new HashMap<>();
        final Map<String, DayOfWeek> sheetDays = new HashMap<>();
        final Map<DayOfWeek, Map<String, String>> columns = new HashMap<>();
        final ImportDictionary dictionary;

        Data(ParseResult parsed, ImportDictionary dictionary) {
            this.dictionary = dictionary;
            for (DayOfWeek d : DayOfWeek.values()) {
                rows.put(d, new ArrayList<>());
            }
            parsed.rows().forEach(r -> rows.get(r.day()).add(r));
            for (DaySheetResult d : parsed.days()) {
                sheetDays.put(ImportDictionary.normalize(d.sheetName()), d.day());
                columns.put(d.day(), d.columns());
            }
        }

        DayOfWeek dayOfSheet(String sheetName) {
            if (sheetName == null) {
                return null;
            }
            String n = ImportDictionary.normalize(sheetName);
            DayOfWeek d = sheetDays.get(n);
            return d != null ? d : dictionary.day(n).orElse(null);
        }

        String mapping(DayOfWeek day, int column) {
            Map<String, String> m = columns.get(day);
            return m == null ? null : m.get(Formula.columnLetter(column));
        }

        BigDecimal value(Intent intent) {
            BigDecimal sum = BigDecimal.ZERO;
            for (DayOfWeek d : intent.days()) {
                for (ParsedRow r : rows.get(d)) {
                    sum = sum.add(amount(intent.measure(), r, intent.label()));
                }
            }
            return sum;
        }

        BigDecimal unlisted(Intent intent, Set<String> listed) {
            BigDecimal sum = BigDecimal.ZERO;
            for (DayOfWeek d : intent.days()) {
                for (ParsedRow r : rows.get(d)) {
                    if (intent.measure().kind() == Kind.BUDGET) {
                        if (!listed.contains(ImportDictionary.normalize(r.primaryBudgetList()))) {
                            sum = sum.add(nz(r.primaryLoanAmount()));
                        }
                        if (!listed.contains(ImportDictionary.normalize(r.secondaryBudgetList()))) {
                            sum = sum.add(nz(r.secondaryLoanAmount()));
                        }
                    } else if (!listed.contains(ImportDictionary.normalize(r.consultantName()))) {
                        sum = sum.add(amount(intent.measure(), r, null));
                    }
                }
            }
            return sum;
        }

        private static BigDecimal amount(Measure m, ParsedRow r, String label) {
            if (m.kind() == Kind.BUDGET) {
                BigDecimal sum = BigDecimal.ZERO;
                if (label == null || label.equals(ImportDictionary.normalize(r.primaryBudgetList()))) {
                    sum = sum.add(nz(r.primaryLoanAmount()));
                }
                if (label == null || label.equals(ImportDictionary.normalize(r.secondaryBudgetList()))) {
                    sum = sum.add(nz(r.secondaryLoanAmount()));
                }
                return sum;
            }
            if (label != null && !label.equals(ImportDictionary.normalize(r.consultantName()))) {
                return BigDecimal.ZERO;
            }
            return switch (m.kind()) {
                case PRODUCT -> nz(r.productAmounts().get(m.productCode()));
                case QUOTATION -> nz(r.quotation());
                case BANKED -> nz(r.banked());
                case COUNT -> BigDecimal.ONE;
                default -> BigDecimal.ZERO;
            };
        }
    }

    static final class Pos {
        String section;
        String blockTitle;
        String rowLabel;
        String columnLabel;
        Intent intent;
        Block block;
        boolean inBlock;
        boolean totalRow;
        boolean totalColumn;
    }

    static final class Block {
        String title;
        final List<Group> groups = new ArrayList<>();
        Measure measure;
        String section;
    }

    static final class Group {
        int headerRow;
        int labelCol;
        final Map<Integer, DayOfWeek> dayOfColumn = new TreeMap<>();
        final Set<Integer> totalColumns = new HashSet<>();
        final Map<Integer, String> headers = new TreeMap<>();
        final Map<Integer, String> rowLabels = new TreeMap<>();
        Integer totalRow;
        String title;
        Block block;

        Collection<Integer> columns() {
            return headers.keySet();
        }

        boolean covers(int row) {
            return row == headerRow || rowLabels.containsKey(row) || Objects.equals(totalRow, row);
        }
    }

    static final class Work {
        final int row;
        final int col;
        final RawCell raw;
        final Node ast;
        Intent intent;
        boolean resolved;
        boolean resolving;
        BigDecimal computed;
        String computedText;
        Boolean matches;
        String defect;
        String productCode;
        String section;

        Work(int row, int col, RawCell raw, Node ast) {
            this.row = row;
            this.col = col;
            this.raw = raw;
            this.ast = ast;
        }
    }

    static final class SheetModel {
        private final String name;
        private final RawSheet sheet;
        private final Data data;
        private final ImportDictionary dictionary;
        private final Map<Long, Pos> positions = new HashMap<>();
        private final Map<Long, Work> work = new LinkedHashMap<>();
        private final List<Group> groups = new ArrayList<>();

        SheetModel(String name, RawSheet sheet, Data data, ImportDictionary dictionary) {
            this.name = name;
            this.sheet = sheet;
            this.data = data;
            this.dictionary = dictionary;
        }

        List<ReportFigure> figures() {
            for (var row : sheet.rows().entrySet()) {
                for (var cell : row.getValue().entrySet()) {
                    RawCell c = cell.getValue();
                    if (c.isFormula() || c.kind() == RawCell.Kind.ERROR) {
                        Node ast = c.isFormula() ? Formula.parse(c.formula()).orElse(null) : null;
                        work.put(key(row.getKey(), cell.getKey()), new Work(row.getKey(), cell.getKey(), c, ast));
                    }
                }
            }
            findDaySummaries();
            findBlocks();
            List<ReportFigure> out = new ArrayList<>();
            for (Work w : work.values()) {
                resolve(w);
            }
            for (Work w : work.values()) {
                out.add(figure(w));
            }
            return out;
        }

        private void findDaySummaries() {
            Map<Integer, TreeMap<Integer, DayOfWeek>> dayCells = new TreeMap<>();
            for (var row : sheet.rows().entrySet()) {
                for (var cell : row.getValue().entrySet()) {
                    DayOfWeek d = exactDay(text(cell.getValue()));
                    if (d != null) {
                        dayCells.computeIfAbsent(cell.getKey(), k -> new TreeMap<>()).put(row.getKey(), d);
                    }
                }
            }
            for (var column : dayCells.entrySet()) {
                int labelCol = column.getKey();
                List<Integer> run = new ArrayList<>();
                Integer previous = null;
                for (int r : column.getValue().keySet()) {
                    if (previous != null && r != previous + 1) {
                        summary(labelCol, run, column.getValue());
                        run = new ArrayList<>();
                    }
                    run.add(r);
                    previous = r;
                }
                summary(labelCol, run, column.getValue());
            }
        }

        private void summary(int labelCol, List<Integer> run, Map<Integer, DayOfWeek> days) {
            if (run.size() < 3) {
                return;
            }
            int first = run.get(0);
            int last = run.get(run.size() - 1);
            Integer valueCol = null;
            Map<Integer, RawCell> firstRow = sheet.rows().get(first);
            for (var cell : firstRow.entrySet()) {
                if (cell.getKey() > labelCol) {
                    if (exactDay(text(cell.getValue())) != null) {
                        return;
                    }
                    valueCol = cell.getKey();
                    break;
                }
            }
            if (valueCol == null) {
                return;
            }
            String header = text(sheet.cell(first - 1, valueCol));
            Measure m = measureOfHeader(header);
            if (m == null) {
                return;
            }
            String section = switch (m.kind()) {
                case BANKED -> DAILY_BANKED;
                case QUOTATION -> DAILY_QUOTATION;
                case TARGET -> DAILY_TARGET;
                case COUNT -> DAILY_CLIENT_COUNT;
                default -> OTHER;
            };
            for (int r : run) {
                Pos p = pos(r, valueCol);
                p.section = section;
                p.blockTitle = header;
                p.rowLabel = text(sheet.cell(r, labelCol));
                p.columnLabel = header;
                p.intent = new Intent(m, EnumSet.of(days.get(r)), null);
                p.inBlock = true;
            }
            String totalLabel = text(sheet.cell(last + 1, labelCol));
            if (isTotal(totalLabel)) {
                Pos p = pos(last + 1, valueCol);
                p.section = section;
                p.blockTitle = header;
                p.rowLabel = totalLabel;
                p.columnLabel = header;
                p.intent = new Intent(m, ALL_DAYS, null);
                p.inBlock = true;
                p.totalRow = true;
            }
        }

        private Measure measureOfHeader(String header) {
            String h = ImportDictionary.normalize(header);
            if (h == null || h.isEmpty()) {
                return null;
            }
            if (h.contains("TARGET")) {
                return Measure.of(Kind.TARGET);
            }
            if (h.contains("BANK")) {
                return Measure.of(Kind.BANKED);
            }
            if (h.contains("QUOT")) {
                return Measure.of(Kind.QUOTATION);
            }
            if (h.contains("CLIENT")) {
                return Measure.of(Kind.COUNT);
            }
            return dictionary.product(h).map(p -> new Measure(Kind.PRODUCT, p.code())).orElse(null);
        }

        private void findBlocks() {
            for (var row : sheet.rows().entrySet()) {
                int r = row.getKey();
                List<Integer> cols = new ArrayList<>(row.getValue().keySet());
                for (int i = 0; i < cols.size(); i++) {
                    int c = cols.get(i);
                    String label = text(row.getValue().get(c));
                    if (label == null || headerDay(label) != null || isTotal(label)) {
                        continue;
                    }
                    Group g = new Group();
                    g.headerRow = r;
                    g.labelCol = c;
                    int next = c + 1;
                    while (true) {
                        String h = text(row.getValue().get(next));
                        if (h == null) {
                            break;
                        }
                        DayOfWeek d = headerDay(h);
                        if (d != null) {
                            g.dayOfColumn.put(next, d);
                        } else if (isTotal(h)) {
                            g.totalColumns.add(next);
                        } else {
                            break;
                        }
                        g.headers.put(next, h);
                        next++;
                    }
                    if (!g.headers.isEmpty() && readRows(g)) {
                        groups.add(g);
                    }
                }
            }
            groups.sort((a, b) -> a.headerRow != b.headerRow ? Integer.compare(a.headerRow, b.headerRow)
                    : Integer.compare(a.labelCol, b.labelCol));
            Map<String, Block> blocks = new LinkedHashMap<>();
            for (Group g : groups) {
                Group parent = null;
                String title = null;
                for (int r = g.headerRow - 1; r >= Math.max(0, g.headerRow - TITLE_SEARCH_ROWS); r--) {
                    int row = r;
                    Optional<Group> earlier = groups.stream()
                            .filter(o -> o != g && o.labelCol == g.labelCol && o.headerRow < g.headerRow
                                    && o.covers(row))
                            .findFirst();
                    if (earlier.isPresent()) {
                        parent = earlier.get();
                        break;
                    }
                    String t = text(sheet.cell(r, g.labelCol));
                    if (t != null) {
                        title = t;
                        break;
                    }
                }
                if (parent != null) {
                    g.title = parent.title;
                    g.block = parent.block;
                } else {
                    g.title = title;
                    String blockKey = title == null ? "#" + g.headerRow + ":" + g.labelCol
                            : ImportDictionary.normalize(title) + "@" + g.headerRow;
                    Block same = title == null ? null
                            : blocks.values().stream()
                                    .filter(b -> b.title != null
                                            && ImportDictionary.normalize(b.title)
                                                    .equals(ImportDictionary.normalize(g.title))
                                            && b.groups.stream().anyMatch(o -> o.headerRow == g.headerRow))
                                    .findFirst().orElse(null);
                    if (same != null) {
                        g.block = same;
                    } else {
                        g.block = new Block();
                        g.block.title = title;
                        blocks.put(blockKey, g.block);
                    }
                }
                g.block.groups.add(g);
            }
            for (Block b : blocks.values()) {
                b.measure = blockMeasure(b);
                if (b.measure == null) {
                    continue;
                }
                b.section = blockSection(b.measure);
                for (Group g : b.groups) {
                    placeGroup(b, g);
                }
            }
        }

        private boolean readRows(Group g) {
            boolean anyFigure = false;
            for (int r = g.headerRow + 1; r <= g.headerRow + MAX_BLOCK_ROWS; r++) {
                String label = text(sheet.cell(r, g.labelCol));
                if (label == null) {
                    break;
                }
                final int row = r;
                boolean header = g.columns().stream().anyMatch(c -> {
                    String h = text(sheet.cell(row, c));
                    return h != null && (headerDay(h) != null || isTotal(h));
                });
                if (header) {
                    break;
                }
                for (int c : g.columns()) {
                    RawCell cell = sheet.cell(r, c);
                    if (cell != null && (cell.isFormula() || cell.kind() == RawCell.Kind.ERROR)) {
                        anyFigure = true;
                    }
                }
                if (isTotal(label)) {
                    g.totalRow = r;
                    break;
                }
                g.rowLabels.put(r, label);
            }
            return anyFigure;
        }

        private Measure blockMeasure(Block b) {
            Measure m = titleMeasure(b.title);
            if (m != null) {
                return m;
            }
            for (Group g : b.groups) {
                for (int c : g.totalColumns) {
                    String h = ImportDictionary.normalize(g.headers.get(c)).replaceFirst("^TOTAL\\s*", "");
                    Optional<ImportDictionary.Product> p = dictionary.product(h);
                    if (p.isPresent()) {
                        return new Measure(Kind.PRODUCT, p.get().code());
                    }
                }
            }
            Map<Measure, Integer> votes = new HashMap<>();
            for (Group g : b.groups) {
                for (int r : g.rowLabels.keySet()) {
                    for (var e : g.dayOfColumn.entrySet()) {
                        Work w = work.get(key(r, e.getKey()));
                        if (w != null && w.ast != null) {
                            for (Term t : terms(w.ast)) {
                                Measure tm = t.measure(data);
                                if (tm != null) {
                                    votes.merge(tm, 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            }
            return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        }

        private Measure titleMeasure(String title) {
            String t = ImportDictionary.normalize(title);
            if (t == null || t.isEmpty()) {
                return null;
            }
            int open = t.indexOf('(');
            int close = t.lastIndexOf(')');
            if (open >= 0 && close > open) {
                t = t.substring(open + 1, close).strip();
            }
            Optional<ImportDictionary.Product> p = dictionary.product(t);
            if (p.isPresent()) {
                return new Measure(Kind.PRODUCT, p.get().code());
            }
            if (t.contains("CLIENT")) {
                return Measure.of(Kind.COUNT);
            }
            if (t.contains("QUOT")) {
                return Measure.of(Kind.QUOTATION);
            }
            if (t.contains("BANK")) {
                return Measure.of(Kind.BANKED);
            }
            if (t.contains("BUDGET")) {
                return Measure.of(Kind.BUDGET);
            }
            return null;
        }

        private String blockSection(Measure m) {
            if (name.equals("Breakdown")) {
                return BREAKDOWN;
            }
            return switch (m.kind()) {
                case PRODUCT -> CONSULTANT_PRODUCT;
                case COUNT -> CONSULTANT_CLIENT_COUNT;
                case QUOTATION, BANKED -> CONSULTANT_QUOTATION;
                case BUDGET -> BUDGET_BY_STRUCTURE;
                default -> OTHER;
            };
        }

        private void placeGroup(Block b, Group g) {
            List<Integer> rows = new ArrayList<>(g.rowLabels.keySet());
            if (g.totalRow != null) {
                rows.add(g.totalRow);
            }
            for (int r : rows) {
                boolean totalRow = Objects.equals(r, g.totalRow);
                String label = totalRow ? null : ImportDictionary.normalize(g.rowLabels.get(r));
                for (int c : g.columns()) {
                    if (positions.containsKey(key(r, c)) && positions.get(key(r, c)).inBlock) {
                        continue;
                    }
                    boolean totalColumn = g.totalColumns.contains(c);
                    Set<DayOfWeek> days = totalColumn ? ALL_DAYS : EnumSet.of(g.dayOfColumn.get(c));
                    Pos p = pos(r, c);
                    p.section = b.section;
                    p.blockTitle = b.title;
                    p.rowLabel = totalRow ? text(sheet.cell(r, g.labelCol)) : g.rowLabels.get(r);
                    p.columnLabel = g.headers.get(c);
                    p.intent = new Intent(b.measure, days, label);
                    p.block = b;
                    p.inBlock = true;
                    p.totalRow = totalRow;
                    p.totalColumn = totalColumn;
                }
            }
        }

        private void resolve(Work w) {
            if (w.resolved || w.resolving) {
                return;
            }
            w.resolving = true;
            Pos p = positions.get(key(w.row, w.col));
            List<Work> refs = w.ast == null ? List.of() : localRefs(w.ast);
            refs.forEach(this::resolve);

            Intent intent = p != null && p.inBlock ? p.intent : outsideIntent(w, refs);
            w.intent = intent;
            w.section = p != null && p.inBlock ? p.section : outsideSection(w, refs);
            w.productCode = actualProduct(w, intent);

            if (intent != null && intent.measure().kind() == Kind.TARGET) {
                w.computedText = evaluateTarget(w, true);
            } else if (intent != null) {
                w.computed = data.value(intent);
            } else if (w.ast != null && !hasDayTerms(w.ast)) {
                Object v = evaluate(w.ast, true, false);
                if (v instanceof BigDecimal n) {
                    w.computed = n;
                } else if (v instanceof String s) {
                    w.computedText = s;
                }
            }
            w.matches = compare(w);
            w.defect = defect(w, intent, refs);
            w.resolving = false;
            w.resolved = true;
        }

        private Boolean compare(Work w) {
            if (w.raw.kind() == RawCell.Kind.ERROR) {
                return w.computed != null || w.computedText != null ? Boolean.FALSE : null;
            }
            if (w.computed != null) {
                if (w.raw.kind() != RawCell.Kind.NUMBER) {
                    return w.raw.kind() == RawCell.Kind.TEXT && w.raw.text().isBlank()
                            ? w.computed.signum() == 0
                            : Boolean.FALSE;
                }
                return round(w.raw.number()).compareTo(round(w.computed)) == 0;
            }
            if (w.computedText != null) {
                String shown = w.raw.kind() == RawCell.Kind.TEXT ? w.raw.text() : null;
                return shown != null && shown.strip().equalsIgnoreCase(w.computedText.strip());
            }
            return null;
        }

        private Intent outsideIntent(Work w, List<Work> refs) {
            String rowLabel = rowLabel(w.row, w.col);
            boolean pureSum = w.ast != null && pureSumRefs(w.ast) != null;
            if (rowLabel != null && (pureSum || w.ast == null || Formula.containsRefError(w.ast))) {
                Optional<ImportDictionary.Product> product = dictionary.product(ImportDictionary.normalize(rowLabel));
                if (product.isPresent()) {
                    return new Intent(new Measure(Kind.PRODUCT, product.get().code()), ALL_DAYS, null);
                }
            }
            if (!pureSum || refs.isEmpty()) {
                return null;
            }
            List<Intent> in = new ArrayList<>();
            for (Work r : pureSumRefs(w.ast)) {
                Intent i = r.intent;
                if (i == null || !i.measure().numeric()) {
                    return null;
                }
                in.add(i);
            }
            Measure m = in.get(0).measure();
            if (in.stream().anyMatch(i -> !i.measure().equals(m))) {
                return null;
            }
            Set<String> labels = new HashSet<>();
            in.forEach(i -> labels.add(i.label()));
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            in.forEach(i -> days.addAll(i.days()));
            if (labels.size() == 1) {
                String label = labels.iterator().next();
                return in.size() == 1 ? in.get(0) : new Intent(m, days.size() > 1 ? ALL_DAYS : days, label);
            }
            String own = ImportDictionary.normalize(rowLabel);
            if (own != null && labels.contains(own)
                    && in.stream().filter(i -> own.equals(i.label())).count() * 2 > in.size()) {
                return new Intent(m, ALL_DAYS, own);
            }
            Set<DayOfWeek> first = in.get(0).days();
            if (in.stream().allMatch(i -> i.days().equals(first))) {
                return new Intent(m, first, null);
            }
            return null;
        }

        private String outsideSection(Work w, List<Work> refs) {
            for (int r = w.row; r >= Math.max(0, w.row - 6); r--) {
                Map<Integer, RawCell> row = sheet.rows().get(r);
                if (row == null) {
                    continue;
                }
                for (var cell : row.entrySet()) {
                    String t = ImportDictionary.normalize(text(cell.getValue()));
                    if (cell.getKey() <= w.col && t != null) {
                        if (t.startsWith("FINANCE")) {
                            return FINANCE;
                        }
                        if (t.startsWith("DIFFERENCE")) {
                            return SHEET_DIFFERENCE;
                        }
                    }
                }
            }
            if (name.equals("Breakdown")) {
                return BREAKDOWN_SUMMARY;
            }
            if (w.intent != null && w.intent.label() != null) {
                return CONSULTANT_TOTAL;
            }
            if (w.intent != null && refs.stream().anyMatch(r -> CONSULTANT_TOTAL.equals(r.section))) {
                return CONSULTANT_TOTAL;
            }
            return OTHER;
        }

        private String actualProduct(Work w, Intent intent) {
            if (w.ast != null) {
                for (Term t : terms(w.ast)) {
                    Measure m = t.measure(data);
                    if (m != null && m.kind() == Kind.PRODUCT) {
                        return m.productCode();
                    }
                }
            }
            return intent != null && intent.measure().kind() == Kind.PRODUCT ? intent.measure().productCode() : null;
        }

        private String defect(Work w, Intent intent, List<Work> refs) {
            if (w.raw.kind() == RawCell.Kind.ERROR) {
                if ("#REF!".equals(w.raw.error()) || (w.ast != null && Formula.containsRefError(w.ast))) {
                    return REF_ERROR;
                }
                return "#VALUE!".equals(w.raw.error()) ? VALUE_ERROR : UNEXPLAINED;
            }
            boolean differs = Boolean.FALSE.equals(w.matches);
            if (w.ast == null) {
                return differs ? UNEXPLAINED : null;
            }
            if (Formula.containsRefError(w.ast)) {
                return REF_ERROR;
            }
            if (intent != null && intent.measure().kind() == Kind.TARGET) {
                if (!differs) {
                    return usesGreaterThan(w.ast) ? THRESHOLD_GREATER_THAN : null;
                }
                if (usesGreaterThan(w.ast) && Objects.equals(text(w.raw), evaluateTarget(w, false))) {
                    return THRESHOLD_GREATER_THAN;
                }
                return inherited(refs);
            }
            List<Term> terms = terms(w.ast);
            List<Work> summed = pureSumRefs(w.ast);
            String structural = null;
            if (intent != null && !terms.isEmpty()) {
                structural = termStructure(intent, terms);
            } else if (intent != null && summed != null) {
                structural = sumStructure(intent, summed);
            }
            if (structural != null || !differs) {
                return structural;
            }
            if (intent != null && !terms.isEmpty()) {
                for (Term t : terms) {
                    if (t.function.equals("COUNT") && intent.measure().kind() == Kind.COUNT
                            && w.raw.kind() == RawCell.Kind.NUMBER && w.raw.number().compareTo(w.computed) < 0) {
                        return TEXT_ID_NOT_COUNTED;
                    }
                }
                return UNEXPLAINED;
            }
            if (intent != null && summed != null) {
                return sumDefect(w, intent, summed);
            }
            return inherited(refs);
        }

        private String termStructure(Intent intent, List<Term> terms) {
            for (Term t : terms) {
                if (t.day == null || !intent.days().contains(t.day)) {
                    return WRONG_SHEET;
                }
            }
            for (Term t : terms) {
                if (t.criteria != null && !criteriaColumnFits(t, intent.measure())) {
                    return WRONG_CRITERIA_COLUMN;
                }
            }
            for (Term t : terms) {
                if (t.criterion != null && intent.label() != null
                        && !intent.label().equals(criterionText(t.criterion))) {
                    return ROW_OFFSET;
                }
            }
            for (Term t : terms) {
                if (!sumColumnFits(t, intent.measure())) {
                    return WRONG_COLUMN;
                }
            }
            return null;
        }

        private String sumStructure(Intent intent, List<Work> summed) {
            if (intent.label() != null && summed.stream().anyMatch(r -> r.intent != null
                    && !intent.label().equals(r.intent.label()))) {
                return ROW_OFFSET;
            }
            Set<DayOfWeek> covered = EnumSet.noneOf(DayOfWeek.class);
            summed.stream().filter(r -> r.intent != null).forEach(r -> covered.addAll(r.intent.days()));
            if (!covered.containsAll(intent.days()) || (intent.label() == null && leavesOutRows(summed))) {
                return RANGE_INCOMPLETE;
            }
            return null;
        }

        private String sumDefect(Work w, Intent intent, List<Work> summed) {
            String inherited = inherited(summed);
            if (!UNEXPLAINED.equals(inherited)) {
                return inherited;
            }
            if (intent.label() == null && summed.stream().allMatch(r -> r.intent != null && r.intent.label() != null)) {
                Set<String> listed = new HashSet<>();
                BigDecimal added = BigDecimal.ZERO;
                for (Work r : summed) {
                    listed.add(r.intent.label());
                    added = added.add(r.computed == null ? BigDecimal.ZERO : r.computed);
                }
                BigDecimal unlisted = data.unlisted(intent, listed);
                if (unlisted.signum() != 0 && round(added.add(unlisted)).compareTo(round(w.computed)) == 0) {
                    return BLANK_CONSULTANT_EXCLUDED;
                }
            }
            return UNEXPLAINED;
        }

        private boolean leavesOutRows(List<Work> summed) {
            Map<Integer, Set<Integer>> rowsByColumn = new HashMap<>();
            for (Work r : summed) {
                rowsByColumn.computeIfAbsent(r.col, k -> new HashSet<>()).add(r.row);
            }
            for (var e : rowsByColumn.entrySet()) {
                int col = e.getKey();
                Set<Integer> rows = e.getValue();
                Work sample = summed.stream().filter(r -> r.col == col).findFirst().orElseThrow();
                int min = rows.stream().min(Integer::compare).orElseThrow();
                int max = rows.stream().max(Integer::compare).orElseThrow();
                for (int r = min; r <= max; r++) {
                    if (!rows.contains(r) && sameLine(r, col, sample)) {
                        return true;
                    }
                }
                if (sameLine(min - 1, col, sample) || sameLine(max + 1, col, sample)) {
                    return true;
                }
            }
            return false;
        }

        private boolean sameLine(int row, int col, Work sample) {
            if (row < 0 || sample.intent == null || sample.intent.label() == null) {
                return false;
            }
            Intent other = intentAt(row, col);
            return other != null && other.label() != null && other.measure().equals(sample.intent.measure())
                    && other.days().equals(sample.intent.days());
        }

        private Intent intentAt(int row, int col) {
            Work w = work.get(key(row, col));
            if (w != null) {
                resolve(w);
                return w.intent;
            }
            Pos p = positions.get(key(row, col));
            return p != null && p.inBlock ? p.intent : null;
        }

        private String inherited(List<Work> refs) {
            Map<String, Integer> votes = new LinkedHashMap<>();
            for (Work r : refs) {
                boolean wrong = Boolean.FALSE.equals(r.matches) || r.raw.kind() == RawCell.Kind.ERROR;
                if (r.defect != null && wrong) {
                    votes.merge(r.defect, 1, Integer::sum);
                }
            }
            return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse(UNEXPLAINED);
        }

        private boolean criteriaColumnFits(Term t, Measure m) {
            if (t.criteria.refError() || t.criteria.width() != 1) {
                return false;
            }
            String mapping = data.mapping(t.day, t.criteria.col1());
            if (m.kind() == Kind.BUDGET) {
                return "FIELD:PRIMARY_BUDGET_LIST".equals(mapping) || "FIELD:SECONDARY_BUDGET_LIST".equals(mapping);
            }
            return "FIELD:CONSULTANT".equals(mapping);
        }

        private boolean sumColumnFits(Term t, Measure m) {
            if (t.function.equals("COUNTIF") || t.function.equals("COUNT") || t.function.equals("COUNTA")) {
                return m.kind() == Kind.COUNT;
            }
            if (t.sum == null || t.sum.refError() || t.sum.width() != 1) {
                return false;
            }
            String mapping = data.mapping(t.day, t.sum.col1());
            return switch (m.kind()) {
                case PRODUCT -> ("PRODUCT:" + m.productCode()).equals(mapping);
                case QUOTATION -> "FIELD:QUOTATION".equals(mapping);
                case BANKED -> "FIELD:BANKED".equals(mapping);
                case BUDGET -> {
                    String list = t.criteria == null ? null : data.mapping(t.day, t.criteria.col1());
                    yield ("FIELD:PRIMARY_BUDGET_LIST".equals(list) && "FIELD:PRIMARY_LOAN_AMOUNT".equals(mapping))
                            || ("FIELD:SECONDARY_BUDGET_LIST".equals(list)
                                    && "FIELD:SECONDARY_LOAN_AMOUNT".equals(mapping));
                }
                default -> false;
            };
        }

        private String criterionText(Node criterion) {
            if (criterion instanceof Text t) {
                return ImportDictionary.normalize(t.value());
            }
            if (criterion instanceof Ref r && r.singleCell() && isOwnSheet(r.sheet())) {
                return ImportDictionary.normalize(text(sheet.cell(r.row1(), r.col1())));
            }
            return null;
        }

        static final class Term {
            String function;
            DayOfWeek day;
            Ref criteria;
            Node criterion;
            Ref sum;

            Measure measure(Data data) {
                if (function.equals("COUNTIF") || function.equals("COUNT") || function.equals("COUNTA")) {
                    return Measure.of(Kind.COUNT);
                }
                if (sum == null || sum.refError() || day == null) {
                    return null;
                }
                String mapping = data.mapping(day, sum.col1());
                if (mapping == null) {
                    return null;
                }
                if (mapping.startsWith("PRODUCT:")) {
                    return new Measure(Kind.PRODUCT, mapping.substring("PRODUCT:".length()));
                }
                return switch (mapping) {
                    case "FIELD:QUOTATION" -> Measure.of(Kind.QUOTATION);
                    case "FIELD:BANKED" -> Measure.of(Kind.BANKED);
                    case "FIELD:PRIMARY_LOAN_AMOUNT", "FIELD:SECONDARY_LOAN_AMOUNT" -> Measure.of(Kind.BUDGET);
                    default -> null;
                };
            }
        }

        private List<Term> terms(Node n) {
            List<Term> out = new ArrayList<>();
            collectTerms(n, out);
            return out;
        }

        private void collectTerms(Node n, List<Term> out) {
            switch (n) {
                case Call c -> {
                    String f = c.name();
                    List<Node> a = c.args();
                    if ((f.equals("SUMIF") || f.equals("COUNTIF")) && a.size() >= 2 && a.get(0) instanceof Ref crit
                            && !isOwnSheet(crit.sheet())) {
                        Term t = new Term();
                        t.function = f;
                        t.day = data.dayOfSheet(crit.sheet());
                        t.criteria = crit;
                        t.criterion = a.get(1);
                        if (f.equals("SUMIF")) {
                            t.sum = a.size() >= 3 && a.get(2) instanceof Ref s ? s : crit;
                            if (t.sum.sheet() != null && !t.sum.sheet().equalsIgnoreCase(crit.sheet())) {
                                t.day = null;
                            }
                        }
                        out.add(t);
                        return;
                    }
                    if (f.equals("SUM") || f.equals("COUNT") || f.equals("COUNTA")) {
                        for (Node arg : a) {
                            if (arg instanceof Ref r && !isOwnSheet(r.sheet())) {
                                Term t = new Term();
                                t.function = f;
                                t.day = data.dayOfSheet(r.sheet());
                                t.sum = r;
                                out.add(t);
                            } else {
                                collectTerms(arg, out);
                            }
                        }
                        return;
                    }
                    a.forEach(arg -> collectTerms(arg, out));
                }
                case Unary u -> collectTerms(u.operand(), out);
                case Binary b -> {
                    collectTerms(b.left(), out);
                    collectTerms(b.right(), out);
                }
                case Ref r -> {
                    if (!isOwnSheet(r.sheet())) {
                        Term t = new Term();
                        t.function = "REF";
                        t.day = data.dayOfSheet(r.sheet());
                        t.sum = r;
                        out.add(t);
                    }
                }
                default -> {
                }
            }
        }

        private boolean hasDayTerms(Node n) {
            return !terms(n).isEmpty();
        }

        private List<Work> localRefs(Node n) {
            List<Work> out = new ArrayList<>();
            collectLocal(n, out);
            return out;
        }

        private void collectLocal(Node n, List<Work> out) {
            switch (n) {
                case Ref r -> {
                    if (isOwnSheet(r.sheet()) && !r.refError() && !r.wholeColumns()
                            && (long) (r.row2() - r.row1() + 1) * r.width() <= MAX_RANGE_CELLS) {
                        for (int row = r.row1(); row <= r.row2(); row++) {
                            for (int col = r.col1(); col <= r.col2(); col++) {
                                Work w = work.get(key(row, col));
                                if (w != null) {
                                    out.add(w);
                                }
                            }
                        }
                    }
                }
                case Call c -> c.args().forEach(a -> collectLocal(a, out));
                case Unary u -> collectLocal(u.operand(), out);
                case Binary b -> {
                    collectLocal(b.left(), out);
                    collectLocal(b.right(), out);
                }
                default -> {
                }
            }
        }

        private List<Work> pureSumRefs(Node n) {
            List<Work> out = new ArrayList<>();
            return pureSum(n, out) && !out.isEmpty() ? out : null;
        }

        private boolean pureSum(Node n, List<Work> out) {
            return switch (n) {
                case Binary b -> b.op().equals("+") && pureSum(b.left(), out) && pureSum(b.right(), out);
                case Unary u -> u.op() == '+' && pureSum(u.operand(), out);
                case Call c -> c.name().equals("SUM") && c.args().stream().allMatch(a -> pureSum(a, out));
                case Ref r -> {
                    if (!isOwnSheet(r.sheet()) || r.refError() || r.wholeColumns()
                            || (long) (r.row2() - r.row1() + 1) * r.width() > MAX_RANGE_CELLS) {
                        yield false;
                    }
                    for (int row = r.row1(); row <= r.row2(); row++) {
                        for (int col = r.col1(); col <= r.col2(); col++) {
                            Work w = work.get(key(row, col));
                            RawCell typed = sheet.cell(row, col);
                            if (w != null) {
                                out.add(w);
                            } else if (typed != null && typed.kind() == RawCell.Kind.NUMBER) {
                                yield false;
                            }
                        }
                    }
                    yield true;
                }
                default -> false;
            };
        }

        private boolean usesGreaterThan(Node n) {
            return switch (n) {
                case Binary b -> b.op().equals(">") || b.op().equals("<") || usesGreaterThan(b.left())
                        || usesGreaterThan(b.right());
                case Call c -> c.args().stream().anyMatch(this::usesGreaterThan);
                case Unary u -> usesGreaterThan(u.operand());
                default -> false;
            };
        }

        private String evaluateTarget(Work w, boolean atLeast) {
            if (w.ast == null) {
                return null;
            }
            Object v = evaluate(w.ast, true, atLeast);
            return v instanceof String s ? s : null;
        }

        private Object evaluate(Node n, boolean intended, boolean atLeast) {
            try {
                return eval(n, intended, atLeast);
            } catch (ArithmeticException | IllegalStateException e) {
                return null;
            }
        }

        private Object eval(Node n, boolean intended, boolean atLeast) {
            return switch (n) {
                case Num x -> x.value();
                case Text t -> t.value();
                case Bool b -> b.value();
                case Err e -> throw new IllegalStateException(e.code());
                case Ref r -> {
                    if (!r.singleCell() || !isOwnSheet(r.sheet())) {
                        throw new IllegalStateException("range");
                    }
                    yield cellValue(r.row1(), r.col1(), intended);
                }
                case Unary u -> {
                    BigDecimal x = number(eval(u.operand(), intended, atLeast));
                    yield switch (u.op()) {
                        case '-' -> x.negate();
                        case '%' -> x.divide(BigDecimal.valueOf(100));
                        default -> x;
                    };
                }
                case Binary b -> binary(b, intended, atLeast);
                case Call c -> call(c, intended, atLeast);
            };
        }

        private Object binary(Binary b, boolean intended, boolean atLeast) {
            Object l = eval(b.left(), intended, atLeast);
            Object r = eval(b.right(), intended, atLeast);
            switch (b.op()) {
                case "&":
                    return String.valueOf(l) + r;
                case "=", "<>", "<", "<=", ">", ">=": {
                    int cmp;
                    if (l instanceof BigDecimal x && r instanceof BigDecimal y) {
                        cmp = x.compareTo(y);
                    } else {
                        cmp = String.valueOf(l).compareToIgnoreCase(String.valueOf(r));
                    }
                    return switch (b.op()) {
                        case "=" -> cmp == 0;
                        case "<>" -> cmp != 0;
                        case "<" -> atLeast ? cmp <= 0 : cmp < 0;
                        case "<=" -> cmp <= 0;
                        case ">" -> atLeast ? cmp >= 0 : cmp > 0;
                        default -> cmp >= 0;
                    };
                }
                default:
                    break;
            }
            BigDecimal x = number(l);
            BigDecimal y = number(r);
            return switch (b.op()) {
                case "+" -> x.add(y);
                case "-" -> x.subtract(y);
                case "*" -> x.multiply(y);
                case "/" -> x.divide(y, 10, RoundingMode.HALF_UP);
                case "^" -> x.pow(y.intValueExact());
                default -> throw new IllegalStateException(b.op());
            };
        }

        private Object call(Call c, boolean intended, boolean atLeast) {
            switch (c.name()) {
                case "SUM": {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (Node a : c.args()) {
                        if (a instanceof Ref r && isOwnSheet(r.sheet()) && !r.refError() && !r.wholeColumns()) {
                            for (int row = r.row1(); row <= r.row2(); row++) {
                                for (int col = r.col1(); col <= r.col2(); col++) {
                                    if (cellValue(row, col, intended) instanceof BigDecimal v) {
                                        sum = sum.add(v);
                                    }
                                }
                            }
                        } else {
                            sum = sum.add(number(eval(a, intended, atLeast)));
                        }
                    }
                    return sum;
                }
                case "IF": {
                    if (c.args().isEmpty()) {
                        throw new IllegalStateException("IF");
                    }
                    Object test = eval(c.args().get(0), intended, atLeast);
                    boolean yes = test instanceof Boolean t ? t : number(test).signum() != 0;
                    if (yes) {
                        return c.args().size() > 1 ? eval(c.args().get(1), intended, atLeast) : Boolean.TRUE;
                    }
                    return c.args().size() > 2 ? eval(c.args().get(2), intended, atLeast) : Boolean.FALSE;
                }
                case "ROUND": {
                    BigDecimal x = number(eval(c.args().get(0), intended, atLeast));
                    int places = c.args().size() > 1 ? number(eval(c.args().get(1), intended, atLeast)).intValue() : 0;
                    return x.setScale(places, RoundingMode.HALF_UP);
                }
                default:
                    throw new IllegalStateException(c.name());
            }
        }

        private static BigDecimal number(Object v) {
            if (v instanceof BigDecimal n) {
                return n;
            }
            if (v instanceof Boolean b) {
                return b ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            if (v == null) {
                return BigDecimal.ZERO;
            }
            throw new IllegalStateException("text in arithmetic");
        }

        private Object cellValue(int row, int col, boolean intended) {
            Work w = work.get(key(row, col));
            if (intended) {
                if (w != null) {
                    resolve(w);
                    if (w.resolving) {
                        throw new IllegalStateException("circular");
                    }
                    if (w.computed != null) {
                        return w.computed;
                    }
                    if (w.computedText != null) {
                        return w.computedText;
                    }
                } else {
                    Intent i = intentAt(row, col);
                    if (i != null && i.measure().numeric()) {
                        return data.value(i);
                    }
                }
            }
            RawCell c = sheet.cell(row, col);
            if (c == null) {
                return BigDecimal.ZERO;
            }
            return switch (c.kind()) {
                case NUMBER -> c.number();
                case TEXT -> c.text();
                case BOOLEAN -> c.bool();
                case ERROR -> throw new IllegalStateException(c.error());
            };
        }

        private ReportFigure figure(Work w) {
            Pos p = positions.get(key(w.row, w.col));
            boolean placed = p != null && p.inBlock;
            String rowLabel = placed ? p.rowLabel : rowLabel(w.row, w.col);
            String columnLabel = placed ? p.columnLabel : columnLabel(w.row, w.col);
            Intent i = w.intent;
            String consultant = null;
            String structure = null;
            if (i != null && i.label() != null) {
                if (i.measure().labelKind() == LabelKind.BUDGET_LIST) {
                    structure = rowLabel;
                } else {
                    consultant = rowLabel;
                }
            }
            BigDecimal sheetValue = w.raw.kind() == RawCell.Kind.NUMBER ? round(w.raw.number()) : null;
            String sheetText = switch (w.raw.kind()) {
                case TEXT -> truncate(w.raw.text(), 100);
                case BOOLEAN -> w.raw.bool() ? "TRUE" : "FALSE";
                default -> null;
            };
            String sheetError = w.raw.kind() == RawCell.Kind.ERROR && w.raw.error() != null
                    && w.raw.error().matches("^#[A-Z0-9/]+[!?]?$") ? w.raw.error() : null;
            if (w.raw.kind() == RawCell.Kind.ERROR && sheetError == null) {
                sheetError = "#VALUE!";
            }
            BigDecimal computed = w.computed == null ? null : round(w.computed);
            BigDecimal difference = sheetValue != null && computed != null ? sheetValue.subtract(computed) : null;
            String formula = w.raw.formula() == null ? null
                    : truncate("=" + w.raw.formula().replaceFirst("^=", ""), 2000);
            return new ReportFigure(name, w.section == null ? OTHER : w.section,
                    truncate(placed ? p.blockTitle : null, 100), truncate(rowLabel, 200), truncate(columnLabel, 100),
                    i == null ? null : i.singleDay(), truncate(consultant, 200), w.productCode,
                    truncate(structure, 200), Formula.cellRef(w.row, w.col), sheetValue, sheetText, sheetError,
                    formula, computed, truncate(w.computedText, 100), difference, w.matches, w.defect);
        }

        private String rowLabel(int row, int col) {
            Map<Integer, RawCell> r = sheet.rows().get(row);
            if (r == null) {
                return null;
            }
            String found = null;
            for (var e : r.entrySet()) {
                if (e.getKey() >= col) {
                    break;
                }
                String t = text(e.getValue());
                if (t != null) {
                    found = t;
                }
            }
            return found;
        }

        private String columnLabel(int row, int col) {
            for (int r = row - 1; r >= Math.max(0, row - 10); r--) {
                String t = text(sheet.cell(r, col));
                if (t != null) {
                    return t;
                }
            }
            return null;
        }

        private Pos pos(int row, int col) {
            return positions.computeIfAbsent(key(row, col), k -> new Pos());
        }

        private boolean isOwnSheet(String sheetName) {
            return sheetName == null || sheetName.equalsIgnoreCase(name);
        }

        private DayOfWeek exactDay(String text) {
            if (text == null) {
                return null;
            }
            String t = ImportDictionary.normalize(text);
            Optional<DayOfWeek> d = dictionary.day(t);
            if (d.isPresent()) {
                return d.get();
            }
            try {
                return DayOfWeek.valueOf(t);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private DayOfWeek headerDay(String text) {
            if (text == null) {
                return null;
            }
            for (String word : ImportDictionary.normalize(text).split("[^A-Z]+")) {
                DayOfWeek d = word.isEmpty() ? null : exactDay(word);
                if (d != null) {
                    return d;
                }
            }
            return null;
        }
    }

    static long key(int row, int col) {
        return ((long) row << 20) | col;
    }

    static boolean isTotal(String text) {
        String t = ImportDictionary.normalize(text);
        return t != null && t.startsWith("TOTAL");
    }

    static String text(RawCell c) {
        if (c == null || c.kind() != RawCell.Kind.TEXT) {
            return null;
        }
        String t = c.text().strip();
        return t.isEmpty() ? null : t;
    }

    static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
