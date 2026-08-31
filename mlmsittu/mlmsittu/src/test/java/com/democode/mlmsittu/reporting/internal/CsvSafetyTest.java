package com.democode.mlmsittu.reporting.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CSV export safety (development plan P6-07, Gate 6).
 *
 * <p>No Spring context: this is a pure function and the whole point is to hammer it with hostile
 * strings, which is much easier when the test starts in milliseconds. It sits in the same package
 * as {@code CsvWriter} so it can reach {@code cell}, which is where the escaping actually lives —
 * testing it only through {@code write} would mean parsing the output back to find out what one
 * cell became.
 */
@DisplayName("CSV export")
class CsvSafetyTest {

    private final CsvWriter csv = new CsvWriter();

    @Test
    @DisplayName("Gate 6 · a field starting with = cannot execute as a formula")
    void formulaeAreNeutralised() {
        // The classic payload. If this reaches a cell intact, opening the export runs it.
        assertThat(csv.cell("=cmd|'/c calc'!A1")).startsWith("'=");

        // Every character a spreadsheet treats as the start of an expression.
        assertThat(csv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(csv.cell("+1")).isEqualTo("'+1");
        assertThat(csv.cell("-1+1")).isEqualTo("'-1+1");
        assertThat(csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(csv.cell("\tSUM")).isEqualTo("'\tSUM");

        // A carriage return needs the apostrophe *and* quoting, so the guard lands inside the
        // quotes rather than in front of them. Both defences apply; neither cancels the other.
        assertThat(csv.cell("\rSUM")).isEqualTo("\"'\rSUM\"");
    }

    @Test
    @DisplayName("ordinary text is left alone")
    void safeTextIsUntouched() {
        assertThat(csv.cell("Nimal Fernando")).isEqualTo("Nimal Fernando");
        assertThat(csv.cell("SLV-001")).isEqualTo("SLV-001");
        assertThat(csv.cell("")).isEmpty();
        assertThat(csv.cell(null)).isEmpty();
    }

    @Test
    @DisplayName("negative numbers are not mangled by the formula guard")
    void numbersKeepTheirSign() {
        // -1 as a number is data, not an expression. Prefixing it would turn every negative
        // quantity in the file into text and break the arithmetic somebody exported it to do.
        assertThat(csv.cell(-42)).isEqualTo("-42");
        assertThat(csv.cell(new java.math.BigDecimal("-1250.50"))).isEqualTo("-1250.50");
        assertThat(csv.cell(true)).isEqualTo("true");
    }

    @Test
    @DisplayName("commas, quotes and newlines are escaped per RFC 4180")
    void separatorsAreEscaped() {
        assertThat(csv.cell("Colombo, Sri Lanka")).isEqualTo("\"Colombo, Sri Lanka\"");
        assertThat(csv.cell("He said \"no\"")).isEqualTo("\"He said \"\"no\"\"\"");
        assertThat(csv.cell("line one\nline two")).isEqualTo("\"line one\nline two\"");
    }

    @Test
    @DisplayName("a hostile value cannot break out of its cell")
    void injectionCannotAddColumns() {
        // A value carrying its own separators must stay one field, or it silently shifts every
        // column after it and the file quietly says something else.
        List<Function<String[], Object>> columns =
                List.of(row -> row[0], row -> row[1]);

        byte[] file =
                csv.write(
                        List.of("Name", "City"),
                        columns,
                        // Type witness: List.of with a lone array spreads it into elements.
                        List.<String[]>of(new String[] {"Evil, Corp\n=1+1", "Colombo"}));

        String text = new String(file, StandardCharsets.UTF_8);

        assertThat(text).contains("\"Evil, Corp\n=1+1\"");
        // Header plus exactly one record: the embedded newline did not become a second row.
        assertThat(text.split("\r\n")).hasSize(2);
    }

    @Test
    @DisplayName("Gate 6 · Sinhala survives the round trip, and Excel is told the encoding")
    void sinhalaIsNotCorrupted() {
        String sinhala = "නිමල් ප්‍රනාන්දු";

        byte[] file =
                csv.write(
                        List.of("Name"),
                        List.of((Function<String, Object>) name -> name),
                        List.of(sinhala));

        // The BOM is the only thing that stops Excel on Windows guessing a code page and turning
        // this into mojibake. Every other tool ignores it.
        assertThat(file[0]).isEqualTo((byte) 0xEF);
        assertThat(file[1]).isEqualTo((byte) 0xBB);
        assertThat(file[2]).isEqualTo((byte) 0xBF);

        assertThat(new String(file, StandardCharsets.UTF_8)).contains(sinhala);
    }

    @Test
    @DisplayName("a mismatched header and column list fails loudly")
    void shapeMismatchIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                csv.write(
                                        List.of("One", "Two"),
                                        List.of((Function<String, Object>) value -> value),
                                        List.of("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
