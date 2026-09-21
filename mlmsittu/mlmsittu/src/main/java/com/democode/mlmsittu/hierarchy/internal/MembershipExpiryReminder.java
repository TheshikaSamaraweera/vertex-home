package com.democode.mlmsittu.hierarchy.internal;

import com.democode.mlmsittu.shared.notify.Notifications;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warns people before their membership runs out, and tells the office who has lapsed.
 *
 * <h2>Why a sweep and not a flag</h2>
 *
 * <p>Nothing here marks anybody expired. Expiry is a date passing, and {@code PortalService}
 * compares that date to the clock at the moment somebody tries to sign in — so it is right at
 * every instant, including the hours between midnight and whenever this ran, and including every
 * day this job was switched off. A column saying {@code expired = true} would be a second,
 * lagging answer to a question the first one already answers correctly.
 *
 * <p>This job exists only to send messages, which genuinely do need a moment to happen at.
 *
 * <h2>Once, not nightly</h2>
 *
 * <p>{@code expiry_reminded_at} is stamped when a warning goes out and cleared when somebody is
 * extended. Without it a customer inside the warning window would be told every single night for
 * a fortnight, which trains people to ignore the bell — and the one notification that mattered
 * would be the one nobody read.
 */
@Component
public class MembershipExpiryReminder {

    private static final Logger log = LoggerFactory.getLogger(MembershipExpiryReminder.class);

    /**
     * How far ahead somebody is warned.
     *
     * <p>Seven days: long enough to do something about it, short enough that the warning is still
     * on their mind when the date arrives.
     */
    private static final int WARN_DAYS = 7;

    private final JdbcTemplate jdbc;
    private final Notifications notifications;
    private final boolean enabled;

    public MembershipExpiryReminder(
            JdbcTemplate jdbc,
            Notifications notifications,
            @Value("${jobs.scheduler.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.enabled = enabled;
    }

    /**
     * Daily, shortly after the Colombo working day starts.
     *
     * <p>Not at midnight. A notification that lands at 00:05 is at the bottom of the bell by the
     * time anybody looks, underneath whatever arrived during the morning.
     */
    @Scheduled(cron = "0 15 8 * * *", zone = "Asia/Colombo")
    public void sweep() {
        if (!enabled) {
            // One instance does the scheduled work. See jobs.scheduler.enabled.
            return;
        }
        try {
            int warned = warnCustomers();
            int lapsed = tellTheOffice();
            if (warned > 0 || lapsed > 0) {
                log.info("Expiry sweep: warned {}, reported {} newly lapsed", warned, lapsed);
            }
        } catch (RuntimeException failure) {
            // A sweep that dies takes every future sweep with it unless it is caught here.
            log.error("Expiry sweep failed", failure);
        }
    }

    /** Warns anybody inside the window who has not been warned for this period. */
    @Transactional
    int warnCustomers() {
        List<Reminder> due =
                jdbc.query(
                        """
                        SELECT d.id,
                               d.user_id,
                               d.business_id,
                               GREATEST(0, DATE_PART('day', d.expires_at - now())::INT) AS days_left
                          FROM distributor d
                         WHERE d.expires_at IS NOT NULL
                           AND d.deleted_at IS NULL
                           AND d.status = 'active'
                           AND d.expires_at < now() + (? * INTERVAL '1 day')
                           AND d.expiry_reminded_at IS NULL
                         ORDER BY d.expires_at
                         LIMIT 500
                        """,
                        (rs, row) ->
                                new Reminder(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("user_id", UUID.class),
                                        rs.getString("business_id"),
                                        rs.getInt("days_left")),
                        WARN_DAYS);

        for (Reminder reminder : due) {
            boolean alreadyGone = reminder.daysLeft() <= 0;
            notifications.raise(
                    reminder.userId(),
                    Notifications.MEMBERSHIP_EXPIRING,
                    alreadyGone ? "Your membership has ended" : "Your membership is ending soon",
                    alreadyGone
                            ? "Contact the office to renew. Your place and your referrals are kept."
                            : "It ends in "
                                    + reminder.daysLeft()
                                    + (reminder.daysLeft() == 1 ? " day." : " days.")
                                    + " Contact the office to renew.",
                    "/portal");

            jdbc.update(
                    "UPDATE distributor SET expiry_reminded_at = now() WHERE id = ?",
                    reminder.id());
        }
        return due.size();
    }

    /**
     * Tells administrators how many have lapsed, as one message rather than one per customer.
     *
     * <p>Fifty separate notifications saying "a customer expired" is not fifty pieces of
     * information, it is one — and it buries everything else in the bell. The list itself is on
     * the customers screen, which is where somebody would act on it anyway.
     */
    @Transactional
    int tellTheOffice() {
        Integer lapsed =
                jdbc.queryForObject(
                        """
                        SELECT count(*) FROM distributor
                         WHERE expires_at IS NOT NULL
                           AND deleted_at IS NULL
                           AND status = 'active'
                           AND expires_at < now()
                        """,
                        Integer.class);

        if (lapsed == null || lapsed == 0) {
            return 0;
        }

        List<UUID> admins =
                jdbc.queryForList(
                        """
                        SELECT DISTINCT u.id
                          FROM app_user u
                          JOIN user_role ur ON ur.user_id = u.id
                          JOIN app_role r   ON r.id = ur.role_id
                         WHERE r.code IN ('ADMIN', 'SUPER_ADMIN') AND u.status = 'active'
                        """,
                        UUID.class);

        notifications.raiseAll(
                admins,
                Notifications.MEMBERSHIP_EXPIRING,
                lapsed == 1 ? "1 membership has lapsed" : lapsed + " memberships have lapsed",
                "They cannot open the portal until somebody extends them.",
                "/distributors");

        return lapsed;
    }

    private record Reminder(UUID id, UUID userId, String businessId, int daysLeft) {}
}
