package com.democode.mlmsittu.catalogue;

import static org.assertj.core.api.Assertions.assertThat;

import com.democode.mlmsittu.catalogue.internal.service.CategoryService;
import com.democode.mlmsittu.catalogue.internal.service.ItemCodeAllocator;
import com.democode.mlmsittu.shared.password.PasswordPolicy;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Item codes and the password policy")
class ItemCodeAndPasswordTest {

    @Autowired private CategoryService categories;
    @Autowired private ItemCodeAllocator codes;

    /** Distinct names per run: prefixes are unique for ever, and this database is not reset. */
    private static final java.util.concurrent.atomic.AtomicInteger RUN =
            new java.util.concurrent.atomic.AtomicInteger(
                    new java.security.SecureRandom().nextInt(1_000));

    @Test
    @DisplayName("codes run in sequence within a category and start again in another")
    void codesAreSequentialPerCategory() {
        UUID first = newCategory("Quilts");
        UUID second = newCategory("Rugs");

        String a = codes.next(first);
        String b = codes.next(first);
        String c = codes.next(second);

        assertThat(a).matches("^[A-Z0-9]{2}0001$");
        assertThat(b).isEqualTo(a.substring(0, 2) + "0002");

        // A separate counter, so one busy category does not push another's numbering along.
        assertThat(c).endsWith("0001");
        assertThat(c.substring(0, 2)).isNotEqualTo(a.substring(0, 2));
    }

    @Test
    @DisplayName("a second category wanting the same two letters is given different ones")
    void prefixesAreUnique() {
        // Both begin "Za". The first takes ZA; the second has to be given something else, and
        // keeps its initial so the code still looks like the category it belongs to.
        String stem = "Za" + RUN.incrementAndGet();
        UUID first = newCategory(stem + "phire");
        UUID second = newCategory(stem + "ndber");

        String firstPrefix = codes.next(first).substring(0, 2);
        String secondPrefix = codes.next(second).substring(0, 2);

        assertThat(secondPrefix).isNotEqualTo(firstPrefix);
        assertThat(secondPrefix).startsWith("Z");
    }

    @ParameterizedTest
    @DisplayName("accepted: 8 to 12 characters with a letter and a number")
    @ValueSource(strings = {"Abcd1234", "a1bcdefg", "Abcdefghij12", "12345678a"})
    void acceptablePasswords(String candidate) {
        assertThat(PasswordPolicy.isAcceptable(candidate)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("refused: too short, too long, or missing a letter or a number")
    @ValueSource(
            strings = {
                "Abc123", // seven
                "Abcdefghij123", // thirteen
                "abcdefghij", // no digit
                "1234567890", // no letter
                "" // nothing
            })
    void unacceptablePasswords(String candidate) {
        assertThat(PasswordPolicy.isAcceptable(candidate)).isFalse();
    }

    @Test
    @DisplayName("a null password is refused rather than throwing")
    void nullIsRefused() {
        assertThat(PasswordPolicy.isAcceptable(null)).isFalse();
    }

    private UUID newCategory(String name) {
        String unique = name + RUN.incrementAndGet();
        return categories.create(unique.toUpperCase(java.util.Locale.ROOT), unique).getId();
    }
}
