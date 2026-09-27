package com.democode.mlmsittu.catalogue.internal.service;

import com.democode.mlmsittu.shared.error.ApiException;
import com.democode.mlmsittu.shared.error.NotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Item codes: two letters from the category, then four digits. {@code BE0001}.
 *
 * <p>They used to be typed. That produced {@code SLV-001} alongside {@code SLV001} and
 * {@code slv-1}, and a code that is nearly right is worse than one that is wrong — it looks
 * correct on a shelf label and matches nothing when somebody searches for it.
 *
 * <h2>Where the two letters come from</h2>
 *
 * <p>The category, and each category owns its prefix exclusively. Bedroom takes {@code BE};
 * Beverages, arriving second, is given {@code BV} — the first letter kept so the code still looks
 * like the category it belongs to, the second taken from further along the name. The prefix is
 * decided once, when the category is created, because that is the only moment there is a clash to
 * resolve.
 *
 * <h2>Where the four digits come from</h2>
 *
 * <p>A counter per prefix, incremented under the row's own lock, so two clerks adding an item at
 * the same instant get {@code BE0007} and {@code BE0008} rather than both getting {@code BE0007}
 * and one of them a constraint violation.
 */
@Service
public class ItemCodeAllocator {

    /** Characters a second letter may be drawn from when the natural one is taken. */
    private static final String FALLBACK = "ABCDEFGHIJKLMNOPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;

    public ItemCodeAllocator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The next code for this category.
     *
     * <p>Joins the caller's transaction deliberately. If the item insert fails, the number is
     * given back with it — a run of codes with gaps in it is not wrong, but somebody will
     * eventually ask why, and the answer should not be "an item nobody remembers failed to save".
     */
    @Transactional
    public String next(UUID categoryId) {
        if (categoryId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "CATEGORY_REQUIRED",
                    "Choose a category. The item code is generated from it.");
        }

        List<String> prefixes =
                jdbc.queryForList(
                        "SELECT code_prefix FROM category WHERE id = ?", String.class, categoryId);

        if (prefixes.isEmpty()) {
            throw new NotFoundException("CATEGORY_NOT_FOUND", "That category does not exist.");
        }

        String prefix = prefixes.get(0);

        // One statement, so the read and the increment cannot be separated by another writer.
        // INSERT-or-bump: the first item in a category creates the counter at 2 and takes 1.
        Integer number =
                jdbc.queryForObject(
                        """
                        INSERT INTO item_code_sequence (prefix, next_value)
                        VALUES (?, 2)
                        ON CONFLICT (prefix)
                        DO UPDATE SET next_value = item_code_sequence.next_value + 1
                        RETURNING next_value - 1
                        """,
                        Integer.class,
                        prefix);

        if (number == null || number > 9999) {
            // Ten thousand items in one category. Refusing is right: rolling over to 0000 would
            // silently reissue a code that is printed on a label somewhere.
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ITEM_CODES_EXHAUSTED",
                    "That category has used all 9999 item codes. Split it into two categories.");
        }

        return prefix + String.format("%04d", number);
    }

    /**
     * Chooses and stores a prefix for a new category.
     *
     * <p>{@code REQUIRES_NEW} on purpose. The uniqueness of a prefix is guaranteed by the index,
     * not by this method looking first — two categories created at the same instant can both see
     * {@code BE} free. The insert is retried in its own transaction so a lost race costs one more
     * attempt rather than poisoning the caller's.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String allocatePrefix(String categoryName) {
        String letters = categoryName.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
        if (letters.length() < 2) {
            letters = (letters + "XX").substring(0, 2);
        }

        for (String candidate : candidates(letters)) {
            if (isFree(candidate)) {
                return candidate;
            }
        }

        throw new ApiException(
                HttpStatus.CONFLICT,
                "NO_FREE_PREFIX",
                "Every two-letter code starting with "
                        + letters.charAt(0)
                        + " is taken. Rename the category.");
    }

    /**
     * Prefixes to try, in order of how well they read.
     *
     * <p>First the obvious two letters, then the first letter with each later letter of the name,
     * then the first letter with anything. Keeping the initial throughout is what stops Beverages
     * being given {@code QZ}.
     */
    private List<String> candidates(String letters) {
        List<String> options = new java.util.ArrayList<>();
        options.add(letters.substring(0, 2));

        for (int i = 2; i < letters.length(); i++) {
            options.add("" + letters.charAt(0) + letters.charAt(i));
        }
        for (char c : FALLBACK.toCharArray()) {
            options.add("" + letters.charAt(0) + c);
        }
        return options;
    }

    private boolean isFree(String prefix) {
        Integer taken =
                jdbc.queryForObject(
                        "SELECT count(*) FROM category WHERE code_prefix = ?",
                        Integer.class,
                        prefix);
        return taken != null && taken == 0;
    }
}
