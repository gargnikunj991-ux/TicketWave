package com.ticketwave.seat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketwave.infrastructure.exception.GlobalExceptionHandler;
import com.ticketwave.infrastructure.exception.SeatHoldConflictException;
import com.ticketwave.seat.dto.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShowSeatController.class)
@Import(GlobalExceptionHandler.class)
class ShowSeatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private SeatHoldService seatHoldService;

    @Test
    @DisplayName("GET /api/v1/shows/{showId}/seats returns 200 OK and seat matrix")
    void testGetSeatMatrix_Returns200() throws Exception {
        SeatMatrixResponse mockResponse = SeatMatrixResponse.builder()
                .showId(1L)
                .eventName("Coldplay")
                .venueName("MSG")
                .screenName("Screen 1")
                .startTime(Instant.now())
                .seats(List.of(
                        SeatMatrixItemDto.builder()
                                .seatId(101L)
                                .row("A")
                                .number(1)
                                .tier(SeatTier.VIP)
                                .price(BigDecimal.valueOf(250.00))
                                .status(SeatStatus.AVAILABLE)
                                .build()
                ))
                .build();

        when(seatHoldService.getSeatMatrix(1L)).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/shows/1/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(1))
                .andExpect(jsonPath("$.eventName").value("Coldplay"))
                .andExpect(jsonPath("$.seats[0].seatId").value(101))
                .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("POST /api/v1/shows/{showId}/hold returns 200 OK on valid hold request")
    void testAcquireHold_Success_Returns200() throws Exception {
        UUID holdRef = UUID.randomUUID();
        SeatHoldResponse mockResponse = SeatHoldResponse.builder()
                .holdReference(holdRef)
                .showId(1L)
                .userId("usr_alice")
                .seatIds(List.of(101L, 102L))
                .totalHoldAmount(BigDecimal.valueOf(500.00))
                .expiresAt(Instant.now().plusSeconds(600))
                .ttlSeconds(600L)
                .build();

        when(seatHoldService.acquireSeatHold(eq(1L), any(SeatHoldRequest.class))).thenReturn(mockResponse);

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_alice")
                .seatIds(List.of(101L, 102L))
                .build();

        mockMvc.perform(post("/api/v1/shows/1/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdReference").value(holdRef.toString()))
                .andExpect(jsonPath("$.userId").value("usr_alice"))
                .andExpect(jsonPath("$.seatIds").isArray())
                .andExpect(jsonPath("$.ttlSeconds").value(600));
    }

    @Test
    @DisplayName("POST /api/v1/shows/{showId}/hold returns 400 Bad Request when exceeding 10 seat limit")
    void testAcquireHold_Exceeds10Seats_Returns400() throws Exception {
        List<Long> elevenSeats = LongStream.rangeClosed(1, 11).boxed().toList();

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_greedy")
                .seatIds(elevenSeats)
                .build();

        mockMvc.perform(post("/api/v1/shows/1/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Bad Request / Validation Failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.seatIds").value("Cannot hold more than 10 seats at once"));
    }

    @Test
    @DisplayName("POST /api/v1/shows/{showId}/hold returns 409 Conflict when seat already held")
    void testAcquireHold_Conflict_Returns409() throws Exception {
        when(seatHoldService.acquireSeatHold(eq(1L), any(SeatHoldRequest.class)))
                .thenThrow(new SeatHoldConflictException("Seat A1 is currently held by another user"));

        SeatHoldRequest request = SeatHoldRequest.builder()
                .userId("usr_charlie")
                .seatIds(List.of(101L))
                .build();

        mockMvc.perform(post("/api/v1/shows/1/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Seat Hold Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Seat A1 is currently held by another user"));
    }

    @Test
    @DisplayName("POST /api/v1/shows/{showId}/release returns 200 OK on release")
    void testReleaseHold_Success_Returns200() throws Exception {
        UUID holdRef = UUID.randomUUID();
        HoldReleaseResponse mockResponse = HoldReleaseResponse.builder()
                .status("SUCCESS")
                .message("Hold released successfully. Seats are now available.")
                .build();

        when(seatHoldService.releaseSeatHold(eq(1L), any(HoldReleaseRequest.class))).thenReturn(mockResponse);

        HoldReleaseRequest request = HoldReleaseRequest.builder()
                .userId("usr_alice")
                .holdReference(holdRef)
                .build();

        mockMvc.perform(post("/api/v1/shows/1/release")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }
}
