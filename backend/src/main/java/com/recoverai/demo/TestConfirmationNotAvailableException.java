package com.recoverai.demo;

/** Thrown when the judge-safe test-confirmation path (P0.4) cannot run right now - never a bypass, just a clear refusal. */
public class TestConfirmationNotAvailableException extends RuntimeException {
    public TestConfirmationNotAvailableException(String message) {
        super(message);
    }
}
