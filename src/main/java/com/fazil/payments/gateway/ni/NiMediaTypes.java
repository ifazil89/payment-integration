package com.fazil.payments.gateway.ni;

import org.springframework.http.MediaType;

/** Vendor media types N-Genius requires on its endpoints. */
final class NiMediaTypes {

	static final MediaType PAYMENT_V2 = MediaType.parseMediaType("application/vnd.ni-payment.v2+json");

	static final MediaType IDENTITY_V1 = MediaType.parseMediaType("application/vnd.ni-identity.v1+json");

	private NiMediaTypes() {
	}

}
