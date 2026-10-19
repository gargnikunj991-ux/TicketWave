package com.ticketwave.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 📖 Phase 7: OpenAPI 3.0 & Swagger UI Contract Documentation
 *
 * Configures the interactive Swagger UI and OpenAPI documentation at /swagger-ui.html:
 * - Architectural description & engineering highlights
 * - Server endpoints (local development & production)
 * - Tag categorizations for Seat Reservation, Checkout & Observability
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ticketWaveOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("🎫 TicketWave — High-Concurrency Ticket Booking & Reservation Engine")
                        .version("1.0.0")
                        .description("""
                                **TicketWave** is a production-grade, distributed ticket reservation and checkout engine \
                                engineered for flash-sale scenarios (concert tours, stadium finals).
                                
                                ### ⚡ Key Architectural Features:
                                * **Zero Overbooking Guarantee:** Redisson distributed locks coordinate seat reservations across nodes.
                                * **Deadlock Freedom:** Multi-seat lock requests are sorted in natural ascending order prior to acquisition.
                                * **10-Minute Temporary Hold Leases:** Fast in-memory reservation leases with Redis TTL keys.
                                * **Payment Idempotency:** Client-generated `Idempotency-Key` headers guarantee financial safety.
                                * **Dual-Tier Inventory Recovery:** Reactive Redis keyspace notifications (`Ex`) + 60s fallback background sweeper.
                                """)
                        .contact(new Contact()
                                .name("TicketWave Architecture & Core Platform Team")
                                .email("engineering@ticketwave.internal")
                                .url("https://github.com/ticketwave"))
                        .license(new License()
                                .name("Apache License 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development Server"),
                        new Server().url("https://api.ticketwave.internal").description("Internal Production Gateway")
                ))
                .tags(List.of(
                        new Tag().name("Seat Reservation Engine").description("High-concurrency seat matrix, distributed holds, and voluntary release APIs"),
                        new Tag().name("Booking & Payment Engine").description("Transactional checkout, payment idempotency gate, and booking lookups"),
                        new Tag().name("Production Observability").description("Actuator health probes, Micrometer metrics, and Redis telemetry")
                ));
    }
}
