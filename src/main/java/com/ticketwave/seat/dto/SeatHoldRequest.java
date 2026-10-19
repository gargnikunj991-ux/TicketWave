package com.ticketwave.seat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Payload to acquire a temporary 10-minute hold on selected seats")
public class SeatHoldRequest {

    @NotBlank(message = "userId must not be blank")
    @Schema(description = "Unique ID of the purchasing user", example = "usr_98124")
    private String userId;

    @NotEmpty(message = "seatIds must not be empty")
    @Size(max = 10, message = "Cannot hold more than 10 seats at once")
    @Schema(description = "List of seat IDs to hold (max 10)", example = "[101, 102]")
    private List<Long> seatIds;
}
