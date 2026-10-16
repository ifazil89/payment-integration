package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiThreeDs2(String transStatus, String messageVersion, String threeDSServerTransID, String acsTransID,
		String acsURL) {

	private static final String CHALLENGE_REQUIRED = "C";

	public boolean isChallengeRequired() {
		return CHALLENGE_REQUIRED.equals(this.transStatus);
	}

}
