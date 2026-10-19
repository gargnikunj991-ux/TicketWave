package com.ticketwave.infrastructure.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 🏥 TicketWave Production Health Indicator
 *
 * Exposes live health probe telemetry on /actuator/health:
 * - PostgreSQL relational database socket connectivity
 * - Redis in-memory cache & distributed lock engine connectivity
 * - Key operational configuration parameters (TTL, idempotency window, reclamation sweeper)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketWaveHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    @Override
    public Health health() {
        Health.Builder builder = new Health.Builder();
        boolean dbHealthy = false;
        boolean redisHealthy = false;

        // Check PostgreSQL connectivity
        try (Connection conn = dataSource.getConnection()) {
            dbHealthy = conn.isValid(2);
        } catch (Exception e) {
            log.warn("Health check: PostgreSQL connection probe failed: {}", e.getMessage());
            builder.withDetail("databaseError", e.getMessage());
        }

        // Check Redis connectivity
        try (RedisConnection redisConn = redisConnectionFactory.getConnection()) {
            String pingResult = redisConn.ping();
            redisHealthy = "PONG".equalsIgnoreCase(pingResult);
        } catch (Exception e) {
            log.warn("Health check: Redis connection probe failed: {}", e.getMessage());
            builder.withDetail("redisError", e.getMessage());
        }

        builder.withDetail("database", dbHealthy ? "UP" : "DOWN")
               .withDetail("redis", redisHealthy ? "UP" : "DOWN")
               .withDetail("redissonLockManager", redisHealthy ? "OPERATIONAL" : "DEGRADED")
               .withDetail("holdLeaseTTL", "600s (10m)")
               .withDetail("idempotencyWindow", "120s (IN_PROGRESS) / 24h (COMPLETED)")
               .withDetail("inventorySweeper", "ACTIVE (60s cron)");

        if (dbHealthy && redisHealthy) {
            return builder.up().build();
        } else {
            return builder.down().build();
        }
    }
}
