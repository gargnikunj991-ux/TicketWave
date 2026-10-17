package com.ticketwave.seat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HoldReleaseRequest {

    @NotBlank(message = "userId must not be blank")
    private String userId;

    @NotNull(message = "holdReference must not be null")
    private UUID holdReference;
}
