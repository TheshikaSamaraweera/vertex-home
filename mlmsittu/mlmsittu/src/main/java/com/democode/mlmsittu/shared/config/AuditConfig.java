package com.democode.mlmsittu.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Pins the {@code @Transactional} advisor at a known order so the audit aspect can sit inside it.
 *
 * <p>Spring Boot's default advisor runs at {@code Ordered.LOWEST_PRECEDENCE}, which leaves no room
 * for an aspect to nest <em>within</em> the transaction — every custom aspect would end up outside
 * it and audit rows would commit separately from the change they describe. Declaring
 * {@code @EnableTransactionManagement} here makes Boot back off and gives us the ordering.
 *
 * <p>{@code proxyTargetClass = true} matches Boot's own default; changing it would switch
 * class-based proxies to interface proxies across the whole application.
 */
@Configuration
@EnableTransactionManagement(proxyTargetClass = true, order = 100)
public class AuditConfig {}
