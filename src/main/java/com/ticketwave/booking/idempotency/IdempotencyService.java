package com.ticketwave.booking.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketwave.booking.dto.CheckoutResponse;
import com.ticketwave.infrastructure.exception.IdempotencyConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    public static final String IN_PROGRESS_STATUS = "IN_PROGRESS";
    public static final long IN_PROGRESS_TTL_SECONDS = 120; // 2 minutes lock window
    public static final long COMPLETED_TTL_SECONDS = 86400;  // 24 hours replay cache

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Attempts to acquire an idempotency lock for the given key.
     *
     * @param idempotencyKey unique UUID header from client
     * @return Optional.empty() if this is a brand new request and lock was acquired.
     *         Optional.of(cachedResponse) if request was already processed (replay).
     * @throws IdempotencyConflictException if another thread is currently processing this key.
     */
    public Optional<CheckoutResponse> acquireOrGetCached(String idempotencyKey) {
        String redisKey = buildKey(idempotencyKey);

        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, IN_PROGRESS_STATUS, Duration.ofSeconds(IN_PROGRESS_TTL_SECONDS));

        if (Boolean.TRUE.equals(acquired)) {
            log.info("Acquired idempotency lock for key [{}]", idempotencyKey);
            return Optional.empty(); // Brand new request, proceed to execution
        }

        // Key already exists! Check its current value
        String existingValue = stringRedisTemplate.opsForValue().get(redisKey);

        if (IN_PROGRESS_STATUS.equals(existingValue)) {
            log.warn("Concurrent duplicate checkout request detected for idempotency key [{}]", idempotencyKey);
            throw new IdempotencyConflictException(
                    "A checkout transaction with idempotency key '" + idempotencyKey + "' is currently in progress."
            );
        }

        if (existingValue != null) {
            log.info("Idempotent replay detected for key [{}]. Returning cached response.", idempotencyKey);
            try {
                CheckoutResponse cachedResponse = objectMapper.readValue(existingValue, CheckoutResponse.class);
                return Optional.of(cachedResponse);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize cached idempotency response for key [{}]", idempotencyKey, e);
                throw new IllegalStateException("Corrupted cached response for idempotency key: " + idempotencyKey, e);
            }
        }

        // Fallback edge case: Key expired between setIfAbsent and get
        return Optional.empty();
    }

    /**
     * Caches the finalized checkout response with 24h TTL.
     */
    public void markCompleted(String idempotencyKey, CheckoutResponse response) {
        String redisKey = buildKey(idempotencyKey);
        try {
            String jsonPayload = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue()
                    .set(redisKey, jsonPayload, Duration.ofSeconds(COMPLETED_TTL_SECONDS));
            log.info("Cached completed checkout response for idempotency key [{}] for 24 hours", idempotencyKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize checkout response for idempotency key [{}]", idempotencyKey, e);
            throw new IllegalStateException("Failed to cache idempotency response", e);
        }
    }

    /**
     * Releases the in-progress lock if checkout fails prior to payment commitment,
     * allowing the client to correct issues (e.g. card details) and retry.
     */
    public void releaseLock(String idempotencyKey) {
        String redisKey = buildKey(idempotencyKey);
        stringRedisTemplate.delete(redisKey);
        log.info("Released in-progress idempotency lock for key [{}]", idempotencyKey);
    }

    private String buildKey(String idempotencyKey) {
        return "idempotency:" + idempotencyKey;
    }
}
