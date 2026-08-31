package com.democode.mlmsittu.shared.datasource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forces a read-only method onto the primary database, never a replica.
 *
 * <p>For reads where <b>staleness is a correctness bug rather than an inconvenience</b>. Stock
 * availability is the case that matters: a replica can be seconds behind, and answering "is there
 * enough?" from a lagging copy is how the same units get promised twice. A report being a few
 * seconds out of date costs nothing; overselling costs a customer order.
 *
 * <p>Has no effect until a replica is actually configured — with one database everything is the
 * primary and this is documentation. That is deliberate: the annotation goes on now, while the
 * reasoning is fresh, rather than being remembered on the day a replica appears.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ReadFromPrimary {}
