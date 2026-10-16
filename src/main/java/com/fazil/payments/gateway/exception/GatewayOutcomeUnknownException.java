package com.fazil.payments.gateway.exception;

/**
 * The request may have been processed but no usable answer came back (read timeout, 5xx, unreadable
 * response). Never retry a non-idempotent call after this; reconcile instead.
 */
public final class GatewayOutcomeUnknownException extends GatewayException {

	public GatewayOutcomeUnknownException(String operation, String message, Throwable cause) {
		super(operation, message, cause);
	}

}
