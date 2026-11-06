package za.co.mafori.creditadvisory.imports.report;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ReportFigureStore {

    private final JdbcTemplate jdbc;

    public ReportFigureStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long ensureWeek(LocalDate weekStarting) {
        jdbc.update("insert into reporting_weeks (week_starting) values (?) on conflict (week_starting) do nothing",
                weekStarting);
        return jdbc.queryForObject("select id from reporting_weeks where week_starting = ?", Long.class,
                weekStarting);
    }

    public record NewFigure(ReportFigure f, Long consultantId, Long productId, Long structureId) {
    }

    public void insert(long jobId, long weekId, List<NewFigure> figures) {
        if (figures.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(figures.size());
        for (NewFigure n : figures) {
            ReportFigure f = n.f();
            args.add(new Object[] { jobId, weekId, f.sheetName(), f.section(), f.blockTitle(), f.rowLabel(),
                    f.columnLabel(), f.dayOfWeek(), n.consultantId(), n.productId(), n.structureId(), f.cellRef(),
                    f.sheetValue(), f.sheetText(), f.sheetError(), f.formula(), f.computedValue(), f.computedText(),
                    f.difference(), f.matches(), f.defectCode() });
        }
        jdbc.batchUpdate("""
                insert into weekly_report_figures (import_job_id, reporting_week_id, sheet_name, section, block_title,
                    row_label, column_label, day_of_week, consultant_id, product_id, structure_id, cell_ref,
                    sheet_value, sheet_text, sheet_error, sheet_formula, computed_value, computed_text, difference,
                    matches, defect_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
    }

    public void setReconciliation(long jobId, String status, int differences) {
        jdbc.update("update import_jobs set reconciliation_status = ?, reconciliation_differences = ? where id = ?",
                status, differences, jobId);
    }

    public record JobReconciliation(String status, int differences) {
    }

    public Optional<JobReconciliation> jobReconciliation(long jobId) {
        return jdbc.query("""
                select reconciliation_status, reconciliation_differences from import_jobs
                where id = ? and import_type = 'WEEKLY_REGISTER'
                """, (rs, n) -> new JobReconciliation(rs.getString(1), rs.getInt(2)), jobId).stream().findFirst();
    }

    private static final String DIFFERENCE = "(f.matches = false or f.sheet_error is not null)";

    public record SectionCount(String sheetName, String section, long figures, long compared, long differences) {
    }

    public List<SectionCount> sectionCounts(long jobId) {
        return jdbc.query("""
                select f.sheet_name, f.section, count(*), count(f.matches),
                       count(*) filter (where %s)
                from weekly_report_figures f where f.import_job_id = ?
                group by f.sheet_name, f.section order by f.sheet_name desc, f.section
                """.formatted(DIFFERENCE), (rs, n) -> new SectionCount(rs.getString(1), rs.getString(2),
                rs.getLong(3), rs.getLong(4), rs.getLong(5)), jobId);
    }

    public record DefectCount(String defectCode, long figures, long differences) {
    }

    public List<DefectCount> defectCounts(long jobId) {
        return jdbc.query("""
                select f.defect_code, count(*), count(*) filter (where %s) from weekly_report_figures f
                where f.import_job_id = ? and f.defect_code is not null
                group by f.defect_code order by count(*) desc, f.defect_code
                """.formatted(DIFFERENCE), (rs, n) -> new DefectCount(rs.getString(1), rs.getLong(2), rs.getLong(3)),
                jobId);
    }

    public record Figure(long id, String sheetName, String section, String blockTitle, String rowLabel,
            String columnLabel, Integer dayOfWeek, Long consultantId, String productCode, Long structureId,
            String cellRef, BigDecimal sheetValue, String sheetText, String sheetError, String formula,
            BigDecimal computedValue, String computedText, BigDecimal difference, Boolean matches,
            String defectCode) {
    }

    public record Filter(String sheet, String section, String defect, boolean onlyDifferences) {
    }

    public List<Figure> figures(long jobId, Filter filter, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = where(jobId, filter, args);
        args.add(limit);
        args.add(offset);
        return jdbc.query("""
                select f.id, f.sheet_name, f.section, f.block_title, f.row_label, f.column_label, f.day_of_week,
                       f.consultant_id, p.code, f.structure_id, f.cell_ref, f.sheet_value, f.sheet_text,
                       f.sheet_error, f.sheet_formula, f.computed_value, f.computed_text, f.difference, f.matches,
                       f.defect_code
                from weekly_report_figures f left join products p on p.id = f.product_id
                """ + where + " order by f.sheet_name desc, f.id limit ? offset ?", ReportFigureStore::figure,
                args.toArray());
    }

    public long countFigures(long jobId, Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(jobId, filter, args);
        return jdbc.queryForObject("select count(*) from weekly_report_figures f " + where, Long.class,
                args.toArray());
    }

    private static String where(long jobId, Filter filter, List<Object> args) {
        StringBuilder sb = new StringBuilder(" where f.import_job_id = ?");
        args.add(jobId);
        if (filter.sheet() != null) {
            sb.append(" and lower(f.sheet_name) = lower(?)");
            args.add(filter.sheet());
        }
        if (filter.section() != null) {
            sb.append(" and f.section = ?");
            args.add(filter.section());
        }
        if (filter.defect() != null) {
            sb.append(" and f.defect_code = ?");
            args.add(filter.defect());
        }
        if (filter.onlyDifferences()) {
            sb.append(" and ").append(DIFFERENCE);
        }
        return sb.toString();
    }

    private static Figure figure(ResultSet rs, int n) throws SQLException {
        return new Figure(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), nullableInt(rs, 7), nullableLong(rs, 8), rs.getString(9), nullableLong(rs, 10),
                rs.getString(11), rs.getBigDecimal(12), rs.getString(13), rs.getString(14), rs.getString(15),
                rs.getBigDecimal(16), rs.getString(17), rs.getBigDecimal(18), nullableBoolean(rs, 19),
                rs.getString(20));
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static Boolean nullableBoolean(ResultSet rs, int column) throws SQLException {
        boolean v = rs.getBoolean(column);
        return rs.wasNull() ? null : v;
    }
}
