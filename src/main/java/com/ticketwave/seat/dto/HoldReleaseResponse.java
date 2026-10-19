package com.ticketwave.seat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response confirming successful voluntary release of held seats")
public class HoldReleaseResponse {

    @Schema(description = "Status of release operation", example = "SUCCESS")
    private String status;

    @Schema(description = "Descriptive confirmation message", example = "Hold released successfully. Seats are now available.")
    private String message;
}
