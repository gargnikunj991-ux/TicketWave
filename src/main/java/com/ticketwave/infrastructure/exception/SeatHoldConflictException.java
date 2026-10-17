package com.ticketwave.infrastructure.exception;

public class SeatHoldConflictException extends RuntimeException {
    public SeatHoldConflictException(String message) {
        super(message);
    }
}
