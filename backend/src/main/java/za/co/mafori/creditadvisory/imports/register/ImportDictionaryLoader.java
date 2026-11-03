package za.co.mafori.creditadvisory.imports.register;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ImportDictionaryLoader {

    private final JdbcTemplate jdbc;

    public ImportDictionaryLoader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ImportDictionary load() {
        List<String[]> aliases = jdbc.query("select alias, canonical, kind from import_header_aliases",
                (rs, n) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
        List<ImportDictionary.Product> products = jdbc.query("""
                select id, code, name, counts_toward_quotation, counts_toward_banked, active
                from products order by display_order, id
                """, (rs, n) -> new ImportDictionary.Product(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6)));
        return ImportDictionary.of(aliases, products);
    }
}
