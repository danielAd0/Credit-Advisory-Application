package za.co.mafori.creditadvisory.common.pdf;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A small writer for plain, text-only A4 PDF documents: a title, headings,
 * label-and-value lines and wrapped text, with "Page n of m" at the foot of
 * each page.
 *
 * <p>
 * Written by hand instead of with a PDF library so the application gains no new
 * dependency to keep patched. It only uses the two fonts every PDF reader has
 * built in (Helvetica and Helvetica-Bold), so nothing is embedded, and it never
 * compresses, so tests can read the text back from the bytes.
 *
 * <p>
 * Text is written in the Windows-1252 character set, which covers English and
 * the accented letters in South African names. Anything outside it (for
 * example emoji) is shown as "?".
 */
public final class SimplePdf {

    private static final float PAGE_WIDTH = 595; // A4, in points
    private static final float PAGE_HEIGHT = 842;
    private static final float MARGIN = 50;
    private static final float TEXT_WIDTH = PAGE_WIDTH - 2 * MARGIN;
    private static final float LABEL_WIDTH = 150;
    private static final float FOOTER_Y = 30;

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Helvetica's character widths (thousandths of the font size) for the
     * characters 32 (space) to 126 (~), from the standard Adobe font metrics.
     * Any other character counts as 556, the width of a digit.
     */
    private static final int[] HELVETICA = { 278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333,
            278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, 1015, 667, 667,
            722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722, 667, 944,
            667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222,
            833, 556, 556, 556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584 };

    /**
     * Bold letters are a little wider; measuring them 10% wider keeps lines inside
     * the margin.
     */
    private static final float BOLD_FACTOR = 1.1f;

    private final List<ByteArrayOutputStream> pages = new ArrayList<>();
    private ByteArrayOutputStream page;
    private float y;

    public SimplePdf() {
        newPage();
    }

    // ---------- what callers write ----------

    /** The document title, large and bold. */
    public SimplePdf title(String text) {
        for (String line : wrap(text, TEXT_WIDTH, 16, true)) {
            write(line, MARGIN, 16, true, 22);
        }
        return this;
    }

    /** A section heading, with some space above it. */
    public SimplePdf heading(String text) {
        space(10);
        ensureRoom(13 + 3 * 14); // never a heading alone at the foot of a page
        for (String line : wrap(text, TEXT_WIDTH, 13, true)) {
            write(line, MARGIN, 13, true, 18);
        }
        return this;
    }

    /**
     * A smaller bold heading, for one record inside a section (one sale, one case).
     */
    public SimplePdf subheading(String text) {
        space(4);
        ensureRoom(2 * 14);
        for (String line : wrap(text, TEXT_WIDTH, 10, true)) {
            write(line, MARGIN, 10, true, 14);
        }
        return this;
    }

