package com.democode.mlmsittu.reporting.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * CSV that Excel opens correctly and cannot be tricked into executing (development plan P6-07).
 *
 * <h2>Formula injection</h2>
 *
 * A spreadsheet treats a cell beginning {@code =}, {@code +}, {@code -}, {@code @}, tab or carriage
 * return as a formula. A customer called {@code =cmd|'/c calc'!A1} is therefore not a name in a
 * cell — it is code that runs when somebody in accounts opens the export, with their privileges,
 * on their machine. The data never had to touch the server's shell to get there; it only had to be
 * typed into a name field and exported.
 *
 * <p>Quoting does not help: Excel strips the quotes and evaluates what is inside. The fix is to
 * make the cell start with something else, so a single quote is prepended. Excel and LibreOffice
 * both read that as "this is text", show the original characters, and evaluate nothing.
 *
 * <h2>Encoding</h2>
 *
 * The file is UTF-8 with a byte-order mark. The BOM is redundant to every other tool and is the
 * only thing that stops Excel on Windows guessing the system code page — which turns Sinhala names
 * into mojibake on the machines this system is actually for.
 */
@Component
public class CsvWriter {

    /** Excel's byte-order mark, written first. */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * Characters that make a spreadsheet treat a cell as a formula.
     *
     * <p>{@code -} is on the list even though negative numbers begin with it. A numeric cell is
     * written by {@link #cell} from a number, never from user text, so the only strings starting
     * with {@code -} are ones somebody typed — and a leading apostrophe on a genuinely negative
     * text field is a far smaller problem than an executing one.
     */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    /**
     * @param headers the header row, written verbatim — these are ours, not user input
     * @param columns one function per header, extracting that column from a row
     */
    public <T> byte[] write(List<String> headers, List<Function<T, Object>> columns, List<T> rows) {
        if (headers.size() != columns.size()) {
            throw new IllegalArgumentException(
                    "CSV has " + headers.size() + " headers but " + columns.size() + " columns");
        }

        StringBuilder out = new StringBuilder();
        appendRow(out, headers.stream().map(header -> (Object) header).toList());

        for (T row : rows) {
            appendRow(out, columns.stream().map(column -> column.apply(row)).toList());
        }

        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, file, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, file, UTF8_BOM.length, body.length);
        return file;
    }

    private void appendRow(StringBuilder out, List<Object> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(cell(values.get(index)));
        }
        // CRLF, because RFC 4180 says so and because Excel on Windows is the target.
        out.append("\r\n");
    }

    /** Renders one value: neutralised if it could be a formula, quoted if it needs to be. */
    String cell(Object value) {
        if (value == null) {
            return "";
        }

        // Numbers and booleans are ours, never user text, so they skip neutralising entirely --
        // otherwise every negative number in the file would arrive with an apostrophe in front.
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        String text = value.toString();
        if (!text.isEmpty() && FORMULA_STARTERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }

        boolean needsQuotes =
                text.indexOf(',') >= 0
                        || text.indexOf('"') >= 0
                        || text.indexOf('\n') >= 0
                        || text.indexOf('\r') >= 0;

        if (!needsQuotes) {
            return text;
        }
        // RFC 4180: a quote inside a quoted field is written twice.
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
