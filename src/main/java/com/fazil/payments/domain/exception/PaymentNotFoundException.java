package com.fazil.payments.domain.exception;

public class PaymentNotFoundException extends PaymentException {

	public PaymentNotFoundException(String paymentId) {
		super("Payment " + paymentId + " not found");
	}

}
