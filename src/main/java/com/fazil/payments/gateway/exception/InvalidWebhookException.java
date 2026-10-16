package com.fazil.payments.gateway.exception;

/** The webhook was authenticated but its payload cannot be used. */
public class InvalidWebhookException extends RuntimeException {

	public InvalidWebhookException(String message) {
		super(message);
	}

}
