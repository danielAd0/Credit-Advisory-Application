package za.co.mafori.creditadvisory.consultant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Gives new consultants their code: C0001, C0002, ... (V19's sequence). Codes
 * already taken,
 * for example typed in by hand, are skipped. After C9999 the numbers simply get
 * longer
 * (C10000), still within the 32 characters the code may have.
 */
@Component
public class ConsultantCodeGenerator {

    private static final String PREFIX = "C";

    private final JdbcTemplate jdbc;

    public ConsultantCodeGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next() {
        while (true) {
            long n = jdbc.queryForObject("select nextval('consultant_code_seq')", Long.class);
            String code = PREFIX + String.format("%04d", n);
            boolean taken = Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists (select 1 from consultants where consultant_code = ?)", Boolean.class, code));
            if (!taken) {
                return code;
            }
        }
    }
}
