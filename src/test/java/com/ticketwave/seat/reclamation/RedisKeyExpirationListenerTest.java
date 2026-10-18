package com.ticketwave.seat.reclamation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisKeyExpirationListenerTest {

    @Mock
    private SeatReclamationService seatReclamationService;

    @InjectMocks
    private RedisKeyExpirationListener listener;

    @Test
    @DisplayName("Extracts showId and seatId from hold key and triggers reactive reclamation")
    void testProcessExpiredKey_ValidHoldKey() {
        when(seatReclamationService.reclaimSeatIfHeld(1L, 101L)).thenReturn(true);

        boolean result = listener.processExpiredKey("hold:show:1:seat:101");

        assertThat(result).isTrue();
        verify(seatReclamationService).reclaimSeatIfHeld(1L, 101L);
    }

    @Test
    @DisplayName("Safely ignores non-hold Redis keys (e.g. idempotency, user_hold)")
    void testProcessExpiredKey_UnrelatedKey_Ignored() {
        boolean result1 = listener.processExpiredKey("idempotency:c03264c1-4b16-43b6-9bb5-47e17cb2a609");
        boolean result2 = listener.processExpiredKey("user_hold:usr_alice:uuid-123");
        boolean result3 = listener.processExpiredKey("some:random:cache:key");

        assertThat(result1).isFalse();
        assertThat(result2).isFalse();
        assertThat(result3).isFalse();
        verifyNoInteractions(seatReclamationService);
    }

    @Test
    @DisplayName("Safely handles null and malformed keys without throwing exceptions")
    void testProcessExpiredKey_NullOrMalformed() {
        assertThat(listener.processExpiredKey(null)).isFalse();
        assertThat(listener.processExpiredKey("")).isFalse();
        assertThat(listener.processExpiredKey("hold:show:abc:seat:xyz")).isFalse();
        verifyNoInteractions(seatReclamationService);
    }

    @Test
    @DisplayName("onMessage extracts bytes and triggers reclamation")
    void testOnMessage() {
        Message message = new DefaultMessage(
                "__keyevent@0__:expired".getBytes(StandardCharsets.UTF_8),
                "hold:show:2:seat:205".getBytes(StandardCharsets.UTF_8)
        );

        when(seatReclamationService.reclaimSeatIfHeld(2L, 205L)).thenReturn(true);

        listener.onMessage(message, null);

        verify(seatReclamationService).reclaimSeatIfHeld(2L, 205L);
    }
}
