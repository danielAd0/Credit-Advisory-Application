package za.co.mafori.creditadvisory.audit;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import za.co.mafori.creditadvisory.auth.AppUserPrincipal;

@Component
public class AuditLogWriter {

    private static final Pattern THIRTEEN_DIGITS = Pattern.compile("\\d{13}");
    private static final int MAX_TEXT = 500;

    private final JdbcTemplate jdbc;

    public AuditLogWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String targetType, Object targetId, Map<String, ?> metadata) {
        if ((targetType == null) != (targetId == null)) {
            throw new IllegalArgumentException("targetType and targetId must both be set or both be null");
        }
        Map<String, ?> safeMetadata = metadata == null ? Map.<String, Object>of() : metadata;
        jdbc.update("""
                insert into audit_logs (actor_user_id, action, target_type, target_id, metadata)
                values (?, ?, ?, ?, ?::jsonb)
                """,
                currentUserId(), action, targetType, targetId == null ? null : String.valueOf(targetId),
                toJson(safeMetadata));
    }

    /** Convenience for building ordered metadata maps that may contain nulls. */
    public static Map<String, Object> meta(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("meta(...) needs key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return map;
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserPrincipal principal) {
            return principal.userId();
        }
        return null;
    }

    // ---------- minimal JSON writer (only flat, simple values are ever logged)
    // ----------

    static String toJson(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        Iterator<? extends Map.Entry<String, ?>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ?> e = it.next();
            appendString(sb, e.getKey());
            sb.append(':');
            appendValue(sb, e.getValue());
            if (it.hasNext()) {
                sb.append(',');
            }
        }
        return sb.append('}').toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof java.math.BigDecimal
                || value instanceof java.math.BigInteger) {
            sb.append(value);
        } else if (value instanceof Collection<?> collection) {
            sb.append('[');
            Iterator<?> it = collection.iterator();
            while (it.hasNext()) {
                appendValue(sb, it.next());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
        } else {
            String text = THIRTEEN_DIGITS.matcher(String.valueOf(value)).replaceAll("[redacted]");
            appendString(sb, text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        }
    }

    private static void appendString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
