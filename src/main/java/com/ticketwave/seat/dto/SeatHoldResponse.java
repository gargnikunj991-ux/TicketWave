package com.ticketwave.seat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatHoldResponse {
    private UUID holdReference;
    private Long showId;
    private String userId;
    private List<Long> seatIds;
    private BigDecimal totalHoldAmount;
    private Instant expiresAt;
    private Long ttlSeconds;
}
