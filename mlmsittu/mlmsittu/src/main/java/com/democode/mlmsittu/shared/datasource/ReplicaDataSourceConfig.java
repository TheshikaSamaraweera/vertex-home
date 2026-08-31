package com.democode.mlmsittu.shared.datasource;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires the replica in, when there is one (P7-07).
 *
 * <p>Conditional on {@code spring.datasource.replica.url}. Without it this configuration does not
 * exist and Spring Boot's ordinary single {@code DataSource} is used — so the routing costs
 * nothing on a one-database installation, and going live with a replica is two properties and a
 * restart rather than a code change. The same switch pattern as SMTP, for the same reason: an
 * environment we cannot build here should not require code we cannot test here.
 *
 * <p>The replica connection is deliberately {@code read-only}. If routing ever sends a write down
 * it by mistake, PostgreSQL refuses it loudly instead of the write silently going to a copy that
 * will be overwritten by replication.
 */
@Configuration
@ConditionalOnProperty(name = "spring.datasource.replica.url")
public class ReplicaDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ReplicaDataSourceConfig.class);

    @Bean
    @Primary
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String primaryUrl,
            @Value("${spring.datasource.username}") String username,
            @Value("${spring.datasource.password}") String password,
            @Value("${spring.datasource.replica.url}") String replicaUrl,
            @Value("${spring.datasource.replica.username:${spring.datasource.username}}")
                    String replicaUsername,
            @Value("${spring.datasource.replica.password:${spring.datasource.password}}")
                    String replicaPassword) {

        DataSource primary = build(primaryUrl, username, password, false);
        DataSource replica = build(replicaUrl, replicaUsername, replicaPassword, true);

        RoutingDataSource routing = new RoutingDataSource();
        routing.setTargetDataSources(
                Map.of(
                        RoutingDataSource.Target.PRIMARY, primary,
                        RoutingDataSource.Target.REPLICA, replica));
        // The fallback is the primary, always. A routing key nobody anticipated should land on the
        // database that is definitely correct, not the one that is merely probably current.
        routing.setDefaultTargetDataSource(primary);
        routing.afterPropertiesSet();

        log.info("Read replica configured at {} — read-only transactions will use it", replicaUrl);
        return routing;
    }

    private DataSource build(String url, String username, String password, boolean readOnly) {
        HikariDataSource pool =
                DataSourceBuilder.create()
                        .type(HikariDataSource.class)
                        .url(url)
                        .username(username)
                        .password(password)
                        .build();
        pool.setReadOnly(readOnly);
        pool.setPoolName(readOnly ? "replica" : "primary");
        return pool;
    }
}
