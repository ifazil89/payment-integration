package com.fazil.payments.gateway;

/**
 * Verified webhook notification. Only used to locate the payment; its state is always re-read from the
 * gateway because webhook payloads are not signed.
 */
public record GatewayWebhookEvent(String eventId, String eventName, String orderReference,
		String merchantReference) {

}
