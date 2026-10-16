package com.fazil.payments.gateway.exception;

/** The gateway answered with a 4xx: the request was received and refused, nothing was processed. */
public final class GatewayRejectedException extends GatewayException {

	private final int httpStatus;

	private final String reasonCode;

	public GatewayRejectedException(String operation, int httpStatus, String reasonCode) {
		super(operation, "rejected with HTTP " + httpStatus + (reasonCode != null ? " (" + reasonCode + ")" : ""),
				null);
		this.httpStatus = httpStatus;
		this.reasonCode = reasonCode;
	}

	public int getHttpStatus() {
		return this.httpStatus;
	}

	/** Gateway error code if one was returned, otherwise {@code HTTP_<status>}. */
	public String getReasonCode() {
		return (this.reasonCode != null) ? this.reasonCode : "HTTP_" + this.httpStatus;
	}

}
