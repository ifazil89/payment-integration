package com.fazil.payments.domain.exception;

/** The requested operation is not allowed for the payment as it currently stands. */
public class PaymentStateException extends PaymentException {

	public PaymentStateException(String message) {
		super(message);
	}

}
