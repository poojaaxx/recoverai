package com.recoverai.demo;

/** Thrown when a requested demo scenario external transaction id is not one of the five fixed demo scenarios. */
public class DemoScenarioNotFoundException extends RuntimeException {

    public DemoScenarioNotFoundException(String externalTransactionId) {
        super("Unknown demo scenario: " + externalTransactionId);
    }
}
