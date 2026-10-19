package com.ticketwave.seat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Confirmation details for an acquired 10-minute temporary seat hold")
public class SeatHoldResponse {

    @Schema(description = "Unique UUID reference assigned to this hold lease", example = "a8098c1a-f86e-11da-bd1a-00112444be1e")
    private UUID holdReference;

    @Schema(description = "ID of the show", example = "1")
    private Long showId;

    @Schema(description = "ID of the holding user", example = "usr_98124")
    private String userId;

    @Schema(description = "List of reserved seat IDs", example = "[101, 102]")
    private List<Long> seatIds;

    @Schema(description = "Total price across all held seats", example = "500.00")
    private BigDecimal totalHoldAmount;

    @Schema(description = "Exact UTC timestamp when hold lease expires")
    private Instant expiresAt;

    @Schema(description = "Lease duration in seconds (600s = 10 minutes)", example = "600")
    private Long ttlSeconds;
}
