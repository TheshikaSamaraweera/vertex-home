package com.democode.mlmsittu.hierarchy.internal;

/**
 * The referral graph — architecture §2.2. Phase 4.
 *
 * <p>The one module that will not use Hibernate. PostgreSQL's {@code ltree} has no Hibernate type,
 * and the queries here are pure graph traversal that gains nothing from an ORM, so this module is
 * isolated behind jOOQ from the first commit rather than retrofitted later.
 *
 * <p>A marker so the package exists. Delete once P4-03 lands.
 */
final class HierarchyModule {
    private HierarchyModule() {}
}
