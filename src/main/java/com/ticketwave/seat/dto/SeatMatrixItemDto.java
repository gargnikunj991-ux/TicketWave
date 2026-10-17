package com.ticketwave.seat.dto;

import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.SeatTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatMatrixItemDto {
    private Long seatId;
    private String row;
    private Integer number;
    private SeatTier tier;
    private BigDecimal price;
    private SeatStatus status;
}
