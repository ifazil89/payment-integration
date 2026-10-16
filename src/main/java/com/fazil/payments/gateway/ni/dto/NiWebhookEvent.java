package com.fazil.payments.gateway.ni.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NiWebhookEvent(String eventId, String eventName, String outletId, Order order) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Order(String reference, String action, String merchantOrderReference) {
	}

}
