package com.ticketwave.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    @DisplayName("Verify OpenAPI configuration bean initializes with metadata, contact, license, servers, and tags")
    void testOpenApiConfiguration() {
        OpenApiConfig config = new OpenApiConfig();
        OpenAPI openAPI = config.ticketWaveOpenAPI();

        assertThat(openAPI).isNotNull();
        assertThat(openAPI.getInfo()).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).contains("TicketWave");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("1.0.0");
        assertThat(openAPI.getInfo().getContact()).isNotNull();
        assertThat(openAPI.getInfo().getContact().getEmail()).isEqualTo("engineering@ticketwave.internal");
        assertThat(openAPI.getInfo().getLicense()).isNotNull();
        assertThat(openAPI.getInfo().getLicense().getName()).isEqualTo("Apache License 2.0");

        assertThat(openAPI.getServers()).isNotEmpty();
        assertThat(openAPI.getServers().get(0).getUrl()).isEqualTo("http://localhost:8080");

        assertThat(openAPI.getTags()).hasSize(3);
        assertThat(openAPI.getTags())
                .extracting("name")
                .containsExactlyInAnyOrder("Seat Reservation Engine", "Booking & Payment Engine", "Production Observability");
    }
}
