package com.democode.mlmsittu.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} work.
 *
 * <p>Architecture §10.1 runs the scheduler on a dedicated instance so jobs do not fire once per
 * replica. That split needs a deployment topology, which arrives in Phase 8 — the property here is
 * the seam it will hang off, so turning it into a profile later touches this file and nothing
 * else. Locally there is one instance, so the default of "on" is correct.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "jobs.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {}
