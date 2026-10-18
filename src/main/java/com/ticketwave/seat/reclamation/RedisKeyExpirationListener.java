package com.ticketwave.seat.reclamation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisKeyExpirationListener implements MessageListener {

    private static final Pattern HOLD_KEY_PATTERN = Pattern.compile("^hold:show:(\\d+):seat:(\\d+)$");

    private final SeatReclamationService seatReclamationService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);
        log.debug("Received Redis expiration event for key: [{}]", expiredKey);

        processExpiredKey(expiredKey);
    }

    public boolean processExpiredKey(String expiredKey) {
        if (expiredKey == null) {
            return false;
        }

        Matcher matcher = HOLD_KEY_PATTERN.matcher(expiredKey.trim());
        if (matcher.matches()) {
            Long showId = Long.parseLong(matcher.group(1));
            Long seatId = Long.parseLong(matcher.group(2));
            log.info("Reactive reclamation triggered: Hold expired for show [{}] seat [{}]", showId, seatId);
            return seatReclamationService.reclaimSeatIfHeld(showId, seatId);
        }

        return false;
    }
}
