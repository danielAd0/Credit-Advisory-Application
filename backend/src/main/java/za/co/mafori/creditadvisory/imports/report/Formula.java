package za.co.mafori.creditadvisory.imports.report;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Formula {

    private Formula() {
    }

    public sealed interface Node permits Num, Text, Bool, Ref, Call, Unary, Binary, Err {
    }

    public record Num(BigDecimal value) implements Node {
    }

    public record Text(String value) implements Node {
    }

    public record Bool(boolean value) implements Node {
    }

    public record Err(String code) implements Node {
    }

    public record Ref(String sheet, int col1, int row1, int col2, int row2, boolean refError) implements Node {

        public boolean wholeColumns() {
            return row1 < 0;
        }

        public boolean singleCell() {
            return !refError && row1 >= 0 && col1 == col2 && row1 == row2;
        }

        public int width() {
            return col2 - col1 + 1;
        }
    }

    public record Call(String name, List<Node> args) implements Node {

        public Call {
            args = List.copyOf(args);
        }
    }

    public record Unary(char op, Node operand) implements Node {
    }

    public record Binary(String op, Node left, Node right) implements Node {
    }

    private static final Pattern REF = Pattern.compile(
            "(?:('(?:[^']|'')+'|[A-Za-z_][A-Za-z0-9_.]*)!)?"
                    + "(#REF!|\\$?([A-Za-z]{1,3})\\$?(\\d+)(?::\\$?([A-Za-z]{1,3})\\$?(\\d+))?"
                    + "|\\$?([A-Za-z]{1,3}):\\$?([A-Za-z]{1,3}))");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?|\\.\\d+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*");
    private static final Pattern ERROR = Pattern.compile("#(?:REF!|VALUE!|DIV/0!|NAME\\?|N/A|NUM!|NULL!)");

    public static Optional<Node> parse(String formula) {
        if (formula == null || formula.isBlank()) {
            return Optional.empty();
        }
        String f = formula.strip();
        if (f.startsWith("=")) {
            f = f.substring(1);
        }
        try {
            Parser p = new Parser(f);
            Node n = p.comparison();
            p.skipSpaces();
            return p.pos == f.length() ? Optional.of(n) : Optional.empty();
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        void skipSpaces() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        boolean eat(String token) {
            skipSpaces();
            if (s.startsWith(token, pos)) {
                pos += token.length();
                return true;
            }
            return false;
        }

        Node comparison() {
            Node left = concat();
            while (true) {
                skipSpaces();
                String op = null;
                for (String o : new String[] { "<=", ">=", "<>", "=", "<", ">" }) {
                    if (s.startsWith(o, pos)) {
                        op = o;
                        break;
                    }
                }
                if (op == null) {
                    return left;
                }
                pos += op.length();
                left = new Binary(op, left, concat());
            }
        }

        Node concat() {
            Node left = additive();
            while (eat("&")) {
                left = new Binary("&", left, additive());
            }
            return left;
        }

        Node additive() {
            Node left = multiplicative();
            while (true) {
                if (eat("+")) {
                    left = new Binary("+", left, multiplicative());
                } else if (eat("-")) {
                    left = new Binary("-", left, multiplicative());
                } else {
                    return left;
                }
            }
        }

        Node multiplicative() {
            Node left = power();
            while (true) {
                if (eat("*")) {
                    left = new Binary("*", left, power());
                } else if (eat("/")) {
                    left = new Binary("/", left, power());
                } else {
                    return left;
                }
            }
        }

        Node power() {
            Node left = unary();
            while (eat("^")) {
                left = new Binary("^", left, unary());
            }
            return left;
        }

        Node unary() {
            if (eat("-")) {
                return new Unary('-', unary());
            }
            if (eat("+")) {
                return new Unary('+', unary());
            }
            Node n = primary();
            while (eat("%")) {
                n = new Unary('%', n);
            }
            return n;
        }

        Node primary() {
            skipSpaces();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(pos);
            if (c == '(') {
                pos++;
                Node n = comparison();
                expect(")");
                return n;
            }
            if (c == '"') {
                return text();
            }
            Matcher err = ERROR.matcher(s).region(pos, s.length());
            if (err.lookingAt()) {
                pos = err.end();
                return new Err(err.group());
            }
            Matcher ref = REF.matcher(s).region(pos, s.length());
            if (ref.lookingAt() && !followedBy(ref.end(), '(')) {
                pos = ref.end();
                return reference(ref);
            }
            Matcher num = NUMBER.matcher(s).region(pos, s.length());
            if (num.lookingAt()) {
                pos = num.end();
                return new Num(new BigDecimal(num.group()));
            }
            Matcher name = NAME.matcher(s).region(pos, s.length());
            if (name.lookingAt()) {
                String id = name.group().toUpperCase(Locale.ROOT);
                pos = name.end();
                if (eat("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!eat(")")) {
                        do {
                            args.add(comparison());
                        } while (eat(","));
                        expect(")");
                    }
                    return new Call(id, args);
                }
                if (id.equals("TRUE") || id.equals("FALSE")) {
                    return new Bool(id.equals("TRUE"));
                }
            }
            throw new IllegalArgumentException("unexpected " + c);
        }

        private boolean followedBy(int at, char c) {
            int i = at;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return i < s.length() && s.charAt(i) == c;
        }

        private Node text() {
            StringBuilder sb = new StringBuilder();
            pos++;
            while (true) {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("unterminated text");
                }
                char ch = s.charAt(pos++);
                if (ch == '"') {
                    if (pos < s.length() && s.charAt(pos) == '"') {
                        sb.append('"');
                        pos++;
                    } else {
                        return new Text(sb.toString());
                    }
                } else {
                    sb.append(ch);
                }
            }
        }

        private void expect(String token) {
            if (!eat(token)) {
                throw new IllegalArgumentException("expected " + token);
            }
        }

        private static Node reference(Matcher m) {
            String sheet = m.group(1);
            if (sheet != null && sheet.startsWith("'")) {
                sheet = sheet.substring(1, sheet.length() - 1).replace("''", "'");
            }
            if (m.group(2).equals("#REF!")) {
                return new Ref(sheet, -1, -1, -1, -1, true);
            }
            if (m.group(3) != null) {
                int c1 = column(m.group(3));
                int r1 = Integer.parseInt(m.group(4)) - 1;
                int c2 = m.group(5) != null ? column(m.group(5)) : c1;
                int r2 = m.group(6) != null ? Integer.parseInt(m.group(6)) - 1 : r1;
                return new Ref(sheet, Math.min(c1, c2), Math.min(r1, r2), Math.max(c1, c2), Math.max(r1, r2), false);
            }
            int c1 = column(m.group(7));
            int c2 = column(m.group(8));
            return new Ref(sheet, Math.min(c1, c2), -1, Math.max(c1, c2), -1, false);
        }
    }

    public static int column(String letters) {
        int n = 0;
        for (char ch : letters.toUpperCase(Locale.ROOT).toCharArray()) {
            n = n * 26 + (ch - 'A' + 1);
        }
        return n - 1;
    }

    public static String columnLetter(int column) {
        StringBuilder sb = new StringBuilder();
        int c = column + 1;
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    public static String cellRef(int row, int column) {
        return columnLetter(column) + (row + 1);
    }

    public static boolean containsRefError(Node n) {
        return switch (n) {
            case Err e -> e.code().equals("#REF!");
            case Ref r -> r.refError();
            case Call c -> c.args().stream().anyMatch(Formula::containsRefError);
            case Unary u -> containsRefError(u.operand());
            case Binary b -> containsRefError(b.left()) || containsRefError(b.right());
            default -> false;
        };
    }
}
