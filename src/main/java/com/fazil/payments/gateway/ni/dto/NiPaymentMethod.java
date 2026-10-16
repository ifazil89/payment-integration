package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fazil.payments.domain.model.CardDetails;

/** @param pan usually already masked by N-Genius; masked again in {@link #toString()} regardless */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NiPaymentMethod(String name, String pan) {

	@Override
	public String toString() {
		return "NiPaymentMethod[name=" + this.name + ", pan="
				+ ((this.pan != null && this.pan.contains("*")) ? this.pan : CardDetails.maskPan(this.pan)) + "]";
	}

}
