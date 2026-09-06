package com.democode.mlmsittu.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.democode.mlmsittu.shared.phone.PhoneNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Phone numbers as login identifiers.
 *
 * <p>The whole of "sign in with your phone number" rests on one property: whatever a person types,
 * it has to become the same string that was stored when they registered. A customer who registered
 * at the desk as {@code 077 123 4567} and later types {@code +94771234567} is the same customer,
 * and if these two spellings ever produce different strings they simply cannot log in — with no
 * error that says so, because "no such account" is the honest answer to a lookup that found
 * nothing.
 *
 * <p>V26 does the same conversion in SQL, for rows that existed before this class did. The two must
 * agree; the cases below are the ones where they could plausibly drift.
 */
class PhoneNumberTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @DisplayName("every spelling of one number becomes the same string")
    @CsvSource({
        "0771234567,      +94771234567",
        "771234567,       +94771234567",
        "94771234567,     +94771234567",
        "+94771234567,    +94771234567",
        "077-123-4567,    +94771234567",
        "077 123 4567,    +94771234567",
        "(077) 123 4567,  +94771234567",
        "077.123.4567,    +94771234567",
        "  0771234567  ,  +94771234567",
    })
    void spellingsConverge(String typed, String stored) {
        assertThat(PhoneNumber.normalise(typed)).isEqualTo(stored);
    }

    @Test
    @DisplayName("an overseas number keeps its own country code")
    void foreignNumbersAreLeftAlone() {
        // A customer living abroad is a real case. Rewriting +971 to +94 would silently point the
        // account at a different phone, and there is no way back from that.
        assertThat(PhoneNumber.normalise("+971501234567")).isEqualTo("+971501234567");
    }

    @ParameterizedTest
    @DisplayName("blank means no number, not a bad one")
    @ValueSource(strings = {"", "   ", "  -  "})
    void blankIsNull(String blank) {
        // An optional field left empty must not be an error: an account identified by its email
        // address alone is now entirely normal.
        assertThat(PhoneNumber.normalise(blank)).isNull();
    }

    @Test
    void nullIsNull() {
        assertThat(PhoneNumber.normalise(null)).isNull();
    }

    @ParameterizedTest
    @DisplayName("what is not a phone number is refused")
    @ValueSource(strings = {"07x1234567", "not a number", "0771234567@test.local", "+", "12345"})
    void rubbishIsRefused(String rubbish) {
        assertThatThrownBy(() -> PhoneNumber.normalise(rubbish))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an email address is never mistaken for a phone number")
    void emailIsNotAPhoneNumber() {
        // This is what routes the single sign-in field to the right lookup. Getting it wrong sends
        // an address to findByMobile, which finds nothing, and the person cannot log in at all.
        assertThat(PhoneNumber.looksLikePhoneNumber("nimal@example.lk")).isFalse();
        assertThat(PhoneNumber.looksLikePhoneNumber("0771234567")).isTrue();
        assertThat(PhoneNumber.looksLikePhoneNumber("+94 77 123 4567")).isTrue();
    }

    @Test
    @DisplayName("a number that is plainly wrong still routes as a number")
    void shortNumberStillRoutesAsAPhoneNumber() {
        // 07712 cannot be a real number, but it is obviously not an email address either. Routing
        // it to the phone lookup gives "no such account"; routing it to the email lookup would
        // give the same answer by accident. The first is the one that stays true if the rules
        // around it change.
        assertThat(PhoneNumber.looksLikePhoneNumber("07712")).isTrue();
    }
}
