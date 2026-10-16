package com.fazil.payments.gateway.ni.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiOrderResponse(String reference, @JsonProperty("_embedded") Embedded embedded) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Embedded(List<NiPaymentResponse> payment) {
	}

	/** One-stage orders carry exactly one payment; {@code null} if none is embedded. */
	public NiPaymentResponse firstPayment() {
		return (this.embedded != null && this.embedded.payment() != null && !this.embedded.payment().isEmpty())
				? this.embedded.payment().getFirst() : null;
	}

}
