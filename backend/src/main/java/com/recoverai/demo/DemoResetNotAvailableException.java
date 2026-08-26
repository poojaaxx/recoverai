package com.recoverai.demo;

/** Thrown when the demo-only data reset path cannot run right now - never a bypass, just a clear refusal. */
public class DemoResetNotAvailableException extends RuntimeException {
    public DemoResetNotAvailableException(String message) {
        super(message);
    }
}
