package com.fazil.payments.gateway.ni.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiPaymentResponse(String reference, String orderReference, String state,
		NiPaymentMethod paymentMethod, NiAuthResponse authResponse, @JsonProperty("3ds2") NiThreeDs2 threeDs2,
		@JsonProperty("_links") Map<String, NiLink> links) {

	public static final String REL_SELF = "self";

	public static final String REL_3DS_AUTHENTICATION = "cnp:3ds2-authentication";

	public static final String REL_3DS_CHALLENGE_RESPONSE = "cnp:3ds2-challenge-response";

	public static final String REL_REFUND = "cnp:refund";

	/** The {@code href} of the given relation, or {@code null} if N-Genius did not return it. */
	public String link(String rel) {
		NiLink link = (this.links != null) ? this.links.get(rel) : null;
		return (link != null) ? link.href() : null;
	}

}
