package com.recoverai.payment;

/**
 * Provider abstraction for executing an already-authorized payment
 * operation. The rest of the application depends only on this interface,
 * never on Razorpay SDK/WebClient details directly - see {@link
 * MockPaymentGateway} (default, deterministic, offline) and {@link
 * RazorpayPaymentGateway} (real Razorpay Test Mode calls, opt-in).
 * <p>
 * <b>This is a pure execution boundary.</b> It has no access to any
 * repository, to {@code RecoveryPolicyService}, or to any other
 * authorization logic - it cannot decide whether an operation is safe,
 * only whether the provider call itself succeeded. Callers must have
 * already run the proposed action through {@code
 * RecoveryPolicyService.evaluate(...)} and confirmed {@code ALLOW} before
 * building a {@link PaymentExecutionRequest}.
 * <p>
 * {@code execute} never throws for an ordinary provider failure -
 * authentication failure, decline, timeout, malformed response, and so on
 * are all represented as a {@link PaymentExecutionResult} with {@code
 * success=false} and a {@link PaymentFailureReason}, so a caller always
 * gets a definitive, structured outcome rather than having to catch an
 * exception to know whether a payment attempt succeeded.
 */
public interface PaymentGateway {

    PaymentExecutionResult execute(PaymentExecutionRequest request);
}
