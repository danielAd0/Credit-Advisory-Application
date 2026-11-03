package za.co.mafori.creditadvisory.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import za.co.mafori.creditadvisory.support.ApiTestBase;

class WeeklyReportSchemaTest extends ApiTestBase {

    private long consultantA;
    private long consultantB;
    private long jobId;
    private long weekId;
    private long client1;
    private long client2;

    @BeforeEach
    void createWeek() {
        consultantA = createConsultant("C001", "Test One");
        consultantB = createConsultant("C002", "Test Two");
        client1 = createClient("Test", "Alpha", null);
        client2 = createClient("Test", "Bravo", null);
        jobId = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                                         expires_at, import_type, week_starting)
                values ('fake.xlsx', repeat('a', 64), 10, 'PREVIEWED', ?, now() + interval '1 day',
                        'WEEKLY_REGISTER', date '2026-09-14')
                returning id
                """, Long.class, adminId);
        weekId = jdbc.queryForObject(
                "insert into reporting_weeks (week_starting) values (date '2026-09-14') returning id", Long.class);

        // Monday: client 1 with consultant A (sheet says quotation 1900, banked 1725),
        //         client 2 with NO consultant (D2). Tuesday: client 1 again, consultant B.
        long t1 = transaction(client1, consultantA, "2026-09-14", 1, 2, "1900", "1725");
        long t2 = transaction(client2, null, "2026-09-14", 1, 3, "1500", "1500");
        transaction(client1, consultantB, "2026-09-15", 2, 2, "130000", "100000");
        line(t1, "COMBO", "1500");
        line(t1, "INSURANCE", "225");
        line(t1, "MAFORI_CARD", "175");
        line(t2, "COMBO", "1500");
        jdbc.update("update import_jobs set state = 'COMMITTED', committed_at = now(), reporting_week_id = ? where id = ?",
                weekId, jobId);
    }

    private long transaction(long client, Long consultant, String date, int day, int sourceRow, String quotation,
            String banked) {
        return jdbc.queryForObject("""
                insert into client_transactions (client_id, consultant_id, transaction_date, created_by_user_id,
                    import_job_id, source_sheet, source_row_number, reporting_week_id, day_of_week,
                    quotation_amount, banked_amount)
                values (?, ?, cast(? as date), ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, client, consultant, date, adminId, jobId, day == 1 ? "Monday" : "Tuesday",
                sourceRow, weekId, day, new BigDecimal(quotation), new BigDecimal(banked));
    }

    private void line(long transaction, String productCode, String amount) {
        jdbc.update("""
                insert into client_transaction_lines (transaction_id, product_id, amount,
                                                      counts_toward_quotation, counts_toward_banked)
                select ?, id, ?, counts_toward_quotation, counts_toward_banked from products where code = ?
                """, transaction, new BigDecimal(amount), productCode);
    }

    @Test
    void dailySummaryHasAllSevenDaysAndUsesTheSheetValues() {
        List<Map<String, Object>> days = jdbc.queryForList(
                "select * from v_week_daily_summary where reporting_week_id = ? order by day_of_week", weekId);

        assertThat(days).hasSize(7);
        Map<String, Object> monday = days.get(0);
        assertThat(monday.get("transaction_count")).isEqualTo(2L);
        assertThat(monday.get("unattributed_count")).isEqualTo(1L);
        assertThat((BigDecimal) monday.get("quotation_total")).isEqualByComparingTo("3400");
        assertThat((BigDecimal) monday.get("banked_total")).isEqualByComparingTo("3225"); // 1725 from the sheet, not 1900
        assertThat(monday.get("target_met")).isEqualTo(false);
        assertThat(days.get(1).get("target_met")).isEqualTo(true);                          // 130,000 >= 120,000
        assertThat(days.get(6).get("transaction_count")).isEqualTo(0L);
    }

    @Test
    void weekTotalsCountDistinctClientsAndTheWeeklyTarget() {
        Map<String, Object> week = jdbc.queryForMap("select * from v_week_totals where reporting_week_id = ?", weekId);

        assertThat(week.get("transaction_count")).isEqualTo(3L);
        assertThat(week.get("client_count")).isEqualTo(2L);
        assertThat(week.get("consultant_count")).isEqualTo(2L);
        assertThat((BigDecimal) week.get("quotation_total")).isEqualByComparingTo("133400");
        assertThat((BigDecimal) week.get("weekly_quotation_target")).isEqualByComparingTo("600000");
        assertThat(week.get("target_met")).isEqualTo(false);
    }

    @Test
    void ruleBasedTotalsAreAvailableForComparison() {
        Map<String, Object> t = jdbc.queryForMap("""
                select quotation_total, banked_total, banked_by_rule from v_transaction_totals
                where client_id = ? and day_of_week = 1
                """, client1);
        assertThat((BigDecimal) t.get("banked_total")).isEqualByComparingTo("1725");
        assertThat((BigDecimal) t.get("banked_by_rule")).isEqualByComparingTo("1900");
    }

    @Test
    void replacedImportDropsOutOfEveryReport() {
        long replacement = jdbc.queryForObject("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                    expires_at, import_type, week_starting, replaces_import_job_id, replace_reason)
                values ('fake2.xlsx', repeat('b', 64), 10, 'PREVIEWED', ?, now() + interval '1 day',
                        'WEEKLY_REGISTER', date '2026-09-14', ?, 'Corrected Tuesday')
                returning id
                """, Long.class, adminId, jobId);
        jdbc.update("update client_transactions set superseded_by_import_job_id = ? where import_job_id = ?",
                replacement, jobId);

        assertThat(jdbc.queryForObject("select transaction_count from v_week_totals where reporting_week_id = ?",
                Long.class, weekId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from v_week_product_daily", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from client_transactions", Long.class)).isEqualTo(3L);
    }

    @Test
    void transactionMustFallInsideItsWeek() {
        assertThatThrownBy(() -> transaction(client2, null, "2026-09-21", 1, 9, "1", "1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void weekMustStartOnMonday() {
        assertThatThrownBy(() -> jdbc.update("insert into reporting_weeks (week_starting) values (date '2026-09-15')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void replacingAWeekNeedsAReason() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into import_jobs (file_name, file_digest, file_size_bytes, state, uploaded_by_user_id,
                    expires_at, import_type, week_starting, replaces_import_job_id)
                values ('x.xlsx', repeat('c', 64), 1, 'PREVIEWED', ?, now(), 'WEEKLY_REGISTER', date '2026-09-14', ?)
                """, adminId, jobId)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void extrasNeverHoldAnIdNumber() {
        long t = jdbc.queryForObject("select min(id) from client_transactions", Long.class);
        jdbc.update("insert into client_transaction_extras (transaction_id, sheet_header, value_text) values (?, 'Column V', 'SEE NOTE')", t);
        assertThatThrownBy(() -> jdbc.update(
                "insert into client_transaction_extras (transaction_id, sheet_header, value_text) values (?, 'X', ?)",
                t, "1234567890123")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void reportFiguresStoreErrorsAndCannotBeEdited() {
        jdbc.update("""
                insert into weekly_report_figures (import_job_id, reporting_week_id, sheet_name, section, block_title,
                    cell_ref, sheet_error, sheet_formula, defect_code)
                values (?, ?, 'Breakdown', 'BREAKDOWN_SUMMARY', 'POLICY REPLACEMENT', 'M8', '#REF!', '=SUM(I101:I111)', 'REF_ERROR')
                """, jobId, weekId);
        assertThatThrownBy(() -> jdbc.update("update weekly_report_figures set sheet_error = null"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void headerAliasesCoverTheWorkbookSpellings() {
        assertThat(jdbc.queryForObject("select canonical from import_header_aliases where alias = 'GROECRY'",
                String.class)).isEqualTo("GROCERY");
        assertThat(jdbc.queryForObject("select canonical from import_header_aliases where alias = 'WEDNSDAY'",
                String.class)).isEqualTo("WEDNESDAY");
    }
}
