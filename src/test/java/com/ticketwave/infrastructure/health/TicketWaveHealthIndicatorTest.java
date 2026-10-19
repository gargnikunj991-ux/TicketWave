package com.ticketwave.infrastructure.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketWaveHealthIndicatorTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private Connection connection;

    @Mock
    private RedisConnectionFactory redisConnectionFactory;

    @Mock
    private RedisConnection redisConnection;

    @InjectMocks
    private TicketWaveHealthIndicator healthIndicator;

    @Test
    @DisplayName("Verify HealthIndicator returns UP when both PostgreSQL and Redis are healthy")
    void testHealthIndicatorUp() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("database", "UP");
        assertThat(health.getDetails()).containsEntry("redis", "UP");
        assertThat(health.getDetails()).containsEntry("redissonLockManager", "OPERATIONAL");
        assertThat(health.getDetails()).containsEntry("holdLeaseTTL", "600s (10m)");
    }

    @Test
    @DisplayName("Verify HealthIndicator returns DOWN when PostgreSQL connection fails")
    void testHealthIndicatorDownDatabase() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("Hikari pool exhausted"));

        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("database", "DOWN");
        assertThat(health.getDetails()).containsKey("databaseError");
    }

    @Test
    @DisplayName("Verify HealthIndicator returns DOWN when Redis ping fails")
    void testHealthIndicatorDownRedis() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(anyInt())).thenReturn(true);

        when(redisConnectionFactory.getConnection()).thenThrow(new RuntimeException("Redis connection refused"));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("redis", "DOWN");
        assertThat(health.getDetails()).containsEntry("redissonLockManager", "DEGRADED");
        assertThat(health.getDetails()).containsKey("redisError");
    }
}
