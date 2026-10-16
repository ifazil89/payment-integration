package com.fazil.payments.domain.exception;

public class IdempotencyConflictException extends PaymentException {

	public IdempotencyConflictException(String paymentId) {
		super("Idempotency key " + paymentId + " was already used for a different amount");
	}

}
