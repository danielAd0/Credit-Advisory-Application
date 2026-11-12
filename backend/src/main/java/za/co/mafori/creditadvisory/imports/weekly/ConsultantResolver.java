package za.co.mafori.creditadvisory.imports.weekly;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.ConsultantRef;
import za.co.mafori.creditadvisory.imports.weekly.WeeklyImportStore.SheetConsultant;


public final class ConsultantResolver {

    /** A consultant the import will create, with the sheet's spellings. */
    public record Registration(String fullName, String email, List<String> otherNames, int rows) {
    }

    /** An existing consultant without an email who gets the sheet's email. */
    public record EmailUpdate(ConsultantRef consultant, String email, int rows) {
    }

    /** A sheet spelling that becomes an alias of an existing consultant. */
    public record AliasAdd(ConsultantRef consultant, String alias) {
    }

    /** A name (and email) that cannot be matched safely. Blocks confirm. */
    public record Conflict(String nameInSheet, String email, String reason, int rows) {
    }

    public record Plan(List<Registration> registrations, List<EmailUpdate> emailUpdates, List<AliasAdd> aliases,
            List<Conflict> conflicts, int namesMatchedByEmail, Set<String> namesDecidedByEmail) {

        /**
         * True when this name is handled by the email rules (registered, matched,
         * or in conflict), so it is never "unmapped".
         */
        public boolean decidedByEmail(String nameInSheet) {
            return namesDecidedByEmail.contains(WeeklyImportStore.key(nameInSheet));
        }

        /** True when confirm will register a new consultant under this sheet name. */
        public boolean registers(String nameInSheet) {
            String key = WeeklyImportStore.key(nameInSheet);
            return registrations.stream().anyMatch(r -> WeeklyImportStore.key(r.fullName()).equals(key)
                    || r.otherNames().stream().anyMatch(o -> WeeklyImportStore.key(o).equals(key)));
        }

        public boolean inConflict(String nameInSheet) {
            String key = WeeklyImportStore.key(nameInSheet);
            return conflicts.stream().anyMatch(c -> WeeklyImportStore.key(c.nameInSheet()).equals(key));
        }
    }

    private ConsultantResolver() {
    }

    
    public static Plan plan(List<SheetConsultant> sheet, Map<String, ConsultantRef> byName,
            Map<String, ConsultantRef> byEmail, Map<Long, String> emailOfConsultant) {
        // name key -> spelling as written, emails seen with it, rows
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, Set<String>> emailsOfName = new LinkedHashMap<>();
        Map<String, Integer> rowsOfName = new HashMap<>();
        Map<String, Integer> rowsOfPair = new HashMap<>();
        for (SheetConsultant s : sheet) {
            String key = WeeklyImportStore.key(s.name());
            spelling.putIfAbsent(key, s.name().strip().replaceAll("\\s+", " "));
            rowsOfName.merge(key, s.rows(), Integer::sum);
            Set<String> emails = emailsOfName.computeIfAbsent(key, k -> new LinkedHashSet<>());
            if (s.email() != null) {
                emails.add(s.email());
                rowsOfPair.merge(key + "\u0000" + s.email(), s.rows(), Integer::sum);
            }
        }

        List<Registration> registrations = new ArrayList<>();
        List<EmailUpdate> emailUpdates = new ArrayList<>();
        List<AliasAdd> aliases = new ArrayList<>();
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> decided = new HashSet<>();
        int matchedByEmail = 0;

        // 1. One name written with two or more different emails: which one is right?
        Map<String, List<String>> namesOfEmail = new LinkedHashMap<>();
        for (var e : emailsOfName.entrySet()) {
            String key = e.getKey();
            if (e.getValue().size() > 1) {
                decided.add(key);
                conflicts.add(new Conflict(spelling.get(key), String.join(", ", e.getValue()),
                        "This name appears with " + e.getValue().size()
                                + " different consultant emails. Use one email per consultant.",
                        rowsOfName.get(key)));
            } else if (e.getValue().size() == 1) {
                namesOfEmail.computeIfAbsent(e.getValue().iterator().next(), x -> new ArrayList<>()).add(key);
            }
        }

        // 2. Each email with the names it came with.
        for (var e : namesOfEmail.entrySet()) {
            String email = e.getKey();
            List<String> names = e.getValue();
            decided.addAll(names);
            int rows = names.stream().mapToInt(k -> rowsOfPair.getOrDefault(k + "\u0000" + email, 0)).sum();
            ConsultantRef owner = byEmail.get(email);

            if (owner != null) {
                boolean clash = false;
                for (String key : names) {
                    ConsultantRef named = byName.get(key);
                    if (named != null && named.id() != owner.id()) {
                        clash = true;
                        conflicts.add(new Conflict(spelling.get(key), email,
                                "The email belongs to " + label(owner) + " but the name matches " + label(named)
                                        + ". Fix the name or the email in the sheet.",
                                rowsOfName.get(key)));
                    }
                }
                if (!clash) {
                    matchedByEmail += names.size();
                    for (String key : names) {
                        if (!byName.containsKey(key)) {
                            aliases.add(new AliasAdd(owner, spelling.get(key)));
                        }
                    }
                }
                continue;
            }

            // A new email: does a name already match someone?
            Map<Long, ConsultantRef> targets = new LinkedHashMap<>();
            for (String key : names) {
                ConsultantRef named = byName.get(key);
                if (named != null) {
                    targets.put(named.id(), named);
                }
            }
            if (targets.size() > 1) {
                for (String key : names) {
                    conflicts.add(new Conflict(spelling.get(key), email,
                            "These names share one email but match different consultants ("
                                    + String.join(", ", targets.values().stream().map(ConsultantRef::fullName).toList())
                                    + ").",
                            rowsOfName.get(key)));
                }
                continue;
            }
            if (targets.size() == 1) {
                ConsultantRef target = targets.values().iterator().next();
                String existing = emailOfConsultant.get(target.id());
                if (existing != null) {
                    for (String key : names) {
                        conflicts.add(new Conflict(spelling.get(key), email,
                                "The name matches " + label(target) + ", whose email is " + existing
                                        + ". Change the email in the sheet, or update the consultant's email first.",
                                rowsOfName.get(key)));
                    }
                    continue;
                }
                emailUpdates.add(new EmailUpdate(target, email, rows));
                matchedByEmail += names.size();
                for (String key : names) {
                    if (!byName.containsKey(key)) {
                        aliases.add(new AliasAdd(target, spelling.get(key)));
                    }
                }
                continue;
            }

            // Nobody: register. The spelling on the most rows becomes the name.
            List<String> ordered = new ArrayList<>(names);
            ordered.sort(Comparator.comparingInt((String k) -> -rowsOfPair.getOrDefault(k + "\u0000" + email, 0))
                    .thenComparing(k -> spelling.get(k)));
            String main = spelling.get(ordered.get(0));
            List<String> others = ordered.subList(1, ordered.size()).stream().map(spelling::get).toList();
            registrations.add(new Registration(main, email, others, rows));
        }

        return new Plan(List.copyOf(registrations), List.copyOf(emailUpdates), List.copyOf(aliases),
                List.copyOf(conflicts), matchedByEmail, Set.copyOf(decided));
    }

    private static String label(ConsultantRef c) {
        return c.fullName() + " (" + c.code() + ")";
    }
}
