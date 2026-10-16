package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiAccessTokenResponse(@JsonProperty("access_token") String accessToken,
		@JsonProperty("expires_in") long expiresIn) {

	@Override
	public String toString() {
		return "NiAccessTokenResponse[accessToken=***, expiresIn=" + this.expiresIn + "]";
	}

}
