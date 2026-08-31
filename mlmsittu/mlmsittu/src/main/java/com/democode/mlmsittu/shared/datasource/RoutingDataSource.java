package com.democode.mlmsittu.shared.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends read-only transactions to the replica and everything else to the primary (P7-07).
 *
 * <p>The routing key is Spring's own {@code readOnly} transaction flag, so nothing has to be
 * annotated twice — a service already marked {@code @Transactional(readOnly = true)} is already
 * saying it does not write.
 *
 * <p>Two exceptions, and both matter:
 *
 * <ul>
 *   <li>Outside a transaction, the primary. A bare read has no {@code readOnly} flag to consult,
 *       and guessing wrong towards the replica risks a stale answer with no warning.
 *   <li>Anything marked {@link ReadFromPrimary}, however read-only it is — see that annotation for
 *       why stock availability must never come from a lagging copy.
 * </ul>
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    public enum Target {
        PRIMARY,
        REPLICA
    }

    @Override
    protected Object determineCurrentLookupKey() {
        if (PrimaryOnly.isPinned()) {
            return Target.PRIMARY;
        }
        boolean readOnly =
                TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return readOnly ? Target.REPLICA : Target.PRIMARY;
    }

    /** The pin {@link ReadFromPrimaryAspect} sets around an annotated call. */
    public static final class PrimaryOnly {

        private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

        private PrimaryOnly() {}

        static boolean isPinned() {
            Integer depth = DEPTH.get();
            return depth != null && depth > 0;
        }

        /** Counted rather than boolean, so nested annotated calls unpin at the right moment. */
        static void pin() {
            DEPTH.set(DEPTH.get() == null ? 1 : DEPTH.get() + 1);
        }

        static void unpin() {
            Integer depth = DEPTH.get();
            if (depth == null || depth <= 1) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth - 1);
            }
        }
    }
}
