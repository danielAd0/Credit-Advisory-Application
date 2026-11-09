package za.co.mafori.creditadvisory.imports.register;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;


public final class ImportDictionary {

    /** A product as the parser needs it. */
    public record Product(long id, String code, String name, boolean countsTowardQuotation,
            boolean countsTowardBanked, boolean active) {
    }

    private final Map<String, RegisterField> fields = new HashMap<>();
    private final Map<String, String> productAliases = new HashMap<>();
    private final Map<String, DayOfWeek> sheets = new HashMap<>();
    private final Map<String, Product> productsByCode = new LinkedHashMap<>();

    private ImportDictionary() {
    }

    
    public static ImportDictionary of(Collection<String[]> aliases, Collection<Product> products) {
        ImportDictionary d = new ImportDictionary();
        for (Product p : products) {
            d.productsByCode.put(p.code(), p);
            // Every product is recognised by its code ("MAFORI_CARD" / "MAFORI CARD") and its name.
            d.productAliases.put(normalize(p.code().replace('_', ' ')), p.code());
            d.productAliases.put(normalize(p.name()), p.code());
        }
        for (String[] a : aliases) {
            String alias = normalize(a[0]);
            String canonical = a[1];
            switch (a[2]) {
                case "FIELD" -> {
                    try {
                        d.fields.put(alias, RegisterField.valueOf(canonical));
                    } catch (IllegalArgumentException e) {
                        // A FIELD alias for a field this version does not know: ignored.
                    }
                }
                case "PRODUCT" -> d.productAliases.put(alias, canonical);
                case "SHEET" -> {
                    try {
                        d.sheets.put(alias, DayOfWeek.valueOf(canonical));
                    } catch (IllegalArgumentException e) {
                        // Not a weekday: ignored (the database CHECK only allows weekdays).
                    }
                }
                default -> {
                    // unknown kind: ignored (the database CHECK allows only the three above)
                }
            }
        }
        return d;
    }

    /** Trims, upper-cases and collapses inner spaces: " id  number " becomes "ID NUMBER". */
    public static String normalize(String text) {
        if (text == null) {
            return null;
        }
        return text.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public Optional<RegisterField> field(String header) {
        return Optional.ofNullable(fields.get(normalize(header)));
    }

    /** The product a column header stands for, if any. */
    public Optional<Product> product(String header) {
        String code = productAliases.get(normalize(header));
        return code == null ? Optional.empty() : Optional.ofNullable(productsByCode.get(code));
    }

    public Optional<DayOfWeek> day(String sheetName) {
        return Optional.ofNullable(sheets.get(normalize(sheetName)));
    }

    public Optional<Product> productByCode(String code) {
        return Optional.ofNullable(productsByCode.get(code));
    }
}
