package za.co.mafori.creditadvisory.imports.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import za.co.mafori.creditadvisory.imports.register.ImportDictionary;

public final class TestDictionary {

    private static final Pattern ALIAS = Pattern.compile("\\('([^']*)',\\s*'([^']*)',\\s*'(PRODUCT|FIELD|SHEET)'\\)");
    private static final Pattern PRODUCT = Pattern.compile(
            "\\('([A-Z_]+)',\\s*'([^']+)',\\s*(?:NULL|[0-9.]+),\\s*(TRUE|FALSE),\\s*(TRUE|FALSE),\\s*\\d+\\)");

    private TestDictionary() {
    }

    public static ImportDictionary standard() {
        return ImportDictionary.of(aliases(), products(true));
    }

    /** Same, with one product deactivated. */
    public static ImportDictionary withInactive(String code) {
        List<ImportDictionary.Product> products = new ArrayList<>();
        for (ImportDictionary.Product p : products(true)) {
            products.add(p.code().equals(code) ? new ImportDictionary.Product(p.id(), p.code(), p.name(),
                    p.countsTowardQuotation(), p.countsTowardBanked(), false) : p);
        }
        return ImportDictionary.of(aliases(), products);
    }

    public static List<String[]> aliases() {
        List<String[]> out = new ArrayList<>();
        Matcher m = ALIAS.matcher(insertBlock(read("db/migration/V13__weekly_register_import.sql"),
                "INSERT INTO import_header_aliases"));
        while (m.find()) {
            out.add(new String[] { m.group(1), m.group(2), m.group(3) });
        }
        if (out.size() < 50) {
            throw new IllegalStateException("Could not read the header aliases from V13");
        }
        // Stage 4: report block titles ("CARD").
        Matcher titles = ALIAS.matcher(insertBlock(read("db/migration/V17__report_reconciliation.sql"),
                "INSERT INTO import_header_aliases"));
        while (titles.find()) {
            out.add(new String[] { titles.group(1), titles.group(2), titles.group(3) });
        }
        return out;
    }

    public static List<ImportDictionary.Product> products(boolean active) {
        List<ImportDictionary.Product> out = new ArrayList<>();
        Matcher m = PRODUCT.matcher(insertBlock(read("db/migration/V6__create_sales_register.sql"),
                "INSERT INTO products"));
        long id = 1;
        while (m.find()) {
            out.add(new ImportDictionary.Product(id++, m.group(1), m.group(2), Boolean.parseBoolean(m.group(3)),
                    Boolean.parseBoolean(m.group(4)), active));
        }
        if (out.size() != 15) {
            throw new IllegalStateException("Expected the 15 products seeded by V6, found " + out.size());
        }
        return out;
    }

    /**
     * Only the INSERT statement itself, so CHECK constraints in the same file are
     * not read as rows.
     */
    private static String insertBlock(String sql, String insert) {
        int start = sql.indexOf(insert);
        if (start < 0) {
            throw new IllegalStateException("\"" + insert + "\" not found");
        }
        int end = sql.indexOf(';', start);
        return sql.substring(start, end < 0 ? sql.length() : end);
    }

    private static String read(String resource) {
        try (InputStream in = TestDictionary.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
