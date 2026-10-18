package com.ticketwave.infrastructure.exception;

public class SeatHoldExpiredException extends RuntimeException {
    public SeatHoldExpiredException(String message) {
        super(message);
    }
}
