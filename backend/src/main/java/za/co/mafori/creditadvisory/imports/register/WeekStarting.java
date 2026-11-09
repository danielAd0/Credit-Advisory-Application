package za.co.mafori.creditadvisory.imports.register;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public final class WeekStarting {

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    /** "14 - 18 Sept 2026", "14-18 September 2026", "14 Sep 2026", "14 September 2026". */
    private static final Pattern DAY_MONTH_YEAR = Pattern.compile(
            "(\\d{1,2})(?:\\s*[-–—]\\s*\\d{1,2})?\\s+([A-Za-z]{3,9})\\.?\\s+(\\d{4})");
    private static final Pattern ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    private WeekStarting() {
    }

    /** The Monday of the week the file name refers to, if a date can be found in it. */
    public static Optional<LocalDate> guessFromFileName(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        Matcher iso = ISO.matcher(fileName);
        if (iso.find()) {
            return date(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                    Integer.parseInt(iso.group(3)));
        }
        Matcher m = DAY_MONTH_YEAR.matcher(fileName);
        while (m.find()) {
            Integer month = MONTHS.get(m.group(2).substring(0, 3).toLowerCase(Locale.ROOT));
            if (month != null) {
                return date(Integer.parseInt(m.group(3)), month, Integer.parseInt(m.group(1)));
            }
        }
        return Optional.empty();
    }

    /** @throws IllegalArgumentException if the date is not a Monday */
    public static LocalDate requireMonday(LocalDate date) {
        if (date == null || date.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("The reporting week must start on a Monday.");
        }
        return date;
    }

    private static Optional<LocalDate> date(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
    }
}
