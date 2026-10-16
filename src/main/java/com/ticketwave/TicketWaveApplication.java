package com.ticketwave;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 🎫 TicketWave Main Application Entry Point
 *
 * Annotations Breakdown:
 * - @SpringBootApplication: Meta-annotation combining:
 *     1. @Configuration: Declares this class as a source of bean definitions for the Spring IoC container.
 *     2. @EnableAutoConfiguration: Instructs Spring Boot to automatically configure beans based on
 *        the classpath (e.g., DataSource, Hibernate EntityManager, RedissonClient, DispatcherServlet).
 *     3. @ComponentScan: Directs Spring to scan package "com.ticketwave" and sub-packages for
 *        @Component, @Service, @Repository, and @RestController stereotypes.
 *
 * - @EnableScheduling: Activates Spring's TaskScheduler infrastructure to execute background cron/sweep
 *   tasks (e.g., our orphaned seat-hold recovery sweeper in Phase 5).
 */
@SpringBootApplication
@EnableScheduling
public class TicketWaveApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketWaveApplication.class, args);
    }
}
