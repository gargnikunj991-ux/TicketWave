package com.ticketwave.seat.dto;

import com.ticketwave.seat.SeatStatus;
import com.ticketwave.seat.SeatTier;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Individual seat status, tier, and pricing within show layout")
public class SeatMatrixItemDto {

    @Schema(description = "Seat database identifier", example = "101")
    private Long seatId;

    @Schema(description = "Row label", example = "A")
    private String row;

    @Schema(description = "Seat number in the row", example = "1")
    private Integer number;

    @Schema(description = "Tier level (VIP, PREMIUM, STANDARD)", example = "VIP")
    private SeatTier tier;

    @Schema(description = "Seat base price", example = "250.00")
    private BigDecimal price;

    @Schema(description = "Real-time dynamic status (AVAILABLE, HELD, BOOKED)", example = "AVAILABLE")
    private SeatStatus status;
}
