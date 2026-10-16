package com.fazil.payments.gateway.exception;

/** The request never reached the gateway (connection refused, DNS, connect timeout, bad credentials). */
public final class GatewayUnavailableException extends GatewayException {

	public GatewayUnavailableException(String operation, String message, Throwable cause) {
		super(operation, message, cause);
	}

}
