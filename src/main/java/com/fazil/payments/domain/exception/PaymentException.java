package com.fazil.payments.domain.exception;

/** Base for business-rule failures. Messages are safe to return to API clients. */
public abstract class PaymentException extends RuntimeException {

	protected PaymentException(String message) {
		super(message);
	}

}
