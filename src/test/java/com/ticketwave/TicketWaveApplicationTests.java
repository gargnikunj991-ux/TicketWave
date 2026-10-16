package com.ticketwave;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🧪 TicketWave Application Context Smoke Test
 *
 * Annotations & Mechanics:
 * - @SpringBootTest: Tells Spring to bootstrap the full ApplicationContext (IoC container).
 *   Under the hood, it:
 *     1. Starts an embedded Spring ApplicationContext.
 *     2. Performs component scanning across "com.ticketwave".
 *     3. Validates all @Autowired and constructor injection dependencies.
 *     4. Instantiates JPA EntityManagerFactory and verifies entity mappings.
 *
 * - @ActiveProfiles("test"): Tells Spring to load "application-test.yml" instead of production configs.
 *   This uses an in-memory H2 database (with PostgreSQL dialect compatibility) so tests can run
 *   rapidly in isolation without requiring an external DB setup.
 *
 * - @MockBean RedissonClient: Replaces the live Redisson Redis socket client with a Mockito dynamic proxy.
 *   This ensures this smoke test can run in environments where Redis is not running locally,
 *   while verifying 100% of Spring's bean wiring.
 */
@SpringBootTest
@ActiveProfiles("test")
class TicketWaveApplicationTests {

    @MockBean
    private RedissonClient redissonClient;

    @Test
    @DisplayName("Verify Spring ApplicationContext boots up and all core beans wire cleanly")
    void contextLoads() {
        assertThat(redissonClient).isNotNull();
    }
}
