package com.fazil.payments.gateway.exception;

/**
 * Base for gateway failures. Messages describe the operation and status only; they never contain card data
 * or gateway response bodies.
 */
public abstract sealed class GatewayException extends RuntimeException
		permits GatewayRejectedException, GatewayUnavailableException, GatewayOutcomeUnknownException {

	protected GatewayException(String operation, String message, Throwable cause) {
		super(operation + ": " + message, cause);
	}

}
