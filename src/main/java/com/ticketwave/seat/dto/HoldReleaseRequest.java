package com.ticketwave.seat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Payload to voluntarily release an active seat hold prior to 10-minute expiration")
public class HoldReleaseRequest {

    @NotBlank(message = "userId must not be blank")
    @Schema(description = "User ID who acquired the hold", example = "usr_98124")
    private String userId;

    @NotNull(message = "holdReference must not be null")
    @Schema(description = "UUID hold reference to release", example = "a8098c1a-f86e-11da-bd1a-00112444be1e")
    private UUID holdReference;
}
