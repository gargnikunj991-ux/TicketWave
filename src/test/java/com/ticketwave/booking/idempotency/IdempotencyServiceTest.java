package com.ticketwave.booking.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ticketwave.booking.BookingStatus;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.infrastructure.exception.IdempotencyConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private ObjectMapper objectMapper;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        idempotencyService = new IdempotencyService(stringRedisTemplate, objectMapper);
    }

    @Test
    @DisplayName("Returns empty optional when brand new idempotency key acquires lock")
    void testAcquireOrGetCached_BrandNewKey() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:key-123"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(true);

        Optional<CheckoutResponse> result = idempotencyService.acquireOrGetCached("key-123");

        assertThat(result).isEmpty();
        verify(valueOperations).setIfAbsent(eq("idempotency:key-123"), eq("IN_PROGRESS"), eq(Duration.ofSeconds(120)));
    }

    @Test
    @DisplayName("Throws IdempotencyConflictException when key is currently IN_PROGRESS")
    void testAcquireOrGetCached_InProgress_ThrowsConflict() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:key-123"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("idempotency:key-123")).thenReturn("IN_PROGRESS");

        assertThatThrownBy(() -> idempotencyService.acquireOrGetCached("key-123"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("currently in progress");
    }

    @Test
    @DisplayName("Returns cached completed response when idempotency key has finished")
    void testAcquireOrGetCached_Completed_ReturnsCached() throws Exception {
        CheckoutResponse cachedResponse = CheckoutResponse.builder()
                .bookingReference("TW-BK-123456")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_alice")
                .paymentTxnId("TXN_UPI_999")
                .totalAmount(BigDecimal.valueOf(15000.00))
                .tickets(List.of())
                .bookedAt(Instant.now())
                .build();

        String json = objectMapper.writeValueAsString(cachedResponse);

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:key-123"), eq("IN_PROGRESS"), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("idempotency:key-123")).thenReturn(json);

        Optional<CheckoutResponse> result = idempotencyService.acquireOrGetCached("key-123");

        assertThat(result).isPresent();
        assertThat(result.get().getBookingReference()).isEqualTo("TW-BK-123456");
        assertThat(result.get().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    @DisplayName("Successfully caches completed response with 24h TTL")
    void testMarkCompleted() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        CheckoutResponse response = CheckoutResponse.builder()
                .bookingReference("TW-BK-789012")
                .status(BookingStatus.CONFIRMED)
                .showId(1L)
                .userId("usr_bob")
                .paymentTxnId("TXN_CARD_123")
                .totalAmount(BigDecimal.valueOf(500.00))
                .tickets(List.of())
                .bookedAt(Instant.now())
                .build();

        idempotencyService.markCompleted("key-456", response);

        verify(valueOperations).set(eq("idempotency:key-456"), anyString(), eq(Duration.ofSeconds(86400)));
    }

    @Test
    @DisplayName("Releases in-progress lock from Redis")
    void testReleaseLock() {
        idempotencyService.releaseLock("key-789");

        verify(stringRedisTemplate).delete("idempotency:key-789");
    }
}
