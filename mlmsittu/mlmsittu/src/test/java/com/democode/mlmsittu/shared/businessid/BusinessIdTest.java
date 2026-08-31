package com.democode.mlmsittu.shared.businessid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P4-01, verified exhaustively rather than asserted.
 *
 * <p>A check-digit scheme is only worth having if it demonstrably catches the mistakes it claims
 * to. These tests mutate real IDs every way a human plausibly would and require every one to be
 * rejected — no sampling, no "spot check".
 */
@DisplayName("Business ID")
class BusinessIdTest {

    @Test
    @DisplayName("1,000 IDs are unique, valid, and free of I L O U")
    void generatedIdsAreUniqueAndValid() {
        Set<String> seen = new HashSet<>();

        for (long sequence = 1; sequence <= 1_000; sequence++) {
            String id = BusinessId.encode(sequence);

            assertThat(seen.add(id)).as("duplicate at %d", sequence).isTrue();
            assertThat(BusinessId.isValid(id)).as("%s should be valid", id).isTrue();
            assertThat(id).matches("^SLV-[0-9A-Z]{5}-[0-9A-Z]$");

            // The characters that get misread. Their absence is the reason for this alphabet.
            assertThat(id.substring(4)).doesNotContain("I", "L", "O", "U");
        }
    }

    @Test
    @DisplayName("every single-character change is rejected")
    void everySingleCharacterErrorIsDetected() {
        char[] alphabet = BusinessId.alphabet();
        int checked = 0;

        for (long sequence = 1; sequence <= 200; sequence++) {
            String id = BusinessId.encode(sequence);

            // Positions 4..8 are the body, 10 is the check character. Both must be protected —
            // a corrupted check character is just as much an error as a corrupted body.
            for (int position : new int[] {4, 5, 6, 7, 8, 10}) {
                for (char replacement : alphabet) {
                    if (id.charAt(position) == replacement) {
                        continue;
                    }
                    String mutated =
                            id.substring(0, position) + replacement + id.substring(position + 1);

                    assertThat(BusinessId.isValid(mutated))
                            .as("%s -> %s (position %d) should be rejected", id, mutated, position)
                            .isFalse();
                    checked++;
                }
            }
        }

        assertThat(checked).as("mutations actually exercised").isGreaterThan(30_000);
    }

    @Test
    @DisplayName("every transposition is rejected, adjacent or not")
    void everyTranspositionIsDetected() {
        int checked = 0;

        for (long sequence = 1; sequence <= 500; sequence++) {
            String id = BusinessId.encode(sequence);
            char[] characters = id.toCharArray();

            // All pairs within the body, not only neighbours — a prime modulus buys the stronger
            // guarantee, so the test asks for it.
            for (int first = 4; first <= 8; first++) {
                for (int second = first + 1; second <= 8; second++) {
                    if (characters[first] == characters[second]) {
                        continue;
                    }
                    char[] swapped = characters.clone();
                    swapped[first] = characters[second];
                    swapped[second] = characters[first];

                    assertThat(BusinessId.isValid(new String(swapped)))
                            .as("%s with %d<->%d swapped should be rejected", id, first, second)
                            .isFalse();
                    checked++;
                }
            }
        }

        assertThat(checked).as("transpositions actually exercised").isGreaterThan(2_000);
    }

    @Test
    @DisplayName("look-alike characters are normalised, not rejected")
    void normalisationRescuesCommonTranscriptionSlips() {
        String id = BusinessId.encode(12_345);

        // Someone reading a printed card and typing what they see.
        String misread = id.replace('1', 'I').replace('0', 'O');

        assertThat(BusinessId.isValid(misread))
                .as("%s misread as %s should still resolve", id, misread)
                .isTrue();
        assertThat(BusinessId.isValid(id.toLowerCase())).isTrue();
        assertThat(BusinessId.isValid("  " + id + "  ")).isTrue();
    }

    @Test
    @DisplayName("malformed input is rejected without throwing")
    void malformedInputIsRejected() {
        assertThat(BusinessId.isValid(null)).isFalse();
        assertThat(BusinessId.isValid("")).isFalse();
        assertThat(BusinessId.isValid("SLV-123-4")).isFalse();
        assertThat(BusinessId.isValid("XXX-12345-6")).isFalse();
        assertThat(BusinessId.isValid("SLV-12345")).isFalse();
        assertThat(BusinessId.isValid("SLV-1234Z-1")).as("Z is outside the alphabet").isFalse();
        assertThat(BusinessId.isValid("'; DROP TABLE distributor; --")).isFalse();
    }

    @Test
    @DisplayName("the space refuses to wrap when exhausted")
    void exhaustionFailsLoudly() {
        assertThat(BusinessId.isValid(BusinessId.encode(BusinessId.MAX_SEQUENCE_VALUE))).isTrue();

        // Wrapping would reissue an identifier that already belongs to somebody.
        assertThatThrownBy(() -> BusinessId.encode(BusinessId.MAX_SEQUENCE_VALUE + 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
    }
}
