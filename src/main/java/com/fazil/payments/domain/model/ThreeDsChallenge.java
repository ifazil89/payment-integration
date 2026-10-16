package com.fazil.payments.domain.model;

/** What the shopper's browser must form-POST (field {@code creq}) to the ACS to start the 3DS challenge. */
public record ThreeDsChallenge(String acsUrl, String creq) {

	@Override
	public String toString() {
		return "ThreeDsChallenge[acsUrl=" + this.acsUrl + ", creq=<" + ((this.creq != null) ? this.creq.length() : 0)
				+ " chars>]";
	}

}
