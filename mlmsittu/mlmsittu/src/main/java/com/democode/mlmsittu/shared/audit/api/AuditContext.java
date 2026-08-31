package com.democode.mlmsittu.shared.audit.api;

import java.util.UUID;

/**
 * Lets an {@link Audited} method hand its before/after snapshot to the audit aspect without
 * knowing anything about how audit rows are written.
 *
 * <p>Business code calls {@link #record} once; the aspect drains it. If nothing is recorded, the
 * aspect still writes a row — action, actor, IP and timestamp — with no diff attached.
 */
public final class AuditContext {

    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private AuditContext() {}

    /** What changed. {@code before} and {@code after} are serialised to JSONB by the aspect. */
    public record Entry(UUID entityId, Object before, Object after) {}

    public static void record(UUID entityId, Object before, Object after) {
        CURRENT.set(new Entry(entityId, before, after));
    }

    /** Read and clear. Called by the aspect only. */
    public static Entry consume() {
        Entry entry = CURRENT.get();
        CURRENT.remove();
        return entry;
    }

    /** Defensive cleanup so a failed method never leaks its snapshot into the next request. */
    public static void clear() {
        CURRENT.remove();
    }
}
