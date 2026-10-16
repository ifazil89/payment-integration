package com.fazil.payments.gateway.exception;

/** The webhook did not carry the expected shared secret. */
public class WebhookAuthenticationException extends RuntimeException {

	public WebhookAuthenticationException(String message) {
		super(message);
	}

}
