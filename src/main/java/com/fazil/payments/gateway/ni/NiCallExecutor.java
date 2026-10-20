package com.fazil.payments.gateway.ni;

import java.util.function.Supplier;

import com.fazil.payments.gateway.exception.GatewayUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.RequiredArgsConstructor;

import org.springframework.core.retry.RetryTemplate;

/**
 * The single path every N-Genius call takes: circuit breaker, then {@link NiErrorTranslator}. Only
 * {@link #callIdempotent} retries; the payment POST and other state-changing calls must use {@link #call}.
 */
@RequiredArgsConstructor
class NiCallExecutor {

	private final CircuitBreaker circuitBreaker;

	private final RetryTemplate retryTemplate;

	/** Runs once. While the breaker is open the call is not sent and fails as unavailable. */
	<T> T call(String operation, Supplier<T> call) {
		try {
			return this.circuitBreaker.executeSupplier(() -> NiErrorTranslator.execute(operation, call));
		}
		catch (CallNotPermittedException ex) {
			throw new GatewayUnavailableException(operation, "circuit breaker open", null);
		}
	}

	/** Retries transient failures with backoff. Only for calls that are safe to repeat (token, GET order). */
	<T> T callIdempotent(String operation, Supplier<T> call) {
		return this.retryTemplate.invoke(() -> call(operation, call));
	}

}
