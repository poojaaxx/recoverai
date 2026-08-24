package com.recoverai.agent;

/** Thrown by an {@link AIRecoveryProvider} on any failure - network, timeout, or malformed response. Always caught by {@code RecoveryAgentService}. */
public class AIProviderException extends RuntimeException {

    public AIProviderException(String message) {
        super(message);
    }

    public AIProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
