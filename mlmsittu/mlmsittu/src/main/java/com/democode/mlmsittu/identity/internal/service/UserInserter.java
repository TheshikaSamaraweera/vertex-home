package com.democode.mlmsittu.identity.internal.service;

import com.democode.mlmsittu.identity.internal.domain.AppUser;
import com.democode.mlmsittu.identity.internal.repo.AppUserRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts a user only if the email is free, without ever raising a constraint violation.
 *
 * <h2>Why not just catch the exception</h2>
 *
 * Signup must answer identically whether or not an address is already registered, so it needs to
 * swallow the duplicate case and carry on. Two earlier attempts at that both failed, for different
 * reasons worth recording:
 *
 * <ol>
 *   <li><b>Catching it inline.</b> PostgreSQL aborts the entire transaction on a constraint
 *       violation — every later statement fails with {@code current transaction is aborted}. The
 *       audit write that followed died with an error that had nothing to do with the cause.
 *   <li><b>Catching it inside {@code REQUIRES_NEW}.</b> Better, but the violation surfaces from
 *       Hibernate's flush, which leaves the persistence context unusable. The inner transaction
 *       then cannot commit and the caller gets {@code UnexpectedRollbackException} instead.
 * </ol>
 *
 * <p>So the insert is done in plain SQL with {@code ON CONFLICT DO NOTHING}. No exception is raised,
 * no transaction is poisoned, no persistence context is broken, and the database still decides —
 * which matters, because a check-then-insert would let two simultaneous signups both pass the
 * check.
 */
@Component
public class UserInserter {

    private static final Logger log = LoggerFactory.getLogger(UserInserter.class);

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;

    public UserInserter(JdbcTemplate jdbc, AppUserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    /** @return the saved user, or empty when that email is already registered */
    @Transactional
    public Optional<AppUser> insertIfEmailFree(AppUser candidate) {
        // The conflict target matches idx_app_user_email, which is an expression index on
        // lower(email) — naming the expression is what lets ON CONFLICT use it.
        var ids =
                jdbc.query(
                        """
                        INSERT INTO app_user
                            (email, mobile, full_name, password_hash, status,
                             email_verified, mobile_verified)
                        VALUES (?, ?, ?, ?, ?, false, false)
                        ON CONFLICT (lower(email)) DO NOTHING
                        RETURNING id
                        """,
                        (rs, rowNum) -> rs.getObject(1, UUID.class),
                        candidate.getEmail(),
                        candidate.getMobile(),
                        candidate.getFullName(),
                        candidate.getPasswordHash(),
                        candidate.getStatus());

        if (ids.isEmpty()) {
            log.info("Signup attempted for an address that already exists");
            return Optional.empty();
        }
        return users.findById(ids.getFirst());
    }
}
