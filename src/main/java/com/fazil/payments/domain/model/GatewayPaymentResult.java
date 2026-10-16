package com.fazil.payments.domain.model;

/**
 * The gateway's view of a payment after a call.
 *
 * @param status mapped status, or {@code null} when the gateway state is not recognised
 * @param rawState gateway state exactly as returned, for diagnostics
 * @param challenge present only when a 3DS challenge must be run in the shopper's browser
 */
public record GatewayPaymentResult(String orderReference, String paymentReference, PaymentStatus status,
		String rawState, GatewayLinks links, ThreeDsChallenge challenge, String maskedPan, String cardBrand,
		String resultCode) {

	public static GatewayPaymentResult empty(String orderReference) {
		return new GatewayPaymentResult(orderReference, null, null, null, GatewayLinks.NONE, null, null, null, null);
	}

}
