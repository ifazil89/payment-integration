package com.fazil.payments.gateway.ni.dto;

public record NiChallengeResponseRequest(String base64EncodedCRes) {

	@Override
	public String toString() {
		return "NiChallengeResponseRequest[base64EncodedCRes=<"
				+ ((this.base64EncodedCRes != null) ? this.base64EncodedCRes.length() : 0) + " chars>]";
	}

}
