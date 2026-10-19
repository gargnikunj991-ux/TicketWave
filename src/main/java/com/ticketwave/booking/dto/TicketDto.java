package com.ticketwave.booking.dto;

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
@Schema(description = "Issued ticket information tied to a specific seat")
public class TicketDto {

    @Schema(description = "Unique ticket serial number", example = "TKT-2026-001A01")
    private String ticketSerial;

    @Schema(description = "Seat database identifier", example = "101")
    private Long seatId;

    @Schema(description = "Row label", example = "A")
    private String row;

    @Schema(description = "Seat number in the row", example = "1")
    private Integer number;

    @Schema(description = "Seat tier categorization", example = "VIP")
    private SeatTier tier;

    @Schema(description = "Ticket price", example = "250.00")
    private BigDecimal price;
}
