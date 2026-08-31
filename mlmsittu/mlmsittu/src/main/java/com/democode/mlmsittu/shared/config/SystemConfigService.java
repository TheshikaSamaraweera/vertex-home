package com.democode.mlmsittu.shared.config;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings an administrator can change without a restart (architecture §2.2).
 *
 * <p>Read straight from the table on every call rather than cached. The values here are consulted
 * a handful of times per registration, and a cache would mean "change the cap" quietly does
 * nothing until someone bounces the service — which is precisely the behaviour P4-04 tests
 * against.
 */
@Service
public class SystemConfigService {

    public static final String REFERRAL_MAX_DIRECT = "referral.max_direct";

    private final JdbcTemplate jdbc;

    public SystemConfigService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public int getInt(String key, int fallback) {
        return get(key).map(value -> parseOr(value, fallback)).orElse(fallback);
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String key) {
        return jdbc
                .query(
                        "SELECT value FROM system_config WHERE key = ?",
                        (rs, rowNum) -> rs.getString(1),
                        key)
                .stream()
                .findFirst();
    }

    @Transactional
    public void set(String key, String value) {
        int updated =
                jdbc.update(
                        "UPDATE system_config SET value = ?, updated_at = now() WHERE key = ?",
                        value,
                        key);
        if (updated == 0) {
            throw new IllegalArgumentException("Unknown configuration key: " + key);
        }
    }

    private int parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            // A malformed value must not take the system down; falling back is safer than
            // refusing every registration because someone typed "four".
            return fallback;
        }
    }
}
