package com.ticketwave.seat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatHoldRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;

    @NotEmpty(message = "seatIds must not be empty")
    @Size(max = 10, message = "Cannot hold more than 10 seats at once")
    private List<Long> seatIds;
}
