package com.ticketwave.infrastructure.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * RFC-7807 Compliant Problem Details Object
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    @Builder.Default
    private String type = "about:blank";
    private String title;
    private int status;
    private String detail;
    private String instance;
    private Instant timestamp;
    private Map<String, String> errors;
}
