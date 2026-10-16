package com.fazil.payments.domain.model;

/**
 * Follow-up URLs returned by the gateway for a payment. Callers pass these back to the gateway unchanged;
 * they are never built by hand.
 */
public record GatewayLinks(String self, String threeDsAuthentication, String threeDsChallengeResponse, String refund) {

	public static final GatewayLinks NONE = new GatewayLinks(null, null, null, null);

	/** Newer non-null links win; links missing from a later response are kept. */
	public GatewayLinks merge(GatewayLinks newer) {
		if (newer == null) {
			return this;
		}
		return new GatewayLinks(pick(newer.self, this.self),
				pick(newer.threeDsAuthentication, this.threeDsAuthentication),
				pick(newer.threeDsChallengeResponse, this.threeDsChallengeResponse), pick(newer.refund, this.refund));
	}

	private static String pick(String preferred, String fallback) {
		return (preferred != null) ? preferred : fallback;
	}

}
