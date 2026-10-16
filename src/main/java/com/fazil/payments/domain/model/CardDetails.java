package com.fazil.payments.domain.model;

/**
 * Raw card data. Lives only for the duration of the payment request: never persisted, never logged.
 */
public record CardDetails(String pan, String expiry, String cvv, String cardholderName) {

	@Override
	public String toString() {
		return "CardDetails[pan=" + maskPan(this.pan) + ", expiry=**, cvv=***, cardholderName=**]";
	}

	/** First six and last four digits, the most PCI DSS allows to be shown. */
	public static String maskPan(String pan) {
		if (pan == null || pan.length() < 10) {
			return "****";
		}
		return pan.substring(0, 6) + "******" + pan.substring(pan.length() - 4);
	}

}
