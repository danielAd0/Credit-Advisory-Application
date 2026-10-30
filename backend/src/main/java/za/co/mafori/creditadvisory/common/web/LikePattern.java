package za.co.mafori.creditadvisory.common.web;

import java.util.Locale;

/**
 * Builds safe "contains" patterns for LIKE queries (use with escape '\' in the
 * query).
 */
public final class LikePattern {

    private LikePattern() {
    }

    /**
     * " Thandi " becomes "%thandi%", with LIKE wildcards in the input escaped. Null
     * if blank.
     */
    public static String contains(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
