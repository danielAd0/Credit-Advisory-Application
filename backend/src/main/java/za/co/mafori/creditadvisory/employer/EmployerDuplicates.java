package za.co.mafori.creditadvisory.employer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds employers that are probably the same employer under different
 * spellings (docs/api-admin.md, section 3, "Duplicate suggestions"). Pure: no
 * database, so every rule has a unit test (EmployerDuplicatesTest).
 *
 * <p>
 * Two names are suggested as the same when they are equal after:
 * <ul>
 * <li>ignoring case, spaces and punctuation ("Shoprite (Pty) Ltd." and
 * "SHOPRITE PTY LTD");</li>
 * <li>dropping company endings at the end of the name: PTY, LTD, LIMITED,
 * PROPRIETARY, CC, INC, NPC, SOC, RF ("SHOPRITE" and "SHOPRITE PTY LTD");</li>
 * <li>reading "&amp;" as AND, and a leading THE as nothing;</li>
 * <li>writing out common short forms: MUNIC as MUNICIPALITY, DEPT as
 * DEPARTMENT and a few more ("PHUMELELA LOCAL MUNIC");</li>
 * <li>ignoring accents ("DECOR" with and without the accent), and ignoring
 * letters that were garbled by a wrong text encoding, so a garbled spelling
 * lands next to the correct one.</li>
 * </ul>
 * Each name gets two keys (one without accents, one without any letter outside
 * plain ASCII). Names that share either key are one group, and so are names
 * linked through another name: garbled ~ accented ~ plain.
 *
 * <p>
 * These are only suggestions. Nothing is merged here: an admin looks at each
 * group and merges with POST /api/employers/{id}/merge.
 */
final class EmployerDuplicates {

    /** Company endings dropped from the end of a name. */
    private static final Set<String> ENDINGS = Set.of("PTY", "LTD", "LIMITED", "PROPRIETARY", "CC", "INC", "NPC",
            "SOC", "RF");

    /** Short forms written out, word by word. */
    private static final Map<String, String> SHORT_FORMS = Map.ofEntries(
            Map.entry("MUN", "MUNICIPALITY"), Map.entry("MUNIC", "MUNICIPALITY"),
            Map.entry("MUNICIP", "MUNICIPALITY"), Map.entry("DEPT", "DEPARTMENT"),
            Map.entry("GOVT", "GOVERNMENT"), Map.entry("DIST", "DISTRICT"), Map.entry("SVCS", "SERVICES"),
            Map.entry("HOSP", "HOSPITAL"));

    private EmployerDuplicates() {
    }

    /**
     * One employer as the grouping sees it.
     *
     * @param clients how many clients have this employer now
     */
    record Candidate(long id, String name, boolean active, boolean needsReview, long clients, long version) {
    }

    /**
     * A group of two or more employers that are probably the same.
     *
     * @param suggestedTarget the one to merge the others into, or null when none
     *                        is active (a merge needs an active target)
     */
    record Group(Candidate suggestedTarget, List<Candidate> employers) {
    }

    /**
     * The groups, biggest first, then by the suggested target's name. Employers
     * inside a group: the suggested target first, then by name.
     */
    static List<Group> groups(List<Candidate> employers) {
        // Union-find over the employers: two that share a key are joined.
        int[] parent = new int[employers.size()];
        Arrays.setAll(parent, i -> i);
        Map<String, Integer> firstWithKey = new HashMap<>();
        for (int i = 0; i < employers.size(); i++) {
            for (String key : keys(employers.get(i).name())) {
                Integer other = firstWithKey.putIfAbsent(key, i);
                if (other != null) {
                    parent[find(parent, i)] = find(parent, other);
                }
            }
        }

        Map<Integer, List<Candidate>> byRoot = new LinkedHashMap<>();
        for (int i = 0; i < employers.size(); i++) {
            byRoot.computeIfAbsent(find(parent, i), r -> new ArrayList<>()).add(employers.get(i));
        }

        List<Group> groups = new ArrayList<>();
        for (List<Candidate> members : byRoot.values()) {
            if (members.size() < 2) {
                continue;
            }
            List<Candidate> ranked = new ArrayList<>(members);
            ranked.sort(BEST_TARGET_FIRST);
            Candidate target = ranked.get(0).active() ? ranked.get(0) : null;
            List<Candidate> ordered = new ArrayList<>(members);
            ordered.sort(Comparator.comparing((Candidate c) -> c != target)
                    .thenComparing(c -> c.name().toUpperCase(Locale.ROOT)).thenComparingLong(Candidate::id));
            groups.add(new Group(target, List.copyOf(ordered)));
        }
        groups.sort(Comparator.comparingInt((Group g) -> -g.employers().size())
                .thenComparing(g -> g.employers().get(0).name().toUpperCase(Locale.ROOT)));
        return groups;
    }

    /**
     * The best one to keep: active, not waiting for review (an employer someone
     * already checked), the most clients, then the oldest.
     */
    private static final Comparator<Candidate> BEST_TARGET_FIRST = Comparator
            .comparing((Candidate c) -> !c.active())
            .thenComparing(Candidate::needsReview)
            .thenComparingLong(c -> -c.clients())
            .thenComparingLong(Candidate::id);

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /**
     * The keys a name is compared by (see the class comment). Empty when the name
     * has no letters or digits left.
     */
    static Set<String> keys(String name) {
        String upper = name.toUpperCase(Locale.ROOT).replace("&", " AND ");
        Set<String> keys = new LinkedHashSet<>();
        String withoutAccents = Normalizer.normalize(upper, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        add(keys, key(withoutAccents.replaceAll("[^\\x00-\\x7F]", "")));
        add(keys, key(upper.replaceAll("[^\\x00-\\x7F]", "")));
        return keys;
    }

    private static void add(Set<String> keys, String key) {
        if (!key.isEmpty()) {
            keys.add(key);
        }
    }

    /**
     * Words only, short forms written out, endings and a leading THE dropped, no
     * spaces.
     */
    private static String key(String asciiUpper) {
        String spaced = asciiUpper.replaceAll("[^A-Z0-9]+", " ").strip();
        if (spaced.isEmpty()) {
            return "";
        }
        List<String> words = new ArrayList<>();
        for (String w : spaced.split(" ")) {
            words.add(SHORT_FORMS.getOrDefault(w, w));
        }
        while (words.size() > 1 && ENDINGS.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        if (words.size() > 1 && words.get(0).equals("THE")) {
            words.remove(0);
        }
        return String.join("", words);
    }
}
