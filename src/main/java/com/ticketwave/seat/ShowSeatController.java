package com.ticketwave.seat;

import com.ticketwave.infrastructure.exception.ErrorResponse;
import com.ticketwave.seat.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shows/{showId}")
@RequiredArgsConstructor
@Tag(name = "Seat Reservation Engine", description = "High-concurrency seat matrix, distributed holds, and release APIs")
public class ShowSeatController {

    private final SeatHoldService seatHoldService;

    @GetMapping("/seats")
    @Operation(summary = "Get real-time seat matrix", description = "Retrieves current seat availability status and pricing for a show.")
    @ApiResponse(responseCode = "200", description = "Seat matrix retrieved successfully")
    @ApiResponse(responseCode = "404", description = "Show not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SeatMatrixResponse> getSeatMatrix(@PathVariable Long showId) {
        return ResponseEntity.ok(seatHoldService.getSeatMatrix(showId));
    }

    @PostMapping("/hold")
    @Operation(summary = "Acquire temporary seat hold", description = "Locks selected seats with Redisson distributed locking and creates a 10-minute hold in Redis.")
    @ApiResponse(responseCode = "200", description = "Seat hold acquired successfully")
    @ApiResponse(responseCode = "409", description = "Seat hold conflict or lock acquisition timeout",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload or exceeding 10 seat limit",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "Show or seat not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<SeatHoldResponse> acquireHold(
            @PathVariable Long showId,
            @Valid @RequestBody SeatHoldRequest request
    ) {
        return ResponseEntity.ok(seatHoldService.acquireSeatHold(showId, request));
    }

    @PostMapping("/release")
    @Operation(summary = "Release temporary seat hold", description = "Voluntarily releases held seats prior to 10-minute expiration.")
    @ApiResponse(responseCode = "200", description = "Hold released successfully")
    @ApiResponse(responseCode = "404", description = "Active hold not found",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<HoldReleaseResponse> releaseHold(
            @PathVariable Long showId,
            @Valid @RequestBody HoldReleaseRequest request
    ) {
        return ResponseEntity.ok(seatHoldService.releaseSeatHold(showId, request));
    }
}
