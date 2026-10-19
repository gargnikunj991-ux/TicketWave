package com.ticketwave.seat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Real-time seat layout, availability, and pricing matrix for a show")
public class SeatMatrixResponse {

    @Schema(description = "Show ID", example = "1")
    private Long showId;

    @Schema(description = "Event title", example = "Coldplay: Music of the Spheres Tour")
    private String eventName;

    @Schema(description = "Venue name", example = "Madison Square Garden")
    private String venueName;

    @Schema(description = "Screen or arena section name", example = "Main Arena")
    private String screenName;

    @Schema(description = "Show start timestamp in UTC")
    private Instant startTime;

    @Schema(description = "List of all seats and their real-time status")
    private List<SeatMatrixItemDto> seats;
}
