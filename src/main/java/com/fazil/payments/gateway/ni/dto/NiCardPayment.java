package com.fazil.payments.gateway.ni.dto;

import com.fazil.payments.domain.model.CardDetails;

public record NiCardPayment(String pan, String expiry, String cvv, String cardholderName) {

	@Override
	public String toString() {
		return "NiCardPayment[pan=" + CardDetails.maskPan(this.pan) + ", expiry=**, cvv=***, cardholderName=**]";
	}

}