    /**
     * A label and its value on one line; a long value wraps under itself. Null or
     * blank values are skipped.
     */
    public SimplePdf field(String label, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        List<String> lines = wrap(value, TEXT_WIDTH - LABEL_WIDTH, 10, false);
        if (width(label, 10, true) > LABEL_WIDTH - 8) {
            // Too long to sit beside the value: the label gets its own line(s), the value
            // goes under it.
            ensureRoom(2 * 14);
            for (String labelLine : wrap(label, TEXT_WIDTH, 10, true)) {
                write(labelLine, MARGIN, 10, true, 14);
            }
            for (String line : lines) {
                write(line, MARGIN + LABEL_WIDTH, 10, false, 14);
            }
            return this;
        }
        ensureRoom(14);
        writeAt(label, MARGIN, 10, true);
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                ensureRoom(14);
            }
            write(lines.get(i), MARGIN + LABEL_WIDTH, 10, false, 14);
        }
        return this;
    }

    /** Ordinary text, wrapped to the page width. */
    public SimplePdf text(String text) {
        for (String line : wrap(text == null ? "" : text, TEXT_WIDTH, 10, false)) {
            write(line, MARGIN, 10, false, 14);
        }
        return this;
    }

    /** Empty space, in points. */
    public SimplePdf space(float points) {
        y -= points;
        return this;
    }

    // ---------- the finished file ----------

    /** The whole PDF file. */
    public byte[] build() {
        int pageCount = pages.size();
        for (int i = 0; i < pageCount; i++) {
            String footer = "Page " + (i + 1) + " of " + pageCount;
            float width = width(footer, 8, false);
            appendText(pages.get(i), footer, (PAGE_WIDTH - width) / 2, FOOTER_Y, 8, false);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        ascii(out, "%PDF-1.4\n");
        out.write(new byte[] { '%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n' }, 0, 6);

        // 1 catalog, 2 page tree, 3 regular font, 4 bold font, 5 document info, then a
        // page and its
        // content for each page.
        int firstPage = 6;
        List<String> kids = new ArrayList<>();
        for (int i = 0; i < pageCount; i++) {
            kids.add((firstPage + 2 * i) + " 0 R");
        }
        object(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(out, offsets, 2, "<< /Type /Pages /Kids [" + String.join(" ", kids) + "] /Count " + pageCount + " >>");
        object(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        object(out, offsets, 4,
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        object(out, offsets, 5, "<< /Producer (Credit Advisory) >>");
        for (int i = 0; i < pageCount; i++) {
            int pageObject = firstPage + 2 * i;
            object(out, offsets, pageObject, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_WIDTH + " "
                    + (int) PAGE_HEIGHT + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents "
                    + (pageObject + 1) + " 0 R >>");
            byte[] content = pages.get(i).toByteArray();
            offsets.add(out.size());
            ascii(out, (pageObject + 1) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.write(content, 0, content.length);
            ascii(out, "\nendstream\nendobj\n");
        }

        int xref = out.size();
        int size = offsets.size() + 1;
        ascii(out, "xref\n0 " + size + "\n0000000000 65535 f \n");
        for (int offset : offsets) {
            ascii(out, String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset));
        }
        ascii(out, "trailer\n<< /Size " + size + " /Root 1 0 R /Info 5 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    // ---------- layout ----------

    private void newPage() {
        page = new ByteArrayOutputStream();
        pages.add(page);
        y = PAGE_HEIGHT - MARGIN;
    }

    /**
     * Starts a new page when fewer than this many points are left above the footer.
     */
    private void ensureRoom(float points) {
        if (y - points < MARGIN) {
            newPage();
        }
    }

    /** One line at x, then moves down by the line height. */
    private void write(String line, float x, float size, boolean bold, float lineHeight) {
        ensureRoom(lineHeight);
        y -= lineHeight - 4;
        appendText(page, line, x, y, size, bold);
        y -= 4;
    }

    /** A label beside the value that follows it on the same line. */
    private void writeAt(String line, float x, float size, boolean bold) {
        appendText(page, line, x, y - 10, size, bold);
    }

    private static void appendText(ByteArrayOutputStream content, String line, float x, float y, float size,
            boolean bold) {
        ascii(content, "BT /" + (bold ? "F2 " : "F1 ") + number(size) + " Tf " + number(x) + " " + number(y)
                + " Td (");
        for (byte b : encode(line)) {
            int c = b & 0xFF;
            if (c == '(' || c == ')' || c == '\\') {
                content.write('\\');
                content.write(c);
            } else if (c < 32 || c > 126) {
                ascii(content, "\\" + String.format("%03o", c)); // keeps the content stream plain ASCII
            } else {
                content.write(c);
            }
        }
        ascii(content, ") Tj ET\n");
    }

    /**
     * Splits text into lines no wider than maxWidth; a word longer than a line is
     * cut.
     */
    static List<String> wrap(String text, float maxWidth, float size, boolean bold) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace("\r", "").split("\n", -1)) {
            String current = "";
            for (String word : paragraph.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (width(candidate, size, bold) <= maxWidth) {
                    current = candidate;
                    continue;
                }
                if (!current.isEmpty()) {
                    lines.add(current);
                }
                current = word;
                while (width(current, size, bold) > maxWidth) {
                    int cut = current.length() - 1;
                    while (cut > 1 && width(current.substring(0, cut), size, bold) > maxWidth) {
                        cut--;
                    }
                    lines.add(current.substring(0, cut));
                    current = current.substring(cut);
                }
            }
            lines.add(current);
        }
        return lines;
    }

    static float width(String text, float size, boolean bold) {
        float units = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            units += c >= 32 && c <= 126 ? HELVETICA[c - 32] : 556;
        }
        return units * size / 1000 * (bold ? BOLD_FACTOR : 1);
    }

    // ---------- bytes ----------

    private static byte[] encode(String text) {
        CharsetEncoder encoder = WINDOWS_1252.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[] { '?' });
        try {
            ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalStateException(e); // cannot happen with REPLACE
        }
    }

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, int number, String body) {
        offsets.add(out.size());
        ascii(out, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    /** 12, or 12.5: PDF numbers without exponents or locale commas. */
    private static String number(float value) {
        return value == Math.rint(value) ? String.valueOf((int) value)
                : String.format(java.util.Locale.ROOT,
                        "%.2f", value);
    }
}
