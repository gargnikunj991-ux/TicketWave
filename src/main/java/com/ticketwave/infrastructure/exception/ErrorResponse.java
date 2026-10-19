package com.ticketwave.infrastructure.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * RFC-7807 Compliant Problem Details Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "RFC-7807 compliant Problem Details error payload")
public class ErrorResponse {
    @Builder.Default
    @Schema(description = "URI reference identifying problem type", example = "about:blank")
    private String type = "about:blank";

    @Schema(description = "Short, human-readable summary of problem", example = "Seat Hold Conflict")
    private String title;

    @Schema(description = "HTTP status code", example = "409")
    private int status;

    @Schema(description = "Detailed explanation specific to this occurrence", example = "Seat 101 is currently held by another user")
    private String detail;

    @Schema(description = "URI reference that identifies the specific occurrence", example = "/api/v1/shows/1/hold")
    private String instance;

    @Schema(description = "Timestamp when error occurred")
    private Instant timestamp;

    @Schema(description = "Field-level validation error details")
    private Map<String, String> errors;
}
